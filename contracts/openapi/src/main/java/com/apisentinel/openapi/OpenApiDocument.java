package com.apisentinel.openapi;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;

public record OpenApiDocument(JsonNode root, Format format) {
    public static final int MAX_BYTES = 1_048_576;
    private static final int MAX_TOKENS = 100_000;
    private static final Set<String> UNSUPPORTED_SCOPE = Set.of("$id", "$dynamicRef", "$recursiveRef");
    private static final StreamReadConstraints LIMITS = StreamReadConstraints.builder()
            .maxNestingDepth(64).maxDocumentLength(MAX_BYTES).maxStringLength(65_536)
            .maxNameLength(1024).maxNumberLength(128).build();
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(LIMITS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper YAML = yamlMapper();

    public enum Format {
        JSON("application/json", "json"), YAML("application/yaml", "yaml");

        private final String mediaType;
        private final String extension;

        Format(String mediaType, String extension) {
            this.mediaType = mediaType;
            this.extension = extension;
        }

        public String mediaType() { return mediaType; }
        public String extension() { return extension; }

        public static Format file(String filename) {
            String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
            if (name.endsWith(".json")) return JSON;
            if (name.endsWith(".yaml") || name.endsWith(".yml")) return YAML;
            throw new Invalid("SPEC_FORMAT", "Choose a .json, .yaml or .yml file.");
        }
    }

    public static OpenApiDocument read(byte[] bytes, Format format) {
        if (bytes.length > MAX_BYTES) throw new Invalid("SPEC_TOO_LARGE", "A specification may be at most 1 MiB.");
        if (bytes.length == 0) throw new Invalid("SPEC_SYNTAX", "The specification is empty.");
        ObjectMapper mapper = format == Format.JSON ? JSON : YAML;
        JsonNode root;
        try {
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            if (format == Format.YAML) inspectYaml(content);
            else inspectJsonTokens(mapper, content);
            root = mapper.readTree(content);
        } catch (IOException | YAMLException error) {
            throw new Invalid("SPEC_SYNTAX", "Use one valid UTF-8 JSON or YAML document within the parsing limits.");
        }
        validateStructure(root);
        inspectReferences(root);
        var options = new ParseOptions();
        options.setResolve(false);
        options.setResolveFully(false);
        options.setResolveCombinators(false);
        var parsed = new OpenAPIV3Parser().readContents(root.toString(), null, options);
        if (parsed.getOpenAPI() == null || (parsed.getMessages() != null && !parsed.getMessages().isEmpty())) {
            String detail = parsed.getMessages() == null ? "" : parsed.getMessages().stream().limit(3)
                    .map(message -> message.substring(0, Math.min(240, message.length())))
                    .reduce((first, next) -> first + ". " + next).orElse("");
            throw new Invalid("SPEC_STRUCTURE", detail.isBlank() ? "OpenAPI validation failed." : detail);
        }
        return new OpenApiDocument(root, format);
    }

    private static void validateStructure(JsonNode root) {
        if (root == null || !root.isObject()) throw new Invalid("SPEC_STRUCTURE", "OpenAPI must be an object.");
        if (!root.path("openapi").asText("").matches("3\\.(0|1)\\.\\d+")) {
            throw new Invalid("SPEC_VERSION", "Supported OpenAPI versions are 3.0.x and 3.1.x.");
        }
        var info = root.path("info");
        if (!info.isObject() || !nonEmptyText(info.path("title")) || !nonEmptyText(info.path("version"))
                || !root.path("paths").isObject()) {
            throw new Invalid("SPEC_STRUCTURE", "OpenAPI requires info.title, info.version and a paths object.");
        }
    }

    private static boolean nonEmptyText(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank();
    }

    private static void inspectJsonTokens(ObjectMapper mapper, String content) throws IOException {
        try (var parser = mapper.createParser(content)) {
            int count = 0;
            while (parser.nextToken() != null) {
                if (++count > MAX_TOKENS) {
                    throw new Invalid("SPEC_COMPLEXITY", "The specification contains too many JSON or YAML tokens.");
                }
            }
        }
    }

    private static void inspectYaml(String content) {
        var parser = new ParserImpl(new StreamReader(content), yamlOptions());
        int documents = 0;
        int tokens = 0;
        int depth = 0;
        for (Event event = parser.getEvent(); event != null; event = parser.getEvent()) {
            if (++tokens > MAX_TOKENS) {
                throw new Invalid("SPEC_COMPLEXITY", "The specification contains too many YAML tokens.");
            }
            if (event.is(Event.ID.DocumentStart) && ++documents > 1) {
                throw new Invalid("SPEC_SYNTAX", "Supply exactly one YAML document.");
            }
            if (event instanceof CollectionStartEvent && ++depth > 64) {
                throw new Invalid("SPEC_SYNTAX", "YAML nesting may not exceed 64 levels.");
            }
            if (event.is(Event.ID.MappingEnd) || event.is(Event.ID.SequenceEnd)) depth--;
            if (event instanceof NodeEvent node && node.getAnchor() != null
                    || event instanceof ScalarEvent scalar && scalar.getTag() != null
                    || event instanceof CollectionStartEvent collection && collection.getTag() != null) {
                throw new Invalid("SPEC_UNSAFE_YAML", "YAML tags, anchors and aliases are disabled.");
            }
        }
    }

    private static void inspectReferences(JsonNode root) {
        var nodes = new ArrayDeque<JsonNode>();
        nodes.push(root);
        while (!nodes.isEmpty()) {
            var node = nodes.pop();
            if (node.isObject()) {
                node.properties().forEach(field -> {
                    String name = field.getKey();
                    var value = field.getValue();
                    if (UNSUPPORTED_SCOPE.contains(name)) {
                        throw new Invalid("SPEC_UNSAFE_REFERENCE", "Reference scope and dynamic references are disabled.");
                    }
                    if ("$ref".equals(name) || "operationRef".equals(name)) {
                        String ref = value.asText("");
                        if (!value.isTextual() || !ref.startsWith("#/") || ref.contains("%")
                                || ref.matches(".*~(?![01]).*")) {
                            throw new Invalid("SPEC_UNSAFE_REFERENCE", "References must use local JSON pointers.");
                        }
                        if (root.at(ref.substring(1)).isMissingNode()) {
                            throw new Invalid("SPEC_MISSING_REFERENCE", "A local reference points to a missing contract object.");
                        }
                    }
                    if (value.isContainerNode()) nodes.push(value);
                });
            } else {
                node.forEach(value -> {
                    if (value.isContainerNode()) nodes.push(value);
                });
            }
        }
    }

    private static ObjectMapper yamlMapper() {
        var factory = YAMLFactory.builder().streamReadConstraints(LIMITS).loaderOptions(yamlOptions())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static LoaderOptions yamlOptions() {
        var loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        loader.setMaxAliasesForCollections(0);
        loader.setAllowRecursiveKeys(false);
        loader.setCodePointLimit(MAX_BYTES);
        loader.setNestingDepthLimit(64);
        return loader;
    }

    public static final class Invalid extends RuntimeException {
        private final String code;

        public Invalid(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}

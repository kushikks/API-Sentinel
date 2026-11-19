package com.apisentinel.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Compatibility {
    public static final int REPORT_SCHEMA_VERSION = 1;
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final Set<String> DOC_KEYS = Set.of("description", "summary", "example", "examples", "externalDocs", "title");
    private static final Set<String> LITERAL_KEYS = Set.of("enum", "default", "const");
    private static final Set<String> MAP_KEYS = Set.of("paths", "properties", "patternProperties", "schemas", "parameters",
            "headers", "responses", "content", "requestBodies", "securitySchemes", "callbacks", "links", "webhooks");
    private static final List<String> SCHEMA_RULES = List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems", "minProperties",
            "maxProperties", "multipleOf", "format", "additionalProperties", "const", "not", "if", "then", "else",
            "prefixItems", "dependentRequired", "unevaluatedProperties", "patternProperties");
    private static final JsonNode MISSING = MissingNode.getInstance();

    private Compatibility() {}

    public enum Severity { BREAKING, POTENTIALLY_BREAKING, NON_BREAKING }
    public record Finding(Severity severity, String category, String httpMethod, String path,
                          String jsonPath, String description, String oldValue, String newValue) {}
    public record Result(int schemaVersion, String outcome, List<Finding> findings) {}

    public static Result compare(OpenApiDocument before, OpenApiDocument after) {
        if (before == null) return new Result(REPORT_SCHEMA_VERSION, "NO_BASELINE", List.of());
        var comparison = new Comparison(before.root(), after.root());
        if (before.root().equals(after.root())) {
            return new Result(REPORT_SCHEMA_VERSION, "COMPARED", List.of());
        }
        if (sameContract(before.root(), after.root(), false)) {
            comparison.add(Severity.NON_BREAKING, "DOCUMENTATION_CHANGED", null, null, "$",
                    "Documentation or contract version metadata changed.", null, null);
            return new Result(REPORT_SCHEMA_VERSION, "COMPARED", List.copyOf(comparison.findings));
        }
        comparison.paths();
        if (comparison.findings.isEmpty()) {
            comparison.add(Severity.POTENTIALLY_BREAKING, "UNCLASSIFIED_CONTRACT_CHANGE", null, null, "$",
                    "The documents differ outside the compatibility rules. Review the contract manually.", null, null);
        }
        return new Result(REPORT_SCHEMA_VERSION, "COMPARED", List.copyOf(comparison.findings));
    }

    private static boolean sameContract(JsonNode before, JsonNode after, boolean namedFields) {
        if (before.isObject() && after.isObject()) {
            for (String key : keys(fields(before), fields(after))) {
                if (!namedFields && DOC_KEYS.contains(key)) continue;
                if (!namedFields && "info".equals(key)) continue;
                var oldValue = before.path(key);
                var newValue = after.path(key);
                if (!namedFields && LITERAL_KEYS.contains(key)) {
                    if (!oldValue.equals(newValue)) return false;
                } else if (!sameContract(oldValue, newValue, !namedFields && MAP_KEYS.contains(key))) {
                    return false;
                }
            }
            return true;
        }
        if (before.isArray() && after.isArray() && before.size() == after.size()) {
            for (int i = 0; i < before.size(); i++) {
                if (!sameContract(before.get(i), after.get(i), false)) return false;
            }
            return true;
        }
        return before.equals(after);
    }

    private static final class Comparison {
        private final JsonNode before;
        private final JsonNode after;
        private final List<Finding> findings = new ArrayList<>();
        private int budget = 100_000;

        Comparison(JsonNode before, JsonNode after) {
            this.before = before;
            this.after = after;
        }

        void paths() {
            var oldPaths = fields(before.path("paths"));
            var newPaths = fields(after.path("paths"));
            if (!before.path("security").equals(after.path("security"))) {
                add(Severity.POTENTIALLY_BREAKING, "SECURITY_REQUIREMENTS_CHANGED", null, null, "$.security",
                        "Global operation security requirements changed.", value(before.path("security")), value(after.path("security")));
            }
            for (String path : keys(oldPaths, newPaths)) {
                if (!path.startsWith("/")) continue;
                var left = oldPaths.get(path);
                var right = newPaths.get(path);
                if (left == null || right == null) {
                    boolean removed = right == null;
                    add(removed ? Severity.BREAKING : Severity.NON_BREAKING, removed ? "ENDPOINT_REMOVED" : "ENDPOINT_ADDED",
                            null, path, "$.paths" + member(path), removed ? "Endpoint was removed." : "Endpoint was added.",
                            removed ? path : null, removed ? null : path);
                    continue;
                }
                left = resolve(before, left);
                right = resolve(after, right);
                for (String method : new TreeSet<>(METHODS)) {
                    var oldOperation = left.path(method);
                    var newOperation = right.path(method);
                    if (oldOperation.isMissingNode() && newOperation.isMissingNode()) continue;
                    var operation = new Operation(method.toUpperCase(Locale.ROOT), path);
                    if (oldOperation.isMissingNode() || newOperation.isMissingNode()) {
                        boolean removed = newOperation.isMissingNode();
                        add(removed ? Severity.BREAKING : Severity.NON_BREAKING,
                                removed ? "HTTP_METHOD_REMOVED" : "HTTP_METHOD_ADDED", operation, "$",
                                removed ? "The method was removed." : "The method was added.",
                                removed ? operation.method() : null, removed ? null : operation.method());
                        continue;
                    }
                    parameters(operation, parameters(before, left, oldOperation), parameters(after, right, newOperation));
                    requestBody(operation, resolve(before, oldOperation.path("requestBody")),
                            resolve(after, newOperation.path("requestBody")));
                    responses(operation, oldOperation.path("responses"), newOperation.path("responses"));
                    if (!oldOperation.path("security").equals(newOperation.path("security"))) {
                        add(Severity.POTENTIALLY_BREAKING, "SECURITY_REQUIREMENTS_CHANGED", operation, "$.security",
                                "Operation security requirements changed.", value(oldOperation.path("security")),
                                value(newOperation.path("security")));
                    }
                }
            }
        }

        private void parameters(Operation operation, Map<String, JsonNode> oldParams, Map<String, JsonNode> newParams) {
            for (String key : keys(oldParams, newParams)) {
                var left = oldParams.get(key);
                var right = newParams.get(key);
                String pointer = "$.parameters" + member(key);
                if (right == null) {
                    add(Severity.POTENTIALLY_BREAKING, "REQUEST_PARAMETER_REMOVED", operation, pointer,
                            "The request parameter " + key + " was removed.", key, null);
                } else if (left == null) {
                    boolean required = required(right);
                    add(required ? Severity.BREAKING : Severity.NON_BREAKING,
                            required ? "REQUEST_PARAMETER_REQUIRED" : "REQUEST_PARAMETER_ADDED", operation, pointer,
                            "A " + (required ? "required" : "optional") + " request parameter " + key + " was added.", null, key);
                } else {
                    requiredness(operation, pointer, "REQUEST_PARAMETER_REQUIREDNESS_CHANGED", required(left), required(right), true);
                    schema(operation, pointer + ".schema", left.path("schema"), right.path("schema"), true, new HashSet<>());
                    media(operation, pointer, left.path("content"), right.path("content"), true);
                }
            }
        }

        private void requestBody(Operation operation, JsonNode left, JsonNode right) {
            if (left.isMissingNode() && right.isMissingNode()) return;
            if (right.isMissingNode()) {
                add(Severity.POTENTIALLY_BREAKING, "REQUEST_BODY_REMOVED", operation, "$.requestBody",
                        "The operation no longer declares a request body.", "declared", null);
                return;
            }
            if (left.isMissingNode()) {
                boolean required = required(right);
                add(required ? Severity.BREAKING : Severity.NON_BREAKING,
                        required ? "REQUEST_BODY_REQUIRED" : "REQUEST_BODY_ADDED", operation, "$.requestBody",
                        required ? "The operation now requires a request body." : "The operation accepts an optional request body.",
                        null, "declared");
                return;
            }
            requiredness(operation, "$.requestBody", "REQUEST_BODY_REQUIREDNESS_CHANGED", required(left), required(right), true);
            media(operation, "$.requestBody", left.path("content"), right.path("content"), true);
        }

        private void responses(Operation operation, JsonNode oldResponses, JsonNode newResponses) {
            var oldMap = fields(oldResponses);
            var newMap = fields(newResponses);
            for (String status : keys(oldMap, newMap)) {
                if (status.startsWith("x-")) continue;
                String pointer = "$.responses" + member(status);
                var left = oldMap.get(status);
                var right = newMap.get(status);
                if (left == null || right == null) {
                    boolean removed = right == null;
                    boolean covered = removed && ((status.matches("[1-5][0-9]{2}") && newMap.containsKey(status.charAt(0) + "XX"))
                            || newMap.containsKey("default"));
                    Severity severity = removed && !covered ? Severity.BREAKING : Severity.POTENTIALLY_BREAKING;
                    add(severity, removed ? "RESPONSE_STATUS_REMOVED" : "RESPONSE_STATUS_ADDED", operation, pointer,
                            removed ? "The documented response for " + status + " was removed."
                                    : "A new response status " + status + " may require client handling.",
                            removed ? status : null, removed ? null : status);
                    continue;
                }
                left = resolve(before, left);
                right = resolve(after, right);
                media(operation, pointer, left.path("content"), right.path("content"), false);
            }
        }

        private void media(Operation operation, String pointer, JsonNode left, JsonNode right, boolean input) {
            var oldMedia = fields(left);
            var newMedia = fields(right);
            String direction = input ? "REQUEST" : "RESPONSE";
            for (String mediaType : keys(oldMedia, newMedia)) {
                var oldEntry = oldMedia.get(mediaType);
                var newEntry = newMedia.get(mediaType);
                String mediaPointer = pointer + ".content" + member(mediaType);
                if (oldEntry == null || newEntry == null) {
                    boolean removed = newEntry == null;
                    var severity = removed ? Severity.BREAKING : input ? Severity.NON_BREAKING : Severity.POTENTIALLY_BREAKING;
                    add(severity, direction + "_MEDIA_TYPE_" + (removed ? "REMOVED" : "ADDED"), operation, mediaPointer,
                            "Media type " + mediaType + (removed ? " was removed." : " was added."),
                            removed ? mediaType : null, removed ? null : mediaType);
                } else {
                    schema(operation, mediaPointer + ".schema", oldEntry.path("schema"), newEntry.path("schema"), input,
                            new HashSet<>());
                }
            }
        }

        private void schema(Operation operation, String pointer, JsonNode left, JsonNode right, boolean input,
                            Set<SchemaPair> active) {
            spend();
            if (active.size() >= 40) throw complexity();
            left = resolve(before, left);
            right = resolve(after, right);
            if (left.isMissingNode() && right.isMissingNode()) return;
            var pair = new SchemaPair(left, right);
            if (!active.add(pair)) return;
            try {
                compareTypes(operation, pointer, left, right, input);
                compareEnum(operation, pointer, left, right, input);
                compareProperties(operation, pointer, left, right, input, active);
                var oldItems = left.path("items");
                var newItems = right.path("items");
                if (!oldItems.isMissingNode() || !newItems.isMissingNode()) {
                    schema(operation, pointer + ".items", oldItems, newItems, input, active);
                }
                for (String rule : SCHEMA_RULES) {
                    if (!left.path(rule).equals(right.path(rule))) {
                        add(Severity.POTENTIALLY_BREAKING, direction(input) + "_SCHEMA_CONSTRAINT_CHANGED", operation,
                                pointer + "." + rule, "The schema constraint " + rule + " changed.",
                                value(left.path(rule)), value(right.path(rule)));
                    }
                }
                for (String operator : List.of("oneOf", "anyOf")) {
                    var oldBranches = left.path(operator);
                    var newBranches = right.path(operator);
                    if (!oldBranches.equals(newBranches)) {
                        add(Severity.POTENTIALLY_BREAKING, direction(input) + "_COMPOSITION_CHANGED", operation,
                                pointer + "." + operator, "Schema alternatives changed and require review.",
                                value(oldBranches), value(newBranches));
                    } else if (oldBranches.isArray()) {
                        for (int index = 0; index < oldBranches.size(); index++) {
                            schema(operation, pointer + "." + operator + "[" + index + "]",
                                    oldBranches.get(index), newBranches.get(index), input, active);
                        }
                    }
                }
            } finally {
                active.remove(pair);
            }
        }

        private void compareProperties(Operation operation, String pointer, JsonNode left, JsonNode right, boolean input,
                                       Set<SchemaPair> active) {
            var oldShape = shape(before, left, input);
            var newShape = shape(after, right, input);
            for (String name : keys(oldShape.properties(), newShape.properties())) {
                var oldProperty = oldShape.properties().get(name);
                var newProperty = newShape.properties().get(name);
                String child = pointer + ".properties" + member(name);
                if (newProperty == null) {
                    add(input ? Severity.POTENTIALLY_BREAKING : Severity.BREAKING,
                            direction(input) + "_PROPERTY_REMOVED", operation, child,
                            "The " + (input ? "request" : "response") + " property " + name + " was removed.",
                            schemaLabel(oldProperty), null);
                } else if (oldProperty == null) {
                    boolean requiredInput = input && newShape.required().contains(name);
                    boolean closedOutput = !input && left.path("additionalProperties").isBoolean()
                            && !left.path("additionalProperties").booleanValue();
                    var severity = requiredInput ? Severity.BREAKING
                            : closedOutput ? Severity.POTENTIALLY_BREAKING : Severity.NON_BREAKING;
                    add(severity,
                            requiredInput ? "REQUEST_PROPERTY_REQUIRED" : direction(input) + "_PROPERTY_ADDED", operation,
                            child, "The " + (requiredInput ? "required request" : input ? "optional request" : "response")
                                    + " property " + name + " was added.", null, schemaLabel(newProperty));
                } else {
                    requiredness(operation, child, direction(input) + "_PROPERTY_REQUIREDNESS_CHANGED",
                            oldShape.required().contains(name), newShape.required().contains(name), input);
                    schema(operation, child, oldProperty, newProperty, input, active);
                }
            }
            for (String name : sortedDifference(newShape.required(), oldShape.required())) {
                if (input && !newShape.properties().containsKey(name)) {
                    add(Severity.BREAKING, "REQUEST_PROPERTY_REQUIRED", operation, pointer + ".required" + member(name),
                            "The request now requires property " + name + ".", "optional", "required");
                }
            }
        }

        private void compareTypes(Operation op, String pointer, JsonNode left, JsonNode right, boolean input) {
            var oldTypes = types(left);
            var newTypes = types(right);
            if (oldTypes.equals(newTypes)) return;
            var severity = input ? (covers(newTypes, oldTypes) ? Severity.NON_BREAKING : Severity.BREAKING)
                    : (covers(oldTypes, newTypes) ? Severity.NON_BREAKING : Severity.BREAKING);
            add(severity, direction(input) + "_SCHEMA_TYPE_CHANGED", op, pointer + ".type", "The schema type changed.",
                    typeLabel(oldTypes), typeLabel(newTypes));
        }

        private void compareEnum(Operation op, String pointer, JsonNode left, JsonNode right, boolean input) {
            var oldEnum = enumeration(left);
            var newEnum = enumeration(right);
            if (java.util.Objects.equals(oldEnum, newEnum)) return;
            boolean narrows = newEnum != null && (oldEnum == null || !newEnum.containsAll(oldEnum));
            boolean widens = oldEnum != null && (newEnum == null || !oldEnum.containsAll(newEnum));
            Severity severity = input ? (narrows ? Severity.BREAKING : Severity.NON_BREAKING)
                    : (widens ? Severity.BREAKING : Severity.POTENTIALLY_BREAKING);
            String change = narrows && widens ? "CHANGED" : narrows ? "NARROWED" : "WIDENED";
            add(severity, direction(input) + "_ENUM_" + change, op, pointer + ".enum",
                    "The enum values " + change.toLowerCase(Locale.ROOT) + ".", value(left.path("enum")), value(right.path("enum")));
        }

        private void requiredness(Operation op, String pointer, String category, boolean was, boolean now, boolean input) {
            if (was == now) return;
            Severity severity = input == now ? Severity.BREAKING : Severity.NON_BREAKING;
            add(severity, category, op, pointer, "Requiredness changed.", was ? "required" : "optional",
                    now ? "required" : "optional");
        }

        private Shape shape(JsonNode root, JsonNode schema, boolean input) {
            var properties = new TreeMap<String, JsonNode>();
            var required = new HashSet<String>();
            collectShape(root, schema, properties, required, new java.util.IdentityHashMap<>());
            var iterator = properties.entrySet().iterator();
            while (iterator.hasNext()) {
                var property = iterator.next();
                var definition = resolve(root, property.getValue());
                if (definition.path(input ? "readOnly" : "writeOnly").asBoolean(false)) {
                    required.remove(property.getKey());
                    iterator.remove();
                }
            }
            return new Shape(properties, required);
        }

        private void collectShape(JsonNode root, JsonNode node, Map<String, JsonNode> properties, Set<String> required,
                                  java.util.IdentityHashMap<JsonNode, Boolean> seen) {
            spend();
            node = resolve(root, node);
            if (seen.containsKey(node)) return;
            if (seen.size() >= 120) throw complexity();
            seen.put(node, Boolean.TRUE);
            for (var item : node.path("allOf")) collectShape(root, item, properties, required, seen);
            fields(node.path("properties")).forEach(properties::put);
            node.path("required").forEach(value -> required.add(value.asText()));
        }

        private JsonNode resolve(JsonNode root, JsonNode start) {
            JsonNode node = start;
            int hops = 0;
            while (node.has("$ref")) {
                spend();
                if (++hops > 24) throw complexity();
                node = root.at(node.path("$ref").textValue().substring(1));
            }
            return node;
        }

        private Map<String, JsonNode> parameters(JsonNode root, JsonNode path, JsonNode operation) {
            var collected = new TreeMap<String, JsonNode>();
            for (var scope : List.of(path, operation)) {
                for (var raw : scope.path("parameters")) {
                    var parameter = resolve(root, raw);
                    String in = parameter.path("in").asText();
                    String name = parameter.path("name").asText();
                    if ("header".equals(in)) name = name.toLowerCase(Locale.ROOT);
                    collected.put(in + ":" + name, parameter);
                }
            }
            return collected;
        }

        private void add(Severity severity, String category, Operation op, String pointer,
                         String description, String oldValue, String newValue) {
            add(severity, category, op.method(), op.path(), pointer, description, oldValue, newValue);
        }

        private void add(Severity severity, String category, String method, String path, String pointer,
                         String description, String oldValue, String newValue) {
            if (findings.size() >= 1_500) throw complexity();
            findings.add(new Finding(severity, category, method, limit(path, 1024), limit(pointer, 2048),
                    limit(description, 1000), limit(oldValue, 500), limit(newValue, 500)));
        }

        private void spend() {
            if (--budget < 0 || Thread.currentThread().isInterrupted()) throw complexity();
        }
    }

    private static Set<JsonNode> enumeration(JsonNode schema) {
        var values = schema.path("enum");
        if (!values.isArray()) return null;
        Set<JsonNode> set = new LinkedHashSet<>();
        values.forEach(set::add);
        return set;
    }

    private static Set<String> types(JsonNode schema) {
        if (schema.isBoolean() && !schema.booleanValue()) return Set.of();
        var values = new TreeSet<String>();
        var type = schema.path("type");
        if (type.isTextual()) values.add(type.textValue());
        if (type.isArray()) type.forEach(value -> values.add(value.asText()));
        if (values.isEmpty()) return Set.of("*");
        if (schema.path("nullable").asBoolean(false)) values.add("null");
        return values;
    }

    private static boolean covers(Set<String> superset, Set<String> subset) {
        return superset.contains("*") || subset.stream().allMatch(type ->
                superset.contains(type) || ("integer".equals(type) && superset.contains("number")));
    }

    private static String typeLabel(Set<String> types) {
        if (types.isEmpty()) return "never";
        if (types.contains("*")) return "any";
        return String.join(" | ", types);
    }

    private static String schemaLabel(JsonNode schema) {
        return schema.has("$ref") ? schema.path("$ref").asText() : typeLabel(types(schema));
    }

    private static boolean required(JsonNode node) { return node.path("required").asBoolean(false); }
    private static String direction(boolean input) { return input ? "REQUEST" : "RESPONSE"; }
    private static String member(String name) {
        return "['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private static Map<String, JsonNode> fields(JsonNode node) {
        var map = new HashMap<String, JsonNode>();
        node.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue()));
        return map;
    }

    private static Set<String> keys(Map<String, ?> left, Map<String, ?> right) {
        var keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        return keys;
    }

    private static Set<String> sortedDifference(Set<String> left, Set<String> right) {
        var result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String value(JsonNode node) {
        if (node == MISSING) return null;
        String value = node.isTextual() ? node.textValue() : node.toString();
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private static OpenApiDocument.Invalid complexity() {
        return new OpenApiDocument.Invalid("COMPARISON_LIMIT", "The comparison exceeded the depth, work or finding limit.");
    }

    private record Shape(Map<String, JsonNode> properties, Set<String> required) {}
    private record Operation(String method, String path) {}
    private record SchemaPair(JsonNode left, JsonNode right) {
        @Override public boolean equals(Object other) {
            return other instanceof SchemaPair pair && left == pair.left && right == pair.right;
        }

        @Override public int hashCode() {
            return 31 * System.identityHashCode(left) + System.identityHashCode(right);
        }
    }
}

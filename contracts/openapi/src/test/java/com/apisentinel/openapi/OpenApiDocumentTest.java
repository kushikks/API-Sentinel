package com.apisentinel.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OpenApiDocumentTest {
    private static final String JSON = """
            {"openapi":"3.1.0","info":{"title":"Roster","version":"1"},"paths":{
              "/roster":{"get":{"responses":{"200":{"description":"OK"}}}}
            }}
            """;
    private static final String YAML = """
            openapi: 3.0.3
            info:
              title: Roster
              version: '1'
            paths:
              /roster:
                get:
                  responses:
                    '200':
                      description: OK
            """;

    @Test
    void acceptsYamlAndOpenApi31Json() {
        assertThat(read(YAML, OpenApiDocument.Format.YAML).root().path("openapi").textValue()).isEqualTo("3.0.3");
        assertThat(read(JSON, OpenApiDocument.Format.JSON).root().path("openapi").textValue()).isEqualTo("3.1.0");
    }

    @Test
    void rejectsExternalAndMissingReferences() {
        String pattern = "\"/roster\":{";
        assertInvalid(JSON.replace(pattern, pattern + "\"$ref\":\"https://example.invalid/remote.yaml\","), "SPEC_UNSAFE_REFERENCE");
        assertInvalid(JSON.replace(pattern, pattern + "\"$ref\":\"#/components/pathItems/Missing\","), "SPEC_MISSING_REFERENCE");
    }

    @Test
    void rejectsDuplicatesTrailingDocumentsAndInvalidStructure() {
        assertInvalid(JSON.replace("\"title\":\"Roster\"", "\"title\":\"Roster\",\"title\":\"Team\""), "SPEC_SYNTAX");
        assertInvalid(JSON + " {}", "SPEC_SYNTAX");
        assertInvalid(JSON.replace("\"responses\"", "\"unknown\""), "SPEC_STRUCTURE");
        assertInvalid(JSON.replace("3.1.0", "2.0.0"), "SPEC_VERSION");
    }

    @Test
    void rejectsOversizedAndDeeplyNestedDocuments() {
        assertThatExceptionOfType(OpenApiDocument.Invalid.class)
                .isThrownBy(() -> OpenApiDocument.read(new byte[1_048_577], OpenApiDocument.Format.JSON))
                .satisfies(error -> assertThat(error.code()).isEqualTo("SPEC_TOO_LARGE"));
        assertInvalid("{\"data\":" + "[".repeat(80) + "0" + "]".repeat(80) + "}", "SPEC_SYNTAX");
    }

    @Test
    void rejectsUnsafeYamlFeaturesAndMultipleDocuments() {
        assertThatExceptionOfType(OpenApiDocument.Invalid.class)
                .isThrownBy(() -> read(YAML.replace("title: Roster", "title: &nickname Roster"), OpenApiDocument.Format.YAML))
                .satisfies(error -> assertThat(error.code()).isEqualTo("SPEC_UNSAFE_YAML"));
        assertThatExceptionOfType(OpenApiDocument.Invalid.class)
                .isThrownBy(() -> read(YAML + "\n---\n" + YAML, OpenApiDocument.Format.YAML));
    }

    private static void assertInvalid(String text, String code) {
        assertThatExceptionOfType(OpenApiDocument.Invalid.class)
                .isThrownBy(() -> read(text, OpenApiDocument.Format.JSON))
                .satisfies(error -> assertThat(error.code()).isEqualTo(code));
    }

    private static OpenApiDocument read(String content, OpenApiDocument.Format format) {
        return OpenApiDocument.read(content.getBytes(StandardCharsets.UTF_8), format);
    }
}

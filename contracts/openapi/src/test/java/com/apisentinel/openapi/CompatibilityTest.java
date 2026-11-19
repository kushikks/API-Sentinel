package com.apisentinel.openapi;

import static com.apisentinel.openapi.Compatibility.Severity.BREAKING;
import static com.apisentinel.openapi.Compatibility.Severity.NON_BREAKING;
import static com.apisentinel.openapi.Compatibility.Severity.POTENTIALLY_BREAKING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void identifiesStudentResponseRemovalsAndAdditionsThroughReferences() throws IOException {
        var base = OpenApiDocument.read(Files.readAllBytes(Path.of("../../tests/developer-a/fixtures/student-v1.yaml")), OpenApiDocument.Format.YAML);
        var changed = OpenApiDocument.read(Files.readAllBytes(Path.of("../../tests/developer-a/fixtures/student-v2.json")), OpenApiDocument.Format.JSON);
        var result = Compatibility.compare(base, changed);
        assertThat(result.findings()).extracting(Compatibility.Finding::category, Compatibility.Finding::severity)
                .containsExactlyInAnyOrder(tuple("RESPONSE_PROPERTY_REMOVED", BREAKING), tuple("RESPONSE_PROPERTY_REMOVED", BREAKING),
                        tuple("RESPONSE_PROPERTY_ADDED", NON_BREAKING), tuple("RESPONSE_PROPERTY_ADDED", NON_BREAKING));
        assertThat(result.findings()).filteredOn(f -> f.severity() == BREAKING)
                .allSatisfy(finding -> assertThat(finding.path()).isEqualTo("/api/students/{id}"));
        assertThat(Compatibility.compare(null, base).outcome()).isEqualTo("NO_BASELINE");
    }

    @Test
    void distinguishesEndpointAndMethodChanges() throws IOException {
        var before = fixture();
        var after = before.deepCopy();
        var paths = (ObjectNode) after.path("paths");
        var items = (ObjectNode) paths.get("/items");
        var operation = items.remove("post");
        items.set("put", operation);
        paths.remove("/old");
        paths.putObject("/new").set("post", operation);
        assertThat(compare(before, after)).extracting(Compatibility.Finding::category, Compatibility.Finding::severity)
                .containsExactlyInAnyOrder(tuple("ENDPOINT_ADDED", NON_BREAKING), tuple("ENDPOINT_REMOVED", BREAKING),
                        tuple("HTTP_METHOD_REMOVED", BREAKING), tuple("HTTP_METHOD_ADDED", NON_BREAKING));
    }

    @Test
    void flagsParameterBodyAndResponseChanges() throws IOException {
        var before = fixture();
        var after = before.deepCopy();
        var operation = operation(after);
        var parameters = operation.putArray("parameters");
        parameters.addObject().put("name", "page").put("in", "query").put("required", true).putObject("schema").put("type", "integer");
        parameters.addObject().put("name", "scope").put("in", "query").put("required", true).putObject("schema").put("type", "string");
        var schema = (ObjectNode) operation.at("/requestBody/content/application~1json/schema");
        schema.withObjectProperty("properties").putObject("owner").put("type", "string");
        schema.putArray("required").add("owner");
        var mode = schema.withObjectProperty("properties").withObjectProperty("mode");
        mode.putArray("enum").add("a");
        var responses = (ObjectNode) operation.get("responses");
        responses.remove("204");
        var media = ((ObjectNode) responses.get("200")).withObjectProperty("content");
        media.remove("application/xml");
        media.withObjectProperty("application/json").withObjectProperty("schema").put("type", "integer");
        assertThat(compare(before, after)).extracting(Compatibility.Finding::category, Compatibility.Finding::severity)
                .contains(tuple("REQUEST_PARAMETER_REMOVED", POTENTIALLY_BREAKING), tuple("REQUEST_PARAMETER_REQUIRED", BREAKING),
                        tuple("REQUEST_PARAMETER_REQUIREDNESS_CHANGED", BREAKING), tuple("REQUEST_PROPERTY_REQUIRED", BREAKING),
                        tuple("REQUEST_ENUM_NARROWED", BREAKING), tuple("RESPONSE_SCHEMA_TYPE_CHANGED", BREAKING),
                        tuple("RESPONSE_STATUS_REMOVED", BREAKING), tuple("RESPONSE_MEDIA_TYPE_REMOVED", BREAKING));
    }

    @Test
    void reportsDocumentationOnlyChangesAndUnclassifiedSemanticChanges() throws IOException {
        var before = fixture();
        var after = before.deepCopy();
        operation(after).put("summary", "New summary");
        assertThat(compare(before, after)).extracting(Compatibility.Finding::category, Compatibility.Finding::severity)
                .containsExactly(tuple("DOCUMENTATION_CHANGED", NON_BREAKING));
        operation(after).put("operationId", "renamed");
        assertThat(compare(before, after)).extracting(Compatibility.Finding::category, Compatibility.Finding::severity)
                .containsExactly(tuple("UNCLASSIFIED_CONTRACT_CHANGE", POTENTIALLY_BREAKING));
    }

    @Test
    void followsRecursiveSchemasWithoutInfiniteExpansion() throws IOException {
        var before = (ObjectNode) JSON.readTree("""
                {"openapi":"3.0.3","info":{"title":"Tree","version":"1"},"paths":{"/tree":{"get":{"responses":{"200":{
                "description":"Tree","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Tree"}}}}}}}},
                "components":{"schemas":{"Tree":{"type":"object","properties":{"child":{"$ref":"#/components/schemas/Tree"},
                "label":{"type":"string"}}}}}}
                """);
        var after = before.deepCopy();
        ((ObjectNode) after.at("/components/schemas/Tree/properties")).remove("label");
        assertThat(compare(before, after)).extracting(Compatibility.Finding::category).containsExactly("RESPONSE_PROPERTY_REMOVED");
    }

    private static ObjectNode fixture() throws IOException {
        return (ObjectNode) JSON.readTree("""
                {"openapi":"3.0.3","info":{"title":"Items","version":"1"},"paths":{
                  "/old":{"get":{"responses":{"200":{"description":"OK"}}}},
                  "/items":{"post":{"parameters":[
                     {"name":"page","in":"query","schema":{"type":"integer"}},
                     {"name":"legacy","in":"query","schema":{"type":"string"}}
                  ],"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{
                      "mode":{"type":"string","enum":["a","b"]}
                  }}}}},"responses":{
                    "200":{"description":"OK","content":{"application/json":{"schema":{"type":"string"}},
                                                         "application/xml":{"schema":{"type":"string"}}}},
                    "204":{"description":"No content"}
                  }}}
                }}
                """);
    }

    private static ObjectNode operation(ObjectNode tree) { return (ObjectNode) tree.at("/paths/~1items/post"); }

    private static List<Compatibility.Finding> compare(ObjectNode before, ObjectNode after) throws IOException {
        var left = OpenApiDocument.read(JSON.writeValueAsBytes(before), OpenApiDocument.Format.JSON);
        var right = OpenApiDocument.read(JSON.writeValueAsBytes(after), OpenApiDocument.Format.JSON);
        return Compatibility.compare(left, right).findings();
    }
}

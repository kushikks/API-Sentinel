package com.apisentinel.analyzer;

import com.apisentinel.openapi.OpenApiDocument;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class RegistryClient {
    private final RestClient http;
    private final CircuitBreaker breaker;
    private final String key;

    RegistryClient(@LoadBalanced RestClient.Builder http, CircuitBreakerFactory<?, ?> circuits,
                   @Value("${sentinel.analyzer-registry-key}") String key) {
        this.http = http.clone().baseUrl("http://registry-service").build();
        this.breaker = circuits.create("registry-spec");
        this.key = key;
    }

    OpenApiDocument fetch(UUID api, UUID version) {
        var reply = breaker.run(() -> http.get()
                .uri("/internal/apis/{api}/versions/{version}/spec", api, version)
                .header("X-Sentinel-Service-Key", key)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 429 || status >= 500) throw new Unavailable();
                    if (status != 200) return new Reply(null, null, "Registry rejected the specification request (HTTP " + status + ").");
                    var type = response.getHeaders().getContentType();
                    String mediaType = type == null ? "" : type.getType() + "/" + type.getSubtype();
                    var format = switch (mediaType) {
                        case "application/json" -> OpenApiDocument.Format.JSON;
                        case "application/yaml" -> OpenApiDocument.Format.YAML;
                        default -> null;
                    };
                    if (format == null) return new Reply(null, null, "Registry returned an unsupported specification format.");
                    var body = response.getBody().readNBytes(OpenApiDocument.MAX_BYTES + 1);
                    return new Reply(body, format, null);
                }), cause -> {
                    throw new Unavailable();
                });
        if (reply.error() != null) throw new OpenApiDocument.Invalid("REGISTRY_SPEC_REJECTED", reply.error());
        return OpenApiDocument.read(reply.bytes(), reply.format());
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() { super("Registry is temporarily unavailable."); }
    }

    private record Reply(byte[] bytes, OpenApiDocument.Format format, String error) {}
}

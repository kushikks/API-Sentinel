package com.apisentinel.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GeminiMigrationClientTest {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final GeminiMigrationClient.FindingContext FINDING = new GeminiMigrationClient.FindingContext(
            "F1", "BREAKING", "RESPONSE_PROPERTY_REMOVED", "GET", "/students", "$.studentId", null, null);
    private static final String ADVICE = """
            {"suggestions":[{"findingRef":"F1","explanation":"The studentId field has been removed.",
            "affectedClientCode":"Response DTOs or mappers that read studentId might be affected.",
            "recommendedChanges":["Update the DTO and remove reads of the old field."],
            "verificationSteps":["Test deserialization using a response without studentId."]}]}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final String fixtureKey = UUID.randomUUID().toString();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsReducedFindingsAndStructuredSchemaInAnAuthenticatedPost() throws Exception {
        var requestBody = new AtomicReference<String>();
        var requestPath = new AtomicReference<String>();
        var requestMethod = new AtomicReference<String>();
        var apiHeader = new AtomicReference<String>();
        var client = start(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestMethod.set(exchange.getRequestMethod());
            apiHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, envelope(ADVICE, "STOP"));
        }, Duration.ofSeconds(3), fixtureKey);

        var result = client.generate(List.of(FINDING));
        assertThat(result).containsOnlyKeys("F1");
        assertThat(result.get("F1").explanation()).contains("studentId");
        assertThat(result.get("F1").verificationSteps()).hasSize(1);
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo("/v1beta/models/" + MODEL + ":generateContent");
        assertThat(apiHeader.get()).isEqualTo(fixtureKey);
        assertThat(requestBody.get()).doesNotContain(fixtureKey);
        var payload = json.readTree(requestBody.get());
        var input = json.readTree(payload.at("/contents/0/parts/0/text").asText());
        assertThat(input.at("/0/jsonPath").asText()).isEqualTo("$.studentId");
        assertThat(input.at("/0/category").asText()).isEqualTo("RESPONSE_PROPERTY_REMOVED");
        assertThat(payload.at("/generationConfig/responseFormat/text/mimeType").asText()).isEqualTo("APPLICATION_JSON");
        assertThat(payload.at("/generationConfig/responseFormat/text/schema/properties/suggestions/items/properties/findingRef/enum/0")
                .asText()).isEqualTo("F1");
        assertThat(payload.at("/tools").isMissingNode()).isTrue();
    }

    @Test
    void rejectsUngroundedAndMalformedSuggestions() throws Exception {
        var content = new AtomicReference<>(ADVICE.replace("F1", "F2"));
        var client = start(exchange -> respond(exchange, 200, envelope(content.get(), "STOP")),
                Duration.ofSeconds(3), fixtureKey);
        assertFailure(client, HttpStatus.BAD_GATEWAY, "GEMINI_INVALID_RESPONSE");
        content.set(ADVICE.replace("[\"Update the DTO and remove reads of the old field.\"]", "[]"));
        assertFailure(client, HttpStatus.BAD_GATEWAY, "GEMINI_INVALID_RESPONSE");
        content.set(ADVICE.replace("\"findingRef\":\"F1\"", "\"findingRef\":\"F1\",\"findingRef\":\"F1\""));
        assertFailure(client, HttpStatus.BAD_GATEWAY, "GEMINI_INVALID_RESPONSE");
        content.set(ADVICE + " {}");
        assertFailure(client, HttpStatus.BAD_GATEWAY, "GEMINI_INVALID_RESPONSE");
    }

    @Test
    void rejectsTruncatedAndOversizedResponses() throws Exception {
        var body = new AtomicReference<>(envelope(ADVICE, "MAX_TOKENS"));
        var client = start(exchange -> respond(exchange, 200, body.get()), Duration.ofSeconds(3), fixtureKey);
        assertFailure(client, HttpStatus.BAD_GATEWAY, "GEMINI_INVALID_RESPONSE");
        body.set("x".repeat(65_537));
        assertFailure(client, HttpStatus.BAD_GATEWAY, "GEMINI_INVALID_RESPONSE");
    }

    @Test
    void mapsProviderQuotaAndParsesBothRetryAfterFormats() throws Exception {
        var retry = new AtomicReference<>("127");
        var client = start(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", retry.get());
            respond(exchange, 429, "{\"error\":\"" + fixtureKey + "\"}");
        }, Duration.ofSeconds(3), fixtureKey);
        assertThat(assertFailure(client, HttpStatus.TOO_MANY_REQUESTS, "GEMINI_RATE_LIMITED").retryAfterSeconds)
                .isEqualTo(127);
        retry.set(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120).format(DateTimeFormatter.RFC_1123_DATE_TIME));
        assertThat(assertFailure(client, HttpStatus.TOO_MANY_REQUESTS, "GEMINI_RATE_LIMITED").retryAfterSeconds)
                .isBetween(115, 121);
        retry.set("invalid");
        assertThat(assertFailure(client, HttpStatus.TOO_MANY_REQUESTS, "GEMINI_RATE_LIMITED").retryAfterSeconds)
                .isEqualTo(60);
    }

    @Test
    void mapsProviderOutagesAndDoesNotFollowRedirectsOrExposeErrors() throws Exception {
        var providerStatus = new AtomicInteger(503);
        var attempts = new AtomicInteger();
        var client = start(exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/v1beta/other");
            respond(exchange, providerStatus.get(), "{\"error\":\"" + fixtureKey + "\"}");
        }, Duration.ofSeconds(3), fixtureKey);
        assertFailure(client, HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_UNAVAILABLE");
        providerStatus.set(307);
        assertFailure(client, HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_UNAVAILABLE");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void boundsSlowResponseBodiesEvenAfterHeadersArrive() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = start(exchange -> {
                executor.submit(() -> {
                    try (exchange) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                        Thread.sleep(1_000);
                        exchange.getResponseBody().write('}');
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (IOException closed) {
                        // The client may close the stream after its deadline.
                    }
                });
            }, Duration.ofMillis(350), fixtureKey);
            assertFailure(client, HttpStatus.GATEWAY_TIMEOUT, "GEMINI_TIMEOUT");
        }
    }

    @Test
    void skipsTransportWithoutServerSideCredentials() throws Exception {
        var calls = new AtomicInteger();
        var client = start(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "");
        }, Duration.ofSeconds(3), "");
        assertThat(client.configured()).isFalse();
        assertFailure(client, HttpStatus.SERVICE_UNAVAILABLE, "GEMINI_NOT_CONFIGURED");
        assertThat(calls).hasValue(0);
    }

    private GeminiMigrationClient start(HttpHandler handler, Duration timeout, String key) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return new GeminiMigrationClient(json, key, MODEL, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), timeout);
    }

    private String envelope(String content, String finishReason) throws IOException {
        return json.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", finishReason,
                "content", Map.of("parts", List.of(Map.of("text", content)))))));
    }

    private static void respond(HttpExchange exchange, int status, String content) throws IOException {
        try (exchange) {
            var body = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private MigrationSuggestionFailure assertFailure(GeminiMigrationClient client, HttpStatus status, String code) {
        var failure = catchThrowableOfType(() -> client.generate(List.of(FINDING)), MigrationSuggestionFailure.class);
        assertThat(failure).isNotNull().hasMessageNotContaining(fixtureKey);
        assertThat(failure.status).isEqualTo(status);
        assertThat(failure.code).isEqualTo(code);
        return failure;
    }
}

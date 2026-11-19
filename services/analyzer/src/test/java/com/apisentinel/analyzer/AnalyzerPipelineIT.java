package com.apisentinel.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apisentinel.openapi.Compatibility;
import com.apisentinel.openapi.OpenApiDocument;
import com.apisentinel.shared.Events;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.flyway.create-schemas=true",
        "sentinel.jwt.issuer=https://sentinel.example.test", "sentinel.outbox-delay=150",
        "sentinel.worker-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Import(AnalyzerPipelineIT.GeminiFixture.class)
class AnalyzerPipelineIT {
    private static final String USER = "sentinel_test";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String KEY = UUID.randomUUID().toString();
    private static final String IMPACT_KEY = UUID.randomUUID().toString();
    private static final UUID BASELINE = UUID.randomUUID();
    private static final UUID UPDATE = UUID.randomUUID();
    private static final String GEMINI_KEY = UUID.randomUUID().toString();
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final ObjectMapper FIXTURE_JSON = new ObjectMapper();
    private static final AtomicInteger GEMINI_STATUS = new AtomicInteger();
    private static final AtomicInteger GEMINI_CALLS = new AtomicInteger();
    private static final AtomicReference<JsonNode> GEMINI_INPUT = new AtomicReference<>();
    private static HttpServer fixtures;

    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @Container static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.2.7-management")
            .withExposedPorts(5672).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(180)))
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2 +SDcpu 1 +SDio 2")
            .withEnv("RABBITMQ_DEFAULT_USER", USER).withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD);

    @Autowired JobStore jobs;
    @Autowired RegistryClient registry;
    @Autowired JdbcClient db;
    @Autowired RabbitTemplate rabbit;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) throws IOException {
        var baseline = Files.readAllBytes(Path.of("../../tests/developer-a/fixtures/student-v1.yaml"));
        var update = Files.readAllBytes(Path.of("../../tests/developer-a/fixtures/student-v2.json"));
        fixtures = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixtures.createContext("/internal/apis/", request -> {
            try (request) {
                if (!KEY.equals(request.getRequestHeaders().getFirst("X-Sentinel-Service-Key"))) {
                    request.sendResponseHeaders(403, -1);
                    return;
                }
                var path = request.getRequestURI().getPath();
                boolean isBaseline = path.endsWith("/" + BASELINE + "/spec");
                boolean isUpdate = path.endsWith("/" + UPDATE + "/spec");
                if (!isBaseline && !isUpdate) {
                    request.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = isBaseline ? baseline : update;
                request.getResponseHeaders().set("Content-Type", isBaseline ? "application/yaml" : "application/json");
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            }
        });
        fixtures.createContext("/v1beta/models/" + MODEL + ":generateContent", request -> {
            try (request) {
                if (!GEMINI_KEY.equals(request.getRequestHeaders().getFirst("x-goog-api-key"))) {
                    request.sendResponseHeaders(401, -1);
                    return;
                }
                GEMINI_CALLS.incrementAndGet();
                var input = FIXTURE_JSON.readTree(FIXTURE_JSON.readTree(request.getRequestBody())
                        .at("/contents/0/parts/0/text").asText());
                GEMINI_INPUT.set(input);
                int status = GEMINI_STATUS.get();
                if (status != 200) {
                    request.getResponseHeaders().set("Retry-After", "83");
                    request.sendResponseHeaders(status, -1);
                    return;
                }
                var suggestions = FIXTURE_JSON.createArrayNode();
                for (var finding : input) {
                    var suggestion = suggestions.addObject().put("findingRef", finding.path("findingRef").asText())
                            .put("explanation", "A response property was removed.")
                            .put("affectedClientCode", "Inspect DTOs and response mappers that read the removed property.");
                    suggestion.putArray("recommendedChanges").add("Update the DTO and mapper to match the new contract.");
                    suggestion.putArray("verificationSteps").add("Run a deserialization test against the new response.");
                }
                String text = FIXTURE_JSON.writeValueAsString(Map.of("suggestions", suggestions));
                byte[] body = FIXTURE_JSON.writeValueAsBytes(Map.of("candidates", List.of(Map.of("finishReason", "STOP",
                        "content", Map.of("parts", List.of(Map.of("text", text)))))));
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            }
        });
        fixtures.start();
        properties.add("spring.cloud.discovery.client.simple.instances.registry-service[0].uri",
                () -> "http://127.0.0.1:" + fixtures.getAddress().getPort());
        properties.add("sentinel.analyzer-registry-key", () -> KEY);
        properties.add("sentinel.impact-key", () -> IMPACT_KEY);
        properties.add("spring.datasource.url", () -> {
            String jdbc = DATABASE.getJdbcUrl();
            return jdbc + (jdbc.contains("?") ? "&" : "?") + "currentSchema=analyzer_db";
        });
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("spring.rabbitmq.host", BROKER::getHost);
        properties.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> USER);
        properties.add("spring.rabbitmq.password", () -> PASSWORD);
    }

    @AfterAll
    static void closeFixture() {
        if (fixtures != null) fixtures.stop(0);
    }

    @BeforeEach
    void reset() {
        db.sql("truncate analysis_findings, analysis_artifacts, analysis_jobs, processed_events, outbox_events, migration_suggestion_limits")
                .update();
        GEMINI_STATUS.set(200);
        GEMINI_CALLS.set(0);
        GEMINI_INPUT.set(null);
    }

    @Test
    void consumesExactlyOneLogicalJobFetchesSpecsAndPublishesFindings() throws Exception {
        var event = event(BASELINE, UPDATE);
        publish(json.writeValueAsBytes(event));
        publish(json.writeValueAsBytes(event));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jobCount()).isEqualTo(1));

        JobStore.Work work;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var first = workers.submit(() -> { gate.await(); return jobs.claim(); });
            var second = workers.submit(() -> { gate.await(); return jobs.claim(); });
            gate.countDown();
            work = first.get();
            var other = second.get();
            assertThat((work == null) != (other == null)).isTrue();
            if (work == null) work = other;
        }
        var job = work.job();
        var result = Compatibility.compare(registry.fetch(job.apiId(), BASELINE), registry.fetch(job.apiId(), UPDATE));
        assertThat(jobs.complete(work, result)).isTrue();
        assertThat(jobs.complete(work, result)).isFalse();
        assertThat(jobCount()).isEqualTo(1);

        var completed = rabbit.receive(Events.IMPACT_QUEUE, 12_000);
        assertThat(completed).isNotNull();
        var message = json.readTree(completed.getBody());
        assertThat(message.path("eventType").textValue()).isEqualTo("ANALYSIS_COMPLETED");
        assertThat(message.path("correlationId").textValue()).isEqualTo(event.correlationId().toString());
        assertThat(message.at("/payload/breakingCount").intValue()).isEqualTo(2);
        assertThat(message.at("/payload/status").textValue()).isEqualTo("COMPLETED");

        String url = "/analyses/" + job.id();
        http.perform(get(url)).andExpect(status().isUnauthorized());
        http.perform(get(url).with(owner(UUID.randomUUID()))).andExpect(status().isNotFound());
        http.perform(get(url + "/report").with(owner(job.ownerId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.findings.length()").value(4));
        String findings = "/internal" + url + "/findings";
        http.perform(get(findings).with(owner(job.ownerId()))).andExpect(status().isForbidden());
        http.perform(get(findings).header("X-Sentinel-Service-Key", IMPACT_KEY).param("severity", "BREAKING"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        assertThat(db.sql("select count(*) from processed_events").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void fencesExpiredWorkerAndRetainsRetryAndFailure() {
        var event = event(null, UPDATE);
        jobs.ingest(event);
        var first = jobs.claim();
        assertThat(jobs.fail(first, "Registry temporarily unavailable", true)).isEqualTo(JobStore.State.RETRY_PENDING);
        assertThat(jobs.claim()).isNull();
        db.sql("update analysis_jobs set next_attempt_at=now() - interval '1 second' where id = ?")
                .param(first.job().id()).update();
        var second = jobs.claim();
        assertThat(second.job().attemptCount()).isEqualTo(2);
        db.sql("update analysis_jobs set lease_until=now() - interval '1 second' where id = ?")
                .param(first.job().id()).update();
        var replacement = jobs.claim();
        assertThat(replacement.job().attemptCount()).isEqualTo(3);
        assertThat(jobs.complete(second, new Compatibility.Result(1, "NO_BASELINE", List.of()))).isFalse();
        assertThat(jobs.fail(replacement, "Malformed specification", false)).isEqualTo(JobStore.State.FAILED);
        assertThat(jobs.claim()).isNull();
        assertThat(db.sql("select status from analysis_jobs where id = ?").param(first.job().id()).query(String.class).single())
                .isEqualTo("FAILED");
        assertThat(db.sql("select count(*) from outbox_events").query(Integer.class).single()).isZero();
    }

    @Test
    void deadLettersInvalidEnvelopesAndTreatsMissingRegistrySpecAsPermanent() {
        publish(new byte[] {'{'});
        assertThat(rabbit.receive(Events.ANALYZER_QUEUE + ".dead", 8000)).isNotNull();
        assertThat(jobCount()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.fetch(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(OpenApiDocument.Invalid.class);
    }

    @Test
    void generatesGroundedSuggestionsForOwnerFromPersistedComparison() throws Exception {
        var job = completeComparison();
        String url = "/analyses/" + job.id() + "/migration-suggestions";
        UUID firstFinding = db.sql("select id from analysis_findings where analysis_id = ? and severity = 'BREAKING' order by ordinal")
                .param(job.id()).query(UUID.class).list().getFirst();

        http.perform(post(url)).andExpect(status().isUnauthorized());
        http.perform(post(url).with(actor(job.ownerId(), "CONSUMER"))).andExpect(status().isForbidden());
        http.perform(post(url).with(owner(UUID.randomUUID()))).andExpect(status().isNotFound());
        assertThat(GEMINI_CALLS).hasValue(0);

        http.perform(post(url).with(owner(job.ownerId())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.analysisId").value(job.id().toString()))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.source").value("GEMINI"))
                .andExpect(jsonPath("$.eligibleFindingCount").value(2))
                .andExpect(jsonPath("$.suggestions.length()").value(2))
                .andExpect(jsonPath("$.suggestions[0].findingId").value(firstFinding.toString()))
                .andExpect(jsonPath("$.suggestions[0].severity").value("BREAKING"))
                .andExpect(jsonPath("$.suggestions[0].recommendedChanges.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].verificationSteps.length()").value(1));
        assertThat(GEMINI_INPUT.get().toString()).contains("studentId", "RESPONSE_PROPERTY_REMOVED")
                .doesNotContain(firstFinding.toString(), job.id().toString(), job.ownerId().toString(), GEMINI_KEY);
        http.perform(post(url).with(owner(job.ownerId())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("MIGRATION_RATE_LIMITED"));
        assertThat(GEMINI_CALLS).hasValue(1);

        http.perform(get("/analyses/" + job.id() + "/report").with(owner(job.ownerId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.findings.length()").value(4));
    }

    @Test
    void rejectsIncompleteAndIneligibleAnalysesBeforeCallingTheProvider() throws Exception {
        var event = event(null, UPDATE);
        jobs.ingest(event);
        var user = owner(event.payload().ownerId());
        String url = "/analyses/" + event.payload().analysisId() + "/migration-suggestions";
        http.perform(post(url).with(user)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_COMPLETED"));
        assertThat(jobs.complete(jobs.claim(), new Compatibility.Result(1, "NO_BASELINE", List.of()))).isTrue();
        http.perform(post(url).with(user)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MIGRATION_FINDINGS"));
        assertThat(GEMINI_CALLS).hasValue(0);
    }

    @Test
    void appliesProviderCooldownAcrossUsersAndLeavesFindingsAvailable() throws Exception {
        var job = completeComparison();
        String url = "/analyses/" + job.id() + "/migration-suggestions";
        GEMINI_STATUS.set(429);
        http.perform(post(url).with(owner(job.ownerId())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "83"))
                .andExpect(jsonPath("$.code").value("GEMINI_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(83));
        UUID admin = UUID.randomUUID();
        http.perform(post(url).with(actor(admin, "ADMIN")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MIGRATION_RATE_LIMITED"));
        assertThat(GEMINI_CALLS).hasValue(1);
        assertThat(db.sql("select count(*) from migration_suggestion_limits where scope = ?")
                .param("user:" + admin).query(Integer.class).single()).isZero();

        db.sql("update migration_suggestion_limits set ready_at = clock_timestamp() - interval '1 second'").update();
        GEMINI_STATUS.set(HttpStatus.SERVICE_UNAVAILABLE.value());
        http.perform(post(url).with(actor(admin, "ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GEMINI_UNAVAILABLE"));
        http.perform(get("/analyses/" + job.id() + "/findings").with(owner(job.ownerId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
    }

    private JobStore.Job completeComparison() {
        jobs.ingest(event(BASELINE, UPDATE));
        var work = jobs.claim();
        var job = work.job();
        var result = Compatibility.compare(registry.fetch(job.apiId(), BASELINE), registry.fetch(job.apiId(), UPDATE));
        assertThat(jobs.complete(work, result)).isTrue();
        return job;
    }

    private int jobCount() {
        return db.sql("select count(*) from analysis_jobs").query(Integer.class).single();
    }

    private void publish(byte[] body) {
        rabbit.send(Events.EXCHANGE, Events.REGISTERED_ROUTE, MessageBuilder.withBody(body).setContentType("application/json").build());
    }

    private static RequestPostProcessor owner(UUID actor) {
        return actor(actor, "API_OWNER");
    }

    private static RequestPostProcessor actor(UUID actor, String role) {
        return jwt().jwt(token -> token.subject(actor.toString()).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static Events.Envelope<Events.VersionRegistered> event(UUID baseline, UUID version) {
        UUID owner = UUID.randomUUID();
        return new Events.Envelope<>(UUID.randomUUID(), 1, "VERSION_REGISTERED", UUID.randomUUID(), version, Instant.now(),
                "registry-service", new Events.VersionRegistered(UUID.randomUUID(), UUID.randomUUID(), version, baseline, owner, owner));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GeminiFixture {
        @Bean
        @Primary
        GeminiMigrationClient mockGemini(ObjectMapper json) {
            var origin = java.net.URI.create("http://127.0.0.1:" + fixtures.getAddress().getPort());
            return new GeminiMigrationClient(json, GEMINI_KEY, MODEL, origin, Duration.ofSeconds(4));
        }
    }
}

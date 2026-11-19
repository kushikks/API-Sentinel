package com.apisentinel.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.apisentinel.shared.Events;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
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
        "sentinel.jwt.issuer=https://sentinel.example.test", "sentinel.outbox-delay=150"})
@AutoConfigureMockMvc
@Testcontainers
class RegistryHttpIT {
    private static final String RABBIT_USER = "sentinel_test";
    private static final String RABBIT_PASSWORD = UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @Container static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.2.7-management")
            .withExposedPorts(5672).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(180)))
            .withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "+S 2:2 +SDcpu 1 +SDio 2")
            .withEnv("RABBITMQ_DEFAULT_USER", RABBIT_USER).withEnv("RABBITMQ_DEFAULT_PASS", RABBIT_PASSWORD);

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired RabbitTemplate rabbit;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> {
            String jdbc = DATABASE.getJdbcUrl();
            return jdbc + (jdbc.contains("?") ? "&" : "?") + "currentSchema=registry_db";
        });
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("spring.rabbitmq.host", BROKER::getHost);
        properties.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        properties.add("spring.rabbitmq.username", () -> RABBIT_USER);
        properties.add("spring.rabbitmq.password", () -> RABBIT_PASSWORD);
    }

    @Test
    void enforcesOwnershipAndRoleAndPersistsImmutableVersions() throws Exception {
        UUID owner = UUID.randomUUID();
        http.perform(get("/apis")).andExpect(status().isUnauthorized());
        http.perform(post("/apis").with(as(owner, "CONSUMER")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Students\"}"))
                .andExpect(status().isForbidden());
        UUID api = create(owner);
        http.perform(post("/apis").with(as(owner, "API_OWNER")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"students\"}"))
                .andExpect(status().isConflict());
        http.perform(get("/apis/" + api).with(as(UUID.randomUUID(), "API_OWNER"))).andExpect(status().isNotFound());
        http.perform(get("/apis/" + api).with(as(UUID.randomUUID(), "ADMIN"))).andExpect(status().isOk());
        http.perform(patch("/apis/" + api).with(as(owner, "API_OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Student information\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Student information"));
        http.perform(get("/apis").param("q", "information").with(as(owner, "API_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(api.toString()));
        byte[] yaml = fixture("student-v1.yaml");
        byte[] jsonSpec = fixture("student-v2.json");
        var first = upload(owner, api, "V1", "student.yaml", yaml, false, 201);
        upload(owner, api, "V2", "student.json", jsonSpec, false, 201);
        upload(owner, api, "v1", "student.json", jsonSpec, false, 409);
        http.perform(get("/apis/" + api + "/versions").with(as(owner, "API_OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].versionLabel").value("V2"))
                .andExpect(jsonPath("$.items[1].versionLabel").value("V1"));
        var loaded = http.perform(get("/apis/" + api + "/versions/" + first + "/spec").with(as(owner, "API_OWNER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(loaded).isEqualTo(yaml);
    }

    @Test
    void rejectsMalformedOversizedAndUnsafeDocuments() throws Exception {
        var owner = UUID.randomUUID();
        var api = create(owner);
        upload(owner, api, "bad", "bad.json", "{".getBytes(java.nio.charset.StandardCharsets.UTF_8), false, 422);
        upload(owner, api, "large", "large.json", new byte[1_048_577], false, 413);
        String yaml = new String(fixture("student-v1.yaml"), java.nio.charset.StandardCharsets.UTF_8);
        byte[] unsafe = yaml.replace("'#/components/schemas/Student'", "'https://example.invalid/model.yaml'")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        upload(owner, api, "unsafe", "unsafe.yaml", unsafe, false, 422);
        assertThat(db.sql("select count(*) from api_versions where api_id = ?").param(api).query(Integer.class).single()).isZero();
    }

    @Test
    void commitsVersionDuringBrokerOutageAndPublishesAfterRecovery() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID api = create(owner);
        UUID version;
        var docker = BROKER.getDockerClient();
        docker.pauseContainerCmd(BROKER.getContainerId()).exec();
        try {
            version = upload(owner, api, "queued", "student.yaml", fixture("student-v1.yaml"), true, 201);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(db.sql("""
                    select count(*) from outbox_events where published_at is null and attempts > 0
                    """).query(Integer.class).single()).isEqualTo(1));
        } finally {
            docker.unpauseContainerCmd(BROKER.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(db.sql("""
                select count(*) from outbox_events where published_at is not null and attempts > 0
                """).query(Integer.class).single()).isEqualTo(1));
        var received = rabbit.receive(Events.ANALYZER_QUEUE, 5000);
        assertThat(received).isNotNull();
        var event = json.readTree(received.getBody());
        assertThat(event.path("eventType").textValue()).isEqualTo("VERSION_REGISTERED");
        assertThat(event.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(event.at("/payload/versionId").textValue()).isEqualTo(version.toString());
        assertThat(event.at("/payload/baselineVersionId").isNull()).isTrue();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(event.path("eventId").textValue());
    }

    private UUID create(UUID owner) throws Exception {
        var response = http.perform(post("/apis").with(as(owner, "API_OWNER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Students\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("id").textValue());
    }

    private UUID upload(UUID owner, UUID api, String label, String filename, byte[] bytes,
                        boolean analyze, int expected) throws Exception {
        var upload = http.perform(multipart("/apis/" + api + "/versions")
                        .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
                        .param("versionLabel", label).param("analyze", Boolean.toString(analyze)).with(as(owner, "API_OWNER")))
                .andExpect(status().is(expected)).andReturn();
        return expected == 201 ? UUID.fromString(json.readTree(upload.getResponse().getContentAsString()).at("/version/id").textValue()) : null;
    }

    private static RequestPostProcessor as(UUID actor, String role) {
        return jwt().jwt(token -> token.subject(actor.toString()).claim("roles", List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static byte[] fixture(String filename) throws Exception {
        return Files.readAllBytes(Path.of("../../tests/developer-a/fixtures", filename));
    }
}

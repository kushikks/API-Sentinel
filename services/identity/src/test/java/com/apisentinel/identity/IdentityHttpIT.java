package com.apisentinel.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.flyway.create-schemas=true",
        "sentinel.jwt.issuer=https://sentinel.example.test", "sentinel.jwt.key-id=identity-it"})
@AutoConfigureMockMvc
@Testcontainers
class IdentityHttpIT {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.11-bookworm");
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @Autowired PasswordEncoder encoder;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) throws Exception {
        properties.add("spring.datasource.url", () -> {
            String jdbc = DATABASE.getJdbcUrl();
            return jdbc + (jdbc.contains("?") ? "&" : "?") + "currentSchema=identity_db";
        });
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        var directory = Files.createTempDirectory("sentinel-identity-test-");
        var key = directory.resolve("signing.p12");
        var password = UUID.randomUUID().toString();
        String binary = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", binary).toString(),
                "-genkeypair", "-alias", "sentinel", "-dname", "CN=Identity Test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", key.toString(), "-storepass:env", "SENTINEL_TEST_KEY_PASSWORD");
        command.environment().put("SENTINEL_TEST_KEY_PASSWORD", password);
        command.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (command.start().waitFor() != 0) throw new IllegalStateException("Test keystore creation failed.");
        directory.toFile().deleteOnExit();
        key.toFile().deleteOnExit();
        properties.add("sentinel.jwt.keystore", () -> key.toUri().toString());
        properties.add("sentinel.jwt.keystore-password", () -> password);
    }

    @Test
    void persistsHashedCredentialsAndEnforcesPublicRoles() throws Exception {
        var email = UUID.randomUUID().toString() + "@example.test";
        var password = UUID.randomUUID().toString();
        register(email, password, "ADMIN", 403);
        register(email, password, "API_OWNER", 201);
        register(email.toUpperCase(java.util.Locale.ROOT), password, "CONSUMER", 409);
        var hash = db.sql("select password_hash from users where email = ?").param(email).query(String.class).single();
        assertThat(hash).isNotEqualTo(password);
        assertThat(encoder.matches(password, hash)).isTrue();
        http.perform(get("/auth/.well-known/jwks.json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        http.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        http.perform(get("/auth/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }

    @Test
    void rotatesRevokesAndRejectsRefreshAndLogoutWithoutCsrf() throws Exception {
        var email = UUID.randomUUID().toString() + "@example.test";
        var password = UUID.randomUUID().toString();
        register(email, password, "CONSUMER", 201);
        login(email, "incorrect", 401);
        var login = login(email, password, 200);
        var access = json.readTree(login.getResponse().getContentAsString()).path("accessToken").textValue();
        var old = cookie(login);
        assertThat(login.getResponse().getHeader("Set-Cookie")).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/auth");
        http.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));

        http.perform(post("/auth/refresh").cookie(old)).andExpect(status().isForbidden());
        var refreshed = http.perform(post("/auth/refresh").header("X-Sentinel-CSRF", "1").cookie(old))
                .andExpect(status().isOk()).andReturn();
        var next = cookie(refreshed);
        assertThat(next.getValue()).isNotEqualTo(old.getValue());
        http.perform(post("/auth/refresh").header("X-Sentinel-CSRF", "1").cookie(old)).andExpect(status().isUnauthorized());
        http.perform(post("/auth/refresh").header("X-Sentinel-CSRF", "1").cookie(next)).andExpect(status().isUnauthorized());

        var active = cookie(login(email, password, 200));
        http.perform(post("/auth/logout").cookie(active)).andExpect(status().isForbidden());
        http.perform(post("/auth/logout").header("X-Sentinel-CSRF", "1").cookie(active)).andExpect(status().isNoContent());
        http.perform(post("/auth/refresh").header("X-Sentinel-CSRF", "1").cookie(active)).andExpect(status().isUnauthorized());
        assertThat(db.sql("select count(*) from authentication_audit where action = 'REFRESH_REUSE'").query(Long.class).single())
                .isPositive();
    }

    private MvcResult login(String email, String password, int expected) throws Exception {
        return http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).header("X-Sentinel-CSRF", "1")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().is(expected)).andReturn();
    }

    private void register(String email, String password, String role, int expected) throws Exception {
        http.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password, "displayName", "Developer", "role", role))))
                .andExpect(status().is(expected));
    }

    private static Cookie cookie(MvcResult result) {
        String value = result.getResponse().getHeader("Set-Cookie").split(";", 2)[0].substring("sentinel_refresh=".length());
        return new Cookie("sentinel_refresh", value);
    }
}

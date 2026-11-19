package com.apisentinel.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

public final class SecurityPolicy {
    private SecurityPolicy() {}

    public static void configure(HttpSecurity http, ObjectMapper json) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                send(response, json, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required."))
                        .accessDeniedHandler((request, response, error) ->
                                send(response, json, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied.")))
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint((request, response, error) ->
                                send(response, json, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required."))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roles())));
    }

    public static CorsConfigurationSource cors(String allowedOrigins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::strip).filter(origin -> !origin.isBlank()).toList());
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Sentinel-CSRF"));
        config.setExposedHeaders(List.of("Location", "Content-Disposition"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    public static NimbusJwtDecoder remoteDecoder(String jwks, String issuer, String audience) {
        var requests = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        requests.setReadTimeout(Duration.ofSeconds(3));
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(new RestTemplate(requests)).build();
        validate(decoder, issuer, audience);
        return decoder;
    }

    public static void validate(NimbusJwtDecoder decoder, String issuer, String audience) {
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience)),
                new JwtClaimValidator<java.time.Instant>("exp", value -> value != null),
                new JwtClaimValidator<String>("sub", SecurityPolicy::isUuid)));
    }

    public static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public static boolean hasRole(Jwt jwt, String role) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains(role);
    }

    public static boolean serviceKey(String expected, String presented) {
        return expected != null && expected.length() >= 32 && presented != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isUuid(String subject) {
        if (subject == null) return false;
        try {
            return UUID.fromString(subject).toString().equals(subject);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static JwtAuthenticationConverter roles() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    private static void send(HttpServletResponse response, ObjectMapper json, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        var body = ApiErrors.body(status, code, detail);
        body.setTitle(status.getReasonPhrase());
        json.writeValue(response.getOutputStream(), body);
    }
}

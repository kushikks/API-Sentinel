package com.apisentinel.analyzer;

import com.apisentinel.shared.SecurityPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
class AnalyzerSecurity {
    @Bean
    SecurityFilterChain analyzerChain(HttpSecurity http, ObjectMapper json,
            @Value("${sentinel.impact-key}") String impact, @Value("${sentinel.registry-analyzer-key}") String registry,
            @Value("${sentinel.metrics-key}") String metrics) throws Exception {
        SecurityPolicy.configure(http, json);
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                            .access((principal, context) -> new AuthorizationDecision(SecurityPolicy.serviceKey(
                                    metrics, context.getRequest().getHeader("X-Sentinel-Service-Key"))))
                        .requestMatchers(HttpMethod.GET, "/internal/analyses/*/status")
                            .access((principal, context) -> new AuthorizationDecision(SecurityPolicy.serviceKey(
                                    registry, context.getRequest().getHeader("X-Sentinel-Service-Key"))))
                        .requestMatchers(HttpMethod.GET, "/internal/analyses/*/findings", "/internal/analyses/*/summary")
                            .access((principal, context) -> new AuthorizationDecision(SecurityPolicy.serviceKey(
                                    impact, context.getRequest().getHeader("X-Sentinel-Service-Key"))))
                        .requestMatchers(HttpMethod.GET, "/analyses", "/analyses/**").hasAnyRole("API_OWNER", "CONSUMER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/analyses/*/migration-suggestions").hasAnyRole("API_OWNER", "ADMIN")
                        .anyRequest().denyAll()).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${sentinel.cors-origins}") String origins) {
        return SecurityPolicy.cors(origins);
    }

    @Bean
    JwtDecoder analyzerDecoder(@Value("${sentinel.jwt.jwks}") String jwks, @Value("${sentinel.jwt.issuer}") String issuer,
                               @Value("${sentinel.jwt.audience}") String audience) {
        return SecurityPolicy.remoteDecoder(jwks, issuer, audience);
    }
}

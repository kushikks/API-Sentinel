package com.apisentinel.registry;

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
class RegistrySecurity {
    @Bean
    SecurityFilterChain registryChain(HttpSecurity http, ObjectMapper json,
            @Value("${sentinel.analyzer-registry-key}") String analyzer,
            @Value("${sentinel.impact-key}") String impact) throws Exception {
        SecurityPolicy.configure(http, json);
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/apis/*/versions/*/spec")
                            .access((principal, context) -> new AuthorizationDecision(SecurityPolicy.serviceKey(
                                    analyzer, context.getRequest().getHeader("X-Sentinel-Service-Key"))))
                        .requestMatchers(HttpMethod.GET, "/internal/apis/*")
                            .access((principal, context) -> {
                                String key = context.getRequest().getHeader("X-Sentinel-Service-Key");
                                return new AuthorizationDecision(SecurityPolicy.serviceKey(analyzer, key)
                                        || SecurityPolicy.serviceKey(impact, key));
                            })
                        .requestMatchers(HttpMethod.GET, "/apis", "/apis/**").hasAnyRole("API_OWNER", "CONSUMER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/apis", "/apis/**").hasAnyRole("API_OWNER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/apis/*").hasAnyRole("API_OWNER", "ADMIN")
                        .anyRequest().denyAll()).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${sentinel.cors-origins}") String origins) {
        return SecurityPolicy.cors(origins);
    }

    @Bean
    JwtDecoder registryDecoder(@Value("${sentinel.jwt.jwks}") String jwks,
                               @Value("${sentinel.jwt.issuer}") String issuer,
                               @Value("${sentinel.jwt.audience}") String audience) {
        return SecurityPolicy.remoteDecoder(jwks, issuer, audience);
    }
}

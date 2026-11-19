package com.apisentinel.identity;

import com.apisentinel.shared.SecurityPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
class IdentitySecurity {
    @Bean
    RSAKey signingKey(@Value("${sentinel.jwt.keystore}") Resource location,
                      @Value("${sentinel.jwt.keystore-password}") String password,
                      @Value("${sentinel.jwt.key-id}") String keyId) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = location.getInputStream()) {
            store.load(input, password.toCharArray());
        }
        var privateKey = (RSAPrivateKey) store.getKey("sentinel", password.toCharArray());
        var publicKey = (RSAPublicKey) store.getCertificate("sentinel").getPublicKey();
        if (privateKey == null || publicKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException("A valid RSA signing key of at least 2048 bits is required");
        }
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
    }

    @Bean
    JwtEncoder encoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder decoder(RSAKey key, @Value("${sentinel.jwt.issuer}") String issuer,
                       @Value("${sentinel.jwt.audience}") String audience) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        SecurityPolicy.validate(decoder, issuer, audience);
        return decoder;
    }

    @Bean
    PasswordEncoder passwords() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${sentinel.cors-origins:}") String allowedOrigins) {
        return SecurityPolicy.cors(allowedOrigins);
    }

    @Bean
    SecurityFilterChain identityChain(HttpSecurity http, ObjectMapper json) throws Exception {
        SecurityPolicy.configure(http, json);
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/auth/.well-known/jwks.json", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
                        .anyRequest().denyAll())
                .build();
    }
}

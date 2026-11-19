package com.apisentinel.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.apisentinel.shared.SecurityPolicy;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenValidationTest {
    @Test
    void checksSignatureIssuerAudienceExpiryAndSubjectOffline() throws Exception {
        var trusted = new RSAKeyGenerator(2048).generate();
        var decoder = NimbusJwtDecoder.withPublicKey(trusted.toRSAPublicKey()).build();
        SecurityPolicy.validate(decoder, "https://sentinel.example.test", "api-sentinel");
        var signer = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(trusted)));
        String user = UUID.randomUUID().toString();
        Instant now = Instant.now();
        var valid = claims(user).expiresAt(now.plusSeconds(120)).build();
        assertThat(decoder.decode(sign(signer, valid)).getSubject()).isEqualTo(user);
        var expired = claims(user).issuedAt(now.minusSeconds(240)).expiresAt(now.minusSeconds(120)).build();
        var wrongAudience = claims(user).audience(List.of("another-app")).expiresAt(now.plusSeconds(120)).build();
        var wrongIssuer = claims(user).issuer("https://elsewhere.example.test").expiresAt(now.plusSeconds(120)).build();
        var invalidSubject = claims("anonymous").expiresAt(now.plusSeconds(120)).build();
        for (var rejected : List.of(expired, wrongAudience, wrongIssuer, invalidSubject, claims(user).build())) {
            assertThatThrownBy(() -> decoder.decode(sign(signer, rejected))).isInstanceOf(JwtException.class);
        }
        var untrusted = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKeyGenerator(2048).generate())));
        assertThatThrownBy(() -> decoder.decode(sign(untrusted, valid))).isInstanceOf(JwtException.class);
    }

    private static JwtClaimsSet.Builder claims(String subject) {
        return JwtClaimsSet.builder().subject(subject).issuer("https://sentinel.example.test")
                .audience(List.of("api-sentinel")).issuedAt(Instant.now());
    }

    private static String sign(NimbusJwtEncoder signer, JwtClaimsSet claims) {
        return signer.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}

package io.contextads;

import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

import static org.junit.jupiter.api.Assertions.*;

class JwtSignatureTest {
    @Test
    void verifiesSignatureAndRejectsWrongIssuerAudienceExpiryAndKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var other = generator.generateKeyPair();
        var key = new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).keyID("test-key").build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).build();
        decoder.setJwtValidator(Security.validator("https://id.example.org", "contextads-api"));
        for (String scenario : List.of("valid", "issuer", "audience", "expired", "missing-expiry")) {
            var builder = JwtClaimsSet.builder().subject("advertiser-subject").issuedAt(Instant.now().minusSeconds(600))
                    .issuer(scenario.equals("issuer") ? "https://wrong.example.org" : "https://id.example.org")
                    .audience(List.of(scenario.equals("audience") ? "other-api" : "contextads-api"));
            if (!scenario.equals("missing-expiry"))
                builder.expiresAt(Instant.now().plusSeconds(scenario.equals("expired") ? -120 : 300));
            String token = encoder.encode(JwtEncoderParameters.from(builder.build())).getTokenValue();
            if (scenario.equals("valid")) {
                assertEquals("advertiser-subject", decoder.decode(token).getSubject());
                var wrong = NimbusJwtDecoder.withPublicKey((RSAPublicKey) other.getPublic()).build();
                assertThrows(JwtException.class, () -> wrong.decode(token));
                String[] parts = token.split("[.]");
                String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"attacker\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThrows(JwtException.class, () -> decoder.decode(parts[0] + "." + payload + "." + parts[2]));
            } else assertThrows(JwtException.class, () -> decoder.decode(token), scenario);
        }
    }
}

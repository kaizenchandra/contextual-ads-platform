package io.contextads;

import java.time.*;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

import static org.junit.jupiter.api.Assertions.*;

class PacingAndIdentityTest {
    final Instant midnight = Instant.parse("2026-11-17T00:00:00Z");

    @Test
    void evenReleaseIsProportionalAndBounded() {
        assertEquals(0, Pacing.allowance("EVEN", 100, midnight, midnight, midnight.plusSeconds(86400)));
        assertEquals(50, Pacing.allowance("EVEN", 100, midnight.plusSeconds(43200), midnight, midnight.plusSeconds(86400)));
        assertEquals(100, Pacing.allowance("ACCELERATED", 100, midnight, midnight, midnight.plusSeconds(86400)));
        assertEquals(0, Pacing.allowance("ACCELERATED", 100, midnight.plusSeconds(86400), midnight, midnight.plusSeconds(86400)));
    }

    @Test
    void partialDayUsesOnlyScheduledWindow() {
        assertEquals(50, Pacing.allowance("EVEN", 100, midnight.plusSeconds(64800), midnight.plusSeconds(43200), midnight.plusSeconds(86400)));
        assertEquals(0, Pacing.allowance("EVEN", 100, midnight, midnight.plusSeconds(43200), midnight.plusSeconds(86400)));
    }

    @Test
    void largeBudgetDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE / 2, Pacing.allowance("EVEN", Long.MAX_VALUE, midnight.plusSeconds(43200), midnight, midnight.plusSeconds(86400)));
    }

    @Test
    void domainValidation() {
        assertEquals("publisher.com", Publishers.domain("Publisher.COM"));
        for (String bad : List.of("127.0.0.1", "localhost", "metadata.internal", "https://publisher.com", "publisher.com/path", "[::1]", "publisher.com.", "-bad.com", "x.local"))
            assertThrows(IllegalArgumentException.class, () -> Publishers.domain(bad), bad);
    }

    Jwt token(String issuer, List<String> audience, Instant expiry) {
        return Jwt.withTokenValue("test").header("alg", "RS256").issuer(issuer).subject("subject").audience(audience).issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry).build();
    }

    @Test
    void issuerAudienceAndExpiryValidated() {
        var validator = Security.validator("https://issuer.example.org", "contextads-api");
        assertFalse(validator.validate(token("https://issuer.example.org", List.of("contextads-api"), Instant.now().plusSeconds(100))).hasErrors());
        assertTrue(validator.validate(token("https://evil.example.org", List.of("contextads-api"), Instant.now().plusSeconds(100))).hasErrors());
        assertTrue(validator.validate(token("https://issuer.example.org", List.of("other"), Instant.now().plusSeconds(100))).hasErrors());
        assertTrue(validator.validate(token("https://issuer.example.org", List.of("contextads-api"), Instant.now().minusSeconds(120))).hasErrors());
    }

    @Test
    void onlyApiClientRolesAreAccepted() {
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("x")
                .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                .claim("resource_access", Map.of("contextads-api", Map.of("roles", List.of("PUBLISHER", "unknown")), "other-api", Map.of("roles", List.of("ADMIN")))).build();
        assertEquals(List.of("PUBLISHER"), Security.roles(jwt));
    }
}

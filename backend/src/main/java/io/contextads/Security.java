package io.contextads;

import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Configuration
class Security {
    private static void problem(jakarta.servlet.http.HttpServletResponse response, int status) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status + ",\"title\":\"" + (status == 401 ? "Unauthorized" : "Forbidden") + "\"}");
    }

    // Only roles explicitly assigned to this API client are trusted; realm-wide roles are ignored.
    static List<String> roles(Jwt jwt) {
        Object access = jwt.getClaim("resource_access");
        if (!(access instanceof Map<?, ?> resources) || !(resources.get("contextads-api") instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof List<?> roles)) return List.of();
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(Set.of("ADVERTISER", "PUBLISHER", "ADMIN")::contains).toList();
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), jwt ->
                jwt.getExpiresAt() != null && jwt.getAudience().contains(audience) && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing subject or API audience", null)));
    }

    @Bean
    SecurityFilterChain filter(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> roles(jwt).stream()
                .<org.springframework.security.core.GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
        return http.csrf(c -> c.disable()) // Bearer-only APIs: no cookie authentication.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/api/v1/ads/impressions", "/api/v1/ads/click/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/publishers/**", "/api/v1/context/**", "/api/v1/ads/decide").hasRole("PUBLISHER")
                        .requestMatchers("/api/v1/campaigns/**", "/api/v1/analytics", "/api/v1/conversions", "/api/v1/billing/**").hasRole("ADVERTISER")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> problem(response, 401))
                        .accessDeniedHandler((request, response, error) -> problem(response, 403)))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, error) -> problem(response, 401))
                        .accessDeniedHandler((request, response, error) -> problem(response, 403))).build();
    }

    @Bean
    JwtDecoder decoder(@Value("${ads.oidc.issuer}") String issuer,
                       @Value("${ads.oidc.jwk-set-uri}") String jwks,
                       @Value("${ads.oidc.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(validator(issuer, audience));
        return decoder;
    }
}

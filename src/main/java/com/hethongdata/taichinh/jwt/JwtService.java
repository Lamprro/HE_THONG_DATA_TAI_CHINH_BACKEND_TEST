package com.hethongdata.taichinh.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

@Service
public class JwtService implements JwtDecoder {
    private static final String ISSUER = "financial-data-api";
    private static final String AUDIENCE = "financial-web";
    private static final Duration LIFETIME = Duration.ofDays(1);
    private static final String SESSION_VERSION = "2";
    private static final String REVOKED_KEY = "financial:auth:revoked:";

    private final boolean persistentKey;
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final StringRedisTemplate redis;

    public JwtService(@Value("${financial.security.jwt-secret:}") String configuredKey,
                      StringRedisTemplate redis) {
        this.redis = redis;
        persistentKey = !configuredKey.isBlank();
        byte[] keyBytes;
        if (persistentKey) {
            keyBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < 32) throw new IllegalArgumentException("FINANCIAL_JWT_SECRET needs at least 32 UTF-8 bytes");
        } else {
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
        }
        var key = new SecretKeySpec(keyBytes, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(ISSUER), new JwtAudienceValidator(AUDIENCE),
                new JwtClaimValidator<String>("session_version", SESSION_VERSION::equals),
                new JwtClaimValidator<Instant>("exp", expires -> expires != null),
                new JwtClaimValidator<String>("sub", subject -> subject != null && !subject.isBlank())));
    }

    public boolean persistentKeyConfigured() {
        return persistentKey;
    }

    public Jwt issue(Authentication authentication, String email) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(authentication.getName()).id(UUID.randomUUID().toString())
                .issuedAt(now).notBefore(now).expiresAt(now.plus(LIFETIME))
                .claim("session_version", SESSION_VERSION)
                .claim("roles", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        if (email != null && !email.isBlank()) claims.claim("email", email);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()));
    }

    public void revoke(Jwt token) {
        Duration remaining = Duration.between(Instant.now(), token.getExpiresAt());
        if (remaining.isPositive()) {
            redis.opsForValue().set(revocationKey(token), "1", remaining.plusMillis(1));
        }
    }

    @Override
    public Jwt decode(String token) {
        Jwt jwt = decoder.decode(token);
        try {
            if (!Boolean.FALSE.equals(redis.hasKey(revocationKey(jwt)))) throw new BadJwtException("Access token revoked");
        } catch (DataAccessException unavailable) {
            throw new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"), unavailable);
        }
        return jwt;
    }

    private static String revocationKey(Jwt token) {
        try {
            return REVOKED_KEY + UUID.fromString(token.getId());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BadJwtException("Invalid access token ID");
        }
    }
}

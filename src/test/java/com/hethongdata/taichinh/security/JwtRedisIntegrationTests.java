package com.hethongdata.taichinh.security;

import com.hethongdata.taichinh.jwt.JwtService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "FINANCIAL_TEST_REDIS_PORT", matches = "[0-9]+")
class JwtRedisIntegrationTests {
    @Test
    void revocationIsSharedAndExpiresWithTheJwt() throws InterruptedException {
        var connection = new LettuceConnectionFactory(
                "127.0.0.1", Integer.parseInt(System.getenv("FINANCIAL_TEST_REDIS_PORT")));
        connection.afterPropertiesSet();
        var redis = new StringRedisTemplate(connection);
        var signingKey = "fixture signing key for Redis JWT logout";
        var tokens = new JwtService(signingKey, redis);
        var verifier = new JwtService(signingKey, redis);
        var account = UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var token = tokens.issue(account, null);
        var next = tokens.issue(account, null);
        var shortId = UUID.randomUUID().toString();
        var key = "financial:auth:revoked:" + token.getId();
        var shortKey = "financial:auth:revoked:" + shortId;
        try {
            assertThat(verifier.decode(token.getTokenValue()).getId()).isEqualTo(token.getId());
            tokens.revoke(token);
            assertThat(redis.getExpire(key)).isBetween(86_380L, 86_400L);
            assertThatThrownBy(() -> verifier.decode(token.getTokenValue())).isInstanceOf(BadJwtException.class);
            assertThat(verifier.decode(next.getTokenValue()).getId()).isEqualTo(next.getId());

            var shortLived = Jwt.withTokenValue("test-only").header("alg", "HS256")
                    .claim("jti", shortId).expiresAt(Instant.now().plusSeconds(2)).build();
            tokens.revoke(shortLived);
            assertThat(redis.hasKey(shortKey)).isTrue();
            for (int attempt = 0; attempt < 30 && Boolean.TRUE.equals(redis.hasKey(shortKey)); attempt++) {
                Thread.sleep(100);
            }
            assertThat(redis.hasKey(shortKey)).isFalse();
        } finally {
            try {
                redis.delete(List.of(key, shortKey));
            } finally {
                connection.destroy();
            }
        }
    }
}

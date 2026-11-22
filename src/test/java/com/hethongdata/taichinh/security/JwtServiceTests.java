package com.hethongdata.taichinh.security;

import com.hethongdata.taichinh.jwt.JwtService;
import com.hethongdata.taichinh.repository.jpa.account.RevokedAccessTokenJpaRepository;
import static org.mockito.Mockito.mock;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTests {
    private static final byte[] FIXTURE_KEY = "fake-local-jwt-expiry-fixture-1234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final JwtService service = new JwtService(new String(FIXTURE_KEY, StandardCharsets.UTF_8), mock(RevokedAccessTokenJpaRepository.class));

    @Test
    void verifiesSignatureAudienceAndExpiry() {
        assertThat(service.decode(signed("financial-web", Instant.now().plusSeconds(60))).getSubject())
                .isEqualTo("fixture");
        assertThatThrownBy(() -> service.decode(signed("financial-web", Instant.now().minusSeconds(60))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.decode(signed("unrelated-app", Instant.now().plusSeconds(60))))
                .isInstanceOf(JwtException.class);
        String valid = signed("financial-web", Instant.now().plusSeconds(60));
        int signature = valid.lastIndexOf('.') + 1;
        String altered = valid.substring(0, signature) + (valid.charAt(signature) == 'A' ? 'B' : 'A')
                + valid.substring(signature + 1);
        assertThatThrownBy(() -> service.decode(altered)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsShortConfiguredTextKeys() {
        assertThatThrownBy(() -> new JwtService("short", mock(RevokedAccessTokenJpaRepository.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 UTF-8 bytes");
    }

    private String signed(String audience, Instant expires) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(FIXTURE_KEY, "HmacSHA256")));
        var claims = JwtClaimsSet.builder().issuer("financial-data-api").audience(List.of(audience))
                .subject("fixture").id(java.util.UUID.randomUUID().toString()).issuedAt(Instant.now().minusSeconds(120)).expiresAt(expires).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}

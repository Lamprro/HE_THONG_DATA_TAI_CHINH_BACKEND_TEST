package com.hethongdata.taichinh.support;

import com.hethongdata.taichinh.jwt.JwtService;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class TestJwt {
    private static final String TEST_KEY = "fake local financial JWT fixture key only";
    public static final String PROPERTY = "financial.security.jwt-secret=" + TEST_KEY;
    private static final JwtService TOKENS = new JwtService(TEST_KEY);

    private TestJwt() {}

    public static String admin() {
        return accessToken("operator", "ADMIN");
    }

    public static String accessToken(String username, String role) {
        return TOKENS.issue(UsernamePasswordAuthenticationToken.authenticated(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))), username + "@example.test").getTokenValue();
    }
}

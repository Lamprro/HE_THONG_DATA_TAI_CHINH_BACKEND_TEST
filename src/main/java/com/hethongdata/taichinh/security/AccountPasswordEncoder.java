package com.hethongdata.taichinh.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

final class AccountPasswordEncoder implements PasswordEncoder {
    private static final String LEGACY_PREFIX = "{bcrypt}";
    private static final String PEPPER_PREFIX = "{bcrypt-pepper}";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String PEPPER_CONTEXT = "financial-account-password-v1";

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
    private final SecretKeySpec pepper;

    AccountPasswordEncoder(String authSecret) {
        pepper = authSecret.isBlank() ? null
                : new SecretKeySpec(hmac(authSecret.getBytes(StandardCharsets.UTF_8), PEPPER_CONTEXT), HMAC_ALGORITHM);
    }

    @Override
    public String encode(CharSequence password) {
        if (pepper == null) return bcrypt.encode(password);
        return PEPPER_PREFIX + bcrypt.encode(prehash(password));
    }

    @Override
    public boolean matches(CharSequence password, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) return false;
        if (storedHash.startsWith(PEPPER_PREFIX)) {
            return pepper != null && bcrypt.matches(prehash(password), storedHash.substring(PEPPER_PREFIX.length()));
        }
        String legacy = storedHash.startsWith(LEGACY_PREFIX)
                ? storedHash.substring(LEGACY_PREFIX.length()) : storedHash;
        return bcrypt.matches(password, legacy);
    }

    @Override
    public boolean upgradeEncoding(String hash) {
        return pepper != null && !hash.startsWith(PEPPER_PREFIX);
    }

    private String prehash(CharSequence password) {
        return HexFormat.of().formatHex(hmac(pepper.getEncoded(), password.toString()));
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
            hmac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return hmac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Password pre-hash unavailable", ex);
        }
    }
}

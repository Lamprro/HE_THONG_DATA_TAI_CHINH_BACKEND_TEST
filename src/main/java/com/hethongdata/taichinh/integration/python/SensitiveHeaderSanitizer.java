package com.hethongdata.taichinh.integration.python;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class SensitiveHeaderSanitizer {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key");

    public Map<String, List<String>> sanitize(HttpHeaders headers) {
        Map<String, List<String>> sanitized = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String key = name.toLowerCase(Locale.ROOT);
            sanitized.put(name, SENSITIVE_HEADERS.contains(key) ? List.of("[REDACTED]") : List.copyOf(values));
        });
        return Map.copyOf(sanitized);
    }
}

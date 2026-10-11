package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.databind.JsonNode;

/** A link is not article evidence. SUCCESS still goes through every content validation rule. */
public final class NewsContentClassification {
    private NewsContentClassification() {}

    public static boolean urlOnly(JsonNode payload) {
        if (payload == null || !payload.isObject()) return false;
        boolean link=false;
        for (String field:java.util.List.of("requested_url","final_url","canonical_url")) {
            try {
                var uri=java.net.URI.create(payload.path(field).asText(""));
                if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost()!=null && uri.getUserInfo()==null) link=true;
            } catch (IllegalArgumentException ignored) {}
        }
        if (!link) return false;
        String status = payload.path("extraction_status").asText("");
        return "FAILED".equals(status) || "SKIPPED".equals(status)
                || (status.isBlank() && payload.path("content_text").asText("").isBlank());
    }

    public static boolean validStatus(JsonNode payload) {
        if (payload == null || !payload.isObject()) return false;
        JsonNode status = payload.get("extraction_status");
        return status == null || status.isNull() || (status.isTextual()
                && java.util.Set.of("SUCCESS", "FAILED", "SKIPPED").contains(status.asText()));
    }
}

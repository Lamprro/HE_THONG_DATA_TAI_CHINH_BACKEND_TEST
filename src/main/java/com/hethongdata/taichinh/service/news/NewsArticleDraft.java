package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Normalized article fields extracted from one validated NEWS_DATA raw payload. */
public record NewsArticleDraft(
        String canonicalUrl,
        String title,
        String sapo,
        String contentText,
        Instant publishedAt,
        String urlHash,
        String contentHash,
        JsonNode metadata) {}

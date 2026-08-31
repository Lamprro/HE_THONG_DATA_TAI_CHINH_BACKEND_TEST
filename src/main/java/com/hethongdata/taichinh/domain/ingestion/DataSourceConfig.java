package com.hethongdata.taichinh.domain.ingestion;

public record DataSourceConfig(
        long id,
        String code,
        String name,
        String provider,
        String baseUrl,
        boolean active) {
}

package com.hethongdata.taichinh.dto.source;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;

public record DataSourceResponse(
        long id, String code, String name, String provider, String baseUrl, boolean active) {

    public static DataSourceResponse from(DataSourceEntity entity) {
        return new DataSourceResponse(entity.getId(), entity.getCode(), entity.getName(),
                entity.getProvider(), entity.getBaseUrl(), entity.isActive());
    }
}

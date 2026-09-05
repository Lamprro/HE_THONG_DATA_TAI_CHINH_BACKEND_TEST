package com.hethongdata.taichinh.dto.source;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class DataSourceResponse {

    private final long id;

    private final String code;

    private final String name;

    private final String provider;

    private final String baseUrl;

    private final boolean active;

    public static DataSourceResponse from(DataSourceEntity entity) {
        return new DataSourceResponse(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getProvider(),
                entity.getBaseUrl(),
                entity.isActive());
    }
}

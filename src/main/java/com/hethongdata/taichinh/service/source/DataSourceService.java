package com.hethongdata.taichinh.service.source;

import com.hethongdata.taichinh.dto.source.DataSourceRequest;
import com.hethongdata.taichinh.dto.source.DataSourceResponse;
import com.hethongdata.taichinh.repository.DataSourceRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSourceService {
    private static final Set<String> SOURCE_TYPES = Set.of("API", "LIBRARY", "WEB", "RSS", "FILE", "MANUAL", "OTHER");
    private static final Set<String> LICENSE_STATUSES = Set.of("UNKNOWN", "FREE", "LICENSED", "RESTRICTED", "INTERNAL");
    private final DataSourceRepository dataSources;

    public DataSourceService(DataSourceRepository dataSources) { this.dataSources = dataSources; }

    @Transactional(readOnly = true)
    public List<DataSourceResponse> list() {
        return dataSources.findAllEntities().stream().map(DataSourceResponse::from).toList();
    }

    @Transactional
    public DataSourceResponse upsert(DataSourceRequest request) {
        String code = requiredUpper(request.code(), "code");
        String sourceType = requiredUpper(request.sourceType(), "sourceType");
        String licenseStatus = request.licenseStatus() == null ? "UNKNOWN" : requiredUpper(request.licenseStatus(), "licenseStatus");
        if (!SOURCE_TYPES.contains(sourceType)) throw new IllegalArgumentException("Unsupported sourceType: " + sourceType);
        if (!LICENSE_STATUSES.contains(licenseStatus)) throw new IllegalArgumentException("Unsupported licenseStatus: " + licenseStatus);
        return DataSourceResponse.from(dataSources.upsert(code, required(request.name(), "name"), sourceType,
                request.baseUrl(), request.provider(), request.official(), licenseStatus, request.active()));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
    private static String requiredUpper(String value, String field) { return required(value, field).toUpperCase(Locale.ROOT); }
}

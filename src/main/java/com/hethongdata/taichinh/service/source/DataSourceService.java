package com.hethongdata.taichinh.service.source;

import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.dto.source.DataSourceRequest;
import com.hethongdata.taichinh.dto.source.DataSourceResponse;
import com.hethongdata.taichinh.repository.ingestion.DataSourceRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataSourceService {
    private final DataSourceRepository dataSources;

    public DataSourceService(DataSourceRepository dataSources) { this.dataSources = dataSources; }

    @Transactional(readOnly = true)
    public List<DataSourceResponse> list() {
        return dataSources.findAllEntities().stream().map(DataSourceResponse::from).toList();
    }

    @Transactional
    public DataSourceResponse upsert(DataSourceRequest request) {
        String code = AppParams.requiredUpper(request.code(), "code");
        String sourceType = AppParams.requiredUpper(request.sourceType(), "sourceType");
        String licenseStatus = request.licenseStatus() == null ? "UNKNOWN" : AppParams.requiredUpper(request.licenseStatus(), "licenseStatus");
        if (!AppParams.DATA_SOURCE_TYPES.contains(sourceType)) throw new IllegalArgumentException("Unsupported sourceType: " + sourceType);
        if (!AppParams.LICENSE_STATUSES.contains(licenseStatus)) throw new IllegalArgumentException("Unsupported licenseStatus: " + licenseStatus);
        return DataSourceResponse.from(dataSources.upsert(code, AppParams.requiredTrimmed(request.name(), "name"), sourceType,
                request.baseUrl(), request.provider(), request.official(), licenseStatus, request.active()));
    }
}

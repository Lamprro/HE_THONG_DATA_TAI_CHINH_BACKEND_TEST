package com.hethongdata.taichinh.repository.ingestion;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class DataSourceRepository {

    private final DataSourceJpaRepository dataSources;

    public DataSourceRepository(DataSourceJpaRepository dataSources) {
        this.dataSources = dataSources;
    }

    public Optional<DataSourceEntity> findEntityActiveByProvider(String provider) {
        List<DataSourceEntity> candidates = dataSources.findActiveByProvider(provider);
        List<DataSourceEntity> exactProvider = candidates.stream()
                .filter(source -> source.getProvider() != null
                        && source.getProvider().equalsIgnoreCase(provider))
                .toList();
        if (exactProvider.size() == 1) return Optional.of(exactProvider.get(0));
        if (exactProvider.size() > 1) {
            String codes = exactProvider.stream().map(DataSourceEntity::getCode).sorted()
                    .reduce((left, right) -> left + ", " + right).orElse("");
            throw new IllegalArgumentException(
                    "Provider '" + provider + "' matches multiple active sources (" + codes
                            + "); specify the exact data source code");
        }
        return Optional.empty();
    }

    public Optional<DataSourceEntity> findEntityActiveByCode(String code) {
        return dataSources.findByCodeIgnoreCase(code).filter(DataSourceEntity::isActive);
    }

    public List<DataSourceEntity> findAllEntities() {
        return dataSources.findAll();
    }

    @Transactional
    public DataSourceEntity upsert(
            String code,
            String name,
            String sourceType,
            String baseUrl,
            String provider,
            boolean official,
            String licenseStatus,
            boolean active) {
        DataSourceEntity entity =
                dataSources
                        .findByCodeIgnoreCase(code)
                        .orElseGet(
                                () ->
                                        DataSourceEntity.create(
                                                code,
                                                name,
                                                sourceType,
                                                baseUrl,
                                                provider,
                                                official,
                                                licenseStatus));
        entity.update(name, sourceType, baseUrl, provider, official, licenseStatus, active);
        return dataSources.save(entity);
    }
}

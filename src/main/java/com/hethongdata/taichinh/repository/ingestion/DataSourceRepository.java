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
        return dataSources.findActiveByProvider(provider);
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
        entity.update(name, baseUrl, provider, active);
        return dataSources.save(entity);
    }
}

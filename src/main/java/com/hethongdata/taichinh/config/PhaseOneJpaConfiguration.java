package com.hethongdata.taichinh.config;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Phase 1 owns only raw ingestion persistence.  Later-phase entities stay in the
 * codebase but are not registered until their schema mapping is implemented and verified.
 */
@Configuration
@EntityScan(basePackageClasses = {
        DataSourceEntity.class, IngestionJobEntity.class, IngestionRunEntity.class, RawPayloadEntity.class})
@EnableJpaRepositories(basePackageClasses = {
        DataSourceJpaRepository.class, IngestionJobJpaRepository.class,
        IngestionRunJpaRepository.class, RawPayloadJpaRepository.class})
public class PhaseOneJpaConfiguration {
}

package com.hethongdata.taichinh.config;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.QuarantinedRecordEntity;
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.QuarantinedRecordJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyAliasJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the verified Phase 1 ingestion entities and Phase 2 validation/versioning entities.
 */
@Configuration
@EntityScan(basePackageClasses = {
        DataSourceEntity.class, IngestionJobEntity.class, IngestionRunEntity.class, RawPayloadEntity.class,
        ValidationRuleEntity.class, ValidationResultEntity.class, DataVersionEntity.class, QuarantinedRecordEntity.class,
        CompanyEntity.class, CompanyAliasEntity.class, SecurityEntity.class})
@EnableJpaRepositories(basePackageClasses = {
        DataSourceJpaRepository.class, IngestionJobJpaRepository.class, IngestionRunJpaRepository.class, RawPayloadJpaRepository.class,
        ValidationRuleJpaRepository.class, ValidationResultJpaRepository.class, DataVersionJpaRepository.class, QuarantinedRecordJpaRepository.class,
        CompanyJpaRepository.class, CompanyAliasJpaRepository.class, SecurityJpaRepository.class})
public class PhaseOneJpaConfiguration {
}

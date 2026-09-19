package com.hethongdata.taichinh.config;

import com.hethongdata.taichinh.entity.NewsArticleCompanyEntity;
import com.hethongdata.taichinh.entity.NewsArticleEntity;
import com.hethongdata.taichinh.entity.IndexPriceEntity;
import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.IndexPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.MarketIndexJpaRepository;
import com.hethongdata.taichinh.repository.jpa.SecurityIndexMembershipJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyAliasJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.CompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registers the ingestion, master data and validation persistence packages used by the application.
 * Other entity packages are intentionally excluded until their database mappings are enabled.
 */
@Configuration
@EntityScan(
        basePackageClasses = {
            DataSourceEntity.class, IngestionJobEntity.class, IngestionRunEntity.class,
                    RawPayloadEntity.class,
            ValidationRuleEntity.class, ValidationResultEntity.class, DataVersionEntity.class,
            CompanyEntity.class, CompanyAliasEntity.class, SecurityEntity.class,
            NewsArticleEntity.class, NewsArticleCompanyEntity.class,
            MarketIndexEntity.class, IndexPriceEntity.class, SecurityIndexMembershipEntity.class
        })
@EnableJpaRepositories(
        basePackageClasses = {
            DataSourceJpaRepository.class, IngestionJobJpaRepository.class,
                    IngestionRunJpaRepository.class, RawPayloadJpaRepository.class,
            ValidationRuleJpaRepository.class, ValidationResultJpaRepository.class,
                    DataVersionJpaRepository.class,
            CompanyJpaRepository.class, CompanyAliasJpaRepository.class, SecurityJpaRepository.class,
            NewsArticleJpaRepository.class, NewsArticleCompanyJpaRepository.class,
            MarketIndexJpaRepository.class, IndexPriceJpaRepository.class,
            SecurityIndexMembershipJpaRepository.class
        })
public class PersistenceConfiguration {}

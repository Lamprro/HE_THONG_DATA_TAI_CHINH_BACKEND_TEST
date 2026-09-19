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
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;

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
public class PersistenceConfiguration {}

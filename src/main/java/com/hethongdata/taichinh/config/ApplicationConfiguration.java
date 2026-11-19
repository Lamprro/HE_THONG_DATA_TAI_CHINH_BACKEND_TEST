package com.hethongdata.taichinh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.financial.OfficialFinancialSources;

import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application-wide infrastructure: JPA entity/repository packages, the Hibernate JSON mapper for
 * stored financial documents, and Spring scheduling (individual schedulers keep their own triggers
 * and opt-in properties).
 *
 * <p>Entities live under {@code entity} and its sub-packages; Spring Data JPA repositories live in
 * the {@code repository.jpa.*} sub-packages. Other repository classes are plain components.
 */
@Configuration
@EnableScheduling
@EntityScan(basePackages = "com.hethongdata.taichinh.entity")
@EnableJpaRepositories(basePackages = "com.hethongdata.taichinh.repository.jpa")
public class ApplicationConfiguration {
    @Bean
    HibernatePropertiesCustomizer financialDocumentStorageJson(ObjectMapper objectMapper) {
        var mapper = new JacksonJsonFormatMapper(OfficialFinancialSources.copyForStorage(objectMapper));
        return properties -> properties.put("hibernate.type.json_format_mapper", mapper);
    }
}

package com.hethongdata.taichinh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.financial.FinancialDocumentJson;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FinancialDocumentStorageConfiguration {
    @Bean
    HibernatePropertiesCustomizer financialDocumentStorageJson(ObjectMapper objectMapper) {
        var mapper = new JacksonJsonFormatMapper(FinancialDocumentJson.copyForStorage(objectMapper));
        return properties -> properties.put("hibernate.type.json_format_mapper", mapper);
    }
}

package com.hethongdata.taichinh.integration.python;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PythonFinancialDataProperties.class)
public class PythonFinancialDataConfiguration {

    @Bean
    @Qualifier("pythonFinancialRestClient")
    RestClient pythonFinancialRestClient(
            RestClient.Builder builder, PythonFinancialDataProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getBaseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "financial-data-api/phase-1")
                .build();
    }
}

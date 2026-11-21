package com.hethongdata.taichinh.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Stop before accepting requests when the deployment lacks its access boundary. */
@Configuration
@Profile("production")
public class ProductionConfiguration {
    public ProductionConfiguration(SecurityConfiguration.AdminCredentials credentials,
                                   @Value("${app.cors.allowed-origins:}") String origins) {
        validate(credentials, origins);
    }
    static void validate(SecurityConfiguration.AdminCredentials credentials, String origins) {
        if (!credentials.adminConfigured()) throw new IllegalStateException("Production requires an admin bearer credential (at least 32 characters)");
        if (origins.contains("*")) throw new IllegalStateException("Production CORS requires exact origins");
        for (String origin: origins.split(",")) {
            if (origin.isBlank()) continue;
            var uri=java.net.URI.create(origin.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().isEmpty())
                throw new IllegalStateException("Production CORS origins must be HTTPS origins without paths");
        }
    }
}

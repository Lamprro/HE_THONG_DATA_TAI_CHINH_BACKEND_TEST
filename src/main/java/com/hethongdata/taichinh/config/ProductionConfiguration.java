package com.hethongdata.taichinh.config;

import com.hethongdata.taichinh.jwt.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Stop before accepting requests when the deployment lacks its access boundary. */
@Configuration
@Profile("production")
public class ProductionConfiguration {
    public ProductionConfiguration(JwtService tokens,
                                   @Value("${app.cors.allowed-origins:}") String origins) {
        validate(tokens.persistentKeyConfigured(), origins);
    }
    static void validate(boolean persistentKey, String origins) {
        if (!persistentKey) throw new IllegalStateException("Production requires FINANCIAL_JWT_SECRET");
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

package com.hethongdata.taichinh.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Browser access policy for the FE console and local development clients.
 *
 * The browser sends an automatic OPTIONS preflight for JSON POST/PATCH/PUT
 * requests. It is not an application endpoint; this configuration lets
 * Spring answer that preflight before the real request is sent.
 */
@Configuration
public class CorsConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfiguration(
            @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,https://*.vercel.app}")
            String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Location", "Content-Type")
                .maxAge(3600);
    }
}

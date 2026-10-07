package com.hethongdata.taichinh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/**
 * Fail-closed admin credential until the application's account/session authentication is
 * implemented.
 */
@Configuration
public class ForecastAdminAccessConfiguration implements WebMvcConfigurer {
  private final String token;
  private final ObjectMapper json;

  public ForecastAdminAccessConfiguration(
      @Value("${financial.admin.api-token:}") String token, ObjectMapper json) {
    this.token = token;
    this.json = json;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler)
                  throws Exception {
                if (org.springframework.web.cors.CorsUtils.isPreFlightRequest(request)) return true;
                String header = request.getHeader("Authorization");
                boolean configured = token.length() >= 32;
                boolean valid =
                    configured
                        && header != null
                        && header.startsWith("Bearer ")
                        && MessageDigest.isEqual(
                            token.getBytes(StandardCharsets.UTF_8),
                            header.substring(7).getBytes(StandardCharsets.UTF_8));
                if (valid) return true;
                response.setStatus(configured ? 403 : 503);
                response.setContentType("application/json");
                json.writeValue(
                    response.getOutputStream(),
                    new com.hethongdata.taichinh.dto.ApiErrorResponse(
                        java.time.Instant.now(),
                        configured ? "ADMIN_ACCESS_DENIED" : "ADMIN_ACCESS_NOT_CONFIGURED",
                        null,
                        null,
                        configured
                            ? "Admin credential required"
                            : "Configure a dedicated admin token of at least 32 characters"));
                return false;
              }
            })
        .addPathPatterns("/api/admin/forecasts/**", "/api/admin/llm/**", "/api/admin/news-recovery/**");
  }
}

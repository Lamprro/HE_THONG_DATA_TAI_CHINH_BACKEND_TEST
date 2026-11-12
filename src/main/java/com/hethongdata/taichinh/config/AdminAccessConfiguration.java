package com.hethongdata.taichinh.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Fail-closed admin credential until account/session authentication exists.
 *
 * <p>Every {@code /api/**} route requires the dedicated admin bearer token, except health probes
 * and the public read-only user namespace {@code /api/v1/**}. One rule for every profile, so a new
 * management route can never be exposed by forgetting to list it.
 */
@Configuration
public class AdminAccessConfiguration implements WebMvcConfigurer {
  public static final String AUTHENTICATED_ATTRIBUTE = "adminAuthenticated";
  private final String token;
  private final ObjectMapper json;

  public AdminAccessConfiguration(
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
                if (CorsUtils.isPreFlightRequest(request)) return true;
                boolean configured = token.length() >= 32;
                if (configured && matches(request.getHeader("Authorization"))) {
                  request.setAttribute(AUTHENTICATED_ATTRIBUTE, true);
                  return true;
                }
                response.setStatus(configured ? 403 : 503);
                response.setContentType("application/json");
                json.writeValue(
                    response.getOutputStream(),
                    new ApiErrorResponse(
                        Instant.now(),
                        configured ? "ADMIN_ACCESS_DENIED" : "ADMIN_ACCESS_NOT_CONFIGURED",
                        null,
                        null,
                        configured
                            ? "Admin credential required"
                            : "Configure a dedicated admin token of at least 32 characters"));
                return false;
              }
            })
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/health", "/api/health/live", "/api/v1/**");
  }

  private boolean matches(String header) {
    return header != null
        && header.startsWith("Bearer ")
        && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8),
            header.substring(7).getBytes(StandardCharsets.UTF_8));
  }
}

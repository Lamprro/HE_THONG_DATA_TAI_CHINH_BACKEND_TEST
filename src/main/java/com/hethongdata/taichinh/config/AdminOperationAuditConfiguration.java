package com.hethongdata.taichinh.config;

import com.hethongdata.taichinh.repository.jpa.admin.AdminAuditJpaRepository;
import com.hethongdata.taichinh.security.SecurityConfiguration;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** HTTP audit complements domain runs. Never captures tokens, headers, bodies or URL query strings. */
@Configuration
public class AdminOperationAuditConfiguration implements WebMvcConfigurer {
  private final AdminAuditJpaRepository auditRepository;

  public AdminOperationAuditConfiguration(AdminAuditJpaRepository auditRepository) {
    this.auditRepository = auditRepository;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getAuthorities().stream()
            .noneMatch(a -> SecurityConfiguration.ADMIN_AUTHORITY.equals(a.getAuthority()))
            || Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) return;
        try {
          auditRepository.recordHttpMutation(UUID.randomUUID(), request.getMethod(), request.getRequestURI(), response.getStatus(), authentication.getName());
        } catch (Exception e) {
          org.slf4j.LoggerFactory.getLogger(AdminOperationAuditConfiguration.class)
              .error("Admin HTTP audit unavailable: {}", e.getClass().getSimpleName());
        }
      }
    }).addPathPatterns("/api/**");
  }
}

package com.hethongdata.taichinh.config;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** HTTP audit complements domain runs. Never captures tokens, headers, bodies or URL query strings. */
@Configuration
public class AdminOperationAuditConfiguration implements WebMvcConfigurer {
  private final JdbcTemplate db;
  public AdminOperationAuditConfiguration(JdbcTemplate db) { this.db=db; }
  @Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception failure) {
        if(!Boolean.TRUE.equals(request.getAttribute("adminAuthenticated")) || Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) return;
        try {
          db.update("insert into admin_operation_events(id,event_type,http_method,request_path,http_status) values (?,'HTTP_MUTATION',?,?,?)",
            UUID.randomUUID(),request.getMethod(),request.getRequestURI(),response.getStatus());
        } catch(Exception e) { org.slf4j.LoggerFactory.getLogger(AdminOperationAuditConfiguration.class).error("Admin HTTP audit unavailable: {}",e.getClass().getSimpleName()); }
      }
    }).addPathPatterns("/api/**");
  }
}

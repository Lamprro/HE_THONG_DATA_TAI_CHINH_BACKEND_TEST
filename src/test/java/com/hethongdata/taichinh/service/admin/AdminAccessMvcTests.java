package com.hethongdata.taichinh.service.admin;

import com.hethongdata.taichinh.repository.jpa.account.RevokedAccessTokenJpaRepository;
import com.hethongdata.taichinh.support.TestJwt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class AdminAccessMvcTests {


  @Configuration @EnableWebMvc @Import(SecurityConfiguration.class) static class Config {
        @Bean RevokedAccessTokenJpaRepository revokedTokens() {
            return org.mockito.Mockito.mock(RevokedAccessTokenJpaRepository.class);
        }
    @Bean ObjectMapper securityJson() { return new ObjectMapper().findAndRegisterModules(); }
    @Bean Probe probe() { return new Probe(); }
  }
  @RestController static class Probe {
    @GetMapping({"/api/admin/console/session","/api/admin/validation/rules","/api/admin/llm/templates",
      "/api/admin/forecasts/configuration","/api/admin/news-recovery/configuration","/api/companies",
      "/api/securities","/api/ingestion-jobs","/api/ingestions","/api/data-sources","/api/raw-payloads",
      "/api/security-job-reconciliation/run","/api/external-financial-data/fetch"})
    String protectedRead() { return "ok"; }
    @GetMapping("/v3/api-docs/admin") String docs() { return "api"; }
    @GetMapping("/api/v1/public-probe") String publicRead() { return "ok"; }
    @GetMapping("/api/health") String health() { return "UP"; }
    @PostMapping("/api/admin/example/{id}") String typed(@PathVariable java.util.UUID id,@RequestBody com.hethongdata.taichinh.dto.forecast.ForecastRequest body) { return "ok"; }
  }
  @Test void invalidIdsAndJsonReturnActionableBadRequestInsteadOfInternalServerError() throws Exception {
    var mvc=MockMvcBuilders.standaloneSetup(new Probe())
        .setControllerAdvice(new com.hethongdata.taichinh.controller.exception.ApiExceptionHandler()).build();
    mvc.perform(post("/api/admin/example/not-a-uuid").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.category").value("VALIDATION"))
        .andExpect(jsonPath("$.message").value("Invalid parameter 'id': expected UUID"));
    mvc.perform(post("/api/admin/example/"+java.util.UUID.randomUUID()).contentType("application/json").content("{broken"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.category").value("VALIDATION"));
  }
  @Test void allManagementFamiliesRequireCredentialWhilePublicHealthStaysAvailable() throws Exception {
    try(var context=new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext()); context.register(Config.class); org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, TestJwt.PROPERTY);
      context.refresh();
      var mvc=MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
      for(String path:java.util.List.of("/api/admin/console/session","/api/admin/validation/rules","/api/admin/llm/templates",
          "/api/admin/forecasts/configuration","/api/admin/news-recovery/configuration","/api/companies","/api/securities",
          "/api/ingestion-jobs","/api/ingestions","/api/data-sources","/api/raw-payloads",
          "/api/security-job-reconciliation/run","/api/external-financial-data/fetch")) {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer " + TestJwt.admin())).andExpect(status().isOk());
      }
      mvc.perform(get("/api/health")).andExpect(status().isOk());
      mvc.perform(get("/api/v1/public-probe").header("Authorization", "Bearer invalid")).andExpect(status().isOk());
      mvc.perform(get("/v3/api-docs/admin")).andExpect(status().isUnauthorized());
      mvc.perform(get("/v3/api-docs/admin").header("Authorization", "Bearer " + TestJwt.admin())).andExpect(status().isOk());
      mvc.perform(options("/api/admin/example/" + java.util.UUID.randomUUID())
              .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
              .header("Access-Control-Request-Headers", "authorization,content-type"))
          .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
      mvc.perform(options("/api/admin/example/" + java.util.UUID.randomUUID())
              .header("Origin", "https://invalid.invalid").header("Access-Control-Request-Method", "POST"))
          .andExpect(status().isForbidden());
    }
  }
}

package com.hethongdata.taichinh.service.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.config.ForecastAdminAccessConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class AdminAccessMvcTests {
  static final String TOKEN="isolated-admin-access-test-32-characters";
  @Configuration @EnableWebMvc static class Config {
    @Bean ForecastAdminAccessConfiguration access() { return new ForecastAdminAccessConfiguration(TOKEN,new ObjectMapper().findAndRegisterModules()); }
    @Bean Probe probe() { return new Probe(); }
  }
  @RestController static class Probe {
    @GetMapping({"/api/admin/console/session","/api/admin/validation/rules","/api/admin/llm/templates",
      "/api/admin/forecasts/configuration","/api/admin/news-recovery/configuration","/api/companies",
      "/api/securities","/api/ingestion-jobs","/api/ingestions","/api/data-sources","/api/raw-payloads"})
    String protectedRead() { return "ok"; }
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
      context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
      var mvc=MockMvcBuilders.webAppContextSetup(context).build();
      for(String path:java.util.List.of("/api/admin/console/session","/api/admin/validation/rules","/api/admin/llm/templates",
          "/api/admin/forecasts/configuration","/api/admin/news-recovery/configuration","/api/companies","/api/securities",
          "/api/ingestion-jobs","/api/ingestions","/api/data-sources","/api/raw-payloads")) {
        mvc.perform(get(path)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer wrong")).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization","Bearer "+TOKEN)).andExpect(status().isOk());
      }
      mvc.perform(get("/api/health")).andExpect(status().isOk());
    }
  }
}

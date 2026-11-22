package com.hethongdata.taichinh.service.financial;
import com.hethongdata.taichinh.controller.admin.FinancialDataAdminController;
import com.hethongdata.taichinh.config.AdminAccessConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(controllers=FinancialDataAdminController.class,properties="financial.admin.api-token=isolated-fixture-admin-token-long-enough")
@Import(AdminAccessConfiguration.class)
class FinancialDataAdminAccessTests {
 @Autowired MockMvc mvc;
 @MockitoBean org.springframework.jdbc.core.JdbcTemplate auditDatabase;
 @MockitoBean FinancialDataQualityService service;
 @MockitoBean HistoricalFinancialMetricService metrics;
 @MockitoBean com.hethongdata.taichinh.service.market.TrainingDataQualityService trainingQuality;
 @Test void missingCredentialCannotReadOrRecalculate()throws Exception {
  mvc.perform(get("/api/admin/financial-data/status?symbol=FPT")).andExpect(status().isForbidden());verifyNoInteractions(service,metrics);
  mvc.perform(post("/api/admin/financial-data/metrics/recalculate").contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/financial-data/metrics/recalculate").header("Authorization","Bearer wrong").contentType("application/json").content("{}")).andExpect(status().isForbidden());
  verifyNoInteractions(metrics);
 }
 @Test void authenticatedReadUsesService()throws Exception {
  when(service.status("FPT")).thenReturn(java.util.Map.of("symbol","FPT"));
  mvc.perform(get("/api/admin/financial-data/status?symbol=FPT").header("Authorization","Bearer isolated-fixture-admin-token-long-enough")).andExpect(status().isOk()).andExpect(jsonPath("$.symbol").value("FPT"));
 }
 @Test void retiredPdfRoutesReturnNotFound() throws Exception {
  String token = "Bearer isolated-fixture-admin-token-long-enough";
  for (String path : java.util.List.of("/disclosures", "/bindings", "/reconciliation")) {
   mvc.perform(get("/api/admin/financial-data" + path).param("symbol", "FPT").header("Authorization", token))
       .andExpect(status().isNotFound());
  }
  for (String path : java.util.List.of("/disclosures/refresh", "/bindings/reconcile")) {
   mvc.perform(post("/api/admin/financial-data" + path).header("Authorization", token)
       .contentType("application/json").content("{}"))
       .andExpect(status().isNotFound());
  }
 }
}

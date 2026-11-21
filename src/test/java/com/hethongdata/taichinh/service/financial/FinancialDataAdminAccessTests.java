package com.hethongdata.taichinh.service.financial;
import com.hethongdata.taichinh.controller.admin.FinancialDataAdminController;
import com.hethongdata.taichinh.config.SecurityConfiguration;
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
@Import(SecurityConfiguration.class)
class FinancialDataAdminAccessTests {
 @Autowired MockMvc mvc;
 @MockitoBean com.hethongdata.taichinh.repository.jdbc.admin.AdminAuditJdbcRepository auditDatabase;
 @MockitoBean FinancialDataQualityService service;
 @MockitoBean FinancialDisclosureDocumentStore store;
 @MockitoBean HistoricalFinancialMetricService metrics;
 @MockitoBean com.hethongdata.taichinh.service.market.TrainingDataQualityService trainingQuality;
 @Test void missingCredentialCannotRefreshOrRead()throws Exception {
  mvc.perform(post("/api/admin/financial-data/disclosures/refresh").contentType("application/json").content("{\"symbol\":\"FPT\"}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/financial-data/status?symbol=FPT")).andExpect(status().isForbidden());verifyNoInteractions(service,store,metrics);
  mvc.perform(post("/api/admin/financial-data/metrics/recalculate").contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/financial-data/metrics/recalculate").header("Authorization","Bearer wrong").contentType("application/json").content("{}")).andExpect(status().isForbidden());
  verifyNoInteractions(metrics);
 }
 @Test void authenticatedReadUsesService()throws Exception {
  when(service.status("FPT")).thenReturn(java.util.Map.of("symbol","FPT"));
  when(service.gaps("FPT",null,"PRICE",null,null,null)).thenReturn(java.util.Map.of("symbol","FPT","missingCount",0));
  when(service.gaps("HPG","HOSE","STATEMENT","BALANCE_SHEET",java.time.LocalDate.of(2023,1,1),java.time.LocalDate.of(2023,12,31)))
      .thenReturn(java.util.Map.of("symbol","HPG","exchange","HOSE","statementType","BALANCE_SHEET","missingCount",0));
  mvc.perform(get("/api/admin/financial-data/status?symbol=FPT").header("Authorization","Bearer isolated-fixture-admin-token-long-enough")).andExpect(status().isOk()).andExpect(jsonPath("$.symbol").value("FPT"));
  mvc.perform(get("/api/admin/financial-data/gaps?symbol=FPT").header("Authorization","Bearer isolated-fixture-admin-token-long-enough")).andExpect(status().isOk()).andExpect(jsonPath("$.missingCount").value(0));
  mvc.perform(get("/api/admin/financial-data/gaps?symbol=HPG&exchange=HOSE&type=STATEMENT&statementType=BALANCE_SHEET&from=2023-01-01&to=2023-12-31").header("Authorization","Bearer isolated-fixture-admin-token-long-enough")).andExpect(status().isOk()).andExpect(jsonPath("$.statementType").value("BALANCE_SHEET"));
  mvc.perform(get("/api/admin/financial-data/gaps?symbol=FPT")).andExpect(status().isForbidden());
 }
}

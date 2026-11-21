package com.hethongdata.taichinh.service.admin;

import com.hethongdata.taichinh.config.AdminOperationAuditConfiguration;
import com.hethongdata.taichinh.config.OpenApiConfiguration;
import com.hethongdata.taichinh.config.SecurityConfiguration;
import com.hethongdata.taichinh.controller.admin.AdminConsoleController;
import com.hethongdata.taichinh.controller.admin.CatalogAdminController;
import com.hethongdata.taichinh.repository.jdbc.admin.AdminAuditJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionJobCatalogService;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import com.hethongdata.taichinh.service.llm.NewsLlmService;
import com.hethongdata.taichinh.service.master.MasterDataCatalogService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AdminConsoleHttpTests.Config.class, properties = {
    "financial.admin.api-token=",
    "financial.admin.credentials.operator.token=isolated-test-operator-credential-not-production",
    "financial.admin.credentials.operator.email=operator@example.test",
    "financial.admin.credentials.operator.roles=ADMIN",
    "financial.admin.credentials.auditor.token=isolated-test-auditor-credential-not-production",
    "financial.admin.credentials.auditor.roles=AUDITOR"
})
@AutoConfigureMockMvc
class AdminConsoleHttpTests {
    private static final String OPERATOR = "Bearer isolated-test-operator-credential-not-production";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfiguration.class, OpenApiConfiguration.class, AdminOperationAuditConfiguration.class,
        AdminConsoleController.class, CatalogAdminController.class, AdminRuntimeService.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @MockitoBean AdminAuditJdbcRepository audit;
    @MockitoBean AdminConsoleReadService reads;
    @MockitoBean AdminCommandService commands;
    @MockitoBean AdminScheduleService schedule;
    @MockitoBean NewsLlmService news;
    @MockitoBean IngestionJobCatalogService jobs;
    @MockitoBean MasterDataCatalogService master;
    @MockitoBean LlmPromptCatalog prompts;

    @Test
    void sessionRuntimeAndDocumentationUseSecurityAndTypedContracts() throws Exception {
        var session = "/api/admin/console/session";
        var runtime = "/api/admin/console/runtime";
        mvc.perform(get(session).header("Authorization", OPERATOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.principal").value("operator"))
                .andExpect(jsonPath("$.email").value("operator@example.test"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"));
        mvc.perform(get(session).header("Authorization", "Bearer isolated-test-auditor-credential-not-production"))
                .andExpect(status().isForbidden());

        when(reads.databaseUp()).thenReturn(true);
        when(commands.workerEnabled()).thenReturn(true);
        mvc.perform(get(runtime).header("Authorization", OPERATOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP")).andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.adminWorkers.commandsEnabled").value(true));

        mvc.perform(get("/admin-api-docs/index.html")).andExpect(status().isOk());
        mvc.perform(get("/webjars/swagger-ui/5.34.0/swagger-ui-bundle.js")).andExpect(status().isOk());
        var docs = "/v3/api-docs/admin";
        mvc.perform(get(docs)).andExpect(status().isForbidden());
        mvc.perform(get(docs).header("Authorization", OPERATOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.adminBearer.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['" + session + "'].get.responses.200").exists())
                .andExpect(jsonPath("$.paths['/api/admin/console/catalog']").doesNotExist());
    }

    @Test
    void writingCatalogRequiresAdminAndAuditsTheAuthenticatedActor() throws Exception {
        var path = "/api/admin/catalog/ingestion-jobs/retry-budget";
        when(jobs.initializeRetryBudgets()).thenReturn(2);
        mvc.perform(post(path)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", OPERATOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));
        verify(jobs).initializeRetryBudgets();
        verify(audit).recordHttpMutation(any(UUID.class), eq("POST"), eq(path), eq(200), eq("operator"));
    }
}

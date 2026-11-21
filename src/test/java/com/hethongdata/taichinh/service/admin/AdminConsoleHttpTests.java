package com.hethongdata.taichinh.service.admin;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hethongdata.taichinh.config.AdminOperationAuditConfiguration;
import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;
import com.hethongdata.taichinh.config.OpenApiConfiguration;
import com.hethongdata.taichinh.config.SecurityConfiguration;
import com.hethongdata.taichinh.controller.admin.AdminConsoleController;
import com.hethongdata.taichinh.controller.admin.CatalogAdminController;
import com.hethongdata.taichinh.repository.jpa.admin.AdminAuditJpaRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionJobCatalogService;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import com.hethongdata.taichinh.service.llm.NewsLlmService;
import com.hethongdata.taichinh.service.master.MasterDataCatalogService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    @MockitoBean AdminAuditJpaRepository audit;
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

    @Test
    void typedAdminApisPreserveExistingJsonFields() throws Exception {
        var base = "/api/admin/console";
        var id = UUID.randomUUID();
        var key = UUID.randomUUID();
        var job = UUID.randomUUID();
        var now = Instant.parse("2026-11-21T10:00:00Z");
        var input = JsonNodeFactory.instance.objectNode().put("jobId",job.toString());
        var command = new Command(id, key, "RUN_JOB", "job:" + job, "Keep contract", "operator", "QUEUED",
                input, null, null, null, null, now, null, null, null);
        when(commands.submit(any(CommandRequest.class))).thenReturn(command);
        when(commands.find(id)).thenReturn(command);
        mvc.perform(post(base + "/commands").header("Authorization", OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"idempotencyKey":"%s","action":"RUN_JOB","reason":"Keep contract","input":{"jobId":"%s"}}
                    """.formatted(key, job)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.idempotency_key").value(key.toString()))
                .andExpect(jsonPath("$.request_json.jobId").value(job.toString()))
                .andExpect(jsonPath("$.created_at").value(now.toString()));
        verify(commands).submit(new CommandRequest(key,"RUN_JOB","Keep contract",input));
        mvc.perform(get(base + "/commands/" + id).header("Authorization", OPERATOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.progress_json").isEmpty());

        var summary = new Summary(List.of(new JobCount(true,2)), List.of(new StatusCount("QUEUED",1)),
                List.of(), List.of(new VersionCount("MARKET_PRICE","ACTIVE",3)),
                List.of(new ValidationCount("RAW","FAIL",4)));
        when(reads.summary()).thenReturn(summary);
        mvc.perform(get(base + "/summary").header("Authorization", OPERATOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].is_active").value(true))
                .andExpect(jsonPath("$.commands[0].count").value(1))
                .andExpect(jsonPath("$.versions[0].data_domain").value("MARKET_PRICE"))
                .andExpect(jsonPath("$.validation[0].validation_target").value("RAW"))
                .andExpect(jsonPath("$.validation[0].result_status").value("FAIL"));

        var policy = new Schedule("INGESTION", false, Set.of("JOB"), 2, "Keep disabled",
                now, now, null, null, null, true, false, "UTC cron / Asia/Ho_Chi_Minh business dates");
        when(schedule.status()).thenReturn(policy);
        when(schedule.update(any(ScheduleUpdate.class))).thenReturn(policy);
        mvc.perform(get(base + "/schedule").header("Authorization", OPERATOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job_codes[0]").value("JOB")).andExpect(jsonPath("$.workerEnabled").value(true))
                .andExpect(jsonPath("$.heartbeat_at").value(now.toString()));
        mvc.perform(put(base + "/schedule").header("Authorization", OPERATOR).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"enabled":false,"jobCodes":["JOB"],"version":1,"reason":"Keep disabled"}
                    """)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        verify(schedule).update(new ScheduleUpdate(false, Set.of("JOB"), 1, "Keep disabled"));
    }
}

package com.hethongdata.taichinh.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hethongdata.taichinh.controller.AuthController;
import com.hethongdata.taichinh.controller.exception.ApiExceptionHandler;
import com.hethongdata.taichinh.entity.account.AccountEntity;
import com.hethongdata.taichinh.entity.account.RoleEntity;
import com.hethongdata.taichinh.repository.jpa.account.AccountJpaRepository;
import com.hethongdata.taichinh.security.AccountSecurityService;
import com.hethongdata.taichinh.support.TestJwt;
import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;
import com.hethongdata.taichinh.config.OpenApiConfiguration;
import com.hethongdata.taichinh.security.SecurityConfiguration;
import com.hethongdata.taichinh.controller.admin.AdminConsoleController;
import com.hethongdata.taichinh.controller.admin.CatalogAdminController;
import com.hethongdata.taichinh.service.ingestion.IngestionJobCatalogService;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import com.hethongdata.taichinh.service.llm.NewsLlmService;
import com.hethongdata.taichinh.service.master.MasterDataCatalogService;
import java.time.Instant;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AdminConsoleHttpTests.Config.class, properties = TestJwt.PROPERTY)
@AutoConfigureMockMvc
class AdminConsoleHttpTests {
    private static final String OPERATOR = "Bearer " + TestJwt.admin();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({SecurityConfiguration.class, OpenApiConfiguration.class,
        AdminConsoleController.class, CatalogAdminController.class, AdminRuntimeService.class,
        AuthController.class, AccountSecurityService.class, ApiExceptionHandler.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @MockitoBean AccountJpaRepository accounts;
    @MockitoBean AdminConsoleReadService reads;
    @MockitoBean AdminCommandService commands;
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
        mvc.perform(get(session).header("Authorization", "Bearer " + TestJwt.accessToken("auditor", "AUDITOR")))
                .andExpect(status().isForbidden());

        when(reads.databaseUp()).thenReturn(true);
        when(commands.workerEnabled()).thenReturn(true);
        mvc.perform(get(runtime).header("Authorization", OPERATOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP")).andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.adminWorkers.commandsEnabled").value(true));

        mvc.perform(get("/admin-api-docs/index.html")).andExpect(status().isOk());
        mvc.perform(get("/webjars/swagger-ui/5.34.0/swagger-ui-bundle.js")).andExpect(status().isOk());
        var docs = "/v3/api-docs/admin";
        mvc.perform(get(docs)).andExpect(status().isUnauthorized());
        mvc.perform(get(docs).header("Authorization", OPERATOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.adminBearer.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['" + session + "'].get.responses.200").exists())
                .andExpect(jsonPath("$.paths['/api/admin/console/catalog']").doesNotExist());
    }

    @Test
    void writingCatalogRequiresAdmin() throws Exception {
        var path = "/api/admin/catalog/ingestion-jobs/retry-budget";
        when(jobs.initializeRetryBudgets()).thenReturn(2);
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", OPERATOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));
        verify(jobs).initializeRetryBudgets();
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

    }

    @Test
    void notesUseTheValidatedUsername() throws Exception {
        var id = UUID.randomUUID();
        when(reads.reviewIssue(id, "Checked provider", "operator")).thenReturn(new ReviewReceipt(id, id, false));
        mvc.perform(post("/api/admin/console/issues/" + id + "/reviews").header("Authorization", OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"Checked provider"}
                    """)).andExpect(status().isOk()).andExpect(jsonPath("$.validationStatusChanged").value(false));
        verify(reads).reviewIssue(id, "Checked provider", "operator");
    }

    @Test
    void loginIssuesJwtFromDatabaseRoleAndRejectsInvalidAndOldCredentials() throws Exception {
        fixture("operator", "ADMIN", "ACTIVE");
        fixture("viewer", "USER", "ACTIVE");
        fixture("locked", "ADMIN", "LOCKED");

        var route = "/api/admin/console/session";
        var access = login("operator", "correct-password")
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse();
        var jwt = json.readTree(access.getContentAsString()).path("accessToken").asText();
        mvc.perform(get(route).header("Authorization", "Bearer " + jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("operator"))
                .andExpect(jsonPath("$.email").value("operator@example.test"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"));
        mvc.perform(get(route).header("Authorization", "Bearer old-configured-admin-bearer-token"))
                .andExpect(status().isUnauthorized());
        login("operator", "wrong-password").andExpect(status().isUnauthorized());
        login("missing", "wrong-password").andExpect(status().isUnauthorized());
        login("locked", "correct-password").andExpect(status().isUnauthorized());
        var duplicate = accounts.findTop2ByUsername("operator").getFirst();
        when(accounts.findTop2ByUsername("operator")).thenReturn(List.of(duplicate, duplicate));
        login("operator", "correct-password").andExpect(status().isUnauthorized());

        var viewer = login("viewer", "correct-password").andExpect(status().isOk()).andReturn().getResponse();
        var viewerToken = json.readTree(viewer.getContentAsString()).path("accessToken").asText();
        mvc.perform(get(route).header("Authorization", "Bearer " + viewerToken)).andExpect(status().isForbidden());
    }

    private void fixture(String username, String role, String status) {
        var account = mock(AccountEntity.class);
        var roles = mock(RoleEntity.class);
        when(account.getUsername()).thenReturn(username);
        when(account.getEmail()).thenReturn(username + "@example.test");
        when(account.getPasswordHash()).thenReturn(passwords.encode("correct-password"));
        when(account.getStatus()).thenReturn(status);
        when(account.getRole()).thenReturn(roles);
        when(roles.getCode()).thenReturn(role);
        when(accounts.findTop2ByUsername(username)).thenReturn(List.of(account));
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new com.hethongdata.taichinh.dto.auth.AuthDtos.LoginRequest(username, password))));
    }
}

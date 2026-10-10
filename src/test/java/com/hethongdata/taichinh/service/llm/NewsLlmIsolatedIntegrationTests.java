package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Opt-in only. Copies real source data into an isolated schema; all synthetic responses stay there. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "financial.llm.catalog.seed-enabled=true","financial.llm.scheduler.enabled=false",
        "financial.admin.api-token=llm-isolated-admin-token-not-a-real-secret",
        "financial.ingestion.scheduler.enabled=false","financial.validation.scheduler.enabled=false"})
@EnabledIfEnvironmentVariable(named="LLM_TEST_DB",matches="1")
class NewsLlmIsolatedIntegrationTests {
    static final String SCHEMA="llm_test_"+UUID.randomUUID().toString().replace("-","");
    static final Properties CONFIG=new Properties();
    static String jdbcUrl;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) {CONFIG.load(reader);}
        jdbcUrl=CONFIG.getProperty("spring.datasource.url");
        try(Connection c=connect(); Statement s=c.createStatement()) {
            s.execute("CREATE SCHEMA "+SCHEMA);
            for(String table:List.of("companies","securities","news_articles","news_article_companies","llm_runs","validation_rules","validation_results"))
                s.execute("CREATE TABLE "+SCHEMA+"."+table+" (LIKE public."+table+" INCLUDING ALL)");
            s.execute("SET search_path TO "+SCHEMA);
            com.hethongdata.taichinh.support.IsolatedSchemaSupport.detachSequences(c, SCHEMA);
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/manual/V20260930_01__llm_news_pipeline.sql"));
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/manual/V20261004_01__llm_attempt_audit.sql"));
            try(var input=new ClassPathResource("db/manual/V20261004_02__llm_shared_validation.sql").getInputStream()) {
                s.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
            try(var input=new ClassPathResource("db/manual/V20261008_01__merge_llm_attempts_into_runs.sql").getInputStream()) {
                s.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
            s.execute("CREATE TABLE news_ai_analyses(id uuid)");
            try(var input=new ClassPathResource("db/manual/V20261004_03__remove_empty_legacy_news_ai.sql").getInputStream()) {
                s.execute(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
            s.execute("INSERT INTO companies SELECT * FROM public.companies");
            s.execute("INSERT INTO securities SELECT * FROM public.securities");
            s.execute("INSERT INTO news_articles SELECT * FROM public.news_articles WHERE dedup_status='UNIQUE' AND length(content_text)>1000 AND canonical_url !~* '(real-db-|test)' ORDER BY published_at DESC LIMIT 1");
            s.execute("INSERT INTO news_article_companies SELECT * FROM public.news_article_companies WHERE news_article_id IN (SELECT id FROM news_articles)");
        }
        registry.add("spring.datasource.url",()->jdbcUrl+(jdbcUrl.contains("?")?"&":"?")+"currentSchema="+SCHEMA);
        registry.add("spring.datasource.username",()->CONFIG.getProperty("spring.datasource.username"));
        registry.add("spring.datasource.password",()->CONFIG.getProperty("spring.datasource.password"));
    }
    static Connection connect() throws SQLException {return DriverManager.getConnection(jdbcUrl,CONFIG.getProperty("spring.datasource.username"),CONFIG.getProperty("spring.datasource.password"));}
    @AfterAll static void cleanup() throws Exception {
        if(!SCHEMA.matches("llm_test_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test schema");
        try(Connection c=connect();Statement s=c.createStatement()) {s.execute("DROP SCHEMA "+SCHEMA+" CASCADE");}
    }
    @Autowired JdbcTemplate db;
    @Autowired LlmJson json;
    @Autowired NewsLlmService service;
    @Autowired LlmRunStore store;
    @Autowired LlmPromptCatalog catalog;
    @Autowired NewsLlmContext contexts;
    @Autowired TestRestTemplate http;
    @MockitoBean LlmGateway gateway;
    UUID article;
    @BeforeEach void setup() throws Exception {
        http.getRestTemplate().setInterceptors(java.util.List.of((request,body,execution)->{
            request.getHeaders().setBearerAuth("llm-isolated-admin-token-not-a-real-secret");
            return execution.execute(request,body);
        }));
        db.update("DELETE FROM validation_results");db.update("DELETE FROM llm_results"); db.update("DELETE FROM llm_runs");
        db.update("UPDATE validation_rules SET is_active=true,severity='ERROR',executor_key=code,rule_config=jsonb_set(rule_config,'{version}','\"1\"')");
        db.update("UPDATE llm_prompt_templates SET enabled=true");
        article=db.queryForObject("SELECT id FROM news_articles LIMIT 1",UUID.class);
        db.update("""
            UPDATE news_articles a SET content_text=p.content_text,content_hash=p.content_hash,sapo=p.sapo,
                dedup_status=p.dedup_status,metadata=p.metadata,updated_at=p.updated_at
            FROM public.news_articles p WHERE p.id=a.id
            """);
        when(gateway.configured()).thenReturn(true);when(gateway.model()).thenReturn("test-only-model");
        when(gateway.routingKey()).thenReturn("test-only-model");
        when(gateway.call(any(),any())).thenAnswer(i->gateway.call(i.getArgument(0)));
        var realRequestBuilder=new GeminiLlmGateway(json,"","gemini-test-only-model",false);
        when(gateway.request(any(),any())).thenAnswer(i->realRequestBuilder.request(i.getArgument(0),i.getArgument(1)));
        when(gateway.call(any())).thenAnswer(i->{
            JsonNode request=i.getArgument(0);
            var input=json.read(request.path("contents").path(0).path("parts").path(0).path("text").asText());
            var output=NewsLlmContractTests.output(json,input,input.path("task_code").asText());
            return new LlmGateway.Reply(200,"{\"test_fixture\":true}",output.toString(),20,30);
        });
    }
    @Test void httpFlowPersistsLogsResultsAndCachesWithoutDuplicateCalls() throws Exception {
        var preview=http.getForObject("/api/admin/llm/news/"+article+"/preview",JsonNode.class);
        assertThat(preview.path("eligible").asBoolean()).isTrue();
        var response=http.postForObject("/api/admin/llm/news/"+article+"/execute",null,JsonNode.class);
        assertThat(response.path("NEWS_SUMMARY").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(response.path("NEWS_DETAIL").path("status").asText()).isEqualTo("SUCCESS");
        assertThat(response.path("NEWS_FINANCIAL_FACTS").path("status").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM llm_runs WHERE request_payload IS NOT NULL AND response_payload IS NOT NULL AND prompt_template_id IS NOT NULL AND status='SUCCESS'",Integer.class)).isEqualTo(2);
        service.executeAll(article);verify(gateway,times(2)).call(any());
        assertThat(http.getForObject("/api/admin/llm/news/"+article+"/results",JsonNode.class).size()).isEqualTo(2);
        assertThat(catalog.seed()).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM vw_company_news_latest WHERE summary IS NOT NULL",Integer.class)).isGreaterThan(0);
    }
    @Test void fallbackAuditsPersistAndCacheUsesRoutingPolicy() throws Exception {
        doAnswer(i->{
            JsonNode request=i.getArgument(0);
            java.util.function.Consumer<LlmGateway.Attempt> audit=i.getArgument(1);
            audit.accept(new LlmGateway.Attempt(1,"gemini-primary",429,"HTTP_429","{}",null,null,null,10));
            var input=json.read(request.path("contents").path(0).path("parts").path(0).path("text").asText());
            String output=NewsLlmContractTests.output(json,input,"NEWS_SUMMARY").toString();
            audit.accept(new LlmGateway.Attempt(2,"gemini-fallback",200,null,"{}",output,20,30,20));
            return new LlmGateway.Reply(200,"{}",output,20,30,"gemini-fallback");
        }).when(gateway).call(any(),any());
        var result=service.execute(article,"NEWS_SUMMARY");
        assertThat(result.status()).isEqualTo("SUCCESS");
        var run=store.run(result.runId());
        assertThat(run.path("model_name").asText()).isEqualTo("gemini-fallback");
        assertThat(run.path("attempts").size()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM llm_runs r CROSS JOIN LATERAL jsonb_array_elements(r.attempts) a WHERE a->'request_payload' IS NOT NULL",Integer.class)).isEqualTo(2);
        service.execute(article,"NEWS_SUMMARY");
        verify(gateway,times(1)).call(any(),any());
    }
    @Test void rejectsBadProviderOutputWithoutPublishingResult() throws Exception {
        doReturn(new LlmGateway.Reply(200,"{\"bad\":true}","{\"wrong\":1}",1,1)).when(gateway).call(any());
        var result=service.execute(article,"NEWS_SUMMARY");
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isZero();
        assertThat(store.run(result.runId()).path("response_text").asText()).contains("wrong");
        assertThat(db.queryForObject("SELECT count(*) FROM validation_results WHERE llm_run_id=? AND result_status='FAIL'",Integer.class,result.runId())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM validation_results WHERE llm_run_id=? AND result_status='SKIP'",Integer.class,result.runId())).isEqualTo(5);
    }
    @Test void sharedValidationRecordsCompleteRulesAndPreservesRechecks() throws Exception {
        var outcome=service.execute(article,"NEWS_SUMMARY");assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(db.queryForObject("SELECT count(*) FROM validation_results WHERE llm_run_id=? AND validation_target='LLM_OUTPUT' AND raw_payload_id IS NULL AND ingestion_run_id IS NULL AND rule_snapshot IS NOT NULL",Integer.class,outcome.runId())).isEqualTo(9);
        assertThat(store.revalidate(outcome.runId()).status()).isEqualTo("SUCCESS");
        assertThat(db.queryForObject("SELECT count(DISTINCT validation_round_id) FROM validation_results WHERE llm_run_id=?",Integer.class,outcome.runId())).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isEqualTo(1);
        verify(gateway,times(1)).call(any());
        assertThatThrownBy(()->db.update("UPDATE validation_results SET raw_payload_id=? WHERE llm_run_id=?",UUID.randomUUID(),outcome.runId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void missingMandatoryRuleStopsBeforeProviderCall() throws Exception {
        db.update("UPDATE validation_rules SET is_active=false WHERE code='LLM_EVIDENCE_QUOTES'");
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("VALIDATION_CONFIGURATION_ERROR");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_runs",Integer.class)).isZero();verify(gateway,never()).call(any());
    }
    @Test void durableResponseCanBeValidatedAfterRestartWithoutProviderCall() throws Exception {
        var template=catalog.active("NEWS_SUMMARY");var context=contexts.build(article,"NEWS_SUMMARY");
        var request=gateway.request(template,context.input());
        var run=store.claim(article,template,context,"8".repeat(64),gateway.model(),request);
        var output=NewsLlmContractTests.output(json,context.input(),"NEWS_SUMMARY");
        assertThat(store.stageResponse(run.runId(),new LlmGateway.Reply(200,"{}",output.toString(),2,3),List.of(),20)).isTrue();
        assertThat(db.queryForObject("SELECT status FROM llm_runs WHERE id=?",String.class,run.runId())).isEqualTo("PENDING_VALIDATION");
        assertThat(store.claim(article,template,context,"9".repeat(64),gateway.model(),request).status()).isEqualTo("RUNNING");
        assertThat(service.validatePending(5)).singleElement().satisfies(r->assertThat(r.status()).isEqualTo("SUCCESS"));
        assertThat(service.validatePending(5)).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM validation_results WHERE llm_run_id=?",Integer.class,run.runId())).isEqualTo(9);
        assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isEqualTo(1);verify(gateway,never()).call(any());
    }
    @Test void correctedValidationConfigurationCanPublishSavedResponseWithoutNewCall() throws Exception {
        doAnswer(i->{
            var input=json.read(((JsonNode)i.getArgument(0)).path("contents").path(0).path("parts").path(0).path("text").asText());
            db.update("UPDATE validation_rules SET is_active=false WHERE code='LLM_EVIDENCE_QUOTES'");
            return new LlmGateway.Reply(200,"{}",NewsLlmContractTests.output(json,input,"NEWS_SUMMARY").toString(),1,1);
        }).when(gateway).call(any());
        var run=service.execute(article,"NEWS_SUMMARY");assertThat(run.status()).isEqualTo("FAILED");
        assertThat(store.run(run.runId()).path("response_text").asText()).isNotEmpty();
        db.update("UPDATE validation_rules SET is_active=true WHERE code='LLM_EVIDENCE_QUOTES'");
        assertThat(store.revalidate(run.runId()).status()).isEqualTo("SUCCESS");
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("CACHED");
        assertThat(db.queryForObject("SELECT count(DISTINCT validation_round_id) FROM validation_results WHERE llm_run_id=?",Integer.class,run.runId())).isEqualTo(2);
        verify(gateway,times(1)).call(any());
    }
    @Test void changedRuleVersionHidesOutputUntilRevalidationAndThenCachesIt() throws Exception {
        var result=service.execute(article,"NEWS_SUMMARY");
        db.update("UPDATE validation_rules SET rule_config=jsonb_set(rule_config,'{version}','\"2\"') WHERE code='LLM_EVIDENCE_QUOTES'");
        assertThat(store.results(article)).isEmpty();
        assertThat(store.revalidate(result.runId()).status()).isEqualTo("SUCCESS");
        assertThat(store.results(article)).hasSize(1);
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("CACHED");
        verify(gateway,times(1)).call(any());
    }
    @Test void policyChangedDuringCallRejectsAndSnapshotsExecutedRules() throws Exception {
        doAnswer(i->{
            var input=json.read(((JsonNode)i.getArgument(0)).path("contents").path(0).path("parts").path(0).path("text").asText());
            db.update("UPDATE validation_rules SET rule_config=jsonb_set(rule_config,'{version}','\"2\"') WHERE code='LLM_EVIDENCE_QUOTES'");
            return new LlmGateway.Reply(200,"{}",NewsLlmContractTests.output(json,input,"NEWS_SUMMARY").toString(),1,1);
        }).when(gateway).call(any());
        var result=service.execute(article,"NEWS_SUMMARY");
        assertThat(result.status()).isEqualTo("REJECTED");assertThat(result.issues()).contains("VALIDATION_RULES_CHANGED_DURING_RUN");
        assertThat(db.queryForObject("SELECT rule_snapshot->'config'->>'version' FROM validation_results WHERE llm_run_id=? AND rule_code='LLM_EVIDENCE_QUOTES'",String.class,result.runId())).isEqualTo("2");
    }
    @Test void rejectedOutputGetsFeedbackWithoutResettingRetryLimit() throws Exception {
        doReturn(new LlmGateway.Reply(200,"{}","{\"wrong\":1}",1,1)).when(gateway).call(any());
        for(int i=0;i<3;i++) assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("REJECTED");
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("RETRY_LIMIT");
        assertThat(db.queryForObject("SELECT count(DISTINCT input_hash) FROM llm_runs",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM llm_runs WHERE jsonb_array_length(request_payload->'contents')=3",Integer.class)).isEqualTo(2);
    }
    @Test void noKeyNeverCreatesRunOrResult() throws Exception {
        when(gateway.configured()).thenReturn(false);
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("CONFIGURATION_REQUIRED");
        verify(gateway,never()).call(any());
        assertThat(db.queryForObject("SELECT count(*) FROM llm_runs",Integer.class)).isZero();
    }
    @Test void financialBranchPersistsThirdResult() throws Exception {
        doAnswer(i->{
            var input=json.read(((JsonNode)i.getArgument(0)).path("contents").path(0).path("parts").path(0).path("text").asText());
            var out=NewsLlmContractTests.output(json,input,input.path("task_code").asText());
            if("NEWS_DETAIL".equals(input.path("task_code").asText())) out.put("contains_financial_figures",true);
            return new LlmGateway.Reply(200,"{\"test_fixture\":true}",out.toString(),10,10);
        }).when(gateway).call(any());
        assertThat(service.executeAll(article).get("NEWS_FINANCIAL_FACTS").status()).isEqualTo("SUCCESS");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isEqualTo(3);
    }
    @Test void sourceChangeRejectsInFlightResult() throws Exception {
        doAnswer(i->{
            var input=json.read(((JsonNode)i.getArgument(0)).path("contents").path(0).path("parts").path(0).path("text").asText());
            db.update("UPDATE news_articles SET sapo=coalesce(sapo,'')||' [test change]' WHERE id=?",article);
            return new LlmGateway.Reply(200,"{}",NewsLlmContractTests.output(json,input,"NEWS_SUMMARY").toString(),1,1);
        }).when(gateway).call(any());
        var result=service.execute(article,"NEWS_SUMMARY");
        assertThat(result.status()).isEqualTo("REJECTED");assertThat(result.issues()).contains("SOURCE_CHANGED_DURING_RUN");
    }
    @Test void concurrentCallsClaimOnlyOneProviderRequest() throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(i->{
            started.countDown(); if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            var input=json.read(((JsonNode)i.getArgument(0)).path("contents").path(0).path("parts").path(0).path("text").asText());
            return new LlmGateway.Reply(200,"{}",NewsLlmContractTests.output(json,input,"NEWS_SUMMARY").toString(),1,1);
        }).when(gateway).call(any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->service.execute(article,"NEWS_SUMMARY"));
            try {assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("RUNNING");
            } finally {release.countDown();}
            assertThat(first.get(15,TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
        }
        verify(gateway,times(1)).call(any());
    }
    @Test void httpErrorAndTransportErrorAreLogged() throws Exception {
        doReturn(new LlmGateway.Reply(429,"{\"error\":\"quota\"}",null,null,null)).when(gateway).call(any());
        var result=service.execute(article,"NEWS_SUMMARY");
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(store.run(result.runId()).path("http_status").asInt()).isEqualTo(429);
        doThrow(new java.io.IOException("test timeout")).when(gateway).call(any());
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("FAILED");
    }
    @Test void urlOnlyDuplicateAndTestSourcesAreSkipped() throws Exception {
        db.update("UPDATE news_articles SET content_text=null WHERE id=?",article);
        assertThat(service.execute(article,"NEWS_SUMMARY").issues()).contains("BODY_MISSING_OR_TOO_SHORT");
        db.update("UPDATE news_articles SET dedup_status='DUPLICATE',metadata=metadata||'{\"is_test\":true}'::jsonb WHERE id=?",article);
        assertThat(service.execute(article,"NEWS_SUMMARY").issues()).contains("DUPLICATE_OR_UNRESOLVED","TEST_OR_EXCLUDED_SOURCE");
        verify(gateway,never()).call(any());
    }
    @Test void changedSourceHidesOldResultAndCreatesNewVersion() {
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("SUCCESS");
        db.update("UPDATE news_articles SET sapo=coalesce(sapo,'')||' changed',updated_at=now() WHERE id=?",article);
        assertThat(store.results(article)).isEmpty();
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("SUCCESS");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM llm_results WHERE is_current",Integer.class)).isEqualTo(1);
    }
    @Test void schedulerCandidatesAndRetryLimitDoNotLoopForever() throws Exception {
        assertThat(service.candidates(5)).contains(article);
        doReturn(new LlmGateway.Reply(500,"{}",null,null,null)).when(gateway).call(any());
        for(int i=0;i<3;i++) assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("FAILED");
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("RETRY_LIMIT");
        assertThat(service.candidates(5)).doesNotContain(article);
        verify(gateway,times(3)).call(any());
    }
    @Test void expiredRunIsRecoveredAndLateWorkerCannotPublish() {
        var template=catalog.active("NEWS_SUMMARY");var context=contexts.build(article,"NEWS_SUMMARY");
        var request=gateway.request(template,context.input());
        var old=store.claim(article,template,context,"1".repeat(64),gateway.model(),request);
        db.update("UPDATE llm_runs SET created_at=now()-interval '11 minutes' WHERE id=?",old.runId());
        assertThat(service.candidates(5)).contains(article);
        assertThat(service.execute(article,"NEWS_SUMMARY").status()).isEqualTo("SUCCESS");
        var out=NewsLlmContractTests.output(json,context.input(),"NEWS_SUMMARY");
        assertThat(store.finish(old.runId(),article,template,context,"1".repeat(64),new LlmGateway.Reply(200,"{}",out.toString(),1,1),out,List.of(),1).status()).isEqualTo("LEASE_LOST");
    }
    @Test void disabledTemplatesAreNotExecutedOrScheduled() throws Exception {
        db.update("UPDATE llm_prompt_templates SET enabled=false");
        assertThat(catalog.activeTemplates()).isEmpty();
        assertThat(service.candidates(5)).isEmpty();
        assertThat(service.executeAll(article).values()).allMatch(r->r.status().equals("TEMPLATE_DISABLED"));
        verify(gateway,never()).call(any());
    }
}

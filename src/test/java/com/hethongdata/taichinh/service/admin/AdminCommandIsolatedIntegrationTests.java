package com.hethongdata.taichinh.service.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import jakarta.validation.Validation;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Real PostgreSQL ledger/constraints; native domain services mocked. Fixtures never enter public. */
@EnabledIfEnvironmentVariable(named="DATA_ENRICHMENT_TEST_DB",matches="1")
class AdminCommandIsolatedIntegrationTests {
  static final String SCHEMA="admin_console_test_"+UUID.randomUUID().toString().replace("-","");
  static JdbcTemplate admin,db; static DriverManagerDataSource scoped;
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final IngestionJobService jobs=mock(IngestionJobService.class);
  AdminCommandService service;
  static DriverManagerDataSource source(String url,Properties p) {
    return new DriverManagerDataSource(url,p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"));
  }
  @BeforeAll static void schema() throws Exception {
    var p=new Properties();try(var r=Files.newBufferedReader(Path.of(System.getProperty("financial.test.config","application-local.properties")))) { p.load(r); }
    String url=p.getProperty("spring.datasource.url"); admin=new JdbcTemplate(source(url,p)); admin.execute("create schema "+SCHEMA);
    String u=url.contains("currentSchema=")?url.replaceAll("currentSchema=[^&]+","currentSchema="+SCHEMA):url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
    scoped=source(u,p); db=new JdbcTemplate(scoped);
    assertThat(db.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
    db.execute(Files.readString(Path.of("src/main/resources/db/manual/V20261019_01__admin_commands.sql")));
    db.execute(Files.readString(Path.of("src/main/resources/db/manual/V20261020_01__admin_flow_controls.sql")));
    db.execute("create table ingestion_runs(id uuid primary key,ingestion_run_id uuid,status text,created_at timestamptz,payload jsonb,value numeric)");
    db.execute("create table securities(id uuid primary key,symbol text)");
    db.execute("create table metric_definitions(id bigint primary key,code text)");
    db.execute("create table financial_periods(id uuid primary key,end_date date)");
    db.execute("create table financial_metrics(id uuid primary key,security_id uuid,metric_definition_id bigint,financial_period_id uuid,value numeric,created_at timestamptz)");
    db.execute("create table llm_prompt_templates (like public.llm_prompt_templates including all)");
  }
  @AfterAll static void cleanup() { if(admin!=null && SCHEMA.matches("admin_console_test_[a-f0-9]{32}")) admin.execute("drop schema "+SCHEMA+" cascade"); }
  @BeforeEach void setup() {
    db.execute("truncate admin_operation_events,admin_commands,ingestion_runs,securities,metric_definitions,financial_periods,financial_metrics,llm_prompt_templates");
    service=new AdminCommandService(db,json,jobs,mock(IngestionService.class),mock(ValidationJobService.class),
        Validation.buildDefaultValidatorFactory().getValidator(),new DataSourceTransactionManager(scoped));
  }
  @AfterEach void stop() { service.stop(); }
  AdminCommandService.Request request(UUID key,UUID job,String reason) {
    return new AdminCommandService.Request(key,"RUN_JOB",reason,json.createObjectNode().put("jobId",job.toString()));
  }
  @Test void duplicateSubmissionDispatchesNativeServiceOnlyOnceAndKeepsResult() {
    UUID key=UUID.randomUUID(),job=UUID.randomUUID(),run=UUID.randomUUID();
    when(jobs.runOnce(job)).thenReturn(new IngestionExecutionResponse(run,null,"NO_CHANGE",200,"application/json",null,false));
    var first=service.submit(request(key,job,"Inspect build"));
    assertThat(service.submit(request(key,job,"Inspect build")).get("id")).isEqualTo(first.get("id"));
    ReflectionTestUtils.invokeMethod(service,"work"); ReflectionTestUtils.invokeMethod(service,"work");
    var result=service.find((UUID)first.get("id"));
    assertThat(result.get("status")).isEqualTo("SUCCEEDED");
    assertThat(result.get("result_json").toString()).contains("NO_CHANGE",run.toString());
    verify(jobs,times(1)).runOnce(job);
    assertThatThrownBy(()->service.submit(request(key,job,"Different intent"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void activeResourceAndUnknownOutcomeRequireReviewBeforeRetry() {
    UUID job=UUID.randomUUID();var first=service.submit(request(UUID.randomUUID(),job,"Check existing"));
    assertThatThrownBy(()->service.submit(request(UUID.randomUUID(),job,"Duplicate"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    db.update("update admin_commands set status='RUNNING',lease_until=now()-interval '1 minute',owner_id=?",UUID.randomUUID());
    ReflectionTestUtils.invokeMethod(service,"leases");
    assertThat(service.find((UUID)first.get("id")).get("status")).isEqualTo("UNKNOWN");
    assertThatThrownBy(()->service.submit(request(UUID.randomUUID(),job,"Still unresolved"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    service.acknowledge((UUID)first.get("id"),"Checked original native run before retry");
    assertThat(service.submit(request(UUID.randomUUID(),job,"Reviewed retry")).get("status")).isEqualTo("QUEUED");
    verifyNoInteractions(jobs);
  }
  @Test void failedNativeCallRetainsRunAndUpstreamDiagnostics() {
    UUID job=UUID.randomUUID(),run=UUID.randomUUID();
    when(jobs.runOnce(job)).thenThrow(new IngestionExecutionException(run,com.hethongdata.taichinh.application.port.error.ExternalErrorCategory.RATE_LIMIT,429,"Source quota reached",null));
    var command=service.submit(request(UUID.randomUUID(),job,"Try source")); ReflectionTestUtils.invokeMethod(service,"work");
    var result=service.find((UUID)command.get("id")); assertThat(result.get("status")).isEqualTo("FAILED");
    assertThat(result.get("result_json").toString()).contains(run.toString(),"RATE_LIMIT","429");
  }
  @Test void browserPaginationFiltersAndDecimalsAreExactAndRawBodyIsExcluded() {
    UUID run=UUID.randomUUID();
    for(int i=0;i<7;i++) db.update("insert into ingestion_runs values (?,?,'FAILED',now(),cast('{\"secret\":\"hidden\"}' as jsonb),12345678901234567890.125)",UUID.randomUUID(),run);
    var reads=new AdminConsoleReadService(scoped,json);
    var first=reads.browse("ingestion_runs",0,3,null,run.toString(),"FAILED");
    var second=reads.browse("ingestion_runs",1,3,null,run.toString(),"FAILED");
    assertThat(first.get("total")).isEqualTo(7L);
    assertThat(first.get("rows").toString()).contains("12345678901234567890.125").doesNotContain("hidden","payload");
    assertThat(first.get("rows")).isNotEqualTo(second.get("rows"));
    assertThat(reads.browse("ingestion_runs",0,25,null,null,"SUCCESS").get("total")).isEqualTo(0L);
  }
  @Test void financialRowsHaveReadableSymbolMetricPeriodAndSearchBySymbolOrMetric() {
    UUID security=UUID.randomUUID(),period=UUID.randomUUID();
    db.update("insert into securities values (?,'FPT')",security);
    db.update("insert into metric_definitions values (1,'ROAE')");
    db.update("insert into financial_periods values (?,'2026-06-30')",period);
    db.update("insert into financial_metrics values (?,?,1,?,22.123456789012345,now())",UUID.randomUUID(),security,period);
    var reads=new AdminConsoleReadService(scoped,json);
    for(String search:List.of("FPT","ROAE")) {
      var result=reads.browse("financial_metrics",0,25,search,null,null);
      assertThat(result.get("total")).isEqualTo(1L);
      assertThat(result.get("rows").toString()).contains("FPT","ROAE","2026-06-30","22.123456789012345");
    }
    assertThat(reads.browse("financial_metrics",0,25,"VCB",null,null).get("total")).isEqualTo(0L);
  }
  @Test void cancellationAndRetryAreAuditedWithoutReusingOriginalCommand() {
    UUID job=UUID.randomUUID();var first=service.submit(request(UUID.randomUUID(),job,"Reviewed job"));UUID id=(UUID)first.get("id");
    assertThat(service.cancel(id,"Cancelled before execution").get("status")).isEqualTo("CANCELLED");
    ReflectionTestUtils.invokeMethod(service,"work");verifyNoInteractions(jobs);
    UUID key=UUID.randomUUID();var retried=service.retry(id,key,"Reviewed and retry",true);
    assertThat(retried.get("id")).isNotEqualTo(id);assertThat(retried.get("parent_command_id")).isEqualTo(id);
    assertThat(service.retry(id,key,"Reviewed and retry",true).get("id")).isEqualTo(retried.get("id"));
    assertThat(db.queryForObject("select count(*) from admin_operation_events where event_type='CANCELLED'",Integer.class)).isEqualTo(1);
    assertThatThrownBy(()->service.cancel((UUID)retried.get("id")," ")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void reorderedJsonHasSameIdempotentIdentityAndReplaySharesSourceResource() throws Exception {
    UUID key=UUID.randomUUID();
    var a=json.readTree("{\"operation\":\"QUOTE\",\"provider\":\"vndirect\",\"symbol\":\"FPT\",\"parameters\":{\"x\":\"a\",\"y\":\"b\"}}");
    var b=json.readTree("{\"parameters\":{\"y\":\"b\",\"x\":\"a\"},\"symbol\":\"FPT\",\"provider\":\"vndirect\",\"operation\":\"QUOTE\"}");
    var one=service.submit(new AdminCommandService.Request(key,"MANUAL_INGESTION","Check order",a));
    assertThat(service.submit(new AdminCommandService.Request(key,"MANUAL_INGESTION","Check order",b)).get("id")).isEqualTo(one.get("id"));
    assertThatThrownBy(()->service.retry((UUID)one.get("id"),UUID.randomUUID(),"Unsafe retry",true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void retryOfPartiallySavedPipelineContinuesOriginalRunRatherThanRefetching() {
    UUID id=UUID.randomUUID(),run=UUID.randomUUID();
    db.update("insert into admin_commands(id,idempotency_key,request_hash,action,resource_key,reason,status,request_json,progress_json) values (?,?,'fixture','INGEST_VALIDATE_BUILD','fixture','fixture','FAILED','{}',cast(? as jsonb))",id,UUID.randomUUID(),"{\"sourceRunId\":\""+run+"\",\"stage\":\"VALIDATING\"}");
    var next=service.retry(id,UUID.randomUUID(),"Continue saved source",true);
    assertThat(next.get("action")).isEqualTo("PROCESS_RUN");assertThat(next.get("request_json").toString()).contains(run.toString());
  }
  @Test void nativeJobGuardBlocksAnotherInstanceAndReleasesAfterFailure() throws Exception {
    var one=new NativeJobGuard(scoped);var two=new NativeJobGuard(scoped);UUID job=UUID.randomUUID();
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var held=worker.submit(()->one.run(job,()->{entered.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "done";}));
      assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(()->two.run(job,()->"must not execute")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      release.countDown();assertThat(held.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("done");
      assertThatThrownBy(()->two.run(job,()->{throw new IllegalArgumentException("Fixture native failure");})).isInstanceOf(IllegalArgumentException.class);
      assertThat(one.run(job,()->"released")).isEqualTo("released");
    } finally {release.countDown();worker.shutdownNow();}
  }
  @Test void schedulerStartsDisabledAndRejectsEmptyOrStaleScope() {
    db.update("update admin_schedule_policy set enabled=false,version=0,job_codes='[]' where id='INGESTION'");
    var definitions=mock(com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository.class);
    var scheduler=new AdminScheduleService(db,json,jobs,definitions,new org.springframework.mock.env.MockEnvironment(),new NativeJobGuard(scoped));
    try {
      assertThat(scheduler.status().get("enabled")).isEqualTo(false);
      ReflectionTestUtils.invokeMethod(scheduler,"tick");verifyNoInteractions(jobs);
      assertThat(scheduler.status().get("heartbeat_at")).isNotNull();
      assertThatThrownBy(()->scheduler.update(new AdminScheduleService.Request(true,Set.of(),0,"Enable without scope"))).isInstanceOf(IllegalArgumentException.class);
      scheduler.update(new AdminScheduleService.Request(false,Set.of(),0,"Keep disabled"));
      assertThatThrownBy(()->scheduler.update(new AdminScheduleService.Request(false,Set.of(),0,"Stale update"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    } finally {scheduler.stop();}
  }
  @Test void promptVersionsRetainSchemasAndPromotionIsAtomicAndAudited() {
    UUID original=UUID.randomUUID();
    db.update("insert into llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,request_schema,response_schema,checksum,enabled) values (?,'NEWS_SUMMARY','fixture-v1','NEWS','Isolated fixture','Original fixture prompt','{\"type\":\"object\"}','{\"required\":[\"evidence\"]}',?,true)",original,"a".repeat(64));
    var controller=new com.hethongdata.taichinh.controller.admin.AdminPromptController(db,json,new com.hethongdata.taichinh.service.ingestion.ChecksumService());
    var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(scoped));
    var request=new com.hethongdata.taichinh.controller.admin.AdminPromptController.VersionRequest(original,"fixture-v2","Updated fixture prompt","Test version lifecycle in isolated schema");
    var created=tx.execute(s->controller.create(request));UUID id=(UUID)created.get("id");
    assertThat(created.get("enabled")).isEqualTo(false);
    assertThat(controller.detail(original).path("system_prompt").asText()).isEqualTo("Original fixture prompt");
    assertThat(controller.detail(id).path("response_schema").toString()).contains("evidence");
    tx.execute(s->controller.enabled(id,new com.hethongdata.taichinh.controller.admin.AdminPromptController.EnableRequest(true,"Reviewed isolated version")));
    assertThat(db.queryForObject("select count(*) from llm_prompt_templates where enabled",Integer.class)).isEqualTo(1);
    assertThat(controller.detail(id).path("enabled").asBoolean()).isTrue();
    assertThatThrownBy(()->tx.execute(s->controller.create(request))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(db.queryForObject("select count(*) from admin_operation_events where event_type like 'PROMPT_%'",Integer.class)).isEqualTo(2);
  }
}

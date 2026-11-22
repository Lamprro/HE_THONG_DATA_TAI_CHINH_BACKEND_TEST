package com.hethongdata.taichinh.service.admin;

import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import com.hethongdata.taichinh.repository.jpa.admin.*;
import com.hethongdata.taichinh.repository.jpa.account.AccountJpaRepository;
import com.hethongdata.taichinh.repository.jpa.account.RevokedAccessTokenJpaRepository;
import com.hethongdata.taichinh.security.AccountSecurityService;
import com.hethongdata.taichinh.jwt.JwtService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import org.springframework.test.util.ReflectionTestUtils;

/** Real PostgreSQL ledger/constraints; native domain services mocked. Fixtures never enter public. */
@EnabledIfEnvironmentVariable(named="DATA_ENRICHMENT_TEST_DB",matches="1")
class AdminCommandIsolatedIntegrationTests {
  static final String SCHEMA="admin_console_test_"+UUID.randomUUID().toString().replace("-","");
  static JdbcTemplate admin,db; static DriverManagerDataSource scoped;
  static AnnotationConfigApplicationContext context;
  static AdminCommandJpaRepository commands;
  static JpaTransactionManager transactions;

  @Configuration(proxyBeanMethods = false)
  @EnableJpaRepositories(basePackageClasses = {AdminCommandJpaRepository.class, AccountJpaRepository.class})
  @EnableTransactionManagement
  static class JpaConfig {
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(source);
      factory.setPackagesToScan("com.hethongdata.taichinh.entity.admin", "com.hethongdata.taichinh.entity.account");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      return factory;
    }

    @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
  }
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final IngestionJobService jobs=mock(IngestionJobService.class);
  AdminCommandService service;
  static DriverManagerDataSource source(String url,Properties p) {
    return new DriverManagerDataSource(url,p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"));
  }
  @BeforeAll static void schema() throws Exception {
    var p=new Properties();try(var r=Files.newBufferedReader(Path.of(Objects.requireNonNull(System.getProperty("financial.test.config"), "Specify a dedicated PostgreSQL test configuration")))) { p.load(r); }
    String url=p.getProperty("spring.datasource.url"); admin=new JdbcTemplate(source(url,p)); admin.execute("create schema "+SCHEMA);
    String u=url.contains("currentSchema=")?url.replaceAll("currentSchema=[^&]+","currentSchema="+SCHEMA):url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
    scoped=source(u,p); db=new JdbcTemplate(scoped);
    assertThat(db.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
    db.execute(Files.readString(Path.of("src/main/resources/db/manual/V20261019_01__admin_commands.sql")));
    db.execute(Files.readString(Path.of("src/main/resources/db/manual/V20261020_01__admin_flow_controls.sql")));
    db.execute(Files.readString(Path.of("src/main/resources/db/manual/V20261122_05__revoke_access_tokens.sql")));
    db.execute("drop table admin_operation_events, admin_schedule_policy");
    db.execute("create table ingestion_runs(id uuid primary key,ingestion_run_id uuid,status text,created_at timestamptz,payload jsonb,value numeric)");
    db.execute("create table securities(id uuid primary key,symbol text)");
    db.execute("create table metric_definitions(id bigint primary key,code text)");
    db.execute("create table financial_periods(id uuid primary key,end_date date)");
    db.execute("create table financial_metrics(id uuid primary key,security_id uuid,metric_definition_id bigint,financial_period_id uuid,value numeric,created_at timestamptz)");
    db.execute("""
        create table llm_prompt_templates (
          id uuid primary key, task_code text not null, version text not null, input_domain text not null,
          description text, system_prompt text, request_schema jsonb, response_schema jsonb, checksum text,
          enabled boolean not null, created_at timestamptz not null default now(), unique (task_code,version)
        );
        create table roles (id bigint primary key, code varchar(50) not null unique);
        create table accounts (id uuid primary key, user_id uuid, username varchar(100), email varchar(255) not null,
          password_hash varchar(255) not null, role_id bigint references roles(id), status varchar(30) not null);
        create table ingestion_jobs (is_active boolean);
        create table data_versions (data_domain text, status text);
        create table validation_results (id uuid primary key, validation_target text, result_status text);
        create table news_articles (id uuid primary key, title text, published_at timestamptz);
        create table data_sources (id bigint primary key, code text);
        alter table ingestion_runs add column data_source_id bigint, add column request_query_params jsonb
        """);
    context = new AnnotationConfigApplicationContext();
    context.registerBean(DataSource.class, () -> scoped);
    context.register(JpaConfig.class);
    context.refresh();
    commands = context.getBean(AdminCommandJpaRepository.class);
    transactions = context.getBean(JpaTransactionManager.class);
  }
  @AfterAll static void cleanup() {
    if (context != null) context.close();
    if (admin != null && SCHEMA.matches("admin_console_test_[a-f0-9]{32}")) admin.execute("drop schema if exists "+SCHEMA+" cascade");
  }
  @BeforeEach void setup() {
    db.execute("truncate admin_commands,ingestion_runs,securities,metric_definitions,financial_periods,financial_metrics,llm_prompt_templates,ingestion_jobs,data_versions,validation_results,news_articles,data_sources,accounts,roles");
    service=new AdminCommandService(commands,json,jobs,mock(IngestionService.class),mock(ValidationJobService.class),
        Validation.buildDefaultValidatorFactory().getValidator(),transactions,mock(AdminPipelineService.class),mock(AdminAiActionService.class));
  }
  @AfterEach void stop() { if (service != null) service.stop(); }
  CommandRequest request(UUID key,UUID job,String reason) {
    return new CommandRequest(key,"RUN_JOB",reason,json.createObjectNode().put("jobId",job.toString()));
  }
  @Test void duplicateSubmissionDispatchesNativeServiceOnlyOnceAndKeepsResult() {
    UUID key=UUID.randomUUID(),job=UUID.randomUUID(),run=UUID.randomUUID();
    when(jobs.runOnce(job)).thenReturn(new IngestionExecutionResponse(run,null,"NO_CHANGE",200,"application/json",null,false));
    var first=service.submit(request(key,job,"Inspect build"));
    assertThat(service.submit(request(key,job,"Inspect build")).id()).isEqualTo(first.id());
    ReflectionTestUtils.invokeMethod(service,"work"); ReflectionTestUtils.invokeMethod(service,"work");
    var result=service.find(first.id());
    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.result().toString()).contains("NO_CHANGE",run.toString());
    verify(jobs,times(1)).runOnce(job);
    assertThatThrownBy(()->service.submit(request(key,job,"Different intent"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void workerLeavesFutureActionsQueuedForCompatibleRuntime() {
    var command=service.submit(request(UUID.randomUUID(),UUID.randomUUID(),"Future runtime compatibility"));
    db.update("update admin_commands set action='FUTURE_NATIVE_ACTION' where id=?",command.id());
    ReflectionTestUtils.invokeMethod(service,"work");
    assertThat(service.find(command.id()).status()).isEqualTo("QUEUED");
    verifyNoInteractions(jobs);
  }
  @Test void activeResourceAndUnknownOutcomeRequireReviewBeforeRetry() {
    UUID job=UUID.randomUUID();var first=service.submit(request(UUID.randomUUID(),job,"Check existing"));
    assertThatThrownBy(()->service.submit(request(UUID.randomUUID(),job,"Duplicate"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    db.update("update admin_commands set status='RUNNING',lease_until=now()-interval '1 minute',owner_id=?",UUID.randomUUID());
    ReflectionTestUtils.invokeMethod(service,"leases");
    assertThat(service.find(first.id()).status()).isEqualTo("UNKNOWN");
    assertThatThrownBy(()->service.submit(request(UUID.randomUUID(),job,"Still unresolved"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    service.acknowledge(first.id(),"Checked original native run before retry");
    assertThat(service.submit(request(UUID.randomUUID(),job,"Reviewed retry")).status()).isEqualTo("QUEUED");
    verifyNoInteractions(jobs);
  }
  @Test void failedNativeCallRetainsRunAndUpstreamDiagnostics() {
    UUID job=UUID.randomUUID(),run=UUID.randomUUID();
    when(jobs.runOnce(job)).thenThrow(new IngestionExecutionException(run,com.hethongdata.taichinh.application.port.error.ExternalErrorCategory.RATE_LIMIT,429,"Source quota reached",null));
    var command=service.submit(request(UUID.randomUUID(),job,"Try source")); ReflectionTestUtils.invokeMethod(service,"work");
    var result=service.find(command.id()); assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.result().toString()).contains(run.toString(),"RATE_LIMIT","429");
  }
  @Test void browserPaginationFiltersAndDecimalsAreExactAndRawBodyIsExcluded() {
    UUID run=UUID.randomUUID();
    for(int i=0;i<7;i++) db.update("insert into ingestion_runs values (?,?,'FAILED',now(),cast('{\"secret\":\"hidden\"}' as jsonb),12345678901234567890.125)",UUID.randomUUID(),run);
    var reads=reads();
    var first=reads.browse("ingestion_runs",0,3,null,run.toString(),"FAILED");
    var second=reads.browse("ingestion_runs",1,3,null,run.toString(),"FAILED");
    assertThat(first.get("total")).isEqualTo(7L);
    assertThat(first.get("rows").toString()).contains("12345678901234567890.125").doesNotContain("hidden","payload");
    assertThat(first.get("rows")).isNotEqualTo(second.get("rows"));
    assertThat(reads.browse("ingestion_runs",0,25,null,null,"SUCCESS").get("total")).isEqualTo(0L);
    assertThat(reads.browse("ingestion_runs",0,25,"hidden",null,null).get("total")).isEqualTo(0L);
    assertThat(reads.browse("ingestion_runs",0,25,"FAILED",null,null).get("total")).isEqualTo(7L);
  }
  @Test void financialRowsHaveReadableSymbolMetricPeriodAndSearchBySymbolOrMetric() {
    UUID security=UUID.randomUUID(),period=UUID.randomUUID();
    db.update("insert into securities values (?,'FPT')",security);
    db.update("insert into metric_definitions values (1,'ROAE')");
    db.update("insert into financial_periods values (?,'2026-06-30')",period);
    db.update("insert into financial_metrics values (?,?,1,?,22.123456789012345,now())",UUID.randomUUID(),security,period);
    var reads=reads();
    for(String search:List.of("FPT","ROAE")) {
      var result=reads.browse("financial_metrics",0,25,search,null,null);
      assertThat(result.get("total")).isEqualTo(1L);
      assertThat(result.get("rows").toString()).contains("FPT","ROAE","2026-06-30","22.123456789012345");
    }
    assertThat(reads.browse("financial_metrics",0,25,"VCB",null,null).get("total")).isEqualTo(0L);
  }
  @Test void cancellationAndRetryKeepTheOriginalCommand() {
    UUID job=UUID.randomUUID();var first=service.submit(request(UUID.randomUUID(),job,"Reviewed job"));UUID id=first.id();
    assertThat(service.cancel(id,"Cancelled before execution").status()).isEqualTo("CANCELLED");
    ReflectionTestUtils.invokeMethod(service,"work");verifyNoInteractions(jobs);
    UUID key=UUID.randomUUID();var retried=service.retry(id,key,"Reviewed and retry",true);
    assertThat(retried.id()).isNotEqualTo(id);assertThat(retried.parentCommandId()).isEqualTo(id);
    assertThat(service.retry(id,key,"Reviewed and retry",true).id()).isEqualTo(retried.id());
    assertThatThrownBy(()->service.cancel(retried.id()," ")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void reorderedJsonHasSameIdempotentIdentityAndReplaySharesSourceResource() throws Exception {
    UUID key=UUID.randomUUID();
    var a=json.readTree("{\"operation\":\"QUOTE\",\"provider\":\"vndirect\",\"symbol\":\"FPT\",\"parameters\":{\"x\":\"a\",\"y\":\"b\"}}");
    var b=json.readTree("{\"parameters\":{\"y\":\"b\",\"x\":\"a\"},\"symbol\":\"FPT\",\"provider\":\"vndirect\",\"operation\":\"QUOTE\"}");
    var one=service.submit(new CommandRequest(key,"MANUAL_INGESTION","Check order",a));
    assertThat(service.submit(new CommandRequest(key,"MANUAL_INGESTION","Check order",b)).id()).isEqualTo(one.id());
    assertThatThrownBy(()->service.retry(one.id(),UUID.randomUUID(),"Unsafe retry",true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
  @Test void retryOfPartiallySavedPipelineContinuesOriginalRunRatherThanRefetching() {
    UUID id=UUID.randomUUID(),run=UUID.randomUUID();
    db.update("insert into admin_commands(id,idempotency_key,request_hash,action,resource_key,reason,status,request_json,progress_json) values (?,?,'fixture','INGEST_VALIDATE_BUILD','fixture','fixture','FAILED','{}',cast(? as jsonb))",id,UUID.randomUUID(),"{\"sourceRunId\":\""+run+"\",\"stage\":\"VALIDATING\"}");
    var next=service.retry(id,UUID.randomUUID(),"Continue saved source",true);
    assertThat(next.action()).isEqualTo("PROCESS_RUN");assertThat(next.request().toString()).contains(run.toString());
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
  private AdminConsoleReadService reads() {
    var entities = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
    return new AdminConsoleReadService(new AdminConsoleQueryRepository(entities),
        mock(com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository.class),
        context.getBean(AccountJpaRepository.class), json);
  }

  @Test void aSecondTransactionSkipsTheLockedCommandAndCannotCompleteItAsAnotherOwner() throws Exception {
    var first = service.submit(request(UUID.randomUUID(), UUID.randomUUID(), "First job"));
    var second = service.submit(request(UUID.randomUUID(), UUID.randomUUID(), "Second job"));
    db.update("update admin_commands set created_at=now()-interval '1 second' where id=?", first.id());
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var originalOwner = UUID.randomUUID();
    var otherOwner = UUID.randomUUID();
    var tx = new TransactionTemplate(transactions);
    try (var workers = Executors.newFixedThreadPool(2)) {
      try {
        var holding = workers.submit(() -> tx.execute(status -> {
          var command = commands.lockNext(Set.of("RUN_JOB")).orElseThrow();
          assertThat(command.getId()).isEqualTo(first.id());
          locked.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out");
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
          }
          commands.markRunning(command.getId(), originalOwner);
          return command.getId();
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        var racing = workers.submit(() -> tx.execute(status -> {
          var command = commands.lockNext(Set.of("RUN_JOB")).orElseThrow();
          commands.markRunning(command.getId(), otherOwner);
          return command.getId();
        }));
        assertThat(racing.get(5, TimeUnit.SECONDS)).isEqualTo(second.id());
        release.countDown();
        assertThat(holding.get(5, TimeUnit.SECONDS)).isEqualTo(first.id());
      } finally { release.countDown(); }
    }
    commands.recordSuccess(first.id(), otherOwner, "{}");
    assertThat(service.find(first.id()).status()).isEqualTo("RUNNING");
    commands.recordSuccess(first.id(), originalOwner, "{}");
    assertThat(service.find(first.id()).status()).isEqualTo("SUCCEEDED");
  }

  @Test void concurrentSubmissionWithTheSameKeyProducesOneCommand() throws Exception {
    var request = request(UUID.randomUUID(), UUID.randomUUID(), "Same intent");
    var start = new CyclicBarrier(2);
    Callable<Command> submit = () -> {
      start.await(5, TimeUnit.SECONDS);
      return service.submit(request);
    };
    try (var workers = Executors.newFixedThreadPool(2)) {
      var one = workers.submit(submit);
      var two = workers.submit(submit);
      assertThat(one.get(10, TimeUnit.SECONDS).id()).isEqualTo(two.get(10, TimeUnit.SECONDS).id());
    }
    assertThat(db.queryForObject("select count(*) from admin_commands", Long.class)).isEqualTo(1L);
  }

  @Test void replayKeepsTheOriginalSourceAndConflictsWithEquivalentManualWork() {
    UUID run = UUID.randomUUID();
    db.update("insert into data_sources values (1,'VNDIRECT')");
    db.update("insert into ingestion_runs (id,data_source_id,request_query_params) values (?,1,cast(? as jsonb))",
        run, """
          {"operation":"QUOTE","provider":"vndirect","symbol":"FPT"}
          """);
    var replay = new CommandRequest(UUID.randomUUID(),"REPLAY_RUN","Replay source",
        json.createObjectNode().put("runId",run.toString()));
    assertThat(service.submit(replay).status()).isEqualTo("QUEUED");
    var manual = new CommandRequest(UUID.randomUUID(),"MANUAL_INGESTION","Same source",
        json.createObjectNode().put("operation","QUOTE").put("provider","vndirect").put("symbol","FPT")
            .put("dataSourceCode","VNDIRECT"));
    assertThatThrownBy(() -> service.submit(manual))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test void accountLoginReadsTheDatabaseRoleAndPasswordHash() {
    var passwords = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    db.update("insert into roles values (1,'ADMIN')");
    db.update("insert into accounts (id,username,email,password_hash,role_id,status) values (?,?,?,?,1,'ACTIVE')",
        UUID.randomUUID(), "fixture-operator", "fixture@example.test", passwords.encode("test-password"));
    var accounts = new AccountSecurityService(context.getBean(AccountJpaRepository.class), passwords);
    var auth = accounts.authenticate("fixture-operator", "test-password");
    var tokens = new JwtService("", context.getBean(RevokedAccessTokenJpaRepository.class));
    var jwt = tokens.decode(tokens.issue(auth, accounts.authenticatedEmail(auth)).getTokenValue());
    assertThat(jwt.getSubject()).isEqualTo("fixture-operator");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN");
    assertThatThrownBy(() -> accounts.authenticate("fixture-operator", "wrong-password"))
        .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    db.update("update accounts set status='LOCKED'");
    assertThatThrownBy(() -> accounts.authenticate("fixture-operator", "test-password"))
        .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
  }

  @Test void logoutPersistsRevocationAndOnlyExpiredEntriesAreDeleted() {
    var repository = context.getBean(RevokedAccessTokenJpaRepository.class);
    var signingKey = "fixture signing key for isolated JWT logout";
    var tokens = new JwtService(signingKey, repository);
    var account = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
        "fixture-operator", null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    var jwt = tokens.issue(account, null);
    var fresh = tokens.issue(account, null);
    var expiredId = UUID.randomUUID();
    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
      repository.revoke(expiredId, java.time.Instant.now().minusSeconds(10));
      tokens.revoke(jwt);
      tokens.revoke(jwt);
    });
    var restarted = new JwtService(signingKey, repository);
    assertThatThrownBy(() -> restarted.decode(jwt.getTokenValue()))
        .isInstanceOf(org.springframework.security.oauth2.jwt.BadJwtException.class)
        .hasMessageContaining("revoked");
    assertThat(restarted.decode(fresh.getTokenValue()).getId()).isNotEqualTo(jwt.getId());
    assertThat(repository.existsById(expiredId)).isFalse();
    assertThat(repository.existsById(UUID.fromString(jwt.getId()))).isTrue();
  }

  @Test void summaryCountsUseTheSameRowsAsTheSqlLedger() {
    db.update("insert into ingestion_jobs (is_active) values (true), (true), (false)");
    db.update("insert into ingestion_runs (id,status) values (?,'SUCCEEDED')", UUID.randomUUID());
    db.update("insert into data_versions values ('MARKET_PRICE','ACTIVE')");
    UUID issue = UUID.randomUUID();
    db.update("insert into validation_results values (?,'RAW','FAIL')", issue);
    UUID article = UUID.randomUUID();
    db.update("insert into news_articles values (?,'Title','2026-11-21T01:02:03.123456Z')", article);
    service.submit(request(UUID.randomUUID(), UUID.randomUUID(), "Queued fixture"));
    var reads = reads();
    var summary = reads.summary();
    assertThat(summary.jobs()).contains(new JobCount(true, 2), new JobCount(false, 1));
    assertThat(summary.commands()).containsExactly(new StatusCount("QUEUED", 1));
    assertThat(summary.runs()).containsExactly(new StatusCount("SUCCEEDED", 1));
    assertThat(summary.versions()).containsExactly(new VersionCount("MARKET_PRICE", "ACTIVE", 1));
    assertThat(summary.validation()).containsExactly(new ValidationCount("RAW", "FAIL", 1));
    assertThat(reads.articleTitles(List.of(article))).containsExactly(
        new NewsCandidate(article, "Title", java.time.Instant.parse("2026-11-21T01:02:03.123456Z")));
  }

  @Test void promptVersionsRetainSchemasAndPromotionIsAtomic() {
    UUID original=UUID.randomUUID();
    db.update("insert into llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,request_schema,response_schema,checksum,enabled) values (?,'NEWS_SUMMARY','fixture-v1','NEWS','Isolated fixture','Original fixture prompt','{\"type\":\"object\"}','{\"required\":[\"evidence\"]}',?,true)",original,"a".repeat(64));
    var promptRepo = new com.hethongdata.taichinh.repository.jdbc.llm.PromptTemplateJdbcRepository(db);
    var promptAdmin = new com.hethongdata.taichinh.service.llm.PromptAdminService(promptRepo, json, new com.hethongdata.taichinh.service.ingestion.ChecksumService());
    var controller = new com.hethongdata.taichinh.controller.admin.LlmAdminController(null, null, null, null, promptAdmin);
    var tx=new TransactionTemplate(transactions);
    var request=new com.hethongdata.taichinh.controller.admin.LlmAdminController.PromptVersionRequest(original,"fixture-v2","Updated fixture prompt","Test version lifecycle in isolated schema");
    java.util.Map<String,Object> created=tx.execute(s->controller.createPrompt(request));UUID id=(UUID)created.get("id");
    assertThat(created.get("enabled")).isEqualTo(false);
    assertThat(controller.prompt(original).path("system_prompt").asText()).isEqualTo("Original fixture prompt");
    assertThat(controller.prompt(id).path("response_schema").toString()).contains("evidence");
    tx.execute(s->controller.setPromptEnabled(id,new com.hethongdata.taichinh.controller.admin.LlmAdminController.PromptEnableRequest(true,"Reviewed isolated version")));
    assertThat(db.queryForObject("select count(*) from llm_prompt_templates where enabled",Integer.class)).isEqualTo(1);
    assertThat(controller.prompt(id).path("enabled").asBoolean()).isTrue();
    assertThatThrownBy(()->tx.execute(s->controller.createPrompt(request))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
}

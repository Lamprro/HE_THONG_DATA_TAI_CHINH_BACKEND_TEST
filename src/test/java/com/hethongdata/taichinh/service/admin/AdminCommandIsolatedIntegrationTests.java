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
    db.execute("create table ingestion_runs(id uuid primary key,ingestion_run_id uuid,status text,created_at timestamptz,payload jsonb,value numeric)");
  }
  @AfterAll static void cleanup() { if(admin!=null && SCHEMA.matches("admin_console_test_[a-f0-9]{32}")) admin.execute("drop schema "+SCHEMA+" cascade"); }
  @BeforeEach void setup() {
    db.execute("truncate admin_commands,ingestion_runs");
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
}

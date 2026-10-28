package com.hethongdata.taichinh.service.user;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Only disposable-schema fixtures. Does not require or modify the suite's public migrations. */
@EnabledIfEnvironmentVariable(named="DATA_ENRICHMENT_TEST_DB",matches="1")
class UserLocalAiIsolatedIntegrationTests {
  static final String SCHEMA="user_ai_test_"+UUID.randomUUID().toString().replace("-","");
  static final UUID COMPANY=UUID.randomUUID(),SECURITY=UUID.randomUUID(),PACKAGE=UUID.randomUUID();
  static JdbcTemplate admin,db;static UserLocalAiService service;static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
  @BeforeAll static void setup() throws Exception {
    var props=new Properties();try(var reader=Files.newBufferedReader(Path.of(System.getProperty("financial.test.config","application-local.properties")))){props.load(reader);}
    String url=props.getProperty("spring.datasource.url"),user=props.getProperty("spring.datasource.username"),password=props.getProperty("spring.datasource.password");
    admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("create schema "+SCHEMA);
    String scoped=url.contains("currentSchema=")?url.replaceAll("currentSchema=[^&]+","currentSchema="+SCHEMA):url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
    db=new JdbcTemplate(new DriverManagerDataSource(scoped,user,password));assertThat(db.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
    db.execute("create table companies (like public.companies including defaults including constraints)");db.execute("create table securities (like public.securities including defaults including constraints)");
    db.execute("create table local_ai_packages(id uuid primary key,manifest jsonb,status text,registered_at timestamptz default now())");
    db.execute("create table local_ai_package_inputs(package_id uuid,model_id text,ticker text,data_as_of text,input_payload text)");
    db.execute("create table local_ai_package_runs(id uuid primary key,package_id uuid,model_id text,ticker text,role text,status text,checks jsonb,http_status integer,completed_at timestamptz)");
    db.execute("create table local_ai_package_predictions(run_id uuid,package_id uuid,model_id text,ticker text,role text,readiness text,prediction jsonb)");
    db.update("insert into companies(id,legal_name,is_active) values (?,'AI fixture',true)",COMPANY);
    db.update("insert into securities(id,company_id,symbol,exchange,is_active) values (?,?,'ZZAI','HOSE',true)",SECURITY,COMPANY);
    String manifest="""
      {"schema":"local_ai.registered.release.v1","production_eligible":false,"replace_llm":false,"private_path":"hidden-private-artifact",
      "models":[{"id":"npat_h2","kind":"npat","horizon":2,"target_period":"2026Q4","production_eligible":false,"selection":{"selected":"RIDGE_A100","gate_pass":true}},
      {"id":"direction_h1","kind":"h3","horizon":1,"target_period":"2026Q4","production_eligible":false},
      {"id":"unsupported_promotion","production_eligible":true}]}
      """;
    db.update("insert into local_ai_packages(id,manifest,status) values (?,?::jsonb,'RESEARCH_ONLY')",PACKAGE,manifest);
    db.update("insert into local_ai_package_inputs(package_id,model_id,ticker,data_as_of,input_payload) values (?,'npat_h2','ZZAI','2026-10-22T17:15:28Z','hidden-feature-values')",PACKAGE);
    for(String status:List.of("SUCCEEDED","PROVIDER_FAILED","CACHED")){
      UUID run=UUID.randomUUID();db.update("insert into local_ai_package_runs values (?,?,'npat_h2','ZZAI','selected',?,'{\"passed\":true}',200,now())",run,PACKAGE,status);
      db.update("insert into local_ai_package_predictions values (?,?,'npat_h2','ZZAI','selected','RESEARCH_ONLY','{\"prediction_vnd\":999999}')",run,PACKAGE);
    }
    var read=new UserReadSupport(db,JSON);service=new UserLocalAiService(read,new UserSecurityService(read));
  }
  @AfterAll static void cleanup(){if(admin!=null&&SCHEMA.matches("user_ai_test_[a-f0-9]{32}"))admin.execute("drop schema "+SCHEMA+" cascade");}
  @Test void catalogUsesRealPackageAndKeepsDevelopmentSelectionSeparateFromPublication() throws Exception {
    var models=service.models(null,0,1);assertThat(models.total()).isEqualTo(2);assertThat(models.hasNext()).isTrue();
    var row=service.models(PACKAGE,1,1).items().getFirst();assertThat(row.get("modelId")).isEqualTo("npat_h2");
    assertThat(row.get("developmentGatePassed")).isEqualTo(true);assertThat(row.get("publicResultsAvailable")).isEqualTo(false);
    assertThat(row.get("storedSelectedResearchResults")).isEqualTo("1");assertThat(row.get("registeredInputs")).isEqualTo("1");
    assertThat(JSON.writeValueAsString(row)).contains("STANDALONE_QUARTER","VND").doesNotContain("prediction_vnd\":999999","hidden-private-artifact","hidden-feature-values","request_json");
    assertThatThrownBy(()->service.models(UUID.randomUUID(),0,25)).hasMessageContaining("404");
  }
  @Test void securityBindingDoesNotGuessExchangeAndMissingInputRemainsMissing() {
    var result=service.security("ZZAI","HOSE",null).data();assertThat(result.get("state")).isEqualTo("RESEARCH_ONLY");
    assertThat(result.get("models").toString()).contains("registeredInputs=0","registeredInputs=1");
    UUID listing=UUID.randomUUID();db.update("insert into securities(id,company_id,symbol,exchange,is_active) values (?,?,'ZZAI','HNX',true)",listing,COMPANY);
    try {assertThat(service.security("ZZAI","HOSE",null).data().get("state")).isEqualTo("TICKER_IDENTITY_AMBIGUOUS");assertThat((List<?>)service.security("ZZAI","HOSE",null).data().get("models")).isEmpty();}
    finally {db.update("delete from securities where id=?",listing);}
  }
  @Test void legacySchemaReturnsUnavailableWithoutQueryingMissingTables() {
    db.execute("alter table local_ai_package_predictions rename to hidden_predictions");
    try {assertThat(service.summary().data().get("state")).isEqualTo("NOT_INSTALLED");assertThat(service.models(null,0,25).items()).isEmpty();assertThat(service.security("ZZAI",null,null).data().get("state")).isEqualTo("NOT_INSTALLED");}
    finally {db.execute("alter table hidden_predictions rename to local_ai_package_predictions");}
  }
}

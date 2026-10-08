package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL migration and append tests; no writes to public business tables. */
@EnabledIfEnvironmentVariable(named="LLM_TEST_DB", matches="1")
class LlmAttemptAuditIntegrationTests {
    Properties config = new Properties();
    String schema;
    DriverManagerDataSource source;
    JdbcTemplate db;
    LlmJson json = new LlmJson(new ObjectMapper());
    UUID run;

    @BeforeEach void setup() throws Exception {
        try(var reader=Files.newBufferedReader(Path.of("application-local.properties"))) { config.load(reader); }
        schema="attempt_test_"+UUID.randomUUID().toString().replace("-", "");
        var url=config.getProperty("spring.datasource.url");
        source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,
            config.getProperty("spring.datasource.username"),config.getProperty("spring.datasource.password"));
        db=new JdbcTemplate(source);
        db.execute("CREATE SCHEMA "+schema);
        db.execute("CREATE TABLE llm_runs(id uuid PRIMARY KEY,status text NOT NULL)");
        try(var c=source.getConnection()) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/manual/V20261004_01__llm_attempt_audit.sql"));
        }
        run=UUID.randomUUID();
        db.update("INSERT INTO llm_runs VALUES (?,'SUCCESS')",run);
    }

    @AfterEach void cleanup() {
        if(schema!=null && schema.matches("attempt_test_[a-f0-9]{32}")) db.execute("DROP SCHEMA "+schema+" CASCADE");
    }

    void migrate() throws Exception {
        try(var c=source.getConnection();var s=c.createStatement();
            var in=new ClassPathResource("db/manual/V20261008_01__merge_llm_attempts_into_runs.sql").getInputStream()) {
            try { s.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
            catch(SQLException e) { s.execute("ROLLBACK"); throw e; }
        }
    }

    void legacy(int number) {
        db.update("""
            INSERT INTO llm_run_attempts(id,llm_run_id,attempt_no,model_name,http_status,error_category,
                request_payload,response_body,response_text,input_tokens,output_tokens,latency_ms)
            VALUES (?,?,?,'legacy-model',429,'LIMIT','{"contents":["original"]}','original response',null,10,null,20)
            """,UUID.randomUUID(),run,number);
    }

    @Test void preservesAllFieldsAndSupportsRerun() throws Exception {
        legacy(1); legacy(2);
        var before=db.query("SELECT to_jsonb(a)::text FROM llm_run_attempts a ORDER BY attempt_no",
            (rs,n)->json.read(rs.getString(1)));
        migrate();
        assertThat(LlmAttemptAudit.read(db,json,run)).containsExactlyElementsOf(before);
        assertThat(db.queryForObject("SELECT to_regclass(?) IS NULL",Boolean.class,schema+".llm_run_attempts")).isTrue();
        migrate();
        assertThat(LlmAttemptAudit.read(db,json,run)).containsExactlyElementsOf(before);
    }

    @Test void refusesRunningWorkWithoutLosingHistory() {
        legacy(1);
        db.update("UPDATE llm_runs SET status='RUNNING'");
        assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class).hasMessageContaining("RUNNING");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_run_attempts",Integer.class)).isEqualTo(1);
    }

    @Test void conflictingHistoryRollsBackInsteadOfDroppingOldTable() {
        legacy(1);
        db.execute("ALTER TABLE llm_runs ADD COLUMN attempts jsonb NOT NULL DEFAULT '[]'");
        db.update("UPDATE llm_runs SET attempts='[{\"attempt_no\":1,\"model_name\":\"different\"}]'");
        assertThatThrownBy(this::migrate).isInstanceOf(SQLException.class).hasMessageContaining("Conflicting");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_run_attempts",Integer.class)).isEqualTo(1);
    }

    @Test void appendsConcurrentCallsWithoutLossAndRejectsDuplicateNumbers() throws Exception {
        migrate();
        var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<?>>();
            for(int i=1;i<=8;i++) {
                final int number=i;
                futures.add(pool.submit(()->LlmAttemptAudit.append(db,json,run,
                    json.read("{\"request\":"+number+"}"),
                    new LlmGateway.Attempt(number,"model-"+number,200,null,"{}","{}",10,20,30))));
            }
            for(var f:futures) f.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        var entries=LlmAttemptAudit.read(db,json,run);
        assertThat(entries).hasSize(8);
        for(int i=0;i<8;i++) {
            assertThat(entries.get(i).path("attempt_no").asInt()).isEqualTo(i+1);
            assertThat(entries.get(i).path("request_payload").path("request").asInt()).isEqualTo(i+1);
        }
        assertThatThrownBy(()->LlmAttemptAudit.append(db,json,run,json.read("{}"),
            new LlmGateway.Attempt(1,"duplicate",200,null,"{}","{}",1,2,3)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(LlmAttemptAudit.read(db,json,run)).hasSize(8);
        assertThatThrownBy(()->LlmAttemptAudit.append(db,json,UUID.randomUUID(),json.read("{}"),
            new LlmGateway.Attempt(1,"missing",200,null,"{}","{}",1,2,3)))
            .isInstanceOf(IllegalStateException.class);
    }
}

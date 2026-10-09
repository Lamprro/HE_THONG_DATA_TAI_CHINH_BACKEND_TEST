package com.hethongdata.taichinh.service.macro;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.model.*;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionJobService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Full application pipeline with mocked provider and a private PostgreSQL schema. No business-table
 * writes.
 */
@SpringBootTest(
        properties = {
            "financial.ingestion.scheduler.enabled=false",
            "financial.validation.scheduler.enabled=false",
            "financial.ingestion.catalog.seed-enabled=false",
            "financial.validation.catalog.seed-enabled=false",
            "financial.master-data.catalog.seed-enabled=false"
        })
@EnabledIfEnvironmentVariable(named = "MACRO_TEST_DB", matches = "1")
class MacroPipelineIntegrationTests {
    static final String SCHEMA = "macro_test_" + UUID.randomUUID().toString().replace("-", "");
    static final Properties CONFIG = new Properties();
    static String url;

    static Connection connection() throws Exception {
        return DriverManager.getConnection(
                url,
                CONFIG.getProperty("spring.datasource.username"),
                CONFIG.getProperty("spring.datasource.password"));
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var reader =
                Files.newBufferedReader(
                        Path.of(
                                System.getenv()
                                        .getOrDefault(
                                                "MACRO_TEST_CONFIG",
                                                "../application-local.properties")))) {
            CONFIG.load(reader);
        }
        url = CONFIG.getProperty("spring.datasource.url");
        try (var c = connection();
                var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute("SET search_path TO " + SCHEMA);
            List<String> tables = new ArrayList<>();
            try (var r =
                    s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public'")) {
                while (r.next()) tables.add(r.getString(1));
            }
            for (String table : tables)
                s.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
            List<String[]> defaults = new ArrayList<>();
            try (var r =
                    s.executeQuery(
                            "SELECT table_name,column_name FROM information_schema.columns WHERE"
                                + " table_schema='"
                                    + SCHEMA
                                    + "' AND column_default LIKE 'nextval%'")) {
                while (r.next()) defaults.add(new String[] {r.getString(1), r.getString(2)});
            }
            for (var d : defaults) {
                String seq = d[0] + "_" + d[1] + "_test_seq";
                s.execute("CREATE SEQUENCE " + seq);
                s.execute(
                        "ALTER TABLE "
                                + d[0]
                                + " ALTER COLUMN "
                                + d[1]
                                + " SET DEFAULT nextval('"
                                + seq
                                + "')");
            }
            try (var in =
                    new ClassPathResource("db/manual/V20261009_01__macro_pipeline.sql")
                            .getInputStream()) {
                s.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        registry.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add(
                "spring.datasource.username",
                () -> CONFIG.getProperty("spring.datasource.username"));
        registry.add(
                "spring.datasource.password",
                () -> CONFIG.getProperty("spring.datasource.password"));
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!SCHEMA.matches("macro_test_[a-f0-9]{32}"))
            throw new IllegalStateException("Unsafe schema");
        try (var c = connection();
                var s = c.createStatement()) {
            s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired MacroJobCatalogService catalog;
    @Autowired IngestionJobService jobs;
    @Autowired ObjectMapper json;
    @Autowired MacroWorkflowPersistenceService writes;
    @Autowired DataVersionJpaRepository versions;
    @Autowired IngestionRunRepository runs;
    @Autowired IngestionJobJpaRepository jobRepository;
    @MockitoBean ExternalFinancialDataPort provider;
    UUID jobId;

    @BeforeEach
    void setup() throws Exception {
        db.execute(
                "ALTER TABLE macro_observations DROP CONSTRAINT IF EXISTS macro_test_reject_value");
        for (String t :
                List.of(
                        "validation_results",
                        "macro_observations",
                        "macro_series",
                        "raw_payloads",
                        "data_versions",
                        "ingestion_runs",
                        "ingestion_jobs",
                        "validation_rules",
                        "data_sources")) db.update("DELETE FROM " + t);
        catalog.seed();
        jobId =
                db.queryForObject(
                        "SELECT id FROM ingestion_jobs WHERE code='MACRO_VN_BIS_QUARTERLY'",
                        UUID.class);
        when(provider.resolveUri(any()))
                .thenReturn(URI.create("https://python.example/api/v1/macro/bis/observations"));
        reply(MacroPayloadParserTests.payload().toString(), Instant.parse("2026-10-09T00:00:00Z"));
    }

    void reply(String body, Instant fetched) {
        when(provider.fetch(any()))
                .thenReturn(
                        new ExternalFetchResponse(
                                ExternalOperation.MACRO_OBSERVATIONS,
                                "bis",
                                URI.create("https://python.example/api/v1/macro/bis/observations"),
                                200,
                                "application/json",
                                Map.of(),
                                body,
                                fetched));
    }

    int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void fetchValidateBuildDuplicateAndCorrectionThroughRealPipeline() throws Exception {
        assertThat(jobs.runNow(jobId).getStatus()).isEqualTo("SUCCESS");
        assertThat(count("macro_observations")).isEqualTo(1);
        assertThat(count("validation_results")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM data_versions", String.class))
                .isEqualTo("ACTIVATED");
        assertThat(jobs.runNow(jobId).getStatus()).isEqualTo("NO_CHANGE");
        assertThat(count("macro_observations")).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM data_versions WHERE status='REJECTED'",
                                Integer.class))
                .isEqualTo(1);
        var corrected = MacroPayloadParserTests.payload();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        corrected.path("data").get(0).path("observations").get(0))
                .put("value", 26000);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        corrected
                                .path("data")
                                .get(0)
                                .path("observations")
                                .get(0)
                                .path("source_record"))
                .put("OBS_VALUE", "26000");
        reply(corrected.toString(), Instant.parse("2026-10-09T01:00:00Z"));
        assertThat(jobs.runNow(jobId).getStatus()).isEqualTo("SUCCESS");
        assertThat(
                        db.queryForObject(
                                "SELECT value FROM macro_observations", java.math.BigDecimal.class))
                .isEqualByComparingTo("26000");
        assertThat(count("raw_payloads")).isEqualTo(3);
        assertThat(count("macro_observations")).isEqualTo(1);
    }

    @Test
    void blocksMalformedBatchBeforeBusinessWrites() throws Exception {
        var bad = MacroPayloadParserTests.payload();
        bad.put("country_code", "USA");
        reply(bad.toString(), Instant.now());
        assertThat(jobs.runNow(jobId).getStatus()).isEqualTo("REJECTED");
        assertThat(count("macro_observations")).isZero();
        assertThat(count("data_versions")).isZero();
        assertThat(count("raw_payloads")).isEqualTo(1);
    }

    @Test
    void staleReplayCannotReplaceNewerCorrection() throws Exception {
        jobs.runNow(jobId);
        UUID versionId = db.queryForObject("SELECT id FROM data_versions", UUID.class);
        var p = MacroPayloadParserTests.payload();
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        p.path("data").get(0).path("observations").get(0))
                .put("value", 27000);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        p.path("data").get(0).path("observations").get(0).path("source_record"))
                .put("OBS_VALUE", "27000");
        reply(p.toString(), Instant.parse("2026-10-09T02:00:00Z"));
        jobs.runNow(jobId);
        // Re-queue only the isolated test version to prove that an old raw cannot replace a new
        // raw.
        db.update("UPDATE data_versions SET status='ACTIVE' WHERE id=?", versionId);
        var job = jobRepository.findById(jobId).orElseThrow();
        var run = runs.startInternalBatch(job.getDataSource(), job, "MANUAL", "MACRO_BUILD");
        assertThat(writes.build(versionId, run).get("stale")).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT value FROM macro_observations", java.math.BigDecimal.class))
                .isEqualByComparingTo("27000");
    }

    @Test
    void concurrentRunsInsertOnlyOneObservation() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> jobs.runNow(jobId));
            var second = pool.submit(() -> jobs.runNow(jobId));
            assertThat(List.of(first.get().getStatus(), second.get().getStatus()))
                    .containsExactlyInAnyOrder("SUCCESS", "NO_CHANGE");
        }
        assertThat(count("macro_observations")).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM data_versions WHERE status='ACTIVATED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM data_versions WHERE status='REJECTED'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedSecondWriteRollsBackEntireBatchAndRejectsVersion() throws Exception {
        jobs.runNow(jobId);
        db.execute(
                "ALTER TABLE macro_observations ADD CONSTRAINT macro_test_reject_value CHECK"
                    + " (value<>99999)");
        var p = MacroPayloadParserTests.payload();
        var values =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        p.path("data").get(0).path("observations");
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) values.get(0);
        first.put("value", 26000);
        ((com.fasterxml.jackson.databind.node.ObjectNode) first.path("source_record"))
                .put("OBS_VALUE", "26000");
        var second = first.deepCopy();
        second.put("period", "2026-Q3").put("observation_date", "2026-09-30").put("value", 99999);
        ((com.fasterxml.jackson.databind.node.ObjectNode) second.path("source_record"))
                .put("TIME_PERIOD", "2026-Q3")
                .put("OBS_VALUE", "99999");
        values.add(second);
        p.put("observation_count", 2);
        reply(p.toString(), Instant.parse("2026-10-09T03:00:00Z"));
        assertThatThrownBy(() -> jobs.runNow(jobId)).isInstanceOf(RuntimeException.class);
        assertThat(count("macro_observations")).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT value FROM macro_observations", java.math.BigDecimal.class))
                .isEqualByComparingTo("25000.123456");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM data_versions WHERE status='REJECTED'",
                                Integer.class))
                .isEqualTo(1);
    }
}

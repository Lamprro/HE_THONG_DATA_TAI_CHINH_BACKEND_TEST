package com.hethongdata.taichinh.service.financial;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.ingestion.*;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL constraints and rollback, synthetic fixtures only in a disposable schema. */
@EnabledIfEnvironmentVariable(named="DATA_ENRICHMENT_TEST_DB",matches="1")
class DataEnrichmentIsolatedIntegrationTests {
    static final String SCHEMA="data_enrichment_test_"+UUID.randomUUID().toString().replace("-","");
    static final UUID COMPANY=UUID.randomUUID(),SECURITY=UUID.randomUUID(),PERIOD=UUID.randomUUID();
    static JdbcTemplate admin,db;static TransactionTemplate tx;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final DataVersionJpaRepository versions=mock(DataVersionJpaRepository.class);
    final RawPayloadJpaRepository raws=mock(RawPayloadJpaRepository.class);
    final IngestionRunJpaRepository runs=mock(IngestionRunJpaRepository.class);
    static DriverManagerDataSource source(String url,Properties p) {
        return new DriverManagerDataSource(url,p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"));
    }
    @BeforeAll static void schema() throws Exception {
        var p=new Properties();try(var reader=Files.newBufferedReader(Path.of(System.getProperty("financial.test.config","application-local.properties")))) {p.load(reader);}
        String url=p.getProperty("spring.datasource.url");admin=new JdbcTemplate(source(url,p));
        admin.execute("CREATE SCHEMA "+SCHEMA);
        for(String t:List.of("companies","securities","metric_definitions","financial_metrics","financial_periods","financial_statements","financial_statement_items","data_sources"))
            admin.execute("CREATE TABLE "+SCHEMA+"."+t+" (LIKE public."+t+" INCLUDING ALL)");
        admin.execute("CREATE TABLE "+SCHEMA+".raw_payloads(id uuid primary key,fetched_at timestamptz not null,ingestion_run_id uuid)");
        admin.execute("CREATE TABLE "+SCHEMA+".data_versions(id uuid primary key,ingestion_run_id uuid,data_domain text,status text)");
        // Fixture IDs are explicit; no copied public sequence default is invoked.
        admin.execute("INSERT INTO "+SCHEMA+".metric_definitions SELECT * FROM public.metric_definitions");
        String scoped=url.contains("currentSchema=")?url.replaceAll("currentSchema=[^&]+","currentSchema="+SCHEMA)
                :url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        var ds=source(scoped,p);db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThat(db.queryForObject("SELECT current_schema()",String.class)).isEqualTo(SCHEMA);
        db.update("INSERT INTO companies(id,company_code,legal_name) VALUES (?,?,?)",COMPANY,"FIXTURE","Isolated fixture company");
        db.update("INSERT INTO securities(id,company_id,symbol,exchange,security_type,currency) VALUES (?,?,?,'HOSE','STOCK','VND')",SECURITY,COMPANY,"FPT");
        db.update("INSERT INTO financial_periods(id,fiscal_year,period_type,start_date,end_date) VALUES (?,2016,'Q2','2016-04-01','2016-06-30')",PERIOD);
        db.update("INSERT INTO data_sources(id,code,name,source_type,provider,priority,is_official,license_status,is_active) VALUES (11,'VNDIRECT','Fixture source','API','vndirect',100,false,'UNKNOWN',true)");
    }
    @AfterAll static void cleanup() {
        if(admin!=null && SCHEMA.matches("data_enrichment_test_[a-f0-9]{32}"))admin.execute("DROP SCHEMA "+SCHEMA+" CASCADE");
    }
    @BeforeEach void empty() {db.execute("TRUNCATE financial_metrics,financial_statement_items,financial_statements,raw_payloads,data_versions");}
    FinancialMetricBuildPersistenceService writer() {
        return new FinancialMetricBuildPersistenceService(db,versions,raws,runs,new ProviderRatioParser(),json,new ChecksumService());
    }
    DataVersionEntity batch(List<RawPayloadEntity> payloads) {
        var version=DataVersionEntity.acceptedForRun("FINANCIAL_METRIC",UUID.randomUUID(),payloads.size(),"fixture-hash");
        ReflectionTestUtils.setField(version,"id",UUID.randomUUID());
        when(versions.findByIdForUpdate(version.getId())).thenReturn(Optional.of(version));
        when(raws.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId())).thenReturn(payloads);return version;
    }
    RawPayloadEntity raw(String date,String rowSymbol,String value) throws Exception {
        var raw=mock(RawPayloadEntity.class);var source=mock(DataSourceEntity.class);UUID id=UUID.randomUUID();
        when(source.getId()).thenReturn(11L);when(raw.getDataSource()).thenReturn(source);
        when(raw.getId()).thenReturn(id);when(raw.getSourceSymbol()).thenReturn("FPT");when(raw.getSecurityId()).thenReturn(SECURITY);
        when(raw.getEntityType()).thenReturn("RATIO");when(raw.getChecksumSha256()).thenReturn("fixture-hash");
        when(raw.getFetchedAt()).thenReturn(Instant.parse(date));
        when(raw.getPayload()).thenReturn(json.readTree("""
{"provider":"vndirect","dataset":"ratios","symbol":"FPT","count":1,"data":[
{"code":"%s","ratioCode":"ROAE_TR_AVG5Q","reportDate":"2016-06-30","value":%s}]}
""".formatted(rowSymbol,value)));
        db.update("INSERT INTO raw_payloads(id,fetched_at) VALUES (?,?)",id,java.sql.Timestamp.from(Instant.parse(date)));return raw;
    }
    @Test void providerRatiosAreIdempotentAndStaleReplayCannotDisplaceNewerCanonical() throws Exception {
        var newest=raw("2026-10-08T00:00:00Z","FPT","0.24");var v=batch(List.of(newest));
        int inserted=tx.execute(s->writer().build(v.getId(),mock(IngestionRunEntity.class)));
        assertThat(inserted).isEqualTo(1);
        assertThat(v.getStatus()).isEqualTo("ACTIVATED");
        var repeat=batch(List.of(newest));int repeated=tx.execute(s->writer().build(repeat.getId(),mock(IngestionRunEntity.class)));
        assertThat(repeated).isZero();
        var stale=raw("2026-10-07T00:00:00Z","FPT","0.31");var old=batch(List.of(stale));
        tx.execute(s->writer().build(old.getId(),mock(IngestionRunEntity.class)));
        assertThat(db.queryForObject("SELECT value FROM financial_metrics WHERE is_canonical",java.math.BigDecimal.class)).isEqualByComparingTo("24");
        assertThat(db.queryForObject("SELECT count(*) FROM financial_metrics",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT input_snapshot->>'transformation' FROM financial_metrics WHERE is_canonical",String.class)).isEqualTo("fraction_to_percent:*100");
    }
    @Test void oneInvalidPayloadRollsBackTheWholeAcceptedBatch() throws Exception {
        var good=raw("2026-10-08T00:00:00Z","FPT","0.24");var bad=raw("2026-10-08T00:00:00Z","VCB","0.31");
        var v=batch(List.of(good,bad));
        assertThatThrownBy(()->tx.execute(s->writer().build(v.getId(),mock(IngestionRunEntity.class)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM financial_metrics",Integer.class)).isZero();
        assertThat(v.getStatus()).isEqualTo("ACTIVE");
    }
    void statement(String type,Map<String,String> values) {
        UUID id=UUID.randomUUID(),raw=UUID.randomUUID(),run=UUID.randomUUID(),version=UUID.randomUUID();
        db.update("INSERT INTO raw_payloads VALUES (?,now(),?)",raw,run);
        db.update("INSERT INTO data_versions VALUES (?,?,'FINANCIAL_STATEMENT','ACTIVATED')",version,run);
        db.update("INSERT INTO financial_statements(id,company_id,security_id,financial_period_id,statement_type,report_scope,currency,unit_scale,data_source_id,raw_payload_id,data_version_id,is_current,is_canonical) VALUES (?,?,?,?,?,'UNKNOWN','VND',1,11,?,?,true,true)",id,COMPANY,SECURITY,PERIOD,type,raw,version);
        for(var e:values.entrySet())db.update("INSERT INTO financial_statement_items(id,financial_statement_id,item_code,item_name,value,unit) VALUES (?,?,?,?,?,'VND')",UUID.randomUUID(),id,e.getKey(),e.getKey(),new java.math.BigDecimal(e.getValue()));
    }
    @Test void historicalRatiosKeepInputsAndAreIdempotentAcrossEightFormulas() {
        statement("BALANCE_SHEET",Map.of("TOTAL_ASSETS","100","LIABILITIES","60","OWNERS_EQUITY","40","CURRENT_ASSETS","20","SHORT_TERM_LIABILITIES","10","CASH_AND_CASH_EQUIVALENTS","2"));
        statement("INCOME_STATEMENT",Map.of("NET_SALES","200","NET_PROFIT_AFTER_TAX","10","GROSS_PROFIT","30","NET_PROFIT_FROM_OPERATING_ACTIVITIES","25"));
        var service=new HistoricalFinancialMetricService(db,new FinancialRatioCalculator(),json,new ChecksumService());
        var req=new HistoricalFinancialMetricService.Request("FPT",LocalDate.of(2016,1,1),LocalDate.of(2016,12,31));
        assertThat(tx.execute(s->service.calculate(req)).inserted()).isEqualTo(8);
        assertThat(tx.execute(s->service.calculate(req)).unchanged()).isEqualTo(8);
        assertThat(db.queryForObject("SELECT count(*) FROM financial_metrics WHERE is_canonical AND quality_status='VALID' AND input_snapshot->'inputs' IS NOT NULL",Integer.class)).isEqualTo(8);
        assertThat(db.queryForObject("SELECT m.value FROM financial_metrics m JOIN metric_definitions d ON d.id=m.metric_definition_id WHERE d.code='NET_MARGIN'",java.math.BigDecimal.class)).isEqualByComparingTo("5");
    }
    @Test void refusesToDeriveFromUnactivatedSourceVersions() {
        statement("BALANCE_SHEET",Map.of("TOTAL_ASSETS","100","LIABILITIES","60","OWNERS_EQUITY","40"));
        db.update("UPDATE data_versions SET status='REJECTED'");
        var service=new HistoricalFinancialMetricService(db,new FinancialRatioCalculator(),json,new ChecksumService());
        var req=new HistoricalFinancialMetricService.Request("FPT",LocalDate.of(2016,1,1),LocalDate.of(2016,12,31));
        assertThat(tx.execute(s->service.calculate(req)).inserted()).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM financial_metrics",Integer.class)).isZero();
    }

    @Test void legacyCalculateJobUsesAuditedCanonicalWriterAndReplayKeepsHistory() {
        statement("BALANCE_SHEET",Map.of("TOTAL_ASSETS","100","LIABILITIES","60","OWNERS_EQUITY","40"));
        var actual=new HistoricalFinancialMetricService(db,new FinancialRatioCalculator(),json,new ChecksumService());
        var audit=mock(com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository.class);
        var run=mock(IngestionRunEntity.class);var job=mock(IngestionJobEntity.class);
        UUID id=UUID.randomUUID();when(run.getId()).thenReturn(id);
        when(job.getParameters()).thenReturn(json.createObjectNode().put("symbol","FPT")
                .put("startDate","2016-01-01").put("endDate","2016-12-31"));
        when(audit.startInternalBatch(any(),eq(job),eq("MANUAL"),eq("FINANCIAL_METRIC_CALCULATE"))).thenReturn(run);
        var workflow=new FinancialMetricCalculateService(db,actual,audit,runs,json);
        assertThat(tx.execute(s->workflow.execute(job,"MANUAL")).getStatus()).isEqualTo("SUCCESS");
        assertThat(tx.execute(s->workflow.execute(job,"MANUAL")).getRunId()).isEqualTo(id);
        assertThat(db.queryForObject("SELECT count(*) FROM financial_metrics WHERE is_canonical "
                + "AND input_snapshot->'inputs' IS NOT NULL AND raw_payload_id IS NOT NULL "
                + "AND data_version_id IS NOT NULL",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM financial_metrics",Integer.class)).isEqualTo(3);
        verify(runs,times(2)).save(run);
    }
}

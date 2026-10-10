package com.hethongdata.taichinh.service.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.model.*;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.dto.master.*;
import com.hethongdata.taichinh.repository.ingestion.*;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import com.hethongdata.taichinh.service.master.MasterDataService;
import com.hethongdata.taichinh.service.validation.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real ingestion/validation/JPA with provider mock confined to a separate schema. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "financial.llm.enabled=false","financial.llm.catalog.seed-enabled=false","financial.llm.scheduler.enabled=false",
    "financial.news-recovery.scheduler.enabled=false","financial.ingestion.scheduler.enabled=false",
    "financial.validation.scheduler.enabled=false","financial.validation.catalog.seed-enabled=false",
    "financial.ingestion.catalog.seed-enabled=false","financial.master-data.catalog.seed-enabled=false",
    "financial.security-job-reconciliation.scheduler.enabled=false","spring.jpa.properties.hibernate.jdbc.batch_size=100"})
@EnabledIfEnvironmentVariable(named="DATA_ENRICHMENT_TEST_DB",matches="1")
class MarketBackfillIsolatedJpaIntegrationTests {
    static final String SCHEMA="market_backfill_test_"+UUID.randomUUID().toString().replace("-","");
    static final Properties CONFIG=new Properties();static String url;
    static Connection connect() throws Exception {return DriverManager.getConnection(url,CONFIG.getProperty("spring.datasource.username"),CONFIG.getProperty("spring.datasource.password"));}
    @DynamicPropertySource static void schema(DynamicPropertyRegistry properties) throws Exception {
        try(var reader=Files.newBufferedReader(Path.of(System.getProperty("financial.test.config","application-local.properties")))) {CONFIG.load(reader);}
        url=CONFIG.getProperty("spring.datasource.url");
        try(var c=connect();var s=c.createStatement()) {
            s.execute("CREATE SCHEMA "+SCHEMA);
            var tables=new ArrayList<String>();
            try(var r=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")) {while(r.next())tables.add(r.getString(1));}
            for(var t:tables)s.execute("CREATE TABLE "+SCHEMA+"."+t+" (LIKE public."+t+" INCLUDING ALL)");
            var defaults=new ArrayList<String[]>();
            try(var r=s.executeQuery("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='"+SCHEMA+"' AND column_default LIKE 'nextval%'")) {while(r.next())defaults.add(new String[]{r.getString(1),r.getString(2)});}
            for(var d:defaults) {
                String seq=SCHEMA+".fixture_"+d[0]+"_"+d[1];
                s.execute("CREATE SEQUENCE "+seq+" START 1000000");
                s.execute("ALTER TABLE "+SCHEMA+"."+d[0]+" ALTER COLUMN "+d[1]+" SET DEFAULT nextval('"+seq+"')");
            }
        }
        String scoped=url.contains("currentSchema=")?url.replaceAll("currentSchema=[^&]+","currentSchema="+SCHEMA):url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        properties.add("spring.datasource.url",()->scoped);properties.add("spring.datasource.username",()->CONFIG.getProperty("spring.datasource.username"));
        properties.add("spring.datasource.password",()->CONFIG.getProperty("spring.datasource.password"));
    }
    @AfterAll static void cleanup() throws Exception {
        if(url!=null && SCHEMA.matches("market_backfill_test_[a-f0-9]{32}"))try(var c=connect();var s=c.createStatement()) {s.execute("DROP SCHEMA "+SCHEMA+" CASCADE");}
    }
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MasterDataService master;
    @Autowired DataSourceRepository sources;
    @Autowired IngestionJobRepository jobs;
    @Autowired IngestionService ingestion;
    @Autowired ValidationJobService validation;
    @Autowired ValidationRuleCatalogService ruleCatalog;
    @Autowired MarketPriceWorkflowService workflow;
    @MockitoBean ExternalFinancialDataPort provider;

    @Test void actualPipelineEnforcesCanonicalUniqueAndStaleReplayForLargeBatches() throws Exception {
        assertThat(db.queryForObject("SELECT current_schema()",String.class)).isEqualTo(SCHEMA);
        var company=new CompanyRequest();company.setCompanyCode("FPT");company.setLegalName("Isolated synthetic fixture");company.setListingStatus("LISTED");
        UUID companyId=master.createCompany(company).getId();
        var security=new SecurityRequest();security.setCompanyId(companyId);security.setSymbol("FPT");
        security.setExchange("HOSE");security.setSecurityType("STOCK");security.setCurrency("VND");security.setActive(true);
        UUID securityId=master.createSecurity(security).getId();
        db.update("UPDATE data_sources SET priority=20 WHERE code='VNSTOCK'");
        sources.upsert("PYTHON_GATEWAY","Fixture gateway","API",null,"gateway",false,"UNKNOWN",true);
        var job=jobs.create("PYTHON_GATEWAY","MARKET_PRICE_BUILD","Fixture workflow","MARKET_PRICE",null,
                json.createObjectNode().put("workflow","MARKET_PRICE_BUILD"),(short)5,120,true);
        ruleCatalog.seed();
        for(int batch=0;batch<4;batch++) {
            String source=batch<2?"vndirect":"vnstock";int close=batch==3?90:batch==2?110:100;
            Instant fetched=Instant.parse(batch==3?"2026-10-07T00:00:00Z":"2026-10-08T00:00:00Z");
            var body=json.createObjectNode().put("provider",source).put("symbol","FPT").put("dataset","equity_ohlcv")
                    .put("schema_version","market_price.v1").put("retrieved_at",fetched.toString()).put("count",100);
            var data=body.putArray("data");
            for(int i=0;i<100;i++)data.addObject().put("symbol","FPT").put("date",LocalDate.of(2016,1,1).plusDays(i).toString())
                    .put("open_price",close).put("high_price",close).put("low_price",close).put("close_price",close);
            URI uri=URI.create("https://isolated.invalid/provider/"+source);
            when(provider.resolveUri(any())).thenReturn(uri);
            when(provider.fetch(any())).thenReturn(new ExternalFetchResponse(ExternalOperation.OHLCV,source,uri,200,"application/json",Map.of(),body.toString(),fetched));
            var request=new ManualIngestionRequest();request.setOperation(ExternalOperation.OHLCV);request.setProvider(source);
            request.setSymbol("FPT");request.setDataSourceCode(source.toUpperCase(Locale.ROOT));
            var raw=ingestion.ingest(request);var accepted=validation.validate(raw.getRawPayloadId());
            assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
            assertThat(workflow.execute(job,"MANUAL").getStatus()).isEqualTo("SUCCESS");
        }
        assertThat(db.queryForObject("SELECT count(*) FROM market_prices WHERE security_id=?",Integer.class,securityId)).isEqualTo(200);
        assertThat(db.queryForObject("SELECT count(*) FROM market_prices WHERE security_id=? AND is_canonical AND close_price=110",Integer.class,securityId)).isEqualTo(100);
        assertThat(db.queryForObject("SELECT count(*) FROM data_versions WHERE status='ACTIVATED'",Integer.class)).isEqualTo(4);
    }
}

package com.hethongdata.taichinh.service.user;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Reads run against real PostgreSQL; all fixture rows and defaults are confined to this schema. */
@EnabledIfEnvironmentVariable(named="DATA_ENRICHMENT_TEST_DB",matches="1")
class UserApiIsolatedIntegrationTests {
  static final String SCHEMA="user_api_test_"+UUID.randomUUID().toString().replace("-","");
  static JdbcTemplate admin,db;static UserReadSupport read;static UserSecurityService securities;
  static final UUID COMPANY=UUID.randomUUID(),SECURITY=UUID.randomUUID(),VERSION=UUID.randomUUID(),REJECTED=UUID.randomUUID();
  static final UUID INDEX=UUID.randomUUID(),MACRO=UUID.randomUUID();
  static final UUID PERIOD=UUID.randomUUID(),REPORT=UUID.randomUUID(),OLD_REPORT=UUID.randomUUID();
  static final UUID ARTICLE=UUID.randomUUID(),HIDDEN_ARTICLE=UUID.randomUUID(),RAW=UUID.randomUUID(),NEWS_RUN=UUID.randomUUID();
  static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
  @BeforeAll static void setup() throws Exception {
    var props=new Properties();try(var r=Files.newBufferedReader(Path.of(System.getProperty("financial.test.config","application-local.properties")))) {props.load(r);}
    String url=props.getProperty("spring.datasource.url"),user=props.getProperty("spring.datasource.username"),password=props.getProperty("spring.datasource.password");
    admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("create schema "+SCHEMA);
    String scoped=url.contains("currentSchema=")?url.replaceAll("currentSchema=[^&]+","currentSchema="+SCHEMA):url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
    db=new JdbcTemplate(new DriverManagerDataSource(scoped,user,password));assertThat(db.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
    for(String table:List.of("companies","securities","data_sources","data_versions","raw_payloads","market_prices","market_indices","index_prices","security_index_memberships","financial_periods","financial_statements","financial_statement_items","financial_metrics","metric_definitions","macro_series","macro_observations","news_articles","news_article_companies")) {
      db.execute("create table "+table+" (like public."+table+" including defaults including constraints)");
      // Never advance a sequence belonging to public, even if a future fixture omits an explicit ID.
      var defaults=db.queryForList("select column_name from information_schema.columns where table_schema=? and table_name=? and column_default like 'nextval%'",String.class,SCHEMA,table);
      for(String column:defaults) {String seq="fixture_"+table+"_"+column;db.execute("create sequence "+seq);db.execute("alter table "+table+" alter column "+column+" set default nextval('"+SCHEMA+"."+seq+"')");}
    }
    db.update("insert into companies(id,legal_name,short_name,is_active) values (?,'Fixture Company','Fixture',true)",COMPANY);
    db.update("insert into securities(id,company_id,symbol,exchange,currency,is_active,shares_outstanding) values (?,?,'ZZTEST','HOSE','VND',true,9007199254740993)",SECURITY,COMPANY);
    db.update("insert into data_sources(id,code,name,source_type) values (1,'FIXTURE','Fixture source','API')");
    db.update("insert into data_versions(id,data_domain,version_code,status) values (?,'MARKET_PRICE','FIXTURE','ACTIVATED'),(?,'MARKET_PRICE','REJECTED','REJECTED')",VERSION,REJECTED);
    db.update("insert into market_prices(id,security_id,data_source_id,data_version_id,price_timestamp,interval_code,close_price,is_canonical,price_basis) values (1,?,1,?,'2026-10-01T08:00:00Z','1d',100,true,'UNADJUSTED'),(2,?,1,?,'2026-10-02T08:00:00Z','1d',110,true,'UNADJUSTED'),(3,?,1,?,'2026-10-02T09:00:00Z','snapshot',999,true,'UNKNOWN'),(4,?,1,?,'2026-10-02T10:00:00Z','1d',777,true,'UNADJUSTED')",SECURITY,VERSION,SECURITY,VERSION,SECURITY,VERSION,SECURITY,REJECTED);
    db.update("insert into market_indices(id,code,name,is_active) values (?,'ZZINDEX','Fixture index',true)",INDEX);
    db.update("insert into index_prices(id,market_index_id,data_source_id,data_version_id,price_timestamp,interval_code,close_value,is_canonical) values (1,?,1,?,'2026-10-01T08:00:00Z','1d',1234,true)",INDEX,VERSION);
    db.update("insert into security_index_memberships(id,market_index_id,security_id,effective_from,effective_to) values (?,?,?,'2026-10-01','2026-10-02')",UUID.randomUUID(),INDEX,SECURITY);
    db.update("insert into macro_series(id,data_source_id,code,name,is_active,unit) values (?,1,'ZZMACRO','Fixture series',true,'%')",MACRO);
    db.update("insert into macro_observations(id,macro_series_id,observation_date,value,data_version_id) values (1,?,'2025-01-01',1.23456789,?),(2,?,'2026-01-01',999,?)",MACRO,VERSION,MACRO,REJECTED);
    db.update("insert into financial_periods(id,fiscal_year,period_type,start_date,end_date) values (?,2025,'FY','2025-01-01','2025-12-31')",PERIOD);
    db.update("insert into financial_statements(id,company_id,financial_period_id,statement_type,data_source_id,data_version_id,is_current,is_canonical,report_scope,period_basis) values (?,?,?,'BALANCE_SHEET',1,?,true,true,'UNKNOWN','POINT_IN_TIME'),(?,?,?,'BALANCE_SHEET',1,?,false,false,'UNKNOWN','POINT_IN_TIME')",REPORT,COMPANY,PERIOD,VERSION,OLD_REPORT,COMPANY,PERIOD,VERSION);
    db.update("insert into financial_statement_items(id,financial_statement_id,item_code,item_name,value,unit) values (?,?,'TOTAL_ASSETS','Fixture assets',9007199254740993,'VND')",UUID.randomUUID(),REPORT);
    db.update("insert into metric_definitions(id,code,name,category,unit) values (1,'ZZMETRIC','Fixture metric','PROFITABILITY','%')");
    db.update("insert into financial_metrics(id,company_id,security_id,metric_definition_id,as_of_date,value,data_version_id,is_derived,is_canonical,quality_status) values (?,?,?,1,'2026-01-01',1.25,?,false,true,'VALID'),(?,?,?,1,'2026-02-01',2.5,null,true,true,'VALID'),(?,?,?,1,'2026-03-01',999,?,false,true,'REJECTED')",UUID.randomUUID(),COMPANY,SECURITY,VERSION,UUID.randomUUID(),COMPANY,SECURITY,UUID.randomUUID(),COMPANY,SECURITY,REJECTED);
    db.update("update data_versions set ingestion_run_id=? where id=?",NEWS_RUN,VERSION);
    db.update("insert into raw_payloads(id,ingestion_run_id,data_source_id,entity_type,checksum_sha256,fetched_at,created_at) values (?,?,1,'NEWS_DATA',repeat('a',64),now(),now())",RAW,NEWS_RUN);
    db.update("insert into news_articles(id,data_source_id,raw_payload_id,title,canonical_url,dedup_status,is_deleted_source,published_at,crawled_at) values (?,1,?,'Fixture link-only article','https://example.org/article','UNIQUE',false,null,'2026-10-01T00:00:00Z'),(?,1,null,'Hidden unpublished article','https://example.org/hidden','UNIQUE',false,'2026-10-01',now())",ARTICLE,RAW,HIDDEN_ARTICLE);
    db.update("insert into news_article_companies(id,news_article_id,company_id,security_id,match_method,match_evidence) values (?,?,?,?,'RULE','{\"kind\":\"source_job\"}'::jsonb)",UUID.randomUUID(),ARTICLE,COMPANY,SECURITY);
    read=new UserReadSupport(db,JSON);securities=new UserSecurityService(read);
  }
  @AfterAll static void cleanup() {if(admin!=null&&SCHEMA.matches("user_api_test_[a-f0-9]{32}"))admin.execute("drop schema "+SCHEMA+" cascade");}
  @Test void masterSearchAndDecimalEncodingArePublicProjections() throws Exception {
    var page=securities.securities("zztest",null,true,0,1);assertThat(page.total()).isEqualTo(1);assertThat(page.hasNext()).isFalse();
    assertThat(page.items().getFirst().get("sharesOutstanding")).isInstanceOf(String.class);
    assertThat(new java.math.BigDecimal(page.items().getFirst().get("sharesOutstanding").toString())).isEqualByComparingTo("9007199254740993");
    String payload=JSON.writeValueAsString(securities.detail("zztest","hose"));assertThat(payload).contains("companyName","legalName").doesNotContain("base_url","request_payload","tax_code");
    assertThat(securities.securities("%",null,true,0,25).items()).isEmpty();
    assertThatThrownBy(()->securities.securities(null,null,true,0,101)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void ambiguousSymbolNeedsExchangeAndUnknownCompanyIs404() {
    UUID other=UUID.randomUUID();db.update("insert into securities(id,company_id,symbol,exchange,is_active) values (?,?,'ZZTEST','HNX',true)",other,COMPANY);
    try {assertThatThrownBy(()->securities.resolve("ZZTEST",null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");assertThat(securities.resolve("ZZTEST","HNX").get("id")).isEqualTo(other.toString());}
    finally {db.update("delete from securities where id=?",other);}
    assertThatThrownBy(()->securities.company(UUID.randomUUID())).hasMessageContaining("404");
  }
  @Test void dailyChartExcludesSnapshotsAndRejectedVersionsAndKeepsPagingOrder() {
    var service=new UserMarketService(read,securities);
    var result=service.prices("ZZTEST","HOSE","1d",java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-02"),0,1);
    assertThat(result.total()).isEqualTo(2);assertThat(result.hasNext()).isTrue();assertThat(result.items().getFirst().get("tradingDate")).isEqualTo("2026-10-01");
    var next=service.prices("ZZTEST","HOSE","1d",java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-02"),1,1);
    assertThat(next.items().getFirst().get("tradingDate")).isEqualTo("2026-10-02");assertThat(next.hasNext()).isFalse();
    var quote=service.latest("ZZTEST","HOSE").data();assertThat(quote.get("daily").toString()).contains("110");assertThat(quote.get("snapshot").toString()).contains("999");
  }
  @Test void marketBreadthUsesOnlyDailyComparableTrackedPrices() {
    var result=new UserMarketService(read,securities).overview("HOSE",java.time.LocalDate.parse("2026-10-02")).data();
    assertThat(result.get("latestStoredTradingDate")).isEqualTo("2026-10-02");
    assertThat(((Map<?,?>)result.get("breadth")).get("rising")).isEqualTo(1);
    assertThat(result.get("securities").toString()).contains("10.00000000").doesNotContain("999","777");
    assertThat(result.get("indices").toString()).contains("ZZINDEX","1234");assertThat(result.get("indicesTruncated")).isEqualTo(false);
  }
  @Test void indexMembershipEndIsExclusiveAndMacroRejectsUnpublishedVersions() {
    var service=new UserMarketService(read,securities);
    assertThat(service.indices(0,25).items()).hasSize(1);
    assertThat(service.indexPrices("ZZINDEX",java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-02"),0,25).items()).hasSize(1);
    assertThat(service.members("ZZINDEX",java.time.LocalDate.parse("2026-10-01"),0,25).items()).hasSize(1);
    assertThat(service.members("ZZINDEX",java.time.LocalDate.parse("2026-10-02"),0,25).items()).isEmpty();
    assertThat(service.macroSeries(0,25).items()).hasSize(1);
    var observations=service.macro(MACRO,java.time.LocalDate.parse("2025-01-01"),java.time.LocalDate.parse("2026-01-01"),0,25);
    assertThat(observations.items()).hasSize(1);assertThat(new java.math.BigDecimal(observations.items().getFirst().get("value").toString())).isEqualByComparingTo("1.23456789");
    assertThatThrownBy(()->service.macro(UUID.randomUUID(),null,null,0,25)).hasMessageContaining("404");
  }
  @Test void financialReportsExposeCurrentCanonicalVersionAndStoredUnitsOnly() {
    var service=new UserFinancialService(read,securities);
    var reports=service.statements("ZZTEST","HOSE","BALANCE_SHEET","FY",null,null,0,25);
    assertThat(reports.items()).hasSize(1);assertThat(reports.items().getFirst().get("id")).isEqualTo(REPORT.toString());
    assertThat(reports.items().getFirst().get("publishedAt")).isNull();
    var detail=service.statement("ZZTEST","HOSE",REPORT).data();assertThat(detail.get("reportScope")).isEqualTo("UNKNOWN");
    assertThat(detail.get("items").toString()).contains("9007199254740993","unit=VND");
    assertThatThrownBy(()->service.statement("ZZTEST","HOSE",OLD_REPORT)).hasMessageContaining("404");
  }
  @Test void canonicalMetricsRetainOriginAndNeverPublishRejectedValues() {
    var service=new UserFinancialService(read,securities);
    assertThat(service.definitions(0,25).items()).hasSize(1);
    var all=service.metrics("ZZTEST","HOSE","ZZMETRIC",null,null,null,null,0,25);
    assertThat(all.items()).hasSize(2);assertThat(all.items().toString()).doesNotContain("999");
    assertThat(service.metrics("ZZTEST","HOSE",null,"DERIVED",null,null,null,0,25).items()).hasSize(1);
    assertThat(service.metrics("ZZTEST","HOSE",null,"PROVIDER",null,null,null,0,25).items()).hasSize(1);
    assertThatThrownBy(()->service.statements("ZZTEST","HOSE","INVENTED",null,null,null,0,25)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.metrics("ZZTEST","HOSE",null,"BAD",null,null,null,0,25)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void newsKeepsUrlOnlySourcesAndRejectsUnpublishedOrWrongDateArticles() {
    var service=new UserNewsService(read,securities);
    var all=service.news("ZZTEST","HOSE",null,null,null,0,25);assertThat(all.items()).hasSize(1);
    assertThat(all.items().getFirst().get("hasContent")).isEqualTo(false);assertThat(all.items().getFirst().get("url")).isEqualTo("https://example.org/article");
    assertThat(service.news(null,null,null,java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-10-01"),0,25).items()).isEmpty();
    var detail=service.detail(ARTICLE).data();assertThat(detail.get("contentText")).isNull();assertThat(detail.get("publishedAt")).isNull();assertThat(detail.get("companies").toString()).contains("SOURCE_JOB");
    assertThatThrownBy(()->service.detail(HIDDEN_ARTICLE)).hasMessageContaining("404");
  }
}

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
  static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
  @BeforeAll static void setup() throws Exception {
    var props=new Properties();try(var r=Files.newBufferedReader(Path.of(System.getProperty("financial.test.config","application-local.properties")))) {props.load(r);}
    String url=props.getProperty("spring.datasource.url"),user=props.getProperty("spring.datasource.username"),password=props.getProperty("spring.datasource.password");
    admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));admin.execute("create schema "+SCHEMA);
    String scoped=url.contains("currentSchema=")?url.replaceAll("currentSchema=[^&]+","currentSchema="+SCHEMA):url+(url.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
    db=new JdbcTemplate(new DriverManagerDataSource(scoped,user,password));assertThat(db.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
    for(String table:List.of("companies","securities","data_sources","data_versions","market_prices","market_indices","index_prices","security_index_memberships","financial_periods","financial_statements","financial_statement_items","financial_metrics","metric_definitions","macro_series","macro_observations","news_articles","news_article_companies")) {
      db.execute("create table "+table+" (like public."+table+" including defaults including constraints)");
      // Never advance a sequence belonging to public, even if a future fixture omits an explicit ID.
      var defaults=db.queryForList("select column_name from information_schema.columns where table_schema=? and table_name=? and column_default like 'nextval%'",String.class,SCHEMA,table);
      for(String column:defaults) {String seq="fixture_"+table+"_"+column;db.execute("create sequence "+seq);db.execute("alter table "+table+" alter column "+column+" set default nextval('"+SCHEMA+"."+seq+"')");}
    }
    db.update("insert into companies(id,legal_name,short_name,is_active) values (?,'Fixture Company','Fixture',true)",COMPANY);
    db.update("insert into securities(id,company_id,symbol,exchange,currency,is_active,shares_outstanding) values (?,?,'ZZTEST','HOSE','VND',true,9007199254740993)",SECURITY,COMPANY);
    db.update("insert into data_sources(id,code,name,source_type) values (1,'FIXTURE','Fixture source','API')");
    db.update("insert into data_versions(id,data_domain,version_code,status) values (?,'MARKET_PRICE','FIXTURE','ACTIVATED'),(?,'MARKET_PRICE','REJECTED','REJECTED')",VERSION,REJECTED);
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
}

package com.hethongdata.taichinh.service.admin;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.hethongdata.taichinh.repository.jpa.admin.AdminAuditJpaRepository;
import com.hethongdata.taichinh.repository.jpa.admin.AdminConsoleQueryRepository;
import static org.mockito.Mockito.mock;

class AdminConsoleSafetyTests {
  @Test void deniesNonAllowlistedIdentifiersBeforeOpeningDatabase() {
    var service=new AdminConsoleReadService(mock(AdminConsoleQueryRepository.class),mock(AdminAuditJpaRepository.class),new ObjectMapper());
    for(String table:java.util.List.of("accounts","system_settings","market_prices;drop table companies","public.market_prices"))
      assertThatThrownBy(()->service.browse(table,0,25,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.browse("market_prices",-1,25,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.browse("market_prices",0,101,null,null,null)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void redactsNestedCredentialsAndPreservesFinancialDecimalText() throws Exception {
    var json=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    var value=AdminConsoleReadService.safe(json.readTree("""
      {"price":12345678901234567890.125,"metadata":{"apiKey":"private","url":"https://example.org?token=private"},"error":"Bearer hidden"}
      """));
    assertThat(value.path("price").isTextual()).isTrue();
    assertThat(value.path("price").asText()).isEqualTo("12345678901234567890.125");
    assertThat(value.path("metadata").path("apiKey").asText()).isEqualTo("[redacted]");
    assertThat(value.toString()).doesNotContain("private","hidden");
  }
}

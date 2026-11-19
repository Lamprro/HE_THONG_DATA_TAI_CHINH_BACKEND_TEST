package com.hethongdata.taichinh.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.master.MasterDataDtos.*;
import com.hethongdata.taichinh.dto.source.DataSourceDtos.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Pins the JSON field names and binding of the master/data-source admin records. */
class AdminDtoJsonContractTests {
  private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

  @Test
  void responsesKeepFieldNames() throws Exception {
    var id = UUID.randomUUID();
    assertThat(json.readTree(json.writeValueAsString(new SecurityResponse(id, id, "FPT", "HOSE", "STOCK", null, "VND",
            LocalDate.of(2006, 12, 13), null, BigDecimal.ONE, BigDecimal.TEN, true, false))).fieldNames())
        .toIterable().containsExactly("id", "companyId", "symbol", "exchange", "securityType", "isin", "currency",
            "listedDate", "delistedDate", "sharesOutstanding", "parValue", "primary", "active");
    assertThat(json.readTree(json.writeValueAsString(new CompanyResponse(id, null, "FPT", "FPT Corp", null, null, null,
            null, null, null, null, null, "LISTED", null, true))).fieldNames())
        .toIterable().containsExactly("id", "taxCode", "companyCode", "legalName", "shortName", "englishName",
            "industryCode", "industryName", "sectorName", "website", "headquarters", "foundedDate", "listingStatus",
            "description", "active");
    assertThat(json.readTree(json.writeValueAsString(new CompanyAliasResponse(1L, "FPT", "TICKER"))).fieldNames())
        .toIterable().containsExactly("id", "alias", "aliasType");
    assertThat(json.readTree(json.writeValueAsString(new DataSourceResponse(1, "VNSTOCK", "n", "p", "API", (short) 10,
            true, "UNKNOWN", null, true))).fieldNames())
        .toIterable().containsExactly("id", "code", "name", "provider", "sourceType", "priority", "official",
            "licenseStatus", "baseUrl", "active");
  }

  @Test
  void requestsBindLikeTheFormerBeans() throws Exception {
    assertThat(json.readValue("{\"active\":false}", UpdateActivationRequest.class).active()).isFalse();
    assertThat(json.readValue("{}", UpdateActivationRequest.class).active()).isNull();
    var security = json.readValue("{\"companyId\":\"" + UUID.randomUUID() + "\",\"symbol\":\"fpt\",\"primary\":null,\"unknown\":1}",
        SecurityRequest.class);
    assertThat(security.symbol()).isEqualTo("fpt");
    assertThat(security.primary()).isNull();
    assertThat(security.active()).isNull();
    var source = json.readValue("{\"code\":\"x\",\"name\":\"n\",\"sourceType\":\"API\"}", DataSourceRequest.class);
    assertThat(source.official()).isFalse();
    assertThat(source.active()).isFalse();
    assertThat(json.readValue("{\"foundedDate\":\"2001-02-03\"}", CompanyRequest.class).foundedDate())
        .isEqualTo(LocalDate.of(2001, 2, 3));

    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var violations = factory.getValidator().validate(new CompanyAliasRequest(" ", null));
      assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactlyInAnyOrder("alias", "aliasType");
      assertThat(factory.getValidator().validate(new UpdateActivationRequest(null))).hasSize(1);
    }
  }
}

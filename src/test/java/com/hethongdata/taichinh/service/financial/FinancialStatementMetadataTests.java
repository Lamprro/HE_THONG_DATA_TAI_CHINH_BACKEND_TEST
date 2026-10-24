package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FinancialStatementMetadataTests {
    final ObjectMapper json=new ObjectMapper();
    final LocalDate end=LocalDate.of(2026,6,30);
    @Test void legacyMissingFieldsAreUnknownNotInvented() throws Exception {
        var meta=FinancialStatementMetadata.parse(json.readTree("""
            {"createdDate":"2026-07-28","modifiedDate":"2026-08-24","formType":"ALL"}
            """),end,"INCOME_STATEMENT");
        assertThat(meta.reportScope()).isEqualTo("UNKNOWN");
        assertThat(meta.periodBasis()).isEqualTo("UNKNOWN");
        assertThat(meta.currency()).isNull();assertThat(meta.unitScale()).isNull();assertThat(meta.publishedAt()).isNull();
    }
    @Test void provenMetadataPreservesUnitsBasisAndPublicationZone() throws Exception {
        var root=json.readTree("""
            {"provider":"vndirect","financial_metadata":{"schema_version":"financial.metadata.v1",
            "provider":"vndirect","report_scope":"CONSOLIDATED","period_basis":"YTD","currency":"VND",
            "unit_scale":1000000,"published_at":"2026-07-20T09:00:00+07:00","evidence":{
            "report_scope":[{"normalized":"CONSOLIDATED"}],"period_basis":[{"normalized":"YTD"}],
            "currency":[{"normalized":"VND"}],"unit_scale":[{"normalized":1000000}],
            "published_at":[{"normalized":"2026-07-20T09:00:00+07:00"}]}}}
            """);
        var meta=FinancialStatementMetadata.parse(root,end,"CASH_FLOW");
        assertThat(meta.unitScale()).isEqualTo(1000000L);
        assertThat(meta.publishedAt()).isEqualTo(Instant.parse("2026-07-20T02:00:00Z"));
        assertThat(meta.periodBasis()).isEqualTo("YTD");
        assertThat(meta.provenance().path("source").path("evidence").isObject()).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("financial_metadata")).put("unit_scale",1000);
        assertThatThrownBy(()->FinancialStatementMetadata.parse(root,end,"CASH_FLOW"))
                .hasMessageContaining("evidence mismatch");
    }
    @Test void invalidExplicitUnitsAndPublicationDatesFail() throws Exception {
        var root=json.readTree("{\"currency\":\"VND\",\"unit_scale\":-1}");
        assertThatThrownBy(()->FinancialStatementMetadata.parse(root,end,"BALANCE_SHEET"))
                .hasMessageContaining("unit scale");
        var date=json.readTree("{\"published_at\":\"2026-05-01T00:00:00+07:00\"}");
        assertThatThrownBy(()->FinancialStatementMetadata.parse(date,end,"INCOME_STATEMENT"))
                .hasMessageContaining("precedes fiscal");
    }
}

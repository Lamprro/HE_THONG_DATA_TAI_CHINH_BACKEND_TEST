package com.hethongdata.taichinh.service.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

class FinancialMetricPayloadParserTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FinancialMetricPayloadParser parser = new FinancialMetricPayloadParser();

    @Test void parsesVndirectAndNormalizesOnlyDocumentedPercentageMetrics() throws Exception {
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getPayload()).thenReturn(mapper.readTree("""
                {"provider":"vndirect","symbol":"FPT","data":[
                  {"code":"FPT","ratioCode":"PRICE_TO_EARNINGS","value":12.47,"reportDate":"2026-08-28"},
                  {"code":"FPT","ratioCode":"ROAE_TR_AVG5Q","value":0.2408673527,"reportDate":"2026-06-30"},
                  {"code":"FPT","ratioCode":"DIVIDEND_YIELD","value":0.027349218,"reportDate":"2026-08-28"}]}
                """));

        var drafts = parser.parse(raw);

        assertThat(drafts).extracting(FinancialMetricPayloadParser.ProviderMetricDraft::code)
                .containsExactly("PRICE_TO_EARNINGS", "ROAE_TR_AVG5Q", "DIVIDEND_YIELD");
        assertThat(drafts.get(0).value()).isEqualByComparingTo("12.47");
        assertThat(drafts.get(1).value()).isEqualByComparingTo("24.08673527");
        assertThat(drafts.get(2).value()).isEqualByComparingTo(new BigDecimal("2.7349218"));
    }

    @Test void parsesObservedVnstockAliasesWithoutAssumingVndirectShape() throws Exception {
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getPayload()).thenReturn(mapper.readTree("""
                {"provider":"vnstock","data":[{"symbol":"FPT","ratio_code":"PRICE_TO_BOOK","ratio_value":"3.12","report_date":"2026-08-28"}]}
                """));
        assertThat(parser.parse(raw)).singleElement().satisfies(metric -> {
            assertThat(metric.symbol()).isEqualTo("FPT");
            assertThat(metric.code()).isEqualTo("PRICE_TO_BOOK");
            assertThat(metric.value()).isEqualByComparingTo("3.12");
        });
    }

    @Test void rejectsUnknownProviderRatherThanGuessingJsonContract() throws Exception {
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        when(raw.getId()).thenReturn(java.util.UUID.randomUUID());
        when(raw.getPayload()).thenReturn(mapper.readTree("{" + "\"provider\":\"other\",\"data\":[]}"));
        assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}

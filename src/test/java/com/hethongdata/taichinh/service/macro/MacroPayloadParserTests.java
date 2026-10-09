package com.hethongdata.taichinh.service.macro;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class MacroPayloadParserTests {
    static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode payload() throws Exception {
        return (ObjectNode)
                JSON.readTree(
                        """
                        {"schema_version":"macro_observations.v1","dataset":"MACRO","provider":"bis","country_code":"VNM",
                         "source_url":"https://stats.bis.org/api/v1/data/WS_XRU/Q.VN.VND.A","retrieved_at":"2026-10-09T00:00:00Z",
                         "start_date":"2016-01-01","end_date":"2026-10-09","count":1,"observation_count":1,
                         "data":[{"code":"VN_USD_VND_QUARTER_AVG","name":"Quarterly FX","description":"Native quarterly average",
                           "source_indicator":"BIS:WS_XRU(1.0)/Q.VN.VND.A","frequency":"QUARTERLY","unit":"VND_PER_USD","country_code":"VNM","provider":"bis",
                           "observations":[{"period":"2026-Q2","observation_date":"2026-06-30","value":25000.123456,
                             "source_record":{"FREQ":"Q","REF_AREA":"VN","CURRENCY":"VND","COLLECTION":"A","TIME_PERIOD":"2026-Q2","OBS_VALUE":"25000.123456","UNIT_MULT":"0"}}]}]}
                        """);
    }

    final MacroPayloadParser parser = new MacroPayloadParser();

    @Test
    void retainsQuarterDateAndPrecision() throws Exception {
        var batch = parser.parse(payload(), "bis");
        assertThat(batch.series().getFirst().observations().getFirst().value())
                .isEqualByComparingTo("25000.123456");
        assertThat(batch.series().getFirst().observations().getFirst().date().toString())
                .isEqualTo("2026-06-30");
    }

    @Test
    void rejectsWrongCountryProviderAndUnits() throws Exception {
        var p = payload();
        p.put("country_code", "USA");
        assertThatThrownBy(() -> parser.parse(p, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(payload(), "worldbank"))
                .isInstanceOf(IllegalArgumentException.class);
        var q = payload();
        ((ObjectNode) q.path("data").get(0)).put("unit", "USD");
        assertThatThrownBy(() -> parser.parse(q, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateDatesAndIncorrectCounts() throws Exception {
        var p = payload();
        var list =
                (com.fasterxml.jackson.databind.node.ArrayNode)
                        p.path("data").get(0).path("observations");
        list.add(list.get(0).deepCopy());
        p.put("observation_count", 2);
        assertThatThrownBy(() -> parser.parse(p, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
        var q = payload();
        q.put("observation_count", 0);
        assertThatThrownBy(() -> parser.parse(q, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncorrectQuarterDatesAndUnpublishedFuturePeriods() throws Exception {
        var p = payload();
        ((ObjectNode) p.path("data").get(0).path("observations").get(0))
                .put("observation_date", "2026-06-29");
        assertThatThrownBy(() -> parser.parse(p, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
        var q = payload();
        q.put("end_date", "2100-01-01");
        assertThatThrownBy(() -> parser.parse(q, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullValuesAndSourceHostMismatch() throws Exception {
        var p = payload();
        ((ObjectNode) p.path("data").get(0).path("observations").get(0)).putNull("value");
        assertThatThrownBy(() -> parser.parse(p, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
        var q = payload();
        q.put("source_url", "https://example.com/data");
        assertThatThrownBy(() -> parser.parse(q, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAlteredSourceEvidence() throws Exception {
        var p = payload();
        ((ObjectNode) p.path("data").get(0).path("observations").get(0).path("source_record"))
                .put("OBS_VALUE", "1");
        assertThatThrownBy(() -> parser.parse(p, "bis"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

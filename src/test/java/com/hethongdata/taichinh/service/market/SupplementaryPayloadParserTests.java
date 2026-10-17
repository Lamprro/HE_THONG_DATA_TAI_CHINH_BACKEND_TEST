package com.hethongdata.taichinh.service.market;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class SupplementaryPayloadParserTests {
    final ObjectMapper json=new ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode foreign() throws Exception {
        return (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
{"schema_version":"foreign_trades.v1","provider":"cafef","volume_unit":"SHARES","symbol":"FPT","count":1,
"retrieved_at":"2026-10-16T10:00:00Z","source_url":"https://cafef.vn/du-lieu/ajax/pagenew/datahistory/gdkhoingoai.ashx",
"start_date":"2026-01-01","end_date":"2026-10-16","data":[{"Symbol":"FPT","Ngay":"16/10/2026","KLMua":null,"KLBan":0}]}
""");
    }
    @Test void missingVolumeIsDistinctFromReportedZero() throws Exception {
        var row=SupplementaryPayloadParser.foreign(foreign(),"FPT").getFirst();
        assertThat(row.buy()).isNull();assertThat(row.sell()).isZero();
    }
    @Test void wrongWindowSymbolAndFractionalVolumesRejectWholeBatch() throws Exception {
        var root=foreign();root.put("start_date","2020-01-01").put("end_date","2020-12-31");
        assertThatThrownBy(()->SupplementaryPayloadParser.foreign(root,"FPT")).isInstanceOf(IllegalArgumentException.class);
        var other=foreign();((com.fasterxml.jackson.databind.node.ObjectNode)other.path("data").get(0)).put("KLBan",1.5);
        assertThatThrownBy(()->SupplementaryPayloadParser.foreign(other,"FPT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SupplementaryPayloadParser.foreign(foreign(),"VCB")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void referenceDoesNotTurnListedSharesIntoOutstandingShares() throws Exception {
        var root=json.readTree("""
{"provider":"vndirect","dataset":"company","symbol":"FPT","count":1,"retrieved_at":"2026-10-16T10:00:00Z",
"data":[{"code":"FPT","listedShares":100,"listedDate":"2020-01-01"}]}
""");
        assertThat(SupplementaryPayloadParser.reference(root,"FPT").has("outstandingShares")).isFalse();
    }
}

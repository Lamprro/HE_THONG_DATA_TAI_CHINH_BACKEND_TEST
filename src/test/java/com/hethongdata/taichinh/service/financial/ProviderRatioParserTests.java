package com.hethongdata.taichinh.service.financial;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class ProviderRatioParserTests {
    final ObjectMapper json=new ObjectMapper();final ProviderRatioParser parser=new ProviderRatioParser();
    @Test void normalizesFractionsExplicitlyAndPreservesOriginal() throws Exception {
        var p=json.readTree("""
{"provider":"vndirect","dataset":"ratios","symbol":"FPT","count":2,"data":[
{"code":"FPT","ratioCode":"ROAE_TR_AVG5Q","reportDate":"2016-06-30","value":0.24},
{"code":"FPT","ratioCode":"PRICE_TO_EARNINGS","reportDate":"2016-06-30","value":11.2}]}
""");
        var rows=parser.parse(p,"FPT");
        assertThat(rows.getFirst().value()).isEqualByComparingTo("24");
        assertThat(rows.getFirst().sourceValue()).isEqualByComparingTo("0.24");
        assertThat(rows.get(1).value()).isEqualByComparingTo("11.2");
    }
    @Test void rejectsMismatchedSecurityAndDuplicateIdentity() throws Exception {
        var p=json.readTree("""
{"provider":"vndirect","dataset":"ratios","symbol":"FPT","count":1,"data":[
{"code":"VCB","ratioCode":"PRICE_TO_BOOK","reportDate":"2016-06-30","value":2}]}
""");
        assertThatThrownBy(()->parser.parse(p,"FPT")).isInstanceOf(IllegalArgumentException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)p.path("data").get(0)).put("code","FPT");
        ((com.fasterxml.jackson.databind.node.ArrayNode)p.path("data")).add(p.path("data").get(0).deepCopy());
        ((com.fasterxml.jackson.databind.node.ObjectNode)p).put("count",2);
        assertThatThrownBy(()->parser.parse(p,"FPT")).hasMessageContaining("Duplicate");
    }
    @Test void rejectsNullValuesInsteadOfInventingZero() throws Exception {
        var p=json.readTree("""
{"provider":"vndirect","dataset":"ratios","symbol":"FPT","count":1,"data":[
{"code":"FPT","ratioCode":"EPS_TR","reportDate":"2016-06-30","value":null}]}
""");
        assertThatThrownBy(()->parser.parse(p,"FPT")).hasMessageContaining("numeric");
    }
}

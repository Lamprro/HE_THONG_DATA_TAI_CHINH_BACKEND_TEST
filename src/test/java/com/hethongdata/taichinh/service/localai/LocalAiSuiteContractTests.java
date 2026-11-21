package com.hethongdata.taichinh.service.localai;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class LocalAiSuiteContractTests {
 final ObjectMapper json=new ObjectMapper();
 JsonNode spec(String kind){return json.valueToTree(java.util.Map.of("id","model","kind",kind,"target_period","2026Q4","artifacts",java.util.Map.of("selected",java.util.Map.of("sha256","hash"))));}
 ObjectNode response(){var n=json.createObjectNode();n.put("schema","local_ai.registered.output.v1").put("package_id","package").put("manifest_sha256","manifest").put("model_id","model").put("role","selected").put("ticker","FPT").put("input_sha256","input").put("data_as_of","2026-09-30").put("issued_at",Instant.now().toString()).put("production_eligible",false).put("replace_llm",false);n.putObject("result").put("ticker","FPT").put("target_period","2026Q4").put("model_sha256","hash").put("production_eligible",false).put("replace_llm",false);return n;}
 boolean valid(ObjectNode r,String kind){return Boolean.TRUE.equals(LocalAiSuiteContract.validate(r,spec(kind),"package","manifest","selected","FPT","input","2026-09-30",Instant.now().minusSeconds(1)).get("passed"));}
 @Test void directionRequiresConsistentLabel(){var r=response();((ObjectNode)r.path("result")).put("score_up",.7).put("threshold",.5).put("direction","UP");assertTrue(valid(r,"h3"));((ObjectNode)r.path("result")).put("direction","NOT_UP");assertFalse(valid(r,"h3"));}
 @Test void quantilesMustNotCross(){var r=response();var x=(ObjectNode)r.path("result");x.put("return_p10",-.2).put("return_p50",.1).put("return_p90",.4).put("return_unit","FRACTION");assertTrue(valid(r,"quantile"));x.put("return_p10",.2);assertFalse(valid(r,"quantile"));}
 @Test void npatRequiresScopeUnitAndTarget(){var r=response();var x=(ObjectNode)r.path("result");x.put("prediction_vnd",-500.0).put("currency","VND").put("target","NET_PROFIT_AFTER_TAX").put("report_scope","CONSOLIDATED").put("period_basis","STANDALONE_QUARTER");assertTrue(valid(r,"npat"));x.put("report_scope","SEPARATE");assertFalse(valid(r,"npat"));}
 @Test void drawdownRangeAndUnits(){var r=response();var x=(ObjectNode)r.path("result");x.put("prediction",.2).put("units","MAX_CLOSE_DRAWDOWN_FRACTION");assertTrue(valid(r,"drawdown"));x.put("prediction",1.1);assertFalse(valid(r,"drawdown"));}
 @Test void missingReadinessCannotMasqueradeAsFalse(){var r=response();var x=(ObjectNode)r.path("result");x.put("prediction",.1).put("units","ADJUSTED_PRICE_RETURN_FRACTION");assertTrue(valid(r,"return"));r.remove("production_eligible");assertFalse(valid(r,"return"));}
 @Test void rejectsOldIssuanceAndWrongArtifact(){var r=response();var x=(ObjectNode)r.path("result");x.put("prediction",.1).put("units","ANNUALIZED_LOG_RETURN_VOLATILITY");assertTrue(valid(r,"volatility"));r.put("issued_at","2020-01-01T00:00:00Z");assertFalse(valid(r,"volatility"));r.put("issued_at",Instant.now().toString());x.put("model_sha256","other");assertFalse(valid(r,"volatility"));}
}

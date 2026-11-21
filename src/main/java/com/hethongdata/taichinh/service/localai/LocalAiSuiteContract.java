package com.hethongdata.taichinh.service.localai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Output policy for the registered research suite, independent of the LLM contract. */
public final class LocalAiSuiteContract {
 private LocalAiSuiteContract(){}
 static boolean number(JsonNode n){return n.isNumber()&&Double.isFinite(n.doubleValue());}
 static boolean no(JsonNode n){return n.isBoolean()&&!n.booleanValue();}
 public static Map<String,Object> validate(JsonNode out,JsonNode spec,String pkg,String manifest,String role,String ticker,String inputHash,String dataAsOf,Instant started){
  Map<String,Object> c=new LinkedHashMap<>();var r=out.path("result");
  c.put("schema",out.path("schema").asText().equals("local_ai.registered.output.v1"));
  c.put("identity",out.path("package_id").asText().equals(pkg)&&out.path("manifest_sha256").asText().equals(manifest)&&out.path("model_id").asText().equals(spec.path("id").asText())&&out.path("ticker").asText().equals(ticker)&&out.path("role").asText().equals(role));
  c.put("snapshot",out.path("input_sha256").asText().equals(inputHash)&&out.path("data_as_of").asText().equals(dataAsOf));
  c.put("readiness",no(out.path("production_eligible"))&&no(out.path("replace_llm"))&&no(r.path("production_eligible"))&&no(r.path("replace_llm")));
  c.put("artifact",r.path("model_sha256").asText().equals(spec.path("artifacts").path(role).path("sha256").asText()));
  c.put("target",r.path("ticker").asText().equals(ticker)&&r.path("target_period").asText().equals(spec.path("target_period").asText()));
  boolean time=false;try{Instant issued=Instant.parse(out.path("issued_at").asText());time=!issued.isBefore(started.minusSeconds(2))&&!issued.isAfter(Instant.now().plusSeconds(2));}catch(Exception ignored){}c.put("issuance",time);
  String kind=spec.path("kind").asText();boolean values;
  if(kind.equals("h3")){double p=r.path("score_up").asDouble(Double.NaN),t=r.path("threshold").asDouble(Double.NaN);values=number(r.path("score_up"))&&p>=0&&p<=1&&number(r.path("threshold"))&&t>=0&&t<=1&&r.path("direction").asText().equals(p>=t?"UP":"NOT_UP");}
  else if(kind.equals("quantile")){
   double a=r.path("return_p10").asDouble(Double.NaN),b=r.path("return_p50").asDouble(Double.NaN),d=r.path("return_p90").asDouble(Double.NaN);
   values=number(r.path("return_p10"))&&number(r.path("return_p50"))&&number(r.path("return_p90"))&&a>=-1&&a<=b&&b<=d&&r.path("return_unit").asText().equals("FRACTION");
  }
  else if(kind.equals("npat")){values=number(r.path("prediction_vnd"))&&r.path("currency").asText().equals("VND")&&r.path("target").asText().equals("NET_PROFIT_AFTER_TAX")&&r.path("report_scope").asText().equals("CONSOLIDATED")&&r.path("period_basis").asText().equals("STANDALONE_QUARTER");}
  else if(kind.equals("rank")){
   double pct=r.path("rank_percentile").asDouble(Double.NaN);
   String bucket=pct>.8?"TOP":pct<=.2?"BOTTOM":"MIDDLE";
   values=r.path("schema").asText().equals("local.stock.rank.output.v3")
    && number(r.path("score")) && number(r.path("rank_percentile")) && pct>0 && pct<=1
    && r.path("bucket").asText().equals(bucket)
    && r.path("universe_hash").asText().matches("[0-9a-f]{64}")
    && r.path("universe_hash").asText().equals(spec.path("universe_hash").asText())
    && r.path("universe_size").isIntegralNumber() && r.path("universe_size").asInt()>=10
    && r.path("universe_size").asInt()==spec.path("universe_size").asInt()
    && r.path("cutoff").asText().equals(dataAsOf)
    && r.path("feature_version").asText().equals(spec.path("feature_version").asText())
    && r.path("probability_status").asText().equals("uncalibrated") && !r.has("probability_outperform")
    && validateRankRisk(r,spec);
  }
  else{
   double p=r.path("prediction").asDouble(Double.NaN);
   String unit=kind.equals("return")?"ADJUSTED_PRICE_RETURN_FRACTION":kind.equals("volatility")?"ANNUALIZED_LOG_RETURN_VOLATILITY":"MAX_CLOSE_DRAWDOWN_FRACTION";
   values=number(r.path("prediction"))&&r.path("units").asText().equals(unit)&&(kind.equals("return")?p>=-1:kind.equals("volatility")?p>=0:p>=0&&p<=1);
  }
  c.put("values_and_units",values);c.put("passed",c.values().stream().allMatch(Boolean.TRUE::equals));return c;
 }
 static boolean validateRankRisk(JsonNode result,JsonNode spec){
  if(result.path("risk_status").asText().equals("UNAVAILABLE_FOR_THIS_SNAPSHOT"))
   return result.path("volatility_h1").isNull() && result.path("drawdown_h1").isNull();
  String digest=result.path("risk_manifest_sha256").asText();
  if(!result.path("risk_status").asText().equals("SEPARATE_RESEARCH_SNAPSHOT")
    || !digest.matches("[0-9a-f]{64}") || !digest.equals(spec.path("risk_manifest_sha256").asText()))return false;
  for(String id:List.of("volatility_h1","drawdown_h1")){
   var value=result.path(id);var provenance=result.path("risk_provenance").path(id);
   if(!number(value)||value.asDouble()<0||id.equals("drawdown_h1")&&value.asDouble()>1
    || !number(provenance.path("value"))||provenance.path("value").asDouble()!=value.asDouble()
    || !provenance.path("model_sha256").asText().matches("[0-9a-f]{64}")
    || !provenance.path("input_sha256").asText().matches("[0-9a-f]{64}"))return false;
   String unit=id.equals("volatility_h1")?"ANNUALIZED_LOG_RETURN_VOLATILITY":"MAX_CLOSE_DRAWDOWN_FRACTION";
   if(!provenance.path("units").asText().equals(unit))return false;
  }
  return true;
 }
}

package com.hethongdata.taichinh.service.user;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/**
 * Reads the registered suite's progress, never its private inputs, artifacts or raw predictions.
 * The opt-in research preview (financial.local-ai.research-preview.enabled, default false, 404 when disabled)
 * projects only whitelisted output-contract fields of stored selected outputs; the package is RESEARCH_ONLY
 * and has no publication policy yet. Inputs, features, artifacts and run payloads never leave the DB.
 */
@Service
@Transactional(readOnly=true,timeout=15,isolation=Isolation.REPEATABLE_READ)
public class UserLocalAiService {
  private final boolean previewEnabled;private final UserReadSupport read;private final ObjectMapper json;
  public UserLocalAiService(@Value("${financial.local-ai.research-preview.enabled:false}") boolean previewEnabled,UserReadSupport read,ObjectMapper json){
    this.previewEnabled=previewEnabled;this.read=read;this.json=json;
  }
  static final String NOTE="Registered research progress only. Counts are stored records, not accuracy, live provider availability or public prediction approval. No model execution, feature submission or training occurs on reads.";
  boolean installed(){
    return read.hasColumns("local_ai_packages",Set.of("id","manifest","status","registered_at"))
      &&read.hasColumns("local_ai_package_inputs",Set.of("package_id","model_id","ticker","data_as_of"))
      &&read.hasColumns("local_ai_package_runs",Set.of("id","package_id","model_id","ticker","role","status","checks","http_status","completed_at"))
      &&read.hasColumns("local_ai_package_predictions",Set.of("run_id","package_id","model_id","ticker","role","readiness"));
  }
  private static final String PACKAGES="""
    from local_ai_packages p where p.status='RESEARCH_ONLY'
    and p.manifest->>'schema'='local_ai.registered.release.v1'
    and p.manifest->'production_eligible'='false'::jsonb and p.manifest->'replace_llm'='false'::jsonb
    and jsonb_typeof(p.manifest->'models')='array'
    """;
  Map<String,Object> packageRow(UUID id){
    var args=new HashMap<String,Object>();String query="select p.id,p.status,p.registered_at as \"registeredAt\" "+PACKAGES;
    if(id!=null){args.put("id",id);query+=" and p.id=:id";}
    var rows=read.rows(query+" order by p.registered_at desc,p.id desc limit 1",args);
    if(rows.isEmpty()&&id!=null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Supported research package not found");
    return rows.isEmpty()?null:rows.getFirst();
  }
  private static final String MODELS="""
    from local_ai_packages p cross join lateral jsonb_array_elements(p.manifest->'models') model
    where p.id=:package and model->>'id' ~ '^[a-z0-9_]{2,60}$'
    and model->'production_eligible'='false'::jsonb
    """;
  private static final String PROGRESS="""
    (select count(*) from local_ai_package_inputs i where i.package_id=p.id and i.model_id=model->>'id'
      and (cast(:ticker as text) is null or i.ticker=:ticker)) as "registeredInputs",
    (select min(i.data_as_of) from local_ai_package_inputs i where i.package_id=p.id and i.model_id=model->>'id'
      and (cast(:ticker as text) is null or i.ticker=:ticker)) as "firstInputAsOf",
    (select max(i.data_as_of) from local_ai_package_inputs i where i.package_id=p.id and i.model_id=model->>'id'
      and (cast(:ticker as text) is null or i.ticker=:ticker)) as "lastInputAsOf",
    (select count(*) from local_ai_package_predictions x join local_ai_package_runs r on r.id=x.run_id
      and r.package_id=x.package_id and r.model_id=x.model_id and r.ticker=x.ticker and r.role=x.role
      where x.package_id=p.id and x.model_id=model->>'id' and x.role='selected' and x.readiness='RESEARCH_ONLY'
      and r.status='SUCCEEDED' and r.http_status=200 and r.completed_at is not null and r.checks->'passed'='true'::jsonb
      and exists(select 1 from local_ai_package_inputs i where i.package_id=x.package_id and i.model_id=x.model_id and i.ticker=x.ticker)
      and (cast(:ticker as text) is null or x.ticker=:ticker)) as "storedSelectedResearchResults"
    """;
  private static final String MODEL_FIELDS="""
    select p.id as "packageId",p.registered_at as "registeredAt",model->>'id' as "modelId",model->>'kind' as kind,
    case when model->>'horizon' ~ '^[1-4]$' then (model->>'horizon')::integer end as "horizonQuarters",
    case when model->>'target_period' ~ '^[0-9]{4}Q[1-4]$' then model->>'target_period' end as "targetPeriod",
    model->'selection'->>'selected' as "selectedAlgorithm",
    model->'selection'->'gate_pass'='true'::jsonb as "developmentGatePassed",
    """;
  private Map<String,Object> params(Map<String,Object> pkg,String ticker){var args=new HashMap<String,Object>();args.put("package",UUID.fromString(pkg.get("id").toString()));args.put("ticker",ticker);return args;}
  private Map<String,Object> project(Map<String,Object> row){
    var result=new LinkedHashMap<>(row);result.put("readiness","RESEARCH_ONLY");result.put("publicResultsAvailable",false);
    result.put("inputMode","REGISTERED_SNAPSHOT");result.put("featureSubmissionAllowed",false);
    result.put("outputContract",contract(Objects.toString(row.get("kind"),"")));return result;
  }
  // Field meanings match LocalAiSuiteContract (registered output v1). No score is recalibrated here.
  private Map<String,Object> contract(String kind){
    return switch(kind){
      case "h3"->Map.of("type","DIRECTION","fields",List.of("direction","score_up","threshold"),"unit","UNCALIBRATED_SCORE_0_1","note","UP or NOT_UP; score is not a calibrated probability. Read horizonQuarters, not the legacy kind name h3.");
      case "quantile"->Map.of("type","RETURN_QUANTILES","fields",List.of("return_p10","return_p50","return_p90"),"unit","FRACTION","note","Selected baseline may use pooled historical quantiles; these are not guaranteed per-security calibrated intervals.");
      case "npat"->Map.of("type","NET_PROFIT_AFTER_TAX","fields",List.of("prediction_vnd"),"unit","VND","reportScope","CONSOLIDATED","periodBasis","STANDALONE_QUARTER");
      case "return"->Map.of("type","ADJUSTED_PRICE_RETURN","fields",List.of("prediction"),"unit","ADJUSTED_PRICE_RETURN_FRACTION");
      case "volatility"->Map.of("type","VOLATILITY","fields",List.of("prediction"),"unit","ANNUALIZED_LOG_RETURN_VOLATILITY");
      case "drawdown"->Map.of("type","DRAWDOWN","fields",List.of("prediction"),"unit","MAX_CLOSE_DRAWDOWN_FRACTION");
      default->Map.of("type","UNSUPPORTED","fields",List.of(),"note","Unknown output contract; no prediction is exposed.");
    };
  }
  public Data<Map<String,Object>> summary(){
    boolean available=installed();var pkg=available?packageRow(null):null;var result=new LinkedHashMap<String,Object>();
    result.put("schemaAvailable",available);result.put("state",!available?"NOT_INSTALLED":pkg==null?"NO_REGISTERED_PACKAGE":"RESEARCH_ONLY");
    result.put("package",pkg);result.put("publicResultsAvailable",false);result.put("executionAvailable",false);
    result.put("modelsRoute","/api/v1/local-ai/models");return data(result,NOTE,"A development gate is model selection evidence, not production eligibility or forward forecast accuracy.");
  }
  public Page<Map<String,Object>> models(UUID packageId,int page,int size){
    paging(page,size,100);if(!installed())return new Page<>(List.of(),0,page,size,false,meta(NOTE,"Suite schema is not installed."));
    var pkg=packageRow(packageId);if(pkg==null)return new Page<>(List.of(),0,page,size,false,meta(NOTE,"No supported research package is registered."));
    var rows=read.page(MODEL_FIELDS+PROGRESS,MODELS,"order by model->>'id'",params(pkg,null),page,size,100,NOTE,"Defaults to the latest registered supported research package. Input dataAsOf retains its source meaning and can be a date or timestamp.");
    return new Page<>(rows.items().stream().map(this::project).toList(),rows.total(),page,size,rows.hasNext(),rows.meta());
  }
  public Data<Map<String,Object>> security(String symbol,String exchange,UUID packageId){
    var security=read.resolve(symbol,exchange);var result=new LinkedHashMap<String,Object>();result.put("security",security);
    result.put("publicResultsAvailable",false);result.put("executionAvailable",false);result.put("models",List.of());
    if(!installed()){result.put("state","NOT_INSTALLED");return data(result,NOTE);}
    var pkg=packageRow(packageId);result.put("package",pkg);if(pkg==null){result.put("state","NO_REGISTERED_PACKAGE");return data(result,NOTE);}
    String ticker=security.get("symbol").toString();var identity=read.one("select count(*) as count from securities where symbol=:symbol",Map.of("symbol",ticker),"Ticker identity");
    if(!"1".equals(identity.get("count").toString())){result.put("state","TICKER_IDENTITY_AMBIGUOUS");return data(result,NOTE,"Suite v1 identifies inputs by ticker without exchange or security UUID. No association is guessed when multiple listings share a ticker.");}
    var rows=read.rows(MODEL_FIELDS+PROGRESS+MODELS+" order by model->>'id' limit 101",params(pkg,ticker));
    result.put("modelsTruncated",rows.size()>100);var models=rows.stream().limit(100).map(this::project).toList();result.put("models",models);
    boolean input=models.stream().anyMatch(m->Long.parseLong(m.get("registeredInputs").toString())>0);
    result.put("state",input?"RESEARCH_ONLY":"NO_REGISTERED_INPUT");return data(result,NOTE,"No stored result value, input features, train files, artifact paths, run payload or retrospective outcome is published. Missing ticker input is not filled from a different company.");
  }

  // ---- opt-in research preview ----
  static final String PREVIEW_NOTE="RESEARCH PREVIEW ONLY. Stored outputs of a RESEARCH_ONLY package without publication approval. Not investment advice, not a calibrated probability or verified forecast accuracy. Reads never execute a model.";
  static final Map<String,List<String>> PREVIEW_FIELDS=Map.of(
    "h3",List.of("direction","score_up","threshold"),
    "quantile",List.of("return_p10","return_p50","return_p90"),
    "npat",List.of("prediction_vnd"),
    "return",List.of("prediction"),
    "volatility",List.of("prediction"),
    "drawdown",List.of("prediction"));
  private static final String PREVIEW_QUERY="""
    select distinct on (x.model_id) x.model_id as "modelId",model->>'kind' as kind,
      case when model->>'horizon' ~ '^[1-4]$' then (model->>'horizon')::integer end as "horizonQuarters",
      x.target_period as "targetPeriod",x.issued_at as "issuedAt",r.completed_at as "completedAt",
      i.data_as_of as "dataAsOf",model->'selection'->>'selected' as "selectedAlgorithm",
      model->'selection'->'gate_pass'='true'::jsonb as "developmentGatePassed",x.prediction::text as prediction
    from local_ai_package_predictions x
    join local_ai_package_runs r on r.id=x.run_id and r.package_id=x.package_id and r.model_id=x.model_id and r.ticker=x.ticker and r.role=x.role
    join local_ai_package_inputs i on i.package_id=x.package_id and i.model_id=x.model_id and i.ticker=x.ticker
    join local_ai_packages p on p.id=x.package_id
    cross join lateral jsonb_array_elements(p.manifest->'models') model
    where x.package_id=:package and x.ticker=:ticker and model->>'id'=x.model_id
      and model->'production_eligible'='false'::jsonb
      and x.role='selected' and x.readiness='RESEARCH_ONLY'
      and r.status='SUCCEEDED' and r.http_status=200 and r.completed_at is not null and r.checks->'passed'='true'::jsonb
    order by x.model_id,x.issued_at desc,x.id desc
    """;

  public boolean previewEnabled(){return previewEnabled;}

  public Data<Map<String,Object>> preview(String symbol,String exchange,UUID packageId){
    if(!previewEnabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Local AI research preview is disabled");
    var security=read.resolve(symbol,exchange);var result=new LinkedHashMap<String,Object>();
    result.put("security",security);result.put("readiness","RESEARCH_ONLY");result.put("researchPreview",true);
    result.put("publicResultsAvailable",false);result.put("predictions",List.of());
    if(!installed()){result.put("state","NOT_INSTALLED");return data(result,PREVIEW_NOTE);}
    var pkg=packageRow(packageId);result.put("package",pkg);
    if(pkg==null){result.put("state","NO_REGISTERED_PACKAGE");return data(result,PREVIEW_NOTE);}
    String ticker=security.get("symbol").toString();
    var identity=read.one("select count(*) as count from securities where symbol=:symbol",Map.of("symbol",ticker),"Ticker identity");
    if(!"1".equals(identity.get("count").toString())){result.put("state","TICKER_IDENTITY_AMBIGUOUS");return data(result,PREVIEW_NOTE);}
    var args=new HashMap<String,Object>();args.put("package",UUID.fromString(pkg.get("id").toString()));args.put("ticker",ticker);
    var rows=read.rows(PREVIEW_QUERY,args).stream().map(this::projectPreview).filter(Objects::nonNull).toList();
    result.put("predictions",rows);result.put("state",rows.isEmpty()?"NO_STORED_RESULT":"RESEARCH_PREVIEW");
    return data(result,PREVIEW_NOTE,"Values are the latest stored selected output per model. Decimal values are strings. Direction score is uncalibrated (0-1).");
  }

  /** Keep metadata, replace raw prediction JSON with whitelisted contract fields only. */
  Map<String,Object> projectPreview(Map<String,Object> row){
    var fields=PREVIEW_FIELDS.get(Objects.toString(row.get("kind"),""));
    if(fields==null)return null;
    JsonNode node;
    try{node=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(Objects.toString(row.get("prediction"),"{}"));}catch(Exception e){return null;}
    var values=new LinkedHashMap<String,Object>();
    for(String field:fields){
      JsonNode value=node.get(field);
      if(value==null||value.isNull())values.put(field,null);
      else if(value.isNumber())values.put(field,value.decimalValue().toPlainString());
      else if(value.isTextual()&&value.asText().length()<=40)values.put(field,value.asText());
      else if(value.isBoolean())values.put(field,value.asBoolean());
      else values.put(field,null);
    }
    var result=new LinkedHashMap<>(row);result.remove("prediction");result.put("values",values);result.put("readiness","RESEARCH_ONLY");
    return result;
  }
}

package com.hethongdata.taichinh.repository.jdbc.user;

import com.hethongdata.taichinh.service.user.UserReadSupport;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class UserLocalAiQueryRepository {
  private static final String PACKAGES = """
    from local_ai_packages p where p.status='RESEARCH_ONLY'
    and p.manifest->>'schema'='local_ai.registered.release.v1'
    and p.manifest->'production_eligible'='false'::jsonb and p.manifest->'replace_llm'='false'::jsonb
    and jsonb_typeof(p.manifest->'models')='array'
    """;

  private static final String MODELS = """
    from local_ai_packages p cross join lateral jsonb_array_elements(p.manifest->'models') model
    where p.id=:package and model->>'id' ~ '^[a-z0-9_]{2,60}$'
    and model->'production_eligible'='false'::jsonb
    """;

  private static final String PROGRESS = """
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

  private static final String MODEL_FIELDS = """
    select p.id as "packageId",p.registered_at as "registeredAt",model->>'id' as "modelId",model->>'kind' as kind,
    case when model->>'horizon' ~ '^[1-4]$' then (model->>'horizon')::integer end as "horizonQuarters",
    case when model->>'target_period' ~ '^[0-9]{4}Q[1-4]$' then model->>'target_period' end as "targetPeriod",
    model->'selection'->>'selected' as "selectedAlgorithm",
    model->'selection'->'gate_pass'='true'::jsonb as "developmentGatePassed",
    """;

  private static final String PREVIEW_QUERY = """
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

  private final UserReadJdbcRepository read;

  public UserLocalAiQueryRepository(UserReadJdbcRepository read) {
    this.read = read;
  }

  public boolean isInstalled() {
    return read.hasColumns("local_ai_packages", Set.of("id", "manifest", "status", "registered_at"))
        && read.hasColumns("local_ai_package_inputs", Set.of("package_id", "model_id", "ticker", "data_as_of"))
        && read.hasColumns("local_ai_package_runs", Set.of("id", "package_id", "model_id", "ticker", "role", "status", "checks", "http_status", "completed_at"))
        && read.hasColumns("local_ai_package_predictions", Set.of("run_id", "package_id", "model_id", "ticker", "role", "readiness"));
  }

  public Map<String, Object> findPackageRow(UUID id) {
    var args = new HashMap<String, Object>();
    String query = "select p.id,p.status,p.registered_at as \"registeredAt\" " + PACKAGES;
    if (id != null) {
      args.put("id", id);
      query += " and p.id=:id";
    }
    var rows = read.rows(query + " order by p.registered_at desc,p.id desc limit 1", args);
    if (rows.isEmpty() && id != null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supported research package not found");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public UserReadSupport.Page<Map<String, Object>> queryModels(
      Map<String, Object> pkg, int page, int size, String note) {
    var args = new HashMap<String, Object>();
    args.put("package", UUID.fromString(pkg.get("id").toString()));
    args.put("ticker", null);
    return read.page(
        MODEL_FIELDS + PROGRESS, MODELS, "order by model->>'id'",
        args, page, size, 100, note,
        "Defaults to the latest registered supported research package. Input dataAsOf retains its source meaning and can be a date or timestamp.");
  }

  public Map<String, Object> checkTickerIdentity(String ticker) {
    return read.one("select count(*) as count from securities where symbol=:symbol", Map.of("symbol", ticker), "Ticker identity");
  }

  public List<Map<String, Object>> querySecurityModels(Map<String, Object> pkg, String ticker) {
    var args = new HashMap<String, Object>();
    args.put("package", UUID.fromString(pkg.get("id").toString()));
    args.put("ticker", ticker);
    return read.rows(MODEL_FIELDS + PROGRESS + MODELS + " order by model->>'id' limit 101", args);
  }

  public List<Map<String, Object>> queryPreviewPredictions(Map<String, Object> pkg, String ticker) {
    var args = new HashMap<String, Object>();
    args.put("package", UUID.fromString(pkg.get("id").toString()));
    args.put("ticker", ticker);
    return read.rows(PREVIEW_QUERY, args);
  }
}

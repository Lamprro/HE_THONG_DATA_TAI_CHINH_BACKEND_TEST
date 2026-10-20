package com.hethongdata.taichinh.service.admin;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.application.port.model.*;
import com.hethongdata.taichinh.dto.ingestion.*;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.*;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.*;
import com.hethongdata.taichinh.service.validation.ValidationJobService;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Coordinates existing services only. Never fetches a provider or writes canonical rows itself. */
@Service
public class AdminPipelineService {
  private final IngestionService ingestion; private final ValidationJobService validation;
  private final IngestionJobService jobs; private final IngestionJobRepository definitions;
  private final RawPayloadJpaRepository raws; private final IngestionRunJpaRepository runs;
  private final DataVersionJpaRepository versions; private final ObjectMapper json;
  public AdminPipelineService(IngestionService ingestion,ValidationJobService validation,IngestionJobService jobs,
      IngestionJobRepository definitions,RawPayloadJpaRepository raws,IngestionRunJpaRepository runs,
      DataVersionJpaRepository versions,ObjectMapper json) {
    this.ingestion=ingestion;this.validation=validation;this.jobs=jobs;this.definitions=definitions;
    this.raws=raws;this.runs=runs;this.versions=versions;this.json=json;
  }
  public static String builder(ExternalOperation operation) {
    return switch(operation) {
      case OHLCV,QUOTE -> "MARKET_PRICE_BUILD";
      case FINANCIAL_STATEMENT -> "FINANCIAL_STATEMENT_BUILD";
      case RATIO -> "FINANCIAL_METRIC_BUILD";
      case INDEX_OHLCV -> "INDEX_PRICE_BUILD";
      case INDEX_MEMBERS -> "INDEX_MEMBERSHIP_BUILD";
      case FOREIGN_TRADES -> "FOREIGN_TRADE_BUILD";
      case COMPANY -> "SECURITY_REFERENCE_BUILD";
      default -> throw new IllegalArgumentException("Operation requires its native dedicated job; it is not a supported canonical pipeline");
    };
  }
  public JsonNode execute(ManualIngestionRequest request,Consumer<JsonNode> progress) {
    builder(request.getOperation());
    var response=ingestion.ingest(request);
    var stage=json.createObjectNode().put("stage","SOURCE_SAVED").put("sourceRunId",response.getRunId().toString());
    stage.set("source",json.valueToTree(response)); progress.accept(stage);
    return processRun(response.getRunId(),progress);
  }
  public JsonNode processRun(UUID runId,Consumer<JsonNode> progress) {
    var run=runs.findById(runId).orElseThrow(()->new IllegalArgumentException("Ingestion run not found"));
    String operation=run.getRequestQueryParams().path("operation").asText();
    String code=builder(ExternalOperation.valueOf(operation));
    var payloads=raws.findByIngestionRunIdOrderByFetchedAtDesc(runId);
    if(payloads.isEmpty() || payloads.size()>200) throw new IllegalArgumentException("Run must contain 1..200 raw payloads");
    var result=json.createObjectNode().put("sourceRunId",runId.toString()).put("builder",code);
    var steps=result.putArray("validation"); result.put("stage","VALIDATING"); progress.accept(result.deepCopy());
    for(var raw:payloads) {
      steps.add(json.valueToTree(validation.validate(raw.getId()))); progress.accept(result.deepCopy());
    }
    var version=versions.findByIngestionRunId(runId);
    if(version.isEmpty()) {
      result.put("status","VALIDATION_BLOCKED").put("stage","STOPPED_AT_VALIDATION");
      progress.accept(result.deepCopy());
      return result;
    }
    result.put("versionId",version.get().getId().toString());
    if("ACTIVATED".equals(version.get().getStatus())) {
      result.put("status","ALREADY_ACTIVATED").put("stage","COMPLETE");
      progress.accept(result.deepCopy());return result;
    }
    if(!"ACTIVE".equals(version.get().getStatus())) {
      result.put("status",version.get().getStatus()).put("stage","STOPPED_AT_VALIDATION");
      progress.accept(result.deepCopy());return result;
    }
    var definition=definitions.findAllEntities().stream().filter(j->code.equals(j.getCode())).findFirst()
        .orElseThrow(()->new IllegalArgumentException("Required builder job is not configured: "+code));
    result.put("stage","BUILDING"); progress.accept(result.deepCopy());
    var build=jobs.runVersion(definition.getId(),version.get().getId());
    result.set("build",json.valueToTree(build));
    String status=versions.findById(version.get().getId()).orElseThrow().getStatus();
    result.put("status",status).put("stage","COMPLETE"); progress.accept(result.deepCopy());
    return result;
  }
}

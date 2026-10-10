package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.dto.forecast.*;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import com.hethongdata.taichinh.service.forecast.*;
import com.hethongdata.taichinh.service.llm.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/forecasts")
public class FinancialForecastAdminController {
  private final FinancialForecastService service;
  private final ForecastContextService contexts;
  private final ForecastRunStore store;
  private final ForecastPromptService prompts;
  private final LlmGateway gateway;
  private final String adminToken;

  public FinancialForecastAdminController(
      FinancialForecastService service,
      ForecastContextService contexts,
      ForecastRunStore store,
      ForecastPromptService prompts,
      LlmGateway gateway,
      @Value("${financial.admin.api-token:}") String adminToken) {
    this.service = service;
    this.contexts = contexts;
    this.store = store;
    this.prompts = prompts;
    this.gateway = gateway;
    this.adminToken = adminToken;
  }

  @GetMapping("/configuration")
  public Configuration configuration() {
    String version;
    try {
      version = prompts.active().version();
    } catch (IllegalArgumentException e) {
      version = "NOT_SEEDED_OR_DISABLED";
    }
    return new Configuration(
        adminToken.length() >= 32,
        gateway.configured(),
        false,
        EnumSet.allOf(ForecastRequest.Target.class),
        ForecastPromptService.TASK,
        version);
  }

  @GetMapping("/securities")
  public List<SecurityOption> securities(@RequestParam(defaultValue = "100") int limit) {
    return contexts.securities(limit);
  }

  @GetMapping("/template")
  public LlmPromptCatalog.Template template() {
    return prompts.active();
  }

  @GetMapping("/templates")
  public List<TemplateSummary> templates() {
    return prompts.list();
  }

  @PostMapping("/template/seed")
  public SeedResponse seed() {
    return new SeedResponse(prompts.seed());
  }

  @PatchMapping("/template/{id}/enabled")
  public TemplateState enabled(
      @PathVariable UUID id, @Valid @RequestBody TemplateStateRequest request) {
    return prompts.enabled(id, request.enabled());
  }

  @PostMapping("/preview")
  public Preview preview(@Valid @RequestBody ForecastRequest request) {
    return service.preview(request);
  }

  @PostMapping("/execute")
  public Outcome execute(@Valid @RequestBody ForecastRequest request) {
    return service.execute(request);
  }

  @PostMapping("/metrics/recalculate")
  public MetricCalculation metrics(@Valid @RequestBody ForecastRequest request) {
    return contexts.recalculate(request);
  }

  @GetMapping("/securities/{securityId}/metrics")
  public List<StoredMetric> storedMetrics(
      @PathVariable UUID securityId, @RequestParam(defaultValue = "100") int limit) {
    return contexts.storedMetrics(securityId, limit);
  }

  @GetMapping("/securities/{securityId}/results")
  public List<Result> results(
      @PathVariable UUID securityId, @RequestParam(defaultValue = "10") int limit) {
    return store.results(securityId, limit);
  }

  @GetMapping("/securities/{securityId}/runs")
  public List<UUID> runs(
      @PathVariable UUID securityId, @RequestParam(defaultValue = "20") int limit) {
    return store.runs(securityId, limit);
  }

  @GetMapping("/runs/{runId}")
  public Run run(@PathVariable UUID runId) {
    return store.run(runId);
  }

  @PostMapping("/runs/{runId}/revalidate")
  public Outcome revalidate(@PathVariable UUID runId) {
    return store.validate(runId);
  }

  @PostMapping("/validation/pending")
  public List<Outcome> pending(@RequestParam(defaultValue = "5") int limit) {
    return service.pending(limit);
  }
}

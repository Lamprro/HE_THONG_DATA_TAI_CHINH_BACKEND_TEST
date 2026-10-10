package com.hethongdata.taichinh.service.forecast;

import com.hethongdata.taichinh.dto.forecast.*;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FinancialForecastService {
  private final ForecastContextService contexts;
  private final ForecastPromptService prompts;
  private final ForecastValidationService validation;
  private final ForecastRunStore store;
  private final LlmGateway gateway;
  private final LlmJson json;
  private final ChecksumService hashes;

  public FinancialForecastService(
      ForecastContextService contexts,
      ForecastPromptService prompts,
      ForecastValidationService validation,
      ForecastRunStore store,
      LlmGateway gateway,
      LlmJson json,
      ChecksumService hashes) {
    this.contexts = contexts;
    this.prompts = prompts;
    this.validation = validation;
    this.store = store;
    this.gateway = gateway;
    this.json = json;
    this.hashes = hashes;
  }

  public Preview preview(ForecastRequest request) {
    var ctx = contexts.build(request);
    var template = prompts.active();
    var issues = new ArrayList<>(ctx.issues());
    try {
      validation.fingerprint();
    } catch (IllegalStateException e) {
      issues.add(e.getMessage());
    }
    return new Preview(
        issues.isEmpty(),
        ctx.hash(),
        ctx.targetDate(),
        ctx.coverage(),
        List.copyOf(issues),
        ctx.input(),
        template.responseSchema());
  }

  /**
   * Forecast entry: contexts.build computes source ratios in Java, then prompts.active and
   * validation.fingerprint define the contract. claim -> gateway.call -> stage -> validate
   * persists a scenario only after source/evidence checks and Java projection arithmetic.
   * See docs/LLM_FLOW_DEV_BA.md, financial execution. SUCCESS does not measure forecast accuracy.
   */
  public Outcome execute(ForecastRequest request) {
    var ctx = contexts.build(request);
    if (!ctx.eligible()) return new Outcome("SKIPPED", null, null, ctx.issues());
    var template = prompts.active();
    String policy;
    try {
      policy = validation.fingerprint();
    } catch (IllegalStateException e) {
      return new Outcome("VALIDATION_CONFIGURATION_ERROR", null, null, List.of(e.getMessage()));
    }
    if (!gateway.configured())
      return new Outcome("CONFIGURATION_REQUIRED", null, null, List.of("Configure Gemini"));
    var payload = gateway.request(template, ctx.input());
    String hash =
        hashes.sha256(
            template.checksum()
                + "\n"
                + gateway.routingKey()
                + "\n"
                + policy
                + "\n"
                + json.canonical(payload));
    var claim =
        store.claim(ctx, template, hash, payload, gateway.model(), policy, gateway.routingKey());
    if (!"CLAIMED".equals(claim.status())) return claim;
    long start = System.nanoTime();
    LlmGateway.Reply reply;
    try {
      reply = gateway.call(payload, attempt -> store.attempt(claim.runId(), payload, attempt));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return store.fail(claim.runId(), "PROVIDER_INTERRUPTED");
    } catch (Exception e) {
      return store.fail(
          claim.runId(), "PROVIDER_TRANSPORT_OR_AUDIT_ERROR:" + e.getClass().getSimpleName());
    }
    if (!store.stage(
        claim.runId(),
        reply,
        (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000)))
      return new Outcome("LEASE_LOST", claim.runId(), null, List.of());
    return store.validate(claim.runId());
  }

  public List<Outcome> pending(int limit) {
    var results = new ArrayList<Outcome>();
    for (var run : store.pending(limit)) {
      try {
        results.add(store.validate(run));
      } catch (Exception e) {
        results.add(
            new Outcome("RECOVERY_ERROR", run, null, List.of(e.getClass().getSimpleName())));
      }
    }
    return List.copyOf(results);
  }
}

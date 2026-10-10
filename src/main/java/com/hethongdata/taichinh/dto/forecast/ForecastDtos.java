package com.hethongdata.taichinh.dto.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Explicit admin contracts. JSON is reserved for snapshots and versioned model output. */
public final class ForecastDtos {
  private ForecastDtos() {}

  public record SourcePoint(
      String id,
      String domain,
      String code,
      BigDecimal value,
      String unit,
      LocalDate periodStart,
      LocalDate periodEnd,
      String periodType,
      String scope,
      String availableAt) {}

  public record DerivedMetric(
      String code, BigDecimal value, String unit, String formula, List<String> sourcePointIds) {}

  public record Coverage(
      int financialPeriods, int marketSessions, int macroObservations, List<String> limitations) {}

  public record Preview(
      boolean eligible,
      String snapshotHash,
      LocalDate forecastPeriodEnd,
      Coverage coverage,
      List<String> issues,
      JsonNode input,
      JsonNode responseSchema) {}

  public record Result(
      UUID id,
      UUID runId,
      UUID companyId,
      UUID securityId,
      LocalDate asOfDate,
      String qualityStatus,
      JsonNode data) {}

  public record Outcome(String status, UUID runId, UUID resultId, List<String> issues) {}

  public record Run(
      UUID id,
      String status,
      String taskCode,
      UUID companyId,
      UUID securityId,
      String provider,
      String modelName,
      Integer httpStatus,
      Integer inputTokens,
      Integer outputTokens,
      Integer latencyMs,
      String errorMessage,
      java.time.Instant createdAt,
      JsonNode request,
      JsonNode response,
      List<JsonNode> attempts,
      List<JsonNode> validations,
      List<String> errors,
      UUID validationRoundId) {}

  public record MetricCalculation(
      ForecastRequest request,
      List<DerivedMetric> metrics,
      List<String> limitations,
      int inserted) {}

  public record Configuration(
      boolean adminTokenConfigured,
      boolean providerConfigured,
      boolean schedulerEnabled,
      Set<ForecastRequest.Target> supportedTargets,
      String taskCode,
      String templateVersion) {}

  public record SecurityOption(
      UUID securityId, UUID companyId, String symbol, String companyName) {}

  public record SeedResponse(int inserted) {}

  public record TemplateStateRequest(@jakarta.validation.constraints.NotNull Boolean enabled) {}

  public record TemplateState(UUID id, boolean enabled) {}

  public record TemplateSummary(
      UUID id, String version, boolean enabled, String checksum, String description) {}

  public record StoredMetric(
      UUID id,
      String code,
      BigDecimal value,
      String unit,
      LocalDate asOfDate,
      boolean derived,
      boolean canonical,
      String qualityStatus,
      String calculationVersion,
      JsonNode provenance) {}
}

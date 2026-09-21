package com.hethongdata.taichinh.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.validation.ValidationExecutionResponse;
import com.hethongdata.taichinh.entity.enums.IngestionRunStatus;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ValidationJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationJobService.class);

    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunJpaRepository ingestionRuns;
    private final ValidationRuleJpaRepository rules;
    private final ValidationResultJpaRepository results;
    private final DataVersionJpaRepository versions;
    private final ChecksumService checksums;
    private final ValidationRuleExecutionService ruleExecutor;

    public ValidationJobService(
            RawPayloadJpaRepository rawPayloads,
            IngestionRunJpaRepository ingestionRuns,
            ValidationRuleJpaRepository rules,
            ValidationResultJpaRepository results,
            DataVersionJpaRepository versions,
            ChecksumService checksums,
            ValidationRuleExecutionService ruleExecutor) {
        this.rawPayloads = rawPayloads;
        this.ingestionRuns = ingestionRuns;
        this.rules = rules;
        this.results = results;
        this.versions = versions;
        this.checksums = checksums;
        this.ruleExecutor = ruleExecutor;
    }

    @Transactional
    public ValidationExecutionResponse validate(UUID rawPayloadId) {
        RawPayloadEntity raw =
                rawPayloads
                        .findById(rawPayloadId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Raw payload was not found"));
        if (results.existsByRawPayloadId(rawPayloadId)) {
            LOGGER.debug("Payload {} has already been validated, returning existing result", rawPayloadId);
            return existing(raw);
        }

        LOGGER.info("Starting payload validation: rawPayloadId={}, entityType={}, key={}",
                rawPayloadId, raw.getEntityType(), raw.getExternalKey());

        int passed = 0, failed = 0, skipped = 0;
        boolean blockingFailure = false;
        boolean duplicate = false;
        for (ValidationRuleEntity rule : rules.findByIsActiveTrueOrderByIdAsc()) {
            if (!applies(rule, raw)) continue;
            ValidationRuleExecutionService.Outcome outcome = ruleExecutor.execute(rule, raw);
            results.save(
                    ValidationResultEntity.create(
                            rule.getId(),
                            rule.getCode(),
                            rule.getSeverity(),
                            raw.getIngestionRun().getId(),
                            raw.getId(),
                            outcome.status(),
                            outcome.observed(),
                            outcome.expected(),
                            outcome.message()));
            if ("PASS".equals(outcome.status())) passed++;
            else if ("SKIP".equals(outcome.status())) skipped++;
            else {
                failed++;
                boolean critical = "CRITICAL".equals(rule.getSeverity());
                blockingFailure |= critical || "ERROR".equals(rule.getSeverity());
                duplicate |= "NEWS_DUPLICATE_HASH".equals(rule.getCode());

                LOGGER.warn(
                        "Validation rule {} failed for rawPayloadId={}: severity={}, observed='{}', expected='{}', message='{}'",
                        rule.getCode(),
                        raw.getId(),
                        rule.getSeverity(),
                        outcome.observed(),
                        outcome.expected(),
                        outcome.message());
            }
        }
        UUID versionId = finalizeIngestionRun(raw.getIngestionRun().getId());
        String finalStatus =
                duplicate
                        ? "DUPLICATE"
                        : blockingFailure ? "REJECTED" : versionId == null ? "VALIDATED" : "ACCEPTED";
        LOGGER.info(
                "Completed validation for rawPayloadId={}: status={}, passed={}, failed={}, skipped={}, versionId={}",
                raw.getId(),
                finalStatus,
                passed,
                failed,
                skipped,
                versionId);

        return new ValidationExecutionResponse(
                raw.getId(),
                raw.getIngestionRun().getId(),
                finalStatus,
                passed,
                failed,
                skipped,
                versionId,
                false);
    }

    @Transactional
    public List<ValidationExecutionResponse> validatePending(int limit) {
        var unvalidated = rawPayloads.findUnvalidated(PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
        LOGGER.info("Executing batch validation for {} pending payloads (requested limit={})", unvalidated.size(), limit);
        List<ValidationExecutionResponse> list = unvalidated.stream()
                .map(raw -> validate(raw.getId()))
                .toList();
        LOGGER.info("Batch validation completed: processed {} payloads", list.size());
        return list;
    }

    private ValidationExecutionResponse existing(RawPayloadEntity raw) {
        var existing = results.findByRawPayloadIdOrderByCheckedAtAsc(raw.getId());
        int pass = 0, fail = 0, skip = 0;
        for (var result : existing) {
            if ("PASS".equals(result.getStatus())) pass++;
            else if ("FAIL".equals(result.getStatus())) fail++;
            else skip++;
        }
        var version =
                versions.findByIngestionRunId(raw.getIngestionRun().getId())
                        .map(DataVersionEntity::getId)
                        .orElse(null);
        boolean blockingFailure =
                existing.stream()
                        .anyMatch(
                                result ->
                                        "FAIL".equals(result.getStatus())
                                                && ("ERROR".equals(result.getSeverity())
                                                        || "CRITICAL".equals(result.getSeverity())));
        boolean duplicate =
                existing.stream()
                        .anyMatch(
                                result ->
                                        "FAIL".equals(result.getStatus())
                                                && "NEWS_DUPLICATE_HASH".equals(result.getRuleCode()));
        return new ValidationExecutionResponse(
                raw.getId(),
                raw.getIngestionRun().getId(),
                duplicate
                        ? "DUPLICATE"
                        : blockingFailure ? "REJECTED" : version == null ? "VALIDATED" : "ACCEPTED",
                pass,
                fail,
                skip,
                version,
                true);
    }

    /**
     * Creates one version only after every raw payload in a completed ingestion run has been
     * validated and no run-blocking result exists. The run lock prevents concurrent validation
     * workers from creating two versions for the same run.
     */
    private UUID finalizeIngestionRun(UUID ingestionRunId) {
        IngestionRunEntity run =
                ingestionRuns
                        .findByIdForUpdate(ingestionRunId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Ingestion run was not found during validation"));
        var existingVersion = versions.findByIngestionRunId(ingestionRunId);
        if (existingVersion.isPresent()) return existingVersion.get().getId();

        if (run.getStatus() != IngestionRunStatus.SUCCESS) {
            LOGGER.info(
                    "Data version deferred for ingestionRunId={}: ingestion status is {}",
                    ingestionRunId,
                    run.getStatus());
            return null;
        }

        long rawPayloadCount = rawPayloads.countByIngestionRunId(ingestionRunId);
        long validatedPayloadCount =
                results.countValidatedRawPayloadsByIngestionRunId(ingestionRunId);
        if (rawPayloadCount == 0 || validatedPayloadCount != rawPayloadCount) {
            LOGGER.info(
                    "Data version deferred for ingestionRunId={}: validatedRawPayloads={}/{}",
                    ingestionRunId,
                    validatedPayloadCount,
                    rawPayloadCount);
            return null;
        }

        boolean blockingFailure =
                results.existsByIngestionRunIdAndStatusAndSeverityIn(
                                ingestionRunId, "FAIL", List.of("ERROR", "CRITICAL"))
                        || results.existsByIngestionRunIdAndStatusAndRuleCode(
                                ingestionRunId, "FAIL", "NEWS_DUPLICATE_HASH");
        if (blockingFailure) {
            LOGGER.warn(
                    "Data version rejected for ingestionRunId={}: a blocking validation result exists",
                    ingestionRunId);
            return null;
        }

        List<RawPayloadEntity> runPayloads =
                rawPayloads.findByIngestionRunIdOrderByFetchedAtDesc(ingestionRunId);
        if (runPayloads.size() != rawPayloadCount) {
            throw new IllegalStateException(
                    "Raw payload count changed while finalizing ingestion run " + ingestionRunId);
        }

        String dataDomain =
                run.getIngestionJob() != null && run.getIngestionJob().getDatasetType() != null
                        ? run.getIngestionJob().getDatasetType()
                        : runPayloads.stream().map(this::domain).distinct().count() == 1
                                ? domain(runPayloads.getFirst())
                                : "OTHER";
        String checksumInput =
                runPayloads.stream()
                        .sorted(Comparator.comparing(RawPayloadEntity::getId))
                        .map(payload -> payload.getId() + ":" + payload.getChecksumSha256())
                        .collect(Collectors.joining("\n"));
        DataVersionEntity version =
                versions.save(
                        DataVersionEntity.acceptedForRun(
                                dataDomain,
                                ingestionRunId,
                                rawPayloadCount,
                                checksums.sha256(checksumInput)));
        LOGGER.info(
                "Data version {} accepted for ingestionRunId={}: rawPayloadCount={}, domain={}",
                version.getId(),
                ingestionRunId,
                rawPayloadCount,
                version.getDataDomain());
        return version.getId();
    }

    private boolean applies(ValidationRuleEntity rule, RawPayloadEntity raw) {
        return switch (rule.getExecutorKey()) {
            case "RAW_ERROR_MESSAGE" -> true;
            case "RAW_ENVELOPE_REQUIRED", "DATA_COUNT_MATCH" -> hasDataEnvelope(raw.getPayload());
            default -> rule.getDataDomain().equals(domain(raw));
        };
    }

    private String domain(RawPayloadEntity raw) {
        return switch (raw.getEntityType().toUpperCase(Locale.ROOT)) {
            case "QUOTE", "OHLCV" -> "MARKET_PRICE";
            case "RATIO" -> "FINANCIAL_METRIC";
            case "INDEX_OHLCV", "INDEX_LATEST", "INDEX_MEMBERS" -> "MARKET_INDEX";
            case "FINANCIAL_STATEMENT" -> "FINANCIAL_STATEMENT";
            case "NEWS", "NEWS_COMPANY" -> "NEWS";
            case "NEWS_DATA" -> "NEWS_DATA";
            default -> "RAW";
        };
    }

    private boolean hasDataEnvelope(JsonNode payload) {
        return payload != null && payload.isObject() && payload.hasNonNull("data");
    }

}

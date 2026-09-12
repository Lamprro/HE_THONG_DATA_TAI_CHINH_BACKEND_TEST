package com.hethongdata.taichinh.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hethongdata.taichinh.dto.validation.ValidationExecutionResponse;
import com.hethongdata.taichinh.entity.enums.IngestionRunStatus;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class ValidationJobServiceTests {

    @Mock private RawPayloadJpaRepository rawPayloads;
    @Mock private IngestionRunJpaRepository ingestionRuns;
    @Mock private ValidationRuleJpaRepository rules;
    @Mock private ValidationResultJpaRepository results;
    @Mock private DataVersionJpaRepository versions;
    @Mock private ValidationRuleExecutionService ruleExecutor;

    private ValidationJobService service;

    @BeforeEach
    void setUp() {
        service =
                new ValidationJobService(
                        rawPayloads,
                        ingestionRuns,
                        rules,
                        results,
                        versions,
                        new ChecksumService(),
                        ruleExecutor);
    }

    @Test
    void createsDataVersionOnlyAfterEveryRawPayloadInRunIsValidated() {
        UUID runId = UUID.randomUUID();
        UUID firstRawId = UUID.randomUUID();
        UUID secondRawId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        IngestionRunEntity run = successfulRun(runId);
        RawPayloadEntity firstRaw = rawPayload(firstRawId, run, "first-checksum");
        RawPayloadEntity secondRaw = rawPayload(secondRawId, run, "second-checksum");
        ValidationRuleEntity rule = passingRawRule();
        DataVersionEntity savedVersion = org.mockito.Mockito.mock(DataVersionEntity.class);

        when(rawPayloads.findById(firstRawId)).thenReturn(Optional.of(firstRaw));
        when(rawPayloads.findById(secondRawId)).thenReturn(Optional.of(secondRaw));
        when(rawPayloads.countByIngestionRunId(runId)).thenReturn(2L);
        when(rawPayloads.findByIngestionRunIdOrderByFetchedAtDesc(runId))
                .thenReturn(List.of(firstRaw, secondRaw));
        when(ingestionRuns.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
        when(rules.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(ruleExecutor.execute(rule, firstRaw))
                .thenReturn(new ValidationRuleExecutionService.Outcome("PASS", null, null, "pass"));
        when(ruleExecutor.execute(rule, secondRaw))
                .thenReturn(new ValidationRuleExecutionService.Outcome("PASS", null, null, "pass"));
        when(results.countValidatedRawPayloadsByIngestionRunId(runId)).thenReturn(1L, 2L);
        when(results.existsByIngestionRunIdAndStatusAndSeverityIn(
                        eq(runId), eq("FAIL"), any()))
                .thenReturn(false);
        when(results.existsByIngestionRunIdAndStatusAndRuleCode(
                        runId, "FAIL", "NEWS_DUPLICATE_HASH"))
                .thenReturn(false);
        when(versions.findByIngestionRunId(runId)).thenReturn(Optional.empty());
        when(versions.save(any(DataVersionEntity.class))).thenReturn(savedVersion);
        when(savedVersion.getId()).thenReturn(versionId);

        ValidationExecutionResponse first = service.validate(firstRawId);

        assertThat(first.getStatus()).isEqualTo("VALIDATED");
        assertThat(first.getDataVersionId()).isNull();
        verify(versions, never()).save(any(DataVersionEntity.class));

        ValidationExecutionResponse second = service.validate(secondRawId);

        assertThat(second.getStatus()).isEqualTo("ACCEPTED");
        assertThat(second.getDataVersionId()).isEqualTo(versionId);
        ArgumentCaptor<DataVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(DataVersionEntity.class);
        verify(versions).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getIngestionRunId()).isEqualTo(runId);
        assertThat(versionCaptor.getValue().getDataDomain()).isEqualTo("MARKET_PRICE");
        assertThat(versionCaptor.getValue().getRowCount()).isEqualTo(2L);
        assertThat(versionCaptor.getValue().getChecksumSha256()).hasSize(64);
    }

    @Test
    void doesNotCreateDataVersionWhenAnyRawPayloadHasBlockingFailure() {
        UUID runId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        IngestionRunEntity run = successfulRun(runId);
        RawPayloadEntity raw = rawPayload(rawId, run, "checksum");
        ValidationRuleEntity rule = passingRawRule();

        when(rawPayloads.findById(rawId)).thenReturn(Optional.of(raw));
        when(rawPayloads.countByIngestionRunId(runId)).thenReturn(1L);
        when(ingestionRuns.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
        when(rules.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(rule));
        when(ruleExecutor.execute(rule, raw))
                .thenReturn(new ValidationRuleExecutionService.Outcome("PASS", null, null, "pass"));
        when(results.countValidatedRawPayloadsByIngestionRunId(runId)).thenReturn(1L);
        when(results.existsByIngestionRunIdAndStatusAndSeverityIn(
                        eq(runId), eq("FAIL"), any()))
                .thenReturn(true);
        when(versions.findByIngestionRunId(runId)).thenReturn(Optional.empty());

        ValidationExecutionResponse response = service.validate(rawId);

        assertThat(response.getStatus()).isEqualTo("VALIDATED");
        assertThat(response.getDataVersionId()).isNull();
        verify(versions, never()).save(any(DataVersionEntity.class));
    }

    private IngestionRunEntity successfulRun(UUID runId) {
        IngestionRunEntity run = org.mockito.Mockito.mock(IngestionRunEntity.class);
        when(run.getId()).thenReturn(runId);
        when(run.getStatus()).thenReturn(IngestionRunStatus.SUCCESS);
        return run;
    }

    private RawPayloadEntity rawPayload(UUID rawId, IngestionRunEntity run, String checksum) {
        RawPayloadEntity raw = org.mockito.Mockito.mock(RawPayloadEntity.class);
        when(raw.getId()).thenReturn(rawId);
        when(raw.getIngestionRun()).thenReturn(run);
        when(raw.getEntityType()).thenReturn("QUOTE");
        when(raw.getExternalKey()).thenReturn(rawId.toString());
        lenient().when(raw.getChecksumSha256()).thenReturn(checksum);
        return raw;
    }

    private ValidationRuleEntity passingRawRule() {
        ValidationRuleEntity rule = org.mockito.Mockito.mock(ValidationRuleEntity.class);
        when(rule.getId()).thenReturn(1L);
        when(rule.getCode()).thenReturn("RAW_ERROR_MESSAGE");
        when(rule.getSeverity()).thenReturn("CRITICAL");
        when(rule.getExecutorKey()).thenReturn("RAW_ERROR_MESSAGE");
        return rule;
    }

    @Test
    void newsDataUsesItsOwnRulesAndBlocksVersionOnSourceHttpFailure() throws Exception {
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        IngestionRunEntity run = successfulRun(runId);
        RawPayloadEntity raw = rawPayload(id, run, "checksum");
        when(raw.getEntityType()).thenReturn("NEWS_DATA");
        var httpRule = NewsValidationRulesTests.rule("NEWS_DATA_HTTP_SUCCESS");
        var titleRule = NewsValidationRulesTests.rule("NEWS_TITLE_REQUIRED");
        when(rawPayloads.findById(id)).thenReturn(Optional.of(raw));
        when(rules.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(titleRule, httpRule));
        when(ruleExecutor.execute(httpRule, raw)).thenReturn(
                new ValidationRuleExecutionService.Outcome("FAIL", "http_status=404", "200..299", "Page not found"));
        when(ingestionRuns.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
        when(rawPayloads.countByIngestionRunId(runId)).thenReturn(1L);
        when(results.countValidatedRawPayloadsByIngestionRunId(runId)).thenReturn(1L);
        when(results.existsByIngestionRunIdAndStatusAndSeverityIn(eq(runId), eq("FAIL"), any())).thenReturn(true);
        var response = service.validate(id);
        assertThat(response.getStatus()).isEqualTo("REJECTED");
        verify(ruleExecutor, never()).execute(titleRule, raw);
        verify(versions, never()).save(any());
        var saved = ArgumentCaptor.forClass(com.hethongdata.taichinh.entity.validation.ValidationResultEntity.class);
        verify(results).save(saved.capture());
        assertThat(saved.getValue().getRuleCode()).isEqualTo("NEWS_DATA_HTTP_SUCCESS");
        assertThat(saved.getValue().getStatus()).isEqualTo("FAIL");
    }
}

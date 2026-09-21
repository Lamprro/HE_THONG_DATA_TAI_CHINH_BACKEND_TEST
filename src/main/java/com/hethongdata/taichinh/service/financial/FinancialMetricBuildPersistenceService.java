package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.FinancialMetricEntity;
import com.hethongdata.taichinh.entity.MetricDefinitionEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.financial.FinancialMetricJpaRepository;
import com.hethongdata.taichinh.repository.jpa.financial.MetricDefinitionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FinancialMetricBuildPersistenceService {
    private final DataVersionJpaRepository versions; private final FinancialMetricJpaRepository metrics;
    private final MetricDefinitionJpaRepository definitions; private final SecurityJpaRepository securities;
    private final IngestionRunJpaRepository runs; private final ObjectMapper objectMapper;
    public FinancialMetricBuildPersistenceService(DataVersionJpaRepository versions, FinancialMetricJpaRepository metrics,
            MetricDefinitionJpaRepository definitions, SecurityJpaRepository securities, IngestionRunJpaRepository runs, ObjectMapper objectMapper) {
        this.versions = versions; this.metrics = metrics; this.definitions = definitions; this.securities = securities; this.runs = runs; this.objectMapper = objectMapper;
    }
    @Transactional
    public Counts persist(UUID versionId, IngestionRunEntity buildRun, List<RawPayloadEntity> payloads, FinancialMetricPayloadParser parser) {
        DataVersionEntity version = versions.findByIdForUpdate(versionId).orElseThrow();
        if (!"FINANCIAL_METRIC".equals(version.getDataDomain()) || !"ACTIVE".equals(version.getStatus())) throw new IllegalStateException("Version is no longer ACTIVE FINANCIAL_METRIC");
        int inserted = 0, updated = 0, skipped = 0, fetched = 0;
        for (RawPayloadEntity payload : payloads) for (FinancialMetricPayloadParser.ProviderMetricDraft draft : parser.parse(payload)) {
            fetched++;
            MetricDefinitionEntity definition = definitions.findByCode(draft.code()).orElse(null);
            if (definition == null) { skipped++; continue; }
            SecurityEntity security = payload.getSecurityId() == null ? securities.findBySymbolIgnoreCase(draft.symbol()).orElse(null) : securities.findById(payload.getSecurityId()).orElse(null);
            if (security == null || security.getCompanyId() == null) throw new IllegalStateException("No company/security for ratio symbol " + draft.symbol());
            List<FinancialMetricEntity> existing = metrics.findProviderIdentity(security.getCompanyId(), security.getId(), definition.getId(), draft.asOfDate(), payload.getDataSource().getId());
            if (existing.isEmpty()) { metrics.save(FinancialMetricEntity.provider(security.getCompanyId(), security.getId(), definition.getId(), draft.asOfDate(), draft.value(), payload.getDataSource().getId(), payload.getId(), versionId)); inserted++; }
            else { FinancialMetricEntity current = existing.getFirst(); if (current.getValue().compareTo(draft.value()) == 0) skipped++; else { current.correctProvider(draft.value(), payload.getId(), versionId); updated++; } }
        }
        Counts counts = new Counts(fetched, inserted, updated, skipped);
        buildRun.markWorkflowSuccess(objectMapper.valueToTree(Map.of("workflow", FinancialMetricBuildService.WORKFLOW, "sourceVersionId", versionId, "skipped", skipped)), Instant.now(), fetched, inserted, updated);
        runs.save(buildRun); version.markActivated(); versions.save(version); return counts;
    }
    @Transactional public void noWork(IngestionRunEntity run) { run.markWorkflowSuccess(objectMapper.valueToTree(Map.of("workflow", FinancialMetricBuildService.WORKFLOW, "noWork", true)), Instant.now(), 0, 0, 0); runs.save(run); }
    public record Counts(int fetched, int inserted, int updated, int skipped) {}
}

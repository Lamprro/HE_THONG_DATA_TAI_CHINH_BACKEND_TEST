package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import org.springframework.stereotype.Service;
import java.util.List; import java.util.UUID;

/** Materializes validated provider RATIO payloads only; it never fetches or validates. */
@Service public class FinancialMetricBuildService {
    public static final String WORKFLOW = "FINANCIAL_METRIC_BUILD";
    private final DataVersionJpaRepository versions; private final RawPayloadJpaRepository rawPayloads; private final IngestionRunRepository runs;
    private final FinancialMetricBuildPersistenceService writes; private final FinancialMetricPayloadParser parser; private final DataVersionLifecycleService lifecycle;
    public FinancialMetricBuildService(DataVersionJpaRepository versions, RawPayloadJpaRepository rawPayloads, IngestionRunRepository runs, FinancialMetricBuildPersistenceService writes, FinancialMetricPayloadParser parser, DataVersionLifecycleService lifecycle) { this.versions=versions; this.rawPayloads=rawPayloads; this.runs=runs; this.writes=writes; this.parser=parser; this.lifecycle=lifecycle; }
    public boolean supports(String code) { return WORKFLOW.equals(code); }
    public IngestionExecutionResponse execute(IngestionJobEntity job, String trigger) {
        List<DataVersionEntity> candidates = versions.findByDataDomainAndStatusOrderByCreatedAtAsc("FINANCIAL_METRIC", "ACTIVE");
        if (candidates.isEmpty()) { IngestionRunEntity run = runs.startInternalBatch(job.getDataSource(), job, trigger, WORKFLOW); writes.noWork(run); return response(run.getId(), 0); }
        UUID last = null; int rejected = 0;
        for (DataVersionEntity version : candidates) { IngestionRunEntity run = runs.startInternalBatch(job.getDataSource(), job, trigger, WORKFLOW); last = run.getId();
            try { List<RawPayloadEntity> payloads = rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(version.getIngestionRunId(), "RATIO"); if (payloads.isEmpty()) throw new IllegalStateException("FINANCIAL_METRIC version has no RATIO payloads: " + version.getId()); writes.persist(version.getId(), run, payloads, parser); }
            catch (RuntimeException e) { runs.markFailed(run, ExternalErrorCategory.PROTOCOL.name(), null, WORKFLOW + " failed for version " + version.getId()); lifecycle.rejectBuildFailure(version.getId(), WORKFLOW, e); rejected++; }
        } return response(last, rejected);
    }
    private static IngestionExecutionResponse response(UUID id, int rejected) { return new IngestionExecutionResponse(id, null, rejected == 0 ? "SUCCESS" : "COMPLETED_WITH_REJECTIONS", 200, "application/json", null, false); }
}

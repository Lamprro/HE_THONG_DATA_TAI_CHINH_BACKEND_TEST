package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Dispatches validated index batches to their matching internal build workflow. */
@Service
public class MarketIndexWorkflowService {
    public static final String PRICE_BUILD = "INDEX_PRICE_BUILD";
    public static final String MEMBERSHIP_BUILD = "INDEX_MEMBERSHIP_BUILD";
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunRepository runs;
    private final MarketIndexWorkflowPersistenceService writes;
    private final DataVersionLifecycleService lifecycle;

    public MarketIndexWorkflowService(DataVersionJpaRepository versions,
            RawPayloadJpaRepository rawPayloads, IngestionRunRepository runs,
            MarketIndexWorkflowPersistenceService writes,
            DataVersionLifecycleService lifecycle) {
        this.versions = versions;
        this.rawPayloads = rawPayloads;
        this.runs = runs;
        this.writes = writes;
        this.lifecycle = lifecycle;
    }

    public boolean supports(String jobCode) {
        return PRICE_BUILD.equals(jobCode) || MEMBERSHIP_BUILD.equals(jobCode);
    }

    public IngestionExecutionResponse execute(IngestionJobEntity job, String triggerType) {
        if (!supports(job.getCode())) throw new IllegalArgumentException("Workflow index không được hỗ trợ");
        String workflow = job.getCode();
        String rawType = PRICE_BUILD.equals(workflow) ? "INDEX_OHLCV" : "INDEX_MEMBERS";
        List<DataVersionEntity> candidates = versions.findByDataDomainAndStatusOrderByCreatedAtAsc(
                "MARKET_INDEX", "ACTIVE").stream()
                .filter(version -> !rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                        version.getIngestionRunId(), rawType).isEmpty())
                .toList();
        if (candidates.isEmpty()) {
            IngestionRunEntity run = runs.startInternalBatch(job.getDataSource(), job, triggerType, workflow);
            writes.noWork(run, workflow);
            return response(run.getId());
        }
        UUID lastRunId = null;
        int rejected = 0;
        for (DataVersionEntity version : candidates) {
            IngestionRunEntity run = runs.startInternalBatch(job.getDataSource(), job, triggerType, workflow);
            lastRunId = run.getId();
            try {
                if (PRICE_BUILD.equals(workflow)) writes.buildPrices(version.getId(), run);
                else writes.buildMemberships(version.getId(), run);
            } catch (RuntimeException exception) {
                runs.markFailed(run, ExternalErrorCategory.PROTOCOL.name(), null,
                        workflow + " failed for version " + version.getId());
                lifecycle.rejectBuildFailure(version.getId(), workflow, exception);
                rejected++;
            }
        }
        return response(lastRunId, rejected);
    }

    private IngestionExecutionResponse response(UUID runId) {
        return response(runId, 0);
    }

    private IngestionExecutionResponse response(UUID runId, int rejected) {
        return new IngestionExecutionResponse(runId, null,
                rejected == 0 ? "SUCCESS" : "COMPLETED_WITH_REJECTIONS", 200,
                "application/json", null, false);
    }
}

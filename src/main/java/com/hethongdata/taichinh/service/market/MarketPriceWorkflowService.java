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

/** Consumes accepted market-price versions and materializes canonical price rows. */
@Service
public class MarketPriceWorkflowService {
    public static final String WORKFLOW = "MARKET_PRICE_BUILD";
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunRepository runs;
    private final MarketPriceWorkflowPersistenceService writes;
    private final DataVersionLifecycleService lifecycle;

    public MarketPriceWorkflowService(DataVersionJpaRepository versions,
            RawPayloadJpaRepository rawPayloads, IngestionRunRepository runs,
            MarketPriceWorkflowPersistenceService writes,
            DataVersionLifecycleService lifecycle) {
        this.versions = versions;
        this.rawPayloads = rawPayloads;
        this.runs = runs;
        this.writes = writes;
        this.lifecycle = lifecycle;
    }

    public boolean supports(String code) { return WORKFLOW.equals(code); }

    public IngestionExecutionResponse execute(IngestionJobEntity job, String triggerType) {
        if (!supports(job.getCode())) throw new IllegalArgumentException("Workflow market price không được hỗ trợ");
        List<DataVersionEntity> candidates = versions.findByDataDomainAndStatusOrderByCreatedAtAsc(
                "MARKET_PRICE", "ACTIVE").stream().filter(this::containsPricePayload).toList();
        if (candidates.isEmpty()) {
            IngestionRunEntity run = runs.startInternalBatch(job.getDataSource(), job, triggerType, WORKFLOW);
            writes.noWork(run);
            return response(run.getId());
        }
        UUID lastRunId = null;
        int rejected = 0;
        for (DataVersionEntity version : candidates) {
            IngestionRunEntity run = runs.startInternalBatch(job.getDataSource(), job, triggerType, WORKFLOW);
            lastRunId = run.getId();
            try {
                writes.build(version.getId(), run);
            } catch (RuntimeException exception) {
                runs.markFailed(run, ExternalErrorCategory.PROTOCOL.name(), null,
                        WORKFLOW + " failed for version " + version.getId());
                lifecycle.rejectBuildFailure(version.getId(), WORKFLOW, exception);
                rejected++;
            }
        }
        return response(lastRunId, rejected);
    }

    private boolean containsPricePayload(DataVersionEntity version) {
        return rawPayloads.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId())
                .stream().anyMatch(raw -> "QUOTE".equalsIgnoreCase(raw.getEntityType())
                        || "OHLCV".equalsIgnoreCase(raw.getEntityType()));
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

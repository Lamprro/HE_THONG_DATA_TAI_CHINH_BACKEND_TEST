package com.hethongdata.taichinh.service.macro;

import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import com.hethongdata.taichinh.service.validation.*;

import org.springframework.stereotype.Service;

import java.util.Set;

/** Scheduler/manual entry uses the same adapter, raw validation and data-version lifecycle. */
@Service
public class MacroWorkflowService {
    public static final Set<String> JOBS =
            Set.of("MACRO_VN_WORLDBANK_QUARTERLY", "MACRO_VN_BIS_QUARTERLY");
    private final IngestionService ingestion;
    private final ValidationJobService validation;
    private final MacroWorkflowPersistenceService writes;
    private final IngestionRunRepository runs;
    private final DataVersionLifecycleService lifecycle;

    public MacroWorkflowService(
            IngestionService ingestion,
            ValidationJobService validation,
            MacroWorkflowPersistenceService writes,
            IngestionRunRepository runs,
            DataVersionLifecycleService lifecycle) {
        this.ingestion = ingestion;
        this.validation = validation;
        this.writes = writes;
        this.runs = runs;
        this.lifecycle = lifecycle;
    }

    public boolean supports(String jobCode) {
        return JOBS.contains(jobCode);
    }

    public IngestionExecutionResponse execute(IngestionJobEntity job, String triggerType) {
        if (!supports(job.getCode()))
            throw new IllegalArgumentException("Unsupported macro workflow");
        var fetched = ingestion.ingestJob(job, triggerType);
        var checked = validation.validate(fetched.getRawPayloadId());
        if (checked.getFailed() > 0 || "REJECTED".equals(checked.getStatus()))
            return new IngestionExecutionResponse(
                    fetched.getRunId(),
                    fetched.getRawPayloadId(),
                    "REJECTED",
                    fetched.getUpstreamStatus(),
                    fetched.getContentType(),
                    fetched.getChecksumSha256(),
                    fetched.isDuplicateChecksum());
        if (checked.getDataVersionId() == null)
            return new IngestionExecutionResponse(
                    fetched.getRunId(),
                    fetched.getRawPayloadId(),
                    "REJECTED",
                    fetched.getUpstreamStatus(),
                    fetched.getContentType(),
                    fetched.getChecksumSha256(),
                    fetched.isDuplicateChecksum());
        var run = runs.startInternalBatch(job.getDataSource(), job, triggerType, "MACRO_BUILD");
        try {
            var counts = writes.build(checked.getDataVersionId(), run);
            return new IngestionExecutionResponse(
                    run.getId(),
                    fetched.getRawPayloadId(),
                    counts.get("inserted") + counts.get("updated") > 0 ? "SUCCESS" : "NO_CHANGE",
                    200,
                    "application/json",
                    fetched.getChecksumSha256(),
                    counts.get("inserted") + counts.get("updated") == 0);
        } catch (RuntimeException e) {
            runs.markFailed(run, "PROTOCOL", null, "Macro build failed: " + e.getMessage());
            lifecycle.rejectBuildFailure(checked.getDataVersionId(), "MACRO_BUILD", e);
            throw e;
        }
    }
}

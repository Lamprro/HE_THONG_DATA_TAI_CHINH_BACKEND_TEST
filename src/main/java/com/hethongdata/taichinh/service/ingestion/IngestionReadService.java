package com.hethongdata.taichinh.service.ingestion;

import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.dto.ingestion.IngestionRunResponse;
import com.hethongdata.taichinh.dto.ingestion.RawPayloadResponse;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.ingestion.RawPayloadRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionReadService {
    private final IngestionRunRepository ingestionRuns;
    private final RawPayloadRepository rawPayloads;
    public IngestionReadService(IngestionRunRepository ingestionRuns, RawPayloadRepository rawPayloads) {
        this.ingestionRuns = ingestionRuns; this.rawPayloads = rawPayloads;
    }
    @Transactional(readOnly = true)
    public Optional<IngestionRunResponse> findRun(UUID id) { return ingestionRuns.findById(id).map(IngestionRunResponse::from); }
    @Transactional(readOnly = true)
    /** Bounds list requests to prevent an accidental unbounded database read. */
    public List<IngestionRunResponse> latestRuns(int limit) { return ingestionRuns.findLatest(AppParams.pageLimit(limit)).stream().map(IngestionRunResponse::from).toList(); }
    @Transactional(readOnly = true)
    public Optional<RawPayloadResponse> findPayload(UUID id, boolean includeBody) { return rawPayloads.findById(id).map(entity -> RawPayloadResponse.from(entity, includeBody)); }
    @Transactional(readOnly = true)
    public List<RawPayloadResponse> findPayloadsByRun(UUID runId, boolean includeBody) { return rawPayloads.findByRunId(runId).stream().map(entity -> RawPayloadResponse.from(entity, includeBody)).toList(); }
}

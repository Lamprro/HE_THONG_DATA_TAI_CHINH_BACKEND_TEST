package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.dto.ingestion.RawPayloadResponse;
import com.hethongdata.taichinh.service.ingestion.IngestionReadService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/raw-payloads")
public class RawPayloadController {

    private final IngestionReadService ingestionReadService;
    private final boolean bodyAccessEnabled;

    public RawPayloadController(
            IngestionReadService ingestionReadService,
            @Value("${financial.raw-diagnostics.include-body-enabled:false}") boolean bodyAccessEnabled) {
        this.ingestionReadService = ingestionReadService;
        this.bodyAccessEnabled = bodyAccessEnabled;
    }

    @GetMapping("/{rawPayloadId}")
    public ResponseEntity<RawPayloadResponse> findById(
            @PathVariable UUID rawPayloadId,
            @RequestParam(defaultValue = "false") boolean includeBody) {
        return ingestionReadService.findPayload(rawPayloadId, includeBody && bodyAccessEnabled)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<RawPayloadResponse> findByRun(
            @RequestParam UUID runId,
            @RequestParam(defaultValue = "false") boolean includeBody) {
        return ingestionReadService.findPayloadsByRun(runId, includeBody && bodyAccessEnabled);
    }
}

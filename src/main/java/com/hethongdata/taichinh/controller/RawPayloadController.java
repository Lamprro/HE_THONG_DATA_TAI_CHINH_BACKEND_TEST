package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.domain.ingestion.RawPayload;
import com.hethongdata.taichinh.repository.RawPayloadRepository;
import java.time.Instant;
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

    private final RawPayloadRepository rawPayloadRepository;
    private final boolean bodyAccessEnabled;

    public RawPayloadController(
            RawPayloadRepository rawPayloadRepository,
            @Value("${financial.raw-diagnostics.include-body-enabled:false}") boolean bodyAccessEnabled) {
        this.rawPayloadRepository = rawPayloadRepository;
        this.bodyAccessEnabled = bodyAccessEnabled;
    }

    @GetMapping("/{rawPayloadId}")
    public ResponseEntity<RawPayloadView> findById(
            @PathVariable UUID rawPayloadId,
            @RequestParam(defaultValue = "false") boolean includeBody) {
        return rawPayloadRepository.findById(rawPayloadId)
                .map(payload -> ResponseEntity.ok(RawPayloadView.from(
                        payload, includeBody && bodyAccessEnabled)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<RawPayloadView> findByRun(
            @RequestParam UUID runId,
            @RequestParam(defaultValue = "false") boolean includeBody) {
        return rawPayloadRepository.findByRunId(runId).stream()
                .map(payload -> RawPayloadView.from(payload, includeBody && bodyAccessEnabled))
                .toList();
    }

    public record RawPayloadView(
            UUID id,
            UUID ingestionRunId,
            long dataSourceId,
            String externalKey,
            String entityType,
            String sourceSymbol,
            String sourceUrl,
            String contentType,
            Object body,
            String checksumSha256,
            Instant fetchedAt,
            Instant createdAt) {

        static RawPayloadView from(RawPayload payload, boolean includeBody) {
            Object body = null;
            if (includeBody) {
                body = payload.jsonPayload() ? payload.payload() : payload.rawText();
            }
            return new RawPayloadView(
                    payload.id(),
                    payload.ingestionRunId(),
                    payload.dataSourceId(),
                    payload.externalKey(),
                    payload.entityType(),
                    payload.sourceSymbol(),
                    payload.sourceUrl(),
                    payload.contentType(),
                    body,
                    payload.checksumSha256(),
                    payload.fetchedAt(),
                    payload.createdAt());
        }
    }
}

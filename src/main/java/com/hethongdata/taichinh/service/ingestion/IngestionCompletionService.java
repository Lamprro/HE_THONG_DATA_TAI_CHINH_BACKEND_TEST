package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.repository.IngestionRunRepository;
import com.hethongdata.taichinh.repository.RawPayloadRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionCompletionService {

    private final RawPayloadRepository rawPayloadRepository;
    private final IngestionRunRepository ingestionRunRepository;

    public IngestionCompletionService(
            RawPayloadRepository rawPayloadRepository,
            IngestionRunRepository ingestionRunRepository) {
        this.rawPayloadRepository = rawPayloadRepository;
        this.ingestionRunRepository = ingestionRunRepository;
    }

    @Transactional
    public UUID persistSuccess(
            UUID runId,
            long dataSourceId,
            ExternalFetchRequest request,
            ExternalFetchResponse response,
            JsonNode jsonBody,
            String textBody,
            String checksum,
            boolean duplicate) {
        UUID rawPayloadId = rawPayloadRepository.save(
                runId, dataSourceId, request, response, jsonBody, textBody, checksum);
        ingestionRunRepository.markSuccess(runId, response, jsonBody, textBody, duplicate);
        return rawPayloadId;
    }
}

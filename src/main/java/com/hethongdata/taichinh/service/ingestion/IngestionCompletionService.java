package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.ingestion.RawPayloadRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
            IngestionRunEntity run,
            DataSourceEntity source,
            ExternalFetchRequest request,
            ExternalFetchResponse response,
            JsonNode jsonBody,
            String textBody,
            String checksum,
            boolean duplicate,
            UUID securityId) {
        UUID rawPayloadId =
                rawPayloadRepository.save(
                        run, source, request, response, jsonBody, textBody, checksum, securityId);
        ingestionRunRepository.markSuccess(run, response, jsonBody, textBody, duplicate);
        return rawPayloadId;
    }
}

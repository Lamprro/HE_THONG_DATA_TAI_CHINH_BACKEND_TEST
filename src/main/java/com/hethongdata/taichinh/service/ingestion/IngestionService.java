package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.domain.ingestion.DataSourceConfig;
import com.hethongdata.taichinh.repository.DataSourceRepository;
import com.hethongdata.taichinh.repository.IngestionRunRepository;
import com.hethongdata.taichinh.repository.RawPayloadRepository;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final ExternalFinancialDataPort externalFinancialDataPort;
    private final DataSourceRepository dataSourceRepository;
    private final IngestionRunRepository ingestionRunRepository;
    private final RawPayloadRepository rawPayloadRepository;
    private final IngestionCompletionService completionService;
    private final ChecksumService checksumService;
    private final ObjectMapper objectMapper;

    public IngestionService(
            ExternalFinancialDataPort externalFinancialDataPort,
            DataSourceRepository dataSourceRepository,
            IngestionRunRepository ingestionRunRepository,
            RawPayloadRepository rawPayloadRepository,
            IngestionCompletionService completionService,
            ChecksumService checksumService,
            ObjectMapper objectMapper) {
        this.externalFinancialDataPort = externalFinancialDataPort;
        this.dataSourceRepository = dataSourceRepository;
        this.ingestionRunRepository = ingestionRunRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.completionService = completionService;
        this.checksumService = checksumService;
        this.objectMapper = objectMapper;
    }

    public IngestionResult ingest(ExternalFetchRequest request) {
        DataSourceConfig source = dataSourceRepository.findActiveByProvider(request.provider())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active data source configured for provider " + request.provider()));
        URI requestUri = externalFinancialDataPort.resolveUri(request);
        UUID runId = ingestionRunRepository.start(source.id(), request, requestUri);

        try {
            ExternalFetchResponse response = externalFinancialDataPort.fetch(request);
            if (!response.isSuccessful()) {
                ExternalErrorCategory category = classifyStatus(response.httpStatus());
                ParsedBody errorBody = parseDiagnosticBody(response);
                String message = "Upstream returned HTTP " + response.httpStatus();
                ingestionRunRepository.markFailedResponse(
                        runId, category.name(), response, errorBody.json(), errorBody.text(), message);
                throw new IngestionExecutionException(
                        runId, category, response.httpStatus(), message, null);
            }

            ParsedBody parsedBody = parseBody(response);
            String checksum = checksumService.sha256(response.rawBody());
            boolean duplicate = rawPayloadRepository.findLatestByChecksum(source.id(), checksum).isPresent();
            UUID rawId = completionService.persistSuccess(
                    runId,
                    source.id(),
                    request,
                    response,
                    parsedBody.json(),
                    parsedBody.text(),
                    checksum,
                    duplicate);

            return new IngestionResult(
                    runId,
                    rawId,
                    "SUCCESS",
                    response.httpStatus(),
                    response.contentType(),
                    checksum,
                    duplicate);
        } catch (IngestionExecutionException exception) {
            throw exception;
        } catch (ExternalFetchException exception) {
            ingestionRunRepository.markFailed(
                    runId, exception.category().name(), exception.upstreamStatus(), exception.getMessage());
            throw new IngestionExecutionException(
                    runId,
                    exception.category(),
                    exception.upstreamStatus(),
                    exception.getMessage(),
                    exception);
        } catch (RuntimeException exception) {
            ingestionRunRepository.markFailed(
                    runId, ExternalErrorCategory.PROTOCOL.name(), null, exception.getMessage());
            throw new IngestionExecutionException(
                    runId,
                    ExternalErrorCategory.PROTOCOL,
                    null,
                    "Unable to complete ingestion: " + exception.getMessage(),
                    exception);
        }
    }

    private ParsedBody parseBody(ExternalFetchResponse response) {
        String contentType = response.contentType().toLowerCase(Locale.ROOT);
        if (!contentType.contains("json")) {
            return new ParsedBody(null, response.rawBody());
        }
        try {
            return new ParsedBody(objectMapper.readTree(response.rawBody()), null);
        } catch (JsonProcessingException exception) {
            throw new ExternalFetchException(
                    ExternalErrorCategory.PROTOCOL,
                    response.httpStatus(),
                    "Upstream declared JSON but returned malformed content",
                    exception);
        }
    }

    private ParsedBody parseDiagnosticBody(ExternalFetchResponse response) {
        if (!response.contentType().toLowerCase(Locale.ROOT).contains("json")) {
            return new ParsedBody(null, response.rawBody());
        }
        try {
            return new ParsedBody(objectMapper.readTree(response.rawBody()), null);
        } catch (JsonProcessingException exception) {
            return new ParsedBody(null, response.rawBody());
        }
    }

    private ExternalErrorCategory classifyStatus(int status) {
        if (status == 429) {
            return ExternalErrorCategory.RATE_LIMIT;
        }
        if (status >= 400 && status < 500) {
            return ExternalErrorCategory.UPSTREAM_CLIENT;
        }
        return ExternalErrorCategory.UPSTREAM_SERVER;
    }

    private record ParsedBody(JsonNode json, String text) {
    }
}

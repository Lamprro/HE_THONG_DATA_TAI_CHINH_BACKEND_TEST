package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.repository.DataSourceRepository;
import com.hethongdata.taichinh.repository.IngestionRunRepository;
import com.hethongdata.taichinh.repository.RawPayloadRepository;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionService.class);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

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

    public IngestionExecutionResponse ingest(ManualIngestionRequest manualRequest) {
        ExternalFetchRequest request = new ExternalFetchRequest(
                manualRequest.operation(), manualRequest.provider(), manualRequest.symbol(),
                manualRequest.startDate(), manualRequest.endDate(), manualRequest.interval(),
                manualRequest.safeParameters());
        DataSourceEntity source = dataSourceRepository.findEntityActiveByProvider(request.provider())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active data source configured for provider " + request.provider()));
        return execute(request, source, null, "MANUAL");
    }

    public IngestionExecutionResponse ingestJob(IngestionJobEntity job, String triggerType) {
        ExternalFetchRequest request = requestFromJob(job);
        return execute(request, job.getDataSource(), job, triggerType);
    }

    private IngestionExecutionResponse execute(
            ExternalFetchRequest request,
            DataSourceEntity source,
            IngestionJobEntity job,
            String triggerType) {
        URI requestUri = externalFinancialDataPort.resolveUri(request);
        IngestionRunEntity run = ingestionRunRepository.start(source, job, triggerType, request, requestUri);
        UUID runId = run.getId();

        try {
            ExternalFetchResponse response = externalFinancialDataPort.fetch(request);
            if (!response.isSuccessful()) {
                ExternalErrorCategory category = classifyStatus(response.httpStatus());
                ParsedBody errorBody = parseDiagnosticBody(response);
                String message = "Upstream returned HTTP " + response.httpStatus();
                ingestionRunRepository.markFailedResponse(
                        run, category.name(), response, errorBody.json(), errorBody.text(), message);
                throw new IngestionExecutionException(
                        runId, category, response.httpStatus(), message, null);
            }

            ParsedBody parsedBody = parseBody(response);
            String checksum = checksumService.sha256(response.rawBody());
            boolean duplicate = rawPayloadRepository.findLatestByChecksum(source.getId(), checksum).isPresent();
            UUID rawId = completionService.persistSuccess(
                    run, source, request, response, parsedBody.json(), parsedBody.text(), checksum, duplicate,
                    securityIdFromJob(job));

            return new IngestionExecutionResponse(
                    runId, rawId, "SUCCESS", response.httpStatus(), response.contentType(), checksum, duplicate);
        } catch (IngestionExecutionException exception) {
            throw exception;
        } catch (ExternalFetchException exception) {
            ingestionRunRepository.markFailed(
                    run, exception.category().name(), exception.upstreamStatus(), exception.getMessage());
            throw new IngestionExecutionException(
                    runId, exception.category(), exception.upstreamStatus(), exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            LOGGER.error("Ingestion run {} failed during internal processing", runId, exception);
            String safeMessage = "Internal ingestion processing failed";
            ingestionRunRepository.markFailed(run, ExternalErrorCategory.PROTOCOL.name(), null, safeMessage);
            throw new IngestionExecutionException(
                    runId, ExternalErrorCategory.PROTOCOL, null, safeMessage, exception);
        }
    }

    private ExternalFetchRequest requestFromJob(IngestionJobEntity job) {
        JsonNode config = job.getParameters();
        String operationValue = requiredText(config, "operation");
        ExternalOperation operation;
        try {
            operation = ExternalOperation.valueOf(operationValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported job operation: " + operationValue, exception);
        }
        String provider = optionalText(config, "provider");
        if (provider == null && job.getDataSource().getProvider() != null) {
            provider = job.getDataSource().getProvider();
        }
        JsonNode nestedParameters = config.path("parameters");
        Map<String, String> parameters = nestedParameters.isObject()
                ? objectMapper.convertValue(nestedParameters, STRING_MAP)
                : Map.of();
        LocalDate startDate = parseDate(optionalText(config, "startDate"));
        LocalDate endDate = parseDate(optionalText(config, "endDate"));
        Integer lookbackDays = optionalPositiveInt(config, "lookbackDays");
        if (lookbackDays != null) {
            // A scheduled price job needs a moving window, not fixed calendar dates from its seed definition.
            endDate = LocalDate.now(ZoneOffset.UTC);
            startDate = endDate.minusDays(lookbackDays);
        }
        return new ExternalFetchRequest(
                operation,
                provider,
                optionalText(config, "symbol"),
                startDate,
                endDate,
                optionalText(config, "interval"),
                parameters);
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Job parameter " + field + " is required");
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private LocalDate parseDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    /** Manual fetches deliberately remain unlinked; provisioned security jobs carry their immutable security id. */
    private UUID securityIdFromJob(IngestionJobEntity job) {
        if (job == null) return null;
        String securityId = optionalText(job.getParameters(), "securityId");
        return securityId == null ? null : UUID.fromString(securityId);
    }

    private Integer optionalPositiveInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        int result = value.asInt(-1);
        if (result < 1 || result > 3650) {
            throw new IllegalArgumentException(field + " must be between 1 and 3650");
        }
        return result;
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

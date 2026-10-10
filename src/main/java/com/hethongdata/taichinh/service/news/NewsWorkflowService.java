package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * The two NEWS business handlers. Candidate selection is exclusively by DataVersion status and
 * ingestion_run_id; lineage is deliberately not consulted.
 */
@Service
public class NewsWorkflowService {
    public static final String NEWS_DATA_FETCH = "NEWS_DATA_FETCH";
    public static final String NEWS_ARTICLE_BUILD = "NEWS_ARTICLE_BUILD";

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter NEWS_LIST_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm");

    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunRepository ingestionRuns;
    private final ExternalFinancialDataPort externalPort;
    private final NewsWorkflowPersistenceService writes;
    private final ChecksumService checksums;
    private final ObjectMapper objectMapper;
    private final DataVersionLifecycleService lifecycle;

    @Value("${financial.news.force-refresh:false}")
    private boolean forceRefresh;

    public NewsWorkflowService(
            DataVersionJpaRepository versions,
            RawPayloadJpaRepository rawPayloads,
            IngestionRunRepository ingestionRuns,
            ExternalFinancialDataPort externalPort,
            NewsWorkflowPersistenceService writes,
            ChecksumService checksums,
            ObjectMapper objectMapper,
            DataVersionLifecycleService lifecycle) {
        this.versions = versions;
        this.rawPayloads = rawPayloads;
        this.ingestionRuns = ingestionRuns;
        this.externalPort = externalPort;
        this.writes = writes;
        this.checksums = checksums;
        this.objectMapper = objectMapper;
        this.lifecycle = lifecycle;
    }

    public boolean supports(String code) {
        return NEWS_DATA_FETCH.equals(code) || NEWS_ARTICLE_BUILD.equals(code);
    }

    public IngestionExecutionResponse execute(IngestionJobEntity job, String triggerType) {
        return switch (job.getCode()) {
            case NEWS_DATA_FETCH -> fetchNewsData(job, triggerType);
            case NEWS_ARTICLE_BUILD -> buildArticles(job, triggerType);
            default -> throw new IllegalArgumentException("Unsupported NEWS workflow job " + job.getCode());
        };
    }

    private IngestionExecutionResponse fetchNewsData(IngestionJobEntity job, String triggerType) {
        List<DataVersionEntity> candidates =
                versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS", "ACTIVE")
                        .stream()
                        .filter(version -> rawPayloads.existsByIngestionRunIdAndEntityType(
                                        version.getIngestionRunId(), "NEWS")
                                || rawPayloads.existsByIngestionRunIdAndEntityType(
                                        version.getIngestionRunId(), "EVENTS"))
                        .toList();
        if (candidates.isEmpty()) return noWork(job, triggerType, NEWS_DATA_FETCH);

        UUID firstRawId = null;
        UUID latestRunId = null;
        int rejected = 0;
        int retryPending = 0;
        Map<String, NewsWorkflowPersistenceService.FetchedNewsData> fetchedByUrl = new LinkedHashMap<>();
        for (DataVersionEntity version : candidates) {
            IngestionRunEntity run = ingestionRuns.startInternalBatch(job.getDataSource(), job, triggerType, NEWS_DATA_FETCH);
            latestRunId = run.getId();
            try {
                List<RawPayloadEntity> sources = rawPayloads
                        .findByIngestionRunIdAndEntityTypeInOrderByFetchedAtAsc(
                                version.getIngestionRunId(), List.of("NEWS", "EVENTS"));
                if (sources.isEmpty()) {
                    throw new IllegalStateException("NEWS data version has no NEWS raw payloads: " + version.getId());
                }
                List<NewsWorkflowPersistenceService.FetchedNewsData> fetched = new ArrayList<>();
                List<Map<String, Object>> fetchFailures = new ArrayList<>();
                int urlCount = 0;
                for (RawPayloadEntity source : sources) {
                    for (NewsListItem item : articleItems(source)) {
                        urlCount++;
                        if (!forceRefresh && writes.hasSourceRelationship(
                                checksums.sha256(item.url()), item.url(), source)) continue;
                        if (forceRefresh && rawPayloads.existsNewsDataForSourceAndUrl(
                                item.url(), source.getSecurityId(), source.getSourceSymbol())) continue;
                        NewsWorkflowPersistenceService.FetchedNewsData previous = fetchedByUrl.get(item.url());
                        try {
                            if (previous == null) {
                                previous = rawPayloads.findReusableNewsDataByRequestedUrl(item.url())
                                        .map(raw -> reusedNewsData(source, item, raw))
                                        .orElseGet(() -> fetchOne(source, item));
                                fetchedByUrl.put(item.url(), previous);
                            }
                            fetched.add(previous.source() == source ? previous : reusedNewsData(source, item, previous));
                        } catch (ExternalFetchException exception) {
                            fetchFailures.add(Map.of(
                                    "url", item.url(),
                                    "category", exception.category().name(),
                                    "upstreamStatus", exception.upstreamStatus() == null ? 0 : exception.upstreamStatus()));
                        }
                    }
                }
                if (urlCount == 0) {
                    throw new IllegalStateException("NEWS data version has no article URL to fetch: " + version.getId());
                }
                List<UUID> rawIds = writes.persistFetchedNewsData(
                        version.getId(), run, fetched, fetchFailures.size(), fetchFailures);
                if (firstRawId == null && !rawIds.isEmpty()) firstRawId = rawIds.getFirst();
                if (!fetchFailures.isEmpty()) retryPending++;
            } catch (ExternalFetchException exception) {
                ingestionRuns.markFailed(run, exception.category().name(), exception.upstreamStatus(), exception.getMessage());
                // A transport/upstream failure does not invalidate an already validated
                // NEWS version. Keep it ACTIVE so a later scheduler run can retry after
                // the Python endpoint or publisher recovers.
                retryPending++;
            } catch (RuntimeException exception) {
                ingestionRuns.markFailed(run, ExternalErrorCategory.PROTOCOL.name(), null, "NEWS_DATA_FETCH failed");
                lifecycle.rejectBuildFailure(version.getId(), NEWS_DATA_FETCH, exception);
                rejected++;
            }
        }
        return new IngestionExecutionResponse(latestRunId, firstRawId,
                rejected > 0 ? "COMPLETED_WITH_REJECTIONS"
                        : retryPending > 0 ? "RETRY_PENDING" : "SUCCESS",
                200, "application/json", null, false);
    }

    private IngestionExecutionResponse buildArticles(IngestionJobEntity job, String triggerType) {
        List<DataVersionEntity> candidates =
                versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS_DATA", "ACTIVE");
        if (candidates.isEmpty()) return noWork(job, triggerType, NEWS_ARTICLE_BUILD);

        UUID latestRunId = null;
        int rejected = 0;
        for (DataVersionEntity version : candidates) {
            IngestionRunEntity run = ingestionRuns.startInternalBatch(job.getDataSource(), job, triggerType, NEWS_ARTICLE_BUILD);
            latestRunId = run.getId();
            try {
                List<RawPayloadEntity> payloads = rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                        version.getIngestionRunId(), "NEWS_DATA");
                if (payloads.isEmpty()) {
                    throw new IllegalStateException("NEWS_DATA version has no NEWS_DATA raw payloads: " + version.getId());
                }
                if (writes.persistBuiltArticles(version.getId(), run, payloads, this::articleDraft).rejected())
                    rejected++;
            } catch (RuntimeException exception) {
                ingestionRuns.markFailed(run, ExternalErrorCategory.PROTOCOL.name(), null, "NEWS_ARTICLE_BUILD failed");
                lifecycle.rejectBuildFailure(version.getId(), NEWS_ARTICLE_BUILD, exception);
                rejected++;
            }
        }
        return new IngestionExecutionResponse(latestRunId, null,
                rejected == 0 ? "SUCCESS" : "COMPLETED_WITH_REJECTIONS",
                200, "application/json", null, false);
    }

    private IngestionExecutionResponse noWork(IngestionJobEntity job, String triggerType, String workflow) {
        IngestionRunEntity run = ingestionRuns.startInternalBatch(job.getDataSource(), job, triggerType, workflow);
        writes.persistBuiltArticlesNoop(run, workflow);
        return new IngestionExecutionResponse(run.getId(), null, "SUCCESS", 200, "application/json", null, false);
    }

    private NewsWorkflowPersistenceService.FetchedNewsData fetchOne(
            RawPayloadEntity source, NewsListItem item) {
        String requestedUrl = item.url();
        if (requestedUrl == null || requestedUrl.isBlank()) {
            throw new IllegalArgumentException("NEWS raw payload has no source_url: " + source.getId());
        }
        ExternalFetchResponse response =
                externalPort.fetch(
                        new ExternalFetchRequest(
                                ExternalOperation.FETCH_URL, null, null, null, null, null,
                                Map.of("url", requestedUrl)));
        if (!response.isSuccessful()) {
            throw new ExternalFetchException(
                    ExternalErrorCategory.UPSTREAM_SERVER,
                    response.httpStatus(),
                    "Python URL fetch endpoint returned HTTP " + response.httpStatus());
        }
        JsonNode envelope = readJson(response.rawBody(), "Python URL fetch response");
        String finalUrl = requiredText(envelope, "final_url");
        String contentType = requiredText(envelope, "content_type");
        boolean textual = envelope.path("textual").asBoolean(false);
        String body = textual && !envelope.path("body").isNull() ? envelope.path("body").asText() : null;
        JsonNode json = null;
        if (body != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
            try {
                json = objectMapper.readTree(body);
            } catch (JsonProcessingException ignored) {
                // The raw body remains text and the existing validation flow decides its quality.
            }
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("requested_url", requestedUrl);
        metadata.put("final_url", finalUrl);
        metadata.put("http_status", envelope.path("http_status").asInt());
        metadata.put("textual", textual);
        for (String field : List.of("extraction_status", "extraction_error", "extraction_version", "canonical_url",
                "title", "sapo", "content_text", "author", "published_at")) {
            if (envelope.hasNonNull(field)) metadata.set(field, envelope.get(field));
        }
        if (item.publishedAt() != null) metadata.put("list_published_at", item.publishedAt());
        if (item.title() != null) metadata.put("list_title", item.title());
        Instant publishedAt = parsePublishedAt(firstText(envelope, "published_at"));
        if (publishedAt == null) publishedAt = parsePublishedAt(item.publishedAt());
        return new NewsWorkflowPersistenceService.FetchedNewsData(
                source,
                finalUrl,
                contentType,
                json == null ? metadata : json,
                json == null ? body : null,
                checksums.sha256(finalUrl + "\n" + (body == null ? "" : body)),
                response.fetchedAt(), publishedAt);
    }

    private NewsWorkflowPersistenceService.FetchedNewsData reusedNewsData(
            RawPayloadEntity source, NewsListItem item, RawPayloadEntity previous) {
        return reusedNewsData(source, item, new NewsWorkflowPersistenceService.FetchedNewsData(
                previous, previous.getSourceUrl(), previous.getContentType(), previous.getPayload(),
                previous.getRawText(), previous.getChecksumSha256(), previous.getFetchedAt(),
                previous.getPublishedAt()));
    }

    private NewsWorkflowPersistenceService.FetchedNewsData reusedNewsData(
            RawPayloadEntity source, NewsListItem item, NewsWorkflowPersistenceService.FetchedNewsData previous) {
        ObjectNode metadata = previous.jsonPayload().deepCopy();
        metadata.put("requested_url", item.url());
        if (item.publishedAt() != null) metadata.put("list_published_at", item.publishedAt());
        if (item.title() != null) metadata.put("list_title", item.title());
        Instant publishedAt = parsePublishedAt(firstText(metadata, "published_at"));
        if (publishedAt == null) publishedAt = parsePublishedAt(item.publishedAt());
        return new NewsWorkflowPersistenceService.FetchedNewsData(source, previous.finalUrl(),
                previous.contentType(), metadata, previous.rawText(), previous.checksum(),
                previous.fetchedAt(), publishedAt);
    }

    /**
     * Existing NEWS raw batches are a JSON envelope whose data array contains article links. For
     * a source that already represents one article, retain its source_url instead.
     */
    private List<NewsListItem> articleItems(RawPayloadEntity source) {
        LinkedHashMap<String, NewsListItem> items = new LinkedHashMap<>();
        JsonNode data = source.getPayload() == null ? null : source.getPayload().path("data");
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                String url = firstText(item, "url", "link", "href");
                if (isHttpUrl(url)) items.putIfAbsent(NewsUrlNormalizer.normalize(url), new NewsListItem(
                        NewsUrlNormalizer.normalize(url), firstText(item, "publishedAt", "published_at"),
                        firstText(item, "title", "headline")));
            }
        }
        if (items.isEmpty() && isHttpUrl(source.getSourceUrl())) {
            String url = NewsUrlNormalizer.normalize(source.getSourceUrl());
            items.put(url, new NewsListItem(url, null, null));
        }
        return List.copyOf(items.values());
    }

    NewsArticleDraft articleDraft(RawPayloadEntity payload) {
        JsonNode json = payload.getPayload();
        if ("FAILED".equals(firstText(json, "extraction_status"))) {
            String url = firstText(json, "requested_url", "final_url", "canonical_url");
            if (!isHttpUrl(url)) throw new IllegalArgumentException("Failed extraction has no valid article URL");
            url = NewsUrlNormalizer.normalize(url);
            String title = clean(firstText(json, "list_title", "title"));
            Instant publishedAt = parsePublishedAt(firstText(json, "list_published_at"));
            String dateSource = publishedAt == null ? "unknown" : "news_list";
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("content_status", "URL_ONLY").put("extraction_status", "FAILED")
                    .put("raw_payload_id", payload.getId().toString()).put("published_at_source", dateSource);
            String error = firstText(json, "extraction_error");
            if (error != null) metadata.put("extraction_error", error);
            return new NewsArticleDraft(url, title == null ? url : title, null, null, null, publishedAt,
                    checksums.sha256(url), null, metadata);
        }
        if (!"SUCCESS".equals(firstText(json, "extraction_status"))) {
            throw new IllegalArgumentException("NEWS_DATA article extraction did not succeed: " + payload.getId());
        }
        String url = firstText(json, "canonical_url");
        String title = firstText(json, "title");
        String sapo = firstText(json, "sapo");
        String content = clean(firstText(json, "content_text"));
        String author = firstText(json, "author");
        if (url == null || title == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("NEWS_DATA extracted article is incomplete: " + payload.getId());
        }
        url = NewsUrlNormalizer.normalize(url);
        Instant publishedAt = parsePublishedAt(firstText(json, "published_at"));
        String publishedAtSource = "article";
        if (publishedAt == null) {
            publishedAt = parsePublishedAt(firstText(json, "list_published_at"));
            publishedAtSource = publishedAt == null ? "unknown" : "news_list";
        }
        if (publishedAt == null)
            throw new IllegalArgumentException("NEWS_DATA article has no published date: " + payload.getId());
        if (content.length() < 200)
            throw new IllegalArgumentException("NEWS_DATA article body is too short: " + payload.getId());
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("content_status", "FULL_TEXT");
        metadata.put("raw_payload_id", payload.getId().toString());
        metadata.put("content_type", payload.getContentType());
        metadata.put("published_at_source", publishedAtSource);
        String extractionVersion = firstText(json, "extraction_version");
        if (extractionVersion != null) metadata.put("extraction_version", extractionVersion);
        return new NewsArticleDraft(
                url, clean(title), clean(sapo), content, clean(author), publishedAt,
                checksums.sha256(url), checksums.sha256(content), metadata);
    }

    private JsonNode readJson(String value, String description) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(description + " is not valid JSON", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText().trim();
        if (value.isBlank()) throw new IllegalArgumentException("Python URL fetch response is missing " + field);
        return value;
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null || node.isNull()) return null;
        for (String name : names) if (node.path(name).isTextual()) return node.path(name).asText();
        JsonNode data = node.path("data");
        if (data.isArray() && !data.isEmpty()) return firstText(data.get(0), names);
        return null;
    }

    private boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private Instant parsePublishedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) { /* NEWS listing dates have no offset. */ }
        try { return LocalDateTime.parse(value, NEWS_LIST_DATE).atZone(VIETNAM).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private String clean(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private record NewsListItem(String url, String publishedAt, String title) {}
}

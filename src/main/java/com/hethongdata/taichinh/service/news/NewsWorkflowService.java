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

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two NEWS business handlers. Candidate selection is exclusively by DataVersion status and
 * ingestion_run_id; lineage is deliberately not consulted.
 */
@Service
public class NewsWorkflowService {
    public static final String NEWS_DATA_FETCH = "NEWS_DATA_FETCH";
    public static final String NEWS_ARTICLE_BUILD = "NEWS_ARTICLE_BUILD";

    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile(
            "(?is)<meta[^>]+(?:property|name)=[\"']og:title[\"'][^>]+content=[\"'](.*?)[\"']");
    private static final Pattern DESCRIPTION = Pattern.compile(
            "(?is)<meta[^>]+(?:name|property)=[\"'](?:description|og:description)[\"'][^>]+content=[\"'](.*?)[\"']");
    private static final Pattern TAGS = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>|<[^>]+>");

    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunRepository ingestionRuns;
    private final ExternalFinancialDataPort externalPort;
    private final NewsWorkflowPersistenceService writes;
    private final ChecksumService checksums;
    private final ObjectMapper objectMapper;
    private final DataVersionLifecycleService lifecycle;

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
                versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS", "ACTIVE");
        if (candidates.isEmpty()) return noWork(job, triggerType, NEWS_DATA_FETCH);

        UUID firstRawId = null;
        UUID latestRunId = null;
        int rejected = 0;
        for (DataVersionEntity version : candidates) {
            IngestionRunEntity run = ingestionRuns.startInternalBatch(job.getDataSource(), job, triggerType, NEWS_DATA_FETCH);
            latestRunId = run.getId();
            try {
                List<RawPayloadEntity> sources = rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                        version.getIngestionRunId(), "NEWS");
                if (sources.isEmpty()) {
                    throw new IllegalStateException("NEWS data version has no NEWS raw payloads: " + version.getId());
                }
                List<NewsWorkflowPersistenceService.FetchedNewsData> fetched = new ArrayList<>();
                for (RawPayloadEntity source : sources) {
                    for (String sourceUrl : articleUrls(source)) {
                        fetched.add(fetchOne(source, sourceUrl));
                    }
                }
                if (fetched.isEmpty()) {
                    throw new IllegalStateException("NEWS data version has no article URL to fetch: " + version.getId());
                }
                List<UUID> rawIds = writes.persistFetchedNewsData(version.getId(), run, fetched);
                if (firstRawId == null && !rawIds.isEmpty()) firstRawId = rawIds.getFirst();
            } catch (ExternalFetchException exception) {
                ingestionRuns.markFailed(run, exception.category().name(), exception.upstreamStatus(), exception.getMessage());
                lifecycle.rejectBuildFailure(version.getId(), NEWS_DATA_FETCH, exception);
                rejected++;
            } catch (RuntimeException exception) {
                ingestionRuns.markFailed(run, ExternalErrorCategory.PROTOCOL.name(), null, "NEWS_DATA_FETCH failed");
                lifecycle.rejectBuildFailure(version.getId(), NEWS_DATA_FETCH, exception);
                rejected++;
            }
        }
        return new IngestionExecutionResponse(latestRunId, firstRawId,
                rejected == 0 ? "SUCCESS" : "COMPLETED_WITH_REJECTIONS",
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
                writes.persistBuiltArticles(version.getId(), run, payloads, this::articleDraft);
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
            RawPayloadEntity source, String requestedUrl) {
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
        return new NewsWorkflowPersistenceService.FetchedNewsData(
                source,
                finalUrl,
                contentType,
                json == null ? metadata : json,
                json == null ? body : null,
                checksums.sha256(finalUrl + "\n" + (body == null ? "" : body)),
                response.fetchedAt());
    }

    /**
     * Existing NEWS raw batches are a JSON envelope whose data array contains article links. For
     * a source that already represents one article, retain its source_url instead.
     */
    private List<String> articleUrls(RawPayloadEntity source) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        JsonNode data = source.getPayload() == null ? null : source.getPayload().path("data");
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                String url = firstText(item, "url", "link", "href");
                if (isHttpUrl(url)) urls.add(url.trim());
            }
        }
        if (urls.isEmpty() && isHttpUrl(source.getSourceUrl())) urls.add(source.getSourceUrl().trim());
        return List.copyOf(urls);
    }

    private NewsArticleDraft articleDraft(RawPayloadEntity payload) {
        String url = payload.getSourceUrl();
        JsonNode json = payload.getPayload();
        String title = firstText(json, "title", "headline", "source_title");
        String sapo = firstText(json, "sapo", "description", "summary");
        String content = firstText(json, "content", "content_text", "body", "text");
        Instant publishedAt = instant(firstText(json, "published_at", "publishedAt", "source_published_at", "date"));
        if ((title == null || title.isBlank()) && payload.getRawText() != null) {
            title = firstMatch(OG_TITLE, payload.getRawText());
            if (title == null) title = firstMatch(TITLE_TAG, payload.getRawText());
            sapo = sapo == null ? firstMatch(DESCRIPTION, payload.getRawText()) : sapo;
            content = stripHtml(payload.getRawText());
        }
        if (title == null || title.isBlank()) title = url;
        if (content == null) content = "";
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("raw_payload_id", payload.getId().toString());
        metadata.put("content_type", payload.getContentType());
        return new NewsArticleDraft(
                url, clean(title), clean(sapo), content, publishedAt,
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

    private Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { return null; }
    }

    private String firstMatch(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? clean(matcher.group(1)) : null;
    }

    private String stripHtml(String value) {
        return clean(TAGS.matcher(value).replaceAll(" "));
    }

    private String clean(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }
}

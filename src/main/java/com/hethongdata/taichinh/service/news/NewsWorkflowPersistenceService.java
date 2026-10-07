package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.NewsArticleEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Owns only the database commit points for the two NEWS workflow jobs. */
@Service
public class NewsWorkflowPersistenceService {
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunJpaRepository ingestionRuns;
    private final DataVersionJpaRepository versions;
    private final NewsArticleJpaRepository articles;
    private final NewsArticleCompanyJpaRepository articleCompanies;
    private final NewsCompanyMatcher matcher;
    private final SecurityJpaRepository securities;
    private final ObjectMapper objectMapper;

    public NewsWorkflowPersistenceService(
            RawPayloadJpaRepository rawPayloads,
            IngestionRunJpaRepository ingestionRuns,
            DataVersionJpaRepository versions,
            NewsArticleJpaRepository articles,
            NewsArticleCompanyJpaRepository articleCompanies,
            NewsCompanyMatcher matcher,
            SecurityJpaRepository securities,
            ObjectMapper objectMapper) {
        this.rawPayloads = rawPayloads;
        this.ingestionRuns = ingestionRuns;
        this.versions = versions;
        this.articles = articles;
        this.articleCompanies = articleCompanies;
        this.matcher = matcher;
        this.securities = securities;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<UUID> persistFetchedNewsData(
            UUID sourceVersionId, IngestionRunEntity run, List<FetchedNewsData> fetched,
            int failedUrlCount, List<java.util.Map<String, Object>> fetchFailures) {
        DataVersionEntity sourceVersion = activeVersion(sourceVersionId, "NEWS");
        List<UUID> rawIds = new java.util.ArrayList<>();
        for (FetchedNewsData item : fetched) {
            RawPayloadEntity raw = RawPayloadEntity.create(
                    run, item.source().getDataSource(), item.source().getExternalKey(),
                    "NEWS_DATA", item.source().getSourceSymbol(), item.finalUrl(),
                    item.contentType(), item.jsonPayload(), item.rawText(), item.checksum(),
                    item.fetchedAt(), item.source().getSecurityId());
            raw.setPublishedAt(item.publishedAt());
            rawIds.add(rawPayloads.save(raw).getId());
        }
        run.markBatchSuccess(
                objectMapper.valueToTree(
                        java.util.Map.of("workflow", "NEWS_DATA_FETCH", "sourceVersionId", sourceVersionId,
                                "failedUrlCount", failedUrlCount, "fetchFailures", fetchFailures)),
                Instant.now(),
                rawIds.size());
        ingestionRuns.save(run);
        if (failedUrlCount == 0 && rawIds.isEmpty()) {
            sourceVersion.markRejected("NEWS_DATA_FETCH found no new article URL or company/security relationship");
        } else if (failedUrlCount == 0) {
            sourceVersion.markActivated();
        }
        versions.save(sourceVersion);
        return rawIds;
    }

    @Transactional
    public BuildResult persistBuiltArticles(
            UUID sourceVersionId, IngestionRunEntity run, List<RawPayloadEntity> payloads,
            java.util.function.Function<RawPayloadEntity, NewsArticleDraft> drafts) {
        DataVersionEntity sourceVersion = activeVersion(sourceVersionId, "NEWS_DATA");
        int inserted = 0;
        int updated = 0;
        int linked = 0;
        int duplicates = 0;
        int invalidPayloads = 0;
        NewsCompanyMatcher.Catalog catalog = matcher.loadCatalog();
        for (RawPayloadEntity payload : payloads) {
            NewsArticleDraft draft;
            try {
                draft = drafts.apply(payload);
            } catch (IllegalArgumentException invalidArticle) {
                // One failed publisher extraction must not discard valid articles from the
                // same NEWS_DATA batch. The invalid raw remains out of news_articles and is
                // counted in this workflow summary for a later fetch retry.
                invalidPayloads++;
                continue;
            }
            List<NewsCompanyMatcher.Match> matches = matcher.match(draft, catalog);
            NewsArticleEntity existing = articles.findByRawPayloadId(payload.getId()).orElse(null);
            boolean sameRawPayload = existing != null;
            boolean sameCanonicalUrl = false;
            if (existing == null) {
                existing = articles.findByUrlHash(draft.urlHash()).orElse(null);
                sameCanonicalUrl = existing != null;
            }
            if (existing == null && draft.contentHash() != null)
                existing = articles.findByContentHash(draft.contentHash()).orElse(null);
            if (existing != null) {
                duplicates++;
                if (draft.contentText() != null && (sameCanonicalUrl || sameRawPayload)
                        && !java.util.Objects.equals(existing.getContentHash(), draft.contentHash())) {
                    existing.refreshFrom(payload.getId(), draft.canonicalUrl(), draft.title(), draft.sapo(),
                            draft.contentText(), draft.author(), draft.publishedAt(), payload.getFetchedAt(),
                            draft.contentHash(), draft.metadata());
                    articles.save(existing);
                    articleCompanies.deleteTextMatchesByArticleId(existing.getId());
                    updated++;
                }
                linked += linkMatches(existing.getId(), payload, matches);
                continue;
            }
            NewsArticleEntity article =
                    articles.saveAndFlush(
                            NewsArticleEntity.create(
                                    payload.getDataSource().getId(),
                                    payload.getId(),
                                    draft.canonicalUrl(),
                                    draft.urlHash(),
                                    draft.title(),
                                    draft.sapo(),
                                    draft.contentText(),
                                    draft.author(),
                                    draft.publishedAt(),
                                    payload.getFetchedAt(),
                                    draft.contentHash(),
                                    draft.metadata()));
            linked += linkMatches(article.getId(), payload, matches);
            inserted++;
        }
        JsonNode summary = objectMapper.valueToTree(java.util.Map.of(
                "workflow", "NEWS_ARTICLE_BUILD", "sourceVersionId", sourceVersionId,
                "newArticles", inserted, "updatedArticles", updated,
                "newLinks", linked, "duplicateArticles", duplicates,
                "invalidPayloads", invalidPayloads));
        // A duplicate article is useful only when it adds a relationship or changed content.
        boolean rejected = inserted == 0 && updated == 0 && linked == 0;
        if (rejected) {
            run.markFailed(200, "application/json", objectMapper.createObjectNode(), null, null,
                    "NEWS_ARTICLE_BUILD found no new article or relationship", summary, Instant.now());
        } else {
            run.markWorkflowSuccess(summary, Instant.now(), payloads.size(), inserted, updated + linked);
        }
        ingestionRuns.save(run);
        if (rejected) {
            sourceVersion.markRejected("NEWS_ARTICLE_BUILD did not create a new article or company/security link");
        } else {
            sourceVersion.markActivated();
        }
        versions.save(sourceVersion);
        return new BuildResult(inserted, updated, linked, duplicates, rejected);
    }

    @Transactional
    public void persistBuiltArticlesNoop(IngestionRunEntity run, String workflow) {
        run.markBatchSuccess(
                objectMapper.valueToTree(java.util.Map.of("workflow", workflow, "noWork", true)),
                Instant.now(),
                0);
        ingestionRuns.save(run);
    }

    private DataVersionEntity activeVersion(UUID id, String domain) {
        DataVersionEntity version =
                versions.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Data version was not found"));
        if (!domain.equals(version.getDataDomain()) || !"ACTIVE".equals(version.getStatus())) {
            throw new IllegalStateException("Data version is no longer an ACTIVE " + domain + " batch");
        }
        return version;
    }

    public boolean hasSourceRelationship(String urlHash, String requestedUrl, RawPayloadEntity source) {
        return articleCompanies.existsSourceLinkByArticleUrl(
                urlHash, requestedUrl, source.getSecurityId(), source.getSourceSymbol());
    }

    private int linkMatches(UUID articleId, RawPayloadEntity payload, List<NewsCompanyMatcher.Match> matches) {
        int created = 0;
        var sourceSecurity = payload.getSecurityId() == null
                ? payload.getSourceSymbol() == null ? java.util.Optional.<com.hethongdata.taichinh.entity.master.SecurityEntity>empty()
                        : securities.findBySymbolIgnoreCase(payload.getSourceSymbol())
                : securities.findById(payload.getSecurityId());
        if (sourceSecurity.isEmpty() && (payload.getSecurityId() != null
                || payload.getSourceSymbol() != null && !payload.getSourceSymbol().isBlank()))
            throw new IllegalArgumentException("NEWS_DATA source security was not found in master data");
        UUID sourceCompanyId = null;
        UUID sourceSecurityId = null;
        if (sourceSecurity.isPresent()) {
            var security = sourceSecurity.get();
            if (payload.getSourceSymbol() != null
                    && !payload.getSourceSymbol().equalsIgnoreCase(security.getSymbol()))
                throw new IllegalArgumentException("NEWS_DATA source symbol and security ID disagree");
            sourceCompanyId = security.getCompanyId();
            sourceSecurityId = security.getId();
            if (sourceCompanyId != null) {
                var evidence = objectMapper.createObjectNode();
                evidence.put("kind", "source_job");
                evidence.put("source_symbol", security.getSymbol());
                created += articleCompanies.insertMatchIfAbsent(articleId, sourceCompanyId,
                        sourceSecurityId, BigDecimal.ONE, "RULE", evidence.toString());
            }
        }
        for (NewsCompanyMatcher.Match match : matches) {
            if (match.companyId().equals(sourceCompanyId)
                    && (match.securityId() == null || match.securityId().equals(sourceSecurityId))) continue;
            created += articleCompanies.insertMatchIfAbsent(articleId, match.companyId(),
                    match.securityId(), match.score(), "TEXT_MATCH", match.evidence().toString());
        }
        return created;
    }

    public record BuildResult(int newArticles, int updatedArticles, int newLinks,
            int duplicateArticles, boolean rejected) {}

    public record FetchedNewsData(
            RawPayloadEntity source,
            String finalUrl,
            String contentType,
            JsonNode jsonPayload,
            String rawText,
            String checksum,
            Instant fetchedAt,
            Instant publishedAt) {}
}

package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.NewsArticleCompanyEntity;
import com.hethongdata.taichinh.entity.NewsArticleEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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
    private final SecurityJpaRepository securities;
    private final ObjectMapper objectMapper;

    public NewsWorkflowPersistenceService(
            RawPayloadJpaRepository rawPayloads,
            IngestionRunJpaRepository ingestionRuns,
            DataVersionJpaRepository versions,
            NewsArticleJpaRepository articles,
            NewsArticleCompanyJpaRepository articleCompanies,
            SecurityJpaRepository securities,
            ObjectMapper objectMapper) {
        this.rawPayloads = rawPayloads;
        this.ingestionRuns = ingestionRuns;
        this.versions = versions;
        this.articles = articles;
        this.articleCompanies = articleCompanies;
        this.securities = securities;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<UUID> persistFetchedNewsData(
            UUID sourceVersionId, IngestionRunEntity run, List<FetchedNewsData> fetched) {
        DataVersionEntity sourceVersion = activeVersion(sourceVersionId, "NEWS");
        List<UUID> rawIds =
                fetched.stream()
                        .map(
                                item ->
                                        rawPayloads
                                                .save(
                                                        RawPayloadEntity.create(
                                                                run,
                                                                item.source().getDataSource(),
                                                                item.source().getExternalKey(),
                                                                "NEWS_DATA",
                                                                item.source().getSourceSymbol(),
                                                                item.finalUrl(),
                                                                item.contentType(),
                                                                item.jsonPayload(),
                                                                item.rawText(),
                                                                item.checksum(),
                                                                item.fetchedAt(),
                                                                item.source().getSecurityId()))
                                                .getId())
                        .toList();
        run.markBatchSuccess(
                objectMapper.valueToTree(
                        java.util.Map.of("workflow", "NEWS_DATA_FETCH", "sourceVersionId", sourceVersionId)),
                Instant.now(),
                rawIds.size());
        ingestionRuns.save(run);
        sourceVersion.markActivated();
        versions.save(sourceVersion);
        return rawIds;
    }

    @Transactional
    public int persistBuiltArticles(
            UUID sourceVersionId, IngestionRunEntity run, List<RawPayloadEntity> payloads,
            java.util.function.Function<RawPayloadEntity, NewsArticleDraft> drafts) {
        DataVersionEntity sourceVersion = activeVersion(sourceVersionId, "NEWS_DATA");
        int inserted = 0;
        for (RawPayloadEntity payload : payloads) {
            NewsArticleDraft draft = drafts.apply(payload);
            if (articles.findByRawPayloadId(payload.getId()).isPresent()
                    || articles.findByUrlHash(draft.urlHash()).isPresent()) continue;
            NewsArticleEntity article =
                    articles.save(
                            NewsArticleEntity.create(
                                    payload.getDataSource().getId(),
                                    payload.getId(),
                                    draft.canonicalUrl(),
                                    draft.urlHash(),
                                    draft.title(),
                                    draft.sapo(),
                                    draft.contentText(),
                                    draft.publishedAt(),
                                    payload.getFetchedAt(),
                                    draft.contentHash(),
                                    draft.metadata()));
            linkSourceSecurity(article.getId(), payload.getSecurityId(), payload.getSourceSymbol());
            inserted++;
        }
        run.markBatchSuccess(
                objectMapper.valueToTree(
                        java.util.Map.of("workflow", "NEWS_ARTICLE_BUILD", "sourceVersionId", sourceVersionId)),
                Instant.now(),
                inserted);
        ingestionRuns.save(run);
        sourceVersion.markActivated();
        versions.save(sourceVersion);
        return inserted;
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

    private void linkSourceSecurity(UUID articleId, UUID securityId, String sourceSymbol) {
        SecurityEntity security =
                securityId == null
                        ? sourceSymbol == null || sourceSymbol.isBlank()
                                ? null
                                : securities.findBySymbolIgnoreCase(sourceSymbol).orElse(null)
                        : securities.findById(securityId).orElse(null);
        if (security == null || security.getCompanyId() == null) return;
        if (!articleCompanies.existsByNewsArticleIdAndCompanyId(articleId, security.getCompanyId())) {
            articleCompanies.save(
                    NewsArticleCompanyEntity.create(
                            articleId,
                            security.getCompanyId(),
                            security.getId(),
                            BigDecimal.ONE,
                            "RULE"));
        }
    }

    public record FetchedNewsData(
            RawPayloadEntity source,
            String finalUrl,
            String contentType,
            JsonNode jsonPayload,
            String rawText,
            String checksum,
            Instant fetchedAt) {}
}

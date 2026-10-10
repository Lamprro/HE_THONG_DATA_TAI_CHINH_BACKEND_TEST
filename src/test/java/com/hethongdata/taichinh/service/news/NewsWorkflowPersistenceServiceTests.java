package com.hethongdata.taichinh.service.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.NewsArticleEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.entity.master.SecurityEntity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class NewsWorkflowPersistenceServiceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
    private final IngestionRunJpaRepository runs = mock(IngestionRunJpaRepository.class);
    private final DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
    private final NewsArticleJpaRepository articles = mock(NewsArticleJpaRepository.class);
    private final NewsArticleCompanyJpaRepository links = mock(NewsArticleCompanyJpaRepository.class);
    private final NewsCompanyMatcher matcher = mock(NewsCompanyMatcher.class);
    private final SecurityJpaRepository securities = mock(SecurityJpaRepository.class);
    private final NewsWorkflowPersistenceService service = new NewsWorkflowPersistenceService(
            rawPayloads, runs, versions, articles, links, matcher, securities, json);

    @Test
    void alreadyLinkedNewsListRejectsItsVersionWithoutFetchingAgain() {
        UUID versionId = UUID.randomUUID();
        DataVersionEntity version = mock(DataVersionEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        when(versions.findByIdForUpdate(versionId)).thenReturn(Optional.of(version));
        when(version.getDataDomain()).thenReturn("NEWS");
        when(version.getStatus()).thenReturn("ACTIVE");

        var rawIds = service.persistFetchedNewsData(versionId, run, List.of(), 0, List.of());

        assertThat(rawIds).isEmpty();
        verify(version).markRejected(any());
        verify(version, never()).markActivated();
        verify(rawPayloads, never()).save(any());
    }

    @Test
    void failedArticleFetchKeepsNewsVersionActiveForRetry() {
        UUID versionId = UUID.randomUUID();
        DataVersionEntity version = mock(DataVersionEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        when(versions.findByIdForUpdate(versionId)).thenReturn(Optional.of(version));
        when(version.getDataDomain()).thenReturn("NEWS");
        when(version.getStatus()).thenReturn("ACTIVE");

        service.persistFetchedNewsData(versionId, run, List.of(), 1,
                List.of(java.util.Map.of("url", "https://cafef.vn/unavailable.chn")));

        verify(version, never()).markRejected(any());
        verify(version, never()).markActivated();
    }

    @Test
    void duplicateArticleAddsOnlyTheMissingCompanyAndActivatesVersion() {
        Fixture fixture = fixture();
        UUID firstCompany = UUID.randomUUID();
        UUID secondCompany = UUID.randomUUID();
        UUID firstSecurity = UUID.randomUUID();
        UUID secondSecurity = UUID.randomUUID();
        when(matcher.match(eq(fixture.draft()), any())).thenReturn(List.of(
                match(firstCompany, firstSecurity), match(secondCompany, secondSecurity)));
        when(links.insertMatchIfAbsent(eq(fixture.articleId()), eq(firstCompany), eq(firstSecurity),
                any(), eq("TEXT_MATCH"), any())).thenReturn(0);
        when(links.insertMatchIfAbsent(eq(fixture.articleId()), eq(secondCompany), eq(secondSecurity),
                any(), eq("TEXT_MATCH"), any())).thenReturn(1);

        var result = service.persistBuiltArticles(fixture.versionId(), fixture.run(),
                List.of(fixture.raw()), ignored -> fixture.draft());

        assertThat(result.newArticles()).isZero();
        assertThat(result.newLinks()).isEqualTo(1);
        assertThat(result.duplicateArticles()).isEqualTo(1);
        assertThat(result.rejected()).isFalse();
        verify(fixture.version()).markActivated();
        verify(links).insertMatchIfAbsent(eq(fixture.articleId()), eq(secondCompany),
                eq(secondSecurity), any(), eq("TEXT_MATCH"), any());
        verify(fixture.run()).markWorkflowSuccess(any(), any(), eq(1), eq(0), eq(1));
    }

    @Test
    void duplicateArticleAndDuplicateRelationshipRejectsVersion() {
        Fixture fixture = fixture();
        UUID company = UUID.randomUUID();
        UUID security = UUID.randomUUID();
        when(matcher.match(eq(fixture.draft()), any())).thenReturn(List.of(match(company, security)));
        when(links.insertMatchIfAbsent(eq(fixture.articleId()), eq(company), eq(security),
                any(), eq("TEXT_MATCH"), any())).thenReturn(0);

        var result = service.persistBuiltArticles(fixture.versionId(), fixture.run(),
                List.of(fixture.raw()), ignored -> fixture.draft());

        assertThat(result.rejected()).isTrue();
        assertThat(result.newArticles()).isZero();
        assertThat(result.newLinks()).isZero();
        assertThat(result.duplicateArticles()).isEqualTo(1);
        assertThat(result.updatedArticles()).isZero();
        verify(fixture.version()).markRejected(any());
        verify(fixture.run()).markFailed(eq(200), eq("application/json"), any(), any(), any(), any(), any(), any());
        verify(links, never()).save(any());
    }

    @Test
    void changedContentAtExistingUrlUpdatesArticleAndActivatesVersion() {
        Fixture fixture = fixture();
        when(fixture.article().getContentHash()).thenReturn("old-content-hash");

        var result = service.persistBuiltArticles(fixture.versionId(), fixture.run(),
                List.of(fixture.raw()), ignored -> fixture.draft());

        assertThat(result.updatedArticles()).isEqualTo(1);
        assertThat(result.rejected()).isFalse();
        verify(fixture.version()).markActivated();
        verify(fixture.article()).refreshFrom(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedRefetchCannotReplaceExistingFullContentWithUrlOnly() {
        Fixture f = fixture();
        var d = f.draft();
        var linkOnly = new NewsArticleDraft(d.canonicalUrl(),d.title(),null,null,null,d.publishedAt(),
                d.urlHash(),null,d.metadata());
        var result = service.persistBuiltArticles(f.versionId(),f.run(),List.of(f.raw()),ignored -> linkOnly);
        assertThat(result.updatedArticles()).isZero();
        verify(f.article(),never()).refreshFrom(any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(articles,never()).findByContentHash(null);
    }

    @Test
    void sourceSecurityIsLinkedWithoutTextMentionAndRepeatedSourceIsIdempotent() {
        Fixture fixture = fixture();
        UUID company = UUID.randomUUID();
        UUID securityId = UUID.randomUUID();
        SecurityEntity security = mock(SecurityEntity.class);
        when(fixture.raw().getSecurityId()).thenReturn(securityId);
        when(securities.findById(securityId)).thenReturn(Optional.of(security));
        when(security.getId()).thenReturn(securityId);
        when(security.getCompanyId()).thenReturn(company);
        when(security.getSymbol()).thenReturn("FPT");
        when(matcher.match(eq(fixture.draft()), any())).thenReturn(List.of());
        when(links.insertMatchIfAbsent(eq(fixture.articleId()), eq(company), eq(securityId),
                eq(BigDecimal.ONE), eq("RULE"), any())).thenReturn(1, 0);

        var first = service.persistBuiltArticles(fixture.versionId(), fixture.run(),
                List.of(fixture.raw()), ignored -> fixture.draft());
        assertThat(first.newLinks()).isEqualTo(1);
        assertThat(first.rejected()).isFalse();

        var second = service.persistBuiltArticles(fixture.versionId(), fixture.run(),
                List.of(fixture.raw()), ignored -> fixture.draft());
        assertThat(second.newLinks()).isZero();
        assertThat(second.rejected()).isTrue();
        assertThat(second.duplicateArticles()).isEqualTo(1);
    }

    @Test
    void sourceAndOtherTextMatchCreateTwoLinksButDoNotRepeatSource() {
        Fixture fixture = fixture();
        UUID fptCompany = UUID.randomUUID();
        UUID fptSecurity = UUID.randomUUID();
        UUID acbCompany = UUID.randomUUID();
        UUID acbSecurity = UUID.randomUUID();
        SecurityEntity security = mock(SecurityEntity.class);
        when(fixture.raw().getSecurityId()).thenReturn(fptSecurity);
        when(securities.findById(fptSecurity)).thenReturn(Optional.of(security));
        when(security.getId()).thenReturn(fptSecurity);
        when(security.getCompanyId()).thenReturn(fptCompany);
        when(security.getSymbol()).thenReturn("FPT");
        when(matcher.match(eq(fixture.draft()), any())).thenReturn(List.of(
                match(fptCompany, fptSecurity), match(acbCompany, acbSecurity)));
        when(links.insertMatchIfAbsent(eq(fixture.articleId()), eq(fptCompany), eq(fptSecurity),
                eq(BigDecimal.ONE), eq("RULE"), any())).thenReturn(1);
        when(links.insertMatchIfAbsent(eq(fixture.articleId()), eq(acbCompany), eq(acbSecurity),
                any(), eq("TEXT_MATCH"), any())).thenReturn(1);

        var result = service.persistBuiltArticles(fixture.versionId(), fixture.run(),
                List.of(fixture.raw()), ignored -> fixture.draft());

        assertThat(result.newLinks()).isEqualTo(2);
        verify(links, never()).insertMatchIfAbsent(eq(fixture.articleId()), eq(fptCompany),
                eq(fptSecurity), any(), eq("TEXT_MATCH"), any());
    }

    @Test
    void invalidExtractionDoesNotDiscardAValidArticleFromTheSameBatch() {
        Fixture fixture = fixture();
        RawPayloadEntity invalid = mock(RawPayloadEntity.class);
        UUID invalidRawId = UUID.randomUUID();
        when(invalid.getId()).thenReturn(invalidRawId);
        when(articles.findByRawPayloadId(invalidRawId)).thenReturn(Optional.empty());
        UUID company = UUID.randomUUID();
        UUID security = UUID.randomUUID();
        when(matcher.match(eq(fixture.draft()), any())).thenReturn(List.of(match(company, security)));
        when(links.insertMatchIfAbsent(eq(fixture.articleId()), eq(company), eq(security),
                any(), eq("TEXT_MATCH"), any())).thenReturn(1);

        var result = service.persistBuiltArticles(fixture.versionId(), fixture.run(),
                List.of(fixture.raw(), invalid), payload -> {
                    if (payload == invalid) throw new IllegalArgumentException("Extraction failed");
                    return fixture.draft();
                });

        assertThat(result.rejected()).isFalse();
        assertThat(result.newLinks()).isEqualTo(1);
        verify(fixture.version()).markActivated();
        verify(fixture.run()).markWorkflowSuccess(any(), any(), eq(2), eq(0), eq(1));
    }

    private NewsCompanyMatcher.Match match(UUID company, UUID security) {
        return new NewsCompanyMatcher.Match(company, security, new BigDecimal("0.90"),
                json.createObjectNode().put("field", "title").put("term", "ACB"));
    }

    private Fixture fixture() {
        UUID versionId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        DataVersionEntity version = mock(DataVersionEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        NewsArticleEntity article = mock(NewsArticleEntity.class);
        when(versions.findByIdForUpdate(versionId)).thenReturn(Optional.of(version));
        when(matcher.loadCatalog()).thenReturn(new NewsCompanyMatcher.Catalog(List.of()));
        when(version.getDataDomain()).thenReturn("NEWS_DATA");
        when(version.getStatus()).thenReturn("ACTIVE");
        when(raw.getId()).thenReturn(rawId);
        when(articles.findByRawPayloadId(rawId)).thenReturn(Optional.of(article));
        when(article.getId()).thenReturn(articleId);
        when(article.getContentHash()).thenReturn("content-hash");
        NewsArticleDraft draft = new NewsArticleDraft("https://cafef.vn/a.chn", "FPT và ACB", null,
                "FPT hợp tác ACB", null, null, "url-hash", "content-hash", null);
        return new Fixture(versionId, articleId, version, run, raw, article, draft);
    }

    private record Fixture(UUID versionId, UUID articleId, DataVersionEntity version,
            IngestionRunEntity run, RawPayloadEntity raw, NewsArticleEntity article, NewsArticleDraft draft) {}
}

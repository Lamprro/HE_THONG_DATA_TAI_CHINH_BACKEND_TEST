package com.hethongdata.taichinh.service.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

class NewsWorkflowServiceTests {

    @Test
    void failedExtractionPreservesLinkWithoutInventingBodyOrDate() {
        var mapper = new ObjectMapper();
        var service = new NewsWorkflowService(mock(DataVersionJpaRepository.class), mock(RawPayloadJpaRepository.class),
                mock(IngestionRunRepository.class), mock(ExternalFinancialDataPort.class),
                mock(NewsWorkflowPersistenceService.class), new ChecksumService(), mapper, mock(DataVersionLifecycleService.class));
        var raw = mock(RawPayloadEntity.class);
        when(raw.getId()).thenReturn(UUID.randomUUID());
        when(raw.getPayload()).thenReturn(mapper.createObjectNode().put("extraction_status", "FAILED")
                .put("requested_url", "https://cafef.vn/source-article.chn").put("list_title", "Tiêu đề từ danh sách"));
        var draft = service.articleDraft(raw);
        assertThat(draft.canonicalUrl()).isEqualTo("https://cafef.vn/source-article.chn");
        assertThat(draft.contentText()).isNull();
        assertThat(draft.contentHash()).isNull();
        assertThat(draft.publishedAt()).isNull();
        assertThat(draft.metadata().path("content_status").asText()).isEqualTo("URL_ONLY");
    }

    @Test
    void articleBuildReportsSuccessWhenEveryArticleAndRelationshipAlreadyExists() {
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        NewsWorkflowPersistenceService writes = mock(NewsWorkflowPersistenceService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        DataVersionEntity version = mock(DataVersionEntity.class);
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        UUID versionId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();
        when(job.getCode()).thenReturn(NewsWorkflowService.NEWS_ARTICLE_BUILD);
        when(job.getDataSource()).thenReturn(mock(DataSourceEntity.class));
        when(version.getId()).thenReturn(versionId);
        when(version.getIngestionRunId()).thenReturn(sourceRunId);
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS_DATA", "ACTIVE"))
                .thenReturn(List.of(version));
        when(runs.startInternalBatch(job.getDataSource(), job, "MANUAL", NewsWorkflowService.NEWS_ARTICLE_BUILD))
                .thenReturn(run);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(sourceRunId, "NEWS_DATA"))
                .thenReturn(List.of(raw));
        when(writes.persistBuiltArticles(eq(versionId), eq(run), anyList(), any()))
                .thenReturn(new NewsWorkflowPersistenceService.BuildResult(0, 0, 0, 1, false));
        NewsWorkflowService service = new NewsWorkflowService(versions, rawPayloads, runs,
                mock(ExternalFinancialDataPort.class), writes, new ChecksumService(),
                new ObjectMapper(), mock(DataVersionLifecycleService.class));

        assertThat(service.execute(job, "MANUAL").getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void preservesEachListingDateAndBuildsFromCleanExtractedBody() throws Exception {
        ObjectMapper json = new ObjectMapper();
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        ExternalFinancialDataPort external = mock(ExternalFinancialDataPort.class);
        NewsWorkflowPersistenceService writes = mock(NewsWorkflowPersistenceService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        DataVersionEntity version = mock(DataVersionEntity.class);
        RawPayloadEntity source = mock(RawPayloadEntity.class);
        UUID versionId = UUID.randomUUID();
        when(job.getCode()).thenReturn(NewsWorkflowService.NEWS_DATA_FETCH);
        when(job.getDataSource()).thenReturn(mock(DataSourceEntity.class));
        when(version.getId()).thenReturn(versionId);
        when(version.getIngestionRunId()).thenReturn(UUID.randomUUID());
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS", "ACTIVE"))
                .thenReturn(List.of(version));
        when(rawPayloads.existsByIngestionRunIdAndEntityType(any(), eq("NEWS"))).thenReturn(true);
        when(runs.startInternalBatch(any(), eq(job), eq("MANUAL"), eq(NewsWorkflowService.NEWS_DATA_FETCH)))
                .thenReturn(run);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeInOrderByFetchedAtAsc(any(), anyList()))
                .thenReturn(List.of(source));
        when(source.getPayload()).thenReturn(json.readTree("""
                {"data":[
                  {"url":"https://cafef.vn/one.chn","publishedAt":"26/09/2026 15:30"},
                  {"url":"https://cafef.vn/two.chn","publishedAt":"25/09/2026 08:00"}
                ]}
                """));
        when(external.fetch(any(ExternalFetchRequest.class))).thenAnswer(invocation -> {
            ExternalFetchRequest request = invocation.getArgument(0);
            String url = request.parameters().get("url");
            String body = json.writeValueAsString(Map.of(
                    "final_url", url, "http_status", 200, "content_type", "text/html",
                    "textual", true, "body", "<html><body>menu</body></html>",
                    "extraction_status", "SUCCESS", "canonical_url", url,
                    "title", "Article", "content_text", "Clean article body"));
            return new ExternalFetchResponse(ExternalOperation.FETCH_URL, "news-web", URI.create(url),
                    200, "application/json", Map.of(), body, Instant.parse("2026-09-26T09:00:00Z"));
        });
        doAnswer(invocation -> {
            List<NewsWorkflowPersistenceService.FetchedNewsData> fetched = invocation.getArgument(2);
            assertThat(fetched).hasSize(2);
            assertThat(fetched.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-26T08:30:00Z"));
            assertThat(fetched.get(1).publishedAt()).isEqualTo(Instant.parse("2026-09-25T01:00:00Z"));
            assertThat(fetched.get(0).jsonPayload().path("list_published_at").asText())
                    .isEqualTo("26/09/2026 15:30");
            return List.of(UUID.randomUUID(), UUID.randomUUID());
        }).when(writes).persistFetchedNewsData(eq(versionId), eq(run), anyList(), eq(0), anyList());

        NewsWorkflowService service = new NewsWorkflowService(versions, rawPayloads, runs, external,
                writes, new ChecksumService(), json, mock(DataVersionLifecycleService.class));
        assertThat(service.execute(job, "MANUAL").getStatus()).isEqualTo("SUCCESS");

        RawPayloadEntity articleRaw = mock(RawPayloadEntity.class);
        when(articleRaw.getId()).thenReturn(UUID.randomUUID());
        String cleanArticleBody = "Clean article body with enough detail. ".repeat(8);
        when(articleRaw.getPayload()).thenReturn(json.createObjectNode()
                .put("extraction_status", "SUCCESS").put("extraction_version", "cafef-content-v4")
                .put("canonical_url", "https://cafef.vn/one.chn").put("title", "Article")
                .put("content_text", cleanArticleBody).put("author", "Tác giả")
                .put("published_at", "2026-09-26T15:30:00+07:00")
                .put("list_published_at", "25/09/2026 08:00"));
        NewsArticleDraft draft = service.articleDraft(articleRaw);
        assertThat(draft.contentText()).isEqualTo(cleanArticleBody.trim());
        assertThat(draft.contentHash()).isEqualTo(new ChecksumService().sha256(cleanArticleBody.replaceAll("\\s+", " ").trim()));
        assertThat(draft.author()).isEqualTo("Tác giả");
        assertThat(draft.publishedAt()).isEqualTo(Instant.parse("2026-09-26T08:30:00Z"));
        assertThat(draft.metadata().path("published_at_source").asText()).isEqualTo("article");

        when(articleRaw.getPayload()).thenReturn(json.createObjectNode()
                .put("extraction_status", "SUCCESS").put("extraction_version", "cafef-content-v4")
                .put("canonical_url", "https://cafef.vn/two.chn").put("title", "Second article")
                .put("content_text", "Another clean body with enough detail. ".repeat(8))
                .put("list_published_at", "25/09/2026 08:00"));
        NewsArticleDraft fallback = service.articleDraft(articleRaw);
        assertThat(fallback.publishedAt()).isEqualTo(Instant.parse("2026-09-25T01:00:00Z"));
        assertThat(fallback.metadata().path("published_at_source").asText()).isEqualTo("news_list");
    }

    @Test
    void buildsFromSuccessfulProductionExtractionWhenParserVersionIsOmitted() throws Exception {
        RawPayloadEntity articleRaw = mock(RawPayloadEntity.class);
        String body = "CafeF extracted article content for downstream analysis. ".repeat(8);
        when(articleRaw.getId()).thenReturn(UUID.randomUUID());
        when(articleRaw.getPayload()).thenReturn(new ObjectMapper().createObjectNode()
                .put("extraction_status", "SUCCESS")
                .put("canonical_url", "https://cafef.vn/article.chn")
                .put("title", "Article title")
                .put("content_text", body)
                .put("published_at", "2026-09-28T00:08:00+07:00"));

        NewsWorkflowService service = new NewsWorkflowService(
                mock(DataVersionJpaRepository.class), mock(RawPayloadJpaRepository.class),
                mock(IngestionRunRepository.class), mock(ExternalFinancialDataPort.class),
                mock(NewsWorkflowPersistenceService.class), new ChecksumService(),
                new ObjectMapper(), mock(DataVersionLifecycleService.class));

        NewsArticleDraft draft = service.articleDraft(articleRaw);

        assertThat(draft.publishedAt()).isEqualTo(Instant.parse("2026-09-27T17:08:00Z"));
        assertThat(draft.contentText()).isEqualTo(body.trim());
        assertThat(draft.metadata().has("extraction_version")).isFalse();
    }

    @Test
    void reusesExistingNewsDataForAnotherSourceWithoutExternalFetch() throws Exception {
        ObjectMapper json = new ObjectMapper();
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        ExternalFinancialDataPort external = mock(ExternalFinancialDataPort.class);
        NewsWorkflowPersistenceService writes = mock(NewsWorkflowPersistenceService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        DataVersionEntity version = mock(DataVersionEntity.class);
        RawPayloadEntity source = mock(RawPayloadEntity.class);
        RawPayloadEntity stored = mock(RawPayloadEntity.class);
        String url = "https://cafef.vn/story.chn";
        when(job.getCode()).thenReturn(NewsWorkflowService.NEWS_DATA_FETCH);
        when(job.getDataSource()).thenReturn(mock(DataSourceEntity.class));
        when(version.getId()).thenReturn(UUID.randomUUID());
        when(version.getIngestionRunId()).thenReturn(UUID.randomUUID());
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS", "ACTIVE"))
                .thenReturn(List.of(version));
        when(rawPayloads.existsByIngestionRunIdAndEntityType(any(), eq("NEWS"))).thenReturn(true);
        when(runs.startInternalBatch(any(), eq(job), eq("MANUAL"), eq(NewsWorkflowService.NEWS_DATA_FETCH)))
                .thenReturn(run);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeInOrderByFetchedAtAsc(any(), anyList()))
                .thenReturn(List.of(source));
        when(source.getPayload()).thenReturn(json.readTree("""
                {"data":[{"url":"https://cafef.vn/story.chn","publishedAt":"26/09/2026 15:30"}]}
                """));
        when(source.getSourceSymbol()).thenReturn("ACB");
        when(rawPayloads.findReusableNewsDataByRequestedUrl(url))
                .thenReturn(java.util.Optional.of(stored));
        when(stored.getSourceUrl()).thenReturn(url);
        when(stored.getContentType()).thenReturn("text/html");
        when(stored.getPayload()).thenReturn(json.readTree("""
                {"requested_url":"https://cafef.vn/story.chn","final_url":"https://cafef.vn/story.chn",
                 "http_status":200,"textual":true,"extraction_status":"SUCCESS",
                 "extraction_version":"cafef-content-v4",
                 "canonical_url":"https://cafef.vn/story.chn","title":"Article","content_text":"Clean body"}
                """));
        when(stored.getRawText()).thenReturn("<html><body>Clean body</body></html>");
        when(stored.getChecksumSha256()).thenReturn("checksum");
        when(stored.getFetchedAt()).thenReturn(Instant.parse("2026-09-26T09:00:00Z"));
        when(writes.persistFetchedNewsData(eq(version.getId()), eq(run), anyList(), eq(0), anyList()))
                .thenAnswer(invocation -> {
                    List<NewsWorkflowPersistenceService.FetchedNewsData> fetched = invocation.getArgument(2);
                    assertThat(fetched).hasSize(1);
                    assertThat(fetched.getFirst().source()).isSameAs(source);
                    assertThat(fetched.getFirst().jsonPayload().path("list_published_at").asText())
                            .isEqualTo("26/09/2026 15:30");
                    return List.of(UUID.randomUUID());
                });
        var service = new NewsWorkflowService(versions, rawPayloads, runs, external, writes,
                new ChecksumService(), json, mock(DataVersionLifecycleService.class));
        assertThat(service.execute(job, "MANUAL").getStatus()).isEqualTo("SUCCESS");
        verifyNoInteractions(external);
    }

    @Test
    void transportFailureKeepsNewsVersionAvailableAndPersistsPartialFailureForRetry() throws Exception {
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        ExternalFinancialDataPort external = mock(ExternalFinancialDataPort.class);
        NewsWorkflowPersistenceService writes = mock(NewsWorkflowPersistenceService.class);
        DataVersionLifecycleService lifecycle = mock(DataVersionLifecycleService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        DataVersionEntity version = mock(DataVersionEntity.class);
        RawPayloadEntity raw = mock(RawPayloadEntity.class);
        UUID versionId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();
        when(job.getCode()).thenReturn(NewsWorkflowService.NEWS_DATA_FETCH);
        when(job.getDataSource()).thenReturn(mock(DataSourceEntity.class));
        when(version.getId()).thenReturn(versionId);
        when(version.getIngestionRunId()).thenReturn(sourceRunId);
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS", "ACTIVE"))
                .thenReturn(List.of(version));
        when(rawPayloads.existsByIngestionRunIdAndEntityType(any(), eq("NEWS"))).thenReturn(true);
        when(runs.startInternalBatch(job.getDataSource(), job, "SCHEDULED", NewsWorkflowService.NEWS_DATA_FETCH))
                .thenReturn(run);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeInOrderByFetchedAtAsc(eq(sourceRunId), anyList()))
                .thenReturn(List.of(raw));
        when(raw.getPayload()).thenReturn(new ObjectMapper().readTree("""
                {"data":[{"url":"https://cafef.vn/story.chn"}]}
                """));
        when(external.fetch(any(ExternalFetchRequest.class))).thenThrow(
                new ExternalFetchException(ExternalErrorCategory.UPSTREAM_CLIENT, 404, "Python route not deployed"));

        NewsWorkflowService service = new NewsWorkflowService(
                versions, rawPayloads, runs, external, writes,
                new ChecksumService(), new ObjectMapper(), lifecycle);

        assertThat(service.execute(job, "SCHEDULED").getStatus()).isEqualTo("RETRY_PENDING");
        verify(writes).persistFetchedNewsData(eq(versionId), eq(run), anyList(), eq(1), anyList());
        verify(runs, never()).markFailed(any(), any(), any(), any());
        verify(lifecycle, never()).rejectBuildFailure(any(), any(), any());
    }

    @Test
    void fetchJobRecordsSuccessfulNoWorkRunWhenNoActiveNewsVersionExists() {
        DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
        RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
        IngestionRunRepository runs = mock(IngestionRunRepository.class);
        ExternalFinancialDataPort external = mock(ExternalFinancialDataPort.class);
        NewsWorkflowPersistenceService writes = mock(NewsWorkflowPersistenceService.class);
        IngestionJobEntity job = mock(IngestionJobEntity.class);
        IngestionRunEntity run = mock(IngestionRunEntity.class);
        when(job.getCode()).thenReturn(NewsWorkflowService.NEWS_DATA_FETCH);
        when(job.getDataSource()).thenReturn(mock(DataSourceEntity.class));
        when(versions.findByDataDomainAndStatusOrderByCreatedAtAsc("NEWS", "ACTIVE"))
                .thenReturn(List.of());
        when(runs.startInternalBatch(job.getDataSource(), job, "MANUAL", NewsWorkflowService.NEWS_DATA_FETCH))
                .thenReturn(run);

        NewsWorkflowService service =
                new NewsWorkflowService(
                        versions,
                        rawPayloads,
                        runs,
                        external,
                        writes,
                        new ChecksumService(),
                        new ObjectMapper(),
                        mock(DataVersionLifecycleService.class));

        IngestionExecutionResponse result = service.execute(job, "MANUAL");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        verify(writes).persistBuiltArticlesNoop(run, NewsWorkflowService.NEWS_DATA_FETCH);
        verifyNoInteractions(rawPayloads, external);
    }
}

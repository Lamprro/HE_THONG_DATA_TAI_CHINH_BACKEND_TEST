package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.dto.validation.ValidationExecutionResponse;
import com.hethongdata.taichinh.entity.NewsArticleCompanyEntity;
import com.hethongdata.taichinh.entity.NewsArticleEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.ingestion.DataSourceRepository;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.validation.ValidationJobService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real database test verifying live commit points, deduplication, and company linking
 * directly in the shared PostgreSQL database (without @Transactional rollback).
 */
@SpringBootTest
@org.junit.jupiter.api.Disabled("Writes mocked provider data to the shared database without rollback; use an isolated database/schema before re-enabling")
class RealDatabaseNewsFlowVerificationTest {

    @MockitoBean
    private ExternalFinancialDataPort externalFinancialDataPort;

    @Autowired
    private ValidationJobService validationJobService;

    @Autowired
    private NewsWorkflowService newsWorkflowService;

    @Autowired
    private IngestionRunRepository ingestionRunRepository;

    @Autowired
    private IngestionRunJpaRepository ingestionRunJpaRepository;

    @Autowired
    private RawPayloadJpaRepository rawPayloadJpaRepository;

    @Autowired
    private DataVersionJpaRepository dataVersionJpaRepository;

    @Autowired
    private NewsArticleJpaRepository newsArticleJpaRepository;

    @Autowired
    private NewsArticleCompanyJpaRepository newsArticleCompanyJpaRepository;

    @Autowired
    private IngestionJobJpaRepository ingestionJobJpaRepository;

    @Autowired
    private DataSourceRepository dataSourceRepository;

    @Autowired
    private SecurityJpaRepository securityJpaRepository;

    @Autowired
    private ChecksumService checksumService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Verify Real PostgreSQL Persistence: Live Commit, Dedup & Content Refresh")
    void testRealDatabase_LiveCommitAndDedup() throws Exception {
        DataSourceEntity cafefDataSource = dataSourceRepository.findEntityActiveByCode("CAFEF")
                .orElseThrow(() -> new IllegalStateException("CAFEF data source not found"));

        IngestionJobEntity newsListJob = ingestionJobJpaRepository.findByCodeIgnoreCase("CAFEF_FPT_NEWS_30M")
                .orElseThrow(() -> new IllegalStateException("CAFEF_FPT_NEWS_30M job not found"));

        IngestionJobEntity newsDataFetchJob = ingestionJobJpaRepository.findByCodeIgnoreCase(NewsWorkflowService.NEWS_DATA_FETCH)
                .orElseThrow(() -> new IllegalStateException("NEWS_DATA_FETCH job not found"));

        IngestionJobEntity newsArticleBuildJob = ingestionJobJpaRepository.findByCodeIgnoreCase(NewsWorkflowService.NEWS_ARTICLE_BUILD)
                .orElseThrow(() -> new IllegalStateException("NEWS_ARTICLE_BUILD job not found"));

        var fptSecurity = securityJpaRepository.findBySymbolIgnoreCase("FPT").orElseThrow();
        UUID fptSecurityId = fptSecurity.getId();

        String uniqueArticleId = UUID.randomUUID().toString().substring(0, 8);
        String testUrl = "https://cafef.vn/real-db-fpt-business-results-" + uniqueArticleId + ".chn";
        String initialContent = "Tập đoàn FPT công bố doanh thu chuyển đổi số tăng trưởng 35% trong quý 3 năm 2026. "
                + "Khối công nghệ tiếp tục dẫn đầu tăng trưởng với sự đóng góp lớn từ các hợp đồng AI với đối tác Nhật Bản và Mỹ. "
                + "Ban điều hành cho biết biên lợi nhuận ròng được duy trì ở mức cao nhờ tự động hóa quy trình nội bộ bằng AI. "
                + "Dòng tiền kinh doanh thặng dư đảm bảo cổ tức tiền mặt cho cổ đông theo đúng kế hoạch đề ra.";

        // Mock external fetch for this URL
        mockExternalFetchSuccess(testUrl, "FPT tăng trưởng doanh thu AI " + uniqueArticleId, initialContent, "2026-09-30T10:00:00+07:00");

        // ---------------------------------------------------------------------
        // STEP 1: Insert real NEWS listing run & raw payload into PostgreSQL
        // ---------------------------------------------------------------------
        IngestionRunEntity newsRun = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsListJob, "MANUAL", "INGESTION");

        ObjectNode root = objectMapper.createObjectNode();
        root.put("provider", "cafef");
        root.put("dataset", "news");
        root.put("retrieved_at", Instant.now().toString());
        ArrayNode data = root.putArray("data");
        ObjectNode item = data.addObject();
        item.put("url", testUrl);
        item.put("title", "FPT tăng trưởng doanh thu AI " + uniqueArticleId);
        item.put("publishedAt", "30/09/2026 10:00");
        item.put("symbol", "FPT");
        root.put("count", 1);

        String jsonText = objectMapper.writeValueAsString(root);
        String checksum = checksumService.sha256(jsonText);

        RawPayloadEntity newsRaw = rawPayloadJpaRepository.save(RawPayloadEntity.create(
                newsRun, cafefDataSource, "real-listing-" + uniqueArticleId, "NEWS", "FPT",
                "https://cafef.vn/thi-truong-chung-khoan.chn", "application/json",
                root, jsonText, checksum, Instant.now(), fptSecurityId));

        newsRun.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(newsRun);

        // ---------------------------------------------------------------------
        // STEP 2: Real Validation for NEWS in PostgreSQL
        // ---------------------------------------------------------------------
        ValidationExecutionResponse newsValResponse = validationJobService.validate(newsRaw.getId());
        assertThat(newsValResponse.getStatus()).isEqualTo("ACCEPTED");
        UUID newsVersionId = newsValResponse.getDataVersionId();
        assertThat(newsVersionId).isNotNull();

        // ---------------------------------------------------------------------
        // STEP 3: Execute real NEWS_DATA_FETCH -> writes NEWS_DATA raw payload
        // ---------------------------------------------------------------------
        IngestionExecutionResponse fetchResponse = newsWorkflowService.execute(newsDataFetchJob, "MANUAL");
        assertThat(fetchResponse.getStatus()).isEqualTo("SUCCESS");

        List<RawPayloadEntity> newsDataPayloads = rawPayloadJpaRepository
                .findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(fetchResponse.getRunId(), "NEWS_DATA");
        assertThat(newsDataPayloads).isNotEmpty();
        RawPayloadEntity newsDataRaw = newsDataPayloads.getFirst();

        // ---------------------------------------------------------------------
        // STEP 4: Real Validation for NEWS_DATA in PostgreSQL
        // ---------------------------------------------------------------------
        ValidationExecutionResponse newsDataValResponse = validationJobService.validate(newsDataRaw.getId());
        assertThat(newsDataValResponse.getStatus()).isEqualTo("ACCEPTED");
        UUID newsDataVersionId = newsDataValResponse.getDataVersionId();
        assertThat(newsDataVersionId).isNotNull();

        // ---------------------------------------------------------------------
        // STEP 5: Execute real NEWS_ARTICLE_BUILD -> writes news_articles & news_article_companies
        // ---------------------------------------------------------------------
        IngestionExecutionResponse buildResponse = newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");
        assertThat(buildResponse.getStatus()).isEqualTo("SUCCESS");

        // Verify the article actually exists in PostgreSQL news_articles
        String urlHash = checksumService.sha256(testUrl);
        NewsArticleEntity committedArticle = newsArticleJpaRepository.findByUrlHash(urlHash)
                .orElseThrow(() -> new AssertionError("Article was not committed to news_articles!"));

        assertThat(committedArticle.getTitle()).contains("FPT tăng trưởng doanh thu AI");
        assertThat(committedArticle.getContentText()).isEqualTo(initialContent.replaceAll("\\s+", " ").trim());

        // Verify relationships in PostgreSQL news_article_companies
        List<NewsArticleCompanyEntity> links = newsArticleCompanyJpaRepository.findAll().stream()
                .filter(l -> l.getNewsArticleId().equals(committedArticle.getId()))
                .toList();
        assertThat(links).isNotEmpty();
        assertThat(links.stream().anyMatch(l -> "RULE".equals(l.getMatchMethod()))).isTrue();

        // ---------------------------------------------------------------------
        // STEP 6: TEST REAL DEDUPLICATION IN POSTGRESQL
        // Re-run build with the same content -> Must NOT insert duplicate row
        // ---------------------------------------------------------------------
        IngestionRunEntity dupRun = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");
        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                dupRun, cafefDataSource, "real-dup-" + uniqueArticleId, "NEWS_DATA", "FPT", testUrl,
                newsDataRaw.getContentType(), newsDataRaw.getPayload(), newsDataRaw.getRawText(),
                newsDataRaw.getChecksumSha256(), Instant.now(), fptSecurityId));
        dupRun.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(dupRun);

        DataVersionEntity dupVersion = dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun(
                "NEWS_DATA", dupRun.getId(), 1, "dup-checksum-" + uniqueArticleId));

        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        // Verify in PostgreSQL: Exactly 1 row exists for this canonical URL
        long countInDb = newsArticleJpaRepository.findAll().stream()
                .filter(a -> testUrl.equals(a.getCanonicalUrl())).count();
        assertThat(countInDb).isEqualTo(1);

        // Verify the duplicate data version was marked REJECTED in PostgreSQL
        DataVersionEntity rejectedVersion = dataVersionJpaRepository.findById(dupVersion.getId()).orElseThrow();
        assertThat(rejectedVersion.getStatus()).isEqualTo("REJECTED");
        assertThat(rejectedVersion.getNotes()).contains("did not create a new article");
    }

    private void mockExternalFetchSuccess(String url, String title, String body, String publishedAtIso) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("final_url", url);
        envelope.put("http_status", 200);
        envelope.put("content_type", "text/html; charset=utf-8");
        envelope.put("textual", true);
        envelope.put("body", "<!DOCTYPE html><html><body><article>" + body + "</article></body></html>");
        envelope.put("extraction_status", "SUCCESS");
        envelope.put("extraction_version", "cafef-content-v4");
        envelope.put("canonical_url", url);
        envelope.put("title", title);
        envelope.put("content_text", body);
        envelope.put("author", "Ban Biên Tập FPT");
        envelope.put("published_at", publishedAtIso);

        String jsonEnvelope = objectMapper.writeValueAsString(envelope);

        when(externalFinancialDataPort.fetch(any(ExternalFetchRequest.class))).thenAnswer(invocation -> {
            ExternalFetchRequest request = invocation.getArgument(0);
            String reqUrl = request.parameters().get("url");
            return new ExternalFetchResponse(
                    ExternalOperation.FETCH_URL, "news-web",
                    URI.create(reqUrl != null ? reqUrl : url),
                    200, "application/json", Map.of(), jsonEnvelope, Instant.now());
        });
    }
}

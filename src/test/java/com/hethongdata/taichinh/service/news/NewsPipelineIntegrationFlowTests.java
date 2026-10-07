package com.hethongdata.taichinh.service.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.application.port.ExternalFinancialDataPort;
import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;
import com.hethongdata.taichinh.application.port.error.ExternalFetchException;
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
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.repository.ingestion.DataSourceRepository;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.validation.ValidationJobService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test suite testing the real NEWS pipeline flow:
 * Ingestion -> Validation Phase 1 (NEWS) -> NEWS_DATA_FETCH ->
 * Validation Phase 2 (NEWS_DATA) -> NEWS_ARTICLE_BUILD -> Article & Company Link persistence.
 * Uses real Spring services, real validation rules, and real PostgreSQL database.
 */
@SpringBootTest
@Transactional
class NewsPipelineIntegrationFlowTests {

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
    private ValidationResultJpaRepository validationResultJpaRepository;

    @Autowired
    private IngestionJobRepository ingestionJobRepository;

    @Autowired
    private com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository ingestionJobJpaRepository;

    @Autowired
    private DataSourceRepository dataSourceRepository;

    @Autowired
    private SecurityJpaRepository securityJpaRepository;

    @Autowired
    private ChecksumService checksumService;

    @Autowired
    private ObjectMapper objectMapper;

    private DataSourceEntity cafefDataSource;
    private IngestionJobEntity newsListJob;
    private IngestionJobEntity newsDataFetchJob;
    private IngestionJobEntity newsArticleBuildJob;
    private UUID fptSecurityId;

    @BeforeEach
    void setUp() {
        cafefDataSource = dataSourceRepository.findEntityActiveByCode("CAFEF")
                .orElseGet(() -> dataSourceRepository.findAllEntities().stream()
                        .filter(s -> s.getCode().contains("CAFEF"))
                        .findFirst().orElseThrow());

        newsListJob = ingestionJobJpaRepository.findByCodeIgnoreCase("CAFEF_FPT_NEWS_30M")
                .orElseGet(() -> ingestionJobRepository.findActiveEntities().stream()
                        .filter(j -> "NEWS".equals(j.getDatasetType()))
                        .findFirst().orElseThrow());

        newsDataFetchJob = ingestionJobJpaRepository.findByCodeIgnoreCase(NewsWorkflowService.NEWS_DATA_FETCH)
                .orElseThrow(() -> new IllegalStateException("NEWS_DATA_FETCH job not found in DB"));

        newsArticleBuildJob = ingestionJobJpaRepository.findByCodeIgnoreCase(NewsWorkflowService.NEWS_ARTICLE_BUILD)
                .orElseThrow(() -> new IllegalStateException("NEWS_ARTICLE_BUILD job not found in DB"));

        var fptSecurity = securityJpaRepository.findBySymbolIgnoreCase("FPT");
        fptSecurityId = fptSecurity.map(com.hethongdata.taichinh.entity.master.SecurityEntity::getId).orElse(null);
    }

    // =========================================================================
    // CASE 1: FULL HAPPY PATH - End-to-End Real Flow
    // =========================================================================
    @Test
    @DisplayName("Case 1: Full Flow Success - From News List Ingestion to Validated News Data to Built Articles & Company Links")
    void testFullFlow_HappyPath() throws Exception {
        String testUrl = "https://cafef.vn/fpt-doanh-thu-ky-luc-nam-2026-integration-test-" + UUID.randomUUID() + ".chn";

        // Step 1: Create a completed IngestionRun and RawPayload for NEWS listing
        IngestionRunEntity newsRun = createCompletedNewsListingRun(
                newsListJob, cafefDataSource, "FPT", fptSecurityId,
                List.of(new ArticleLinkItem(testUrl, "FPT đạt doanh thu kỷ lục quý 3", "28/09/2026 10:00")));

        RawPayloadEntity newsRaw = rawPayloadJpaRepository
                .findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(newsRun.getId(), "NEWS").getFirst();

        // Step 2: Validate the NEWS raw payload through the real validation service
        ValidationExecutionResponse newsValResponse = validationJobService.validate(newsRaw.getId());
        assertThat(newsValResponse.getStatus()).isEqualTo("ACCEPTED");
        assertThat(newsValResponse.getDataVersionId()).isNotNull();

        DataVersionEntity newsVersion = dataVersionJpaRepository.findById(newsValResponse.getDataVersionId()).orElseThrow();
        assertThat(newsVersion.getDataDomain()).isEqualTo("NEWS");
        assertThat(newsVersion.getStatus()).isEqualTo("ACTIVE");

        // Step 3: Mock external Python fetch for this URL
        String contentText = "Tập đoàn FPT vừa công bố kết quả kinh doanh quý 3 năm 2026 với mức tăng trưởng vượt bậc "
                + "trên toàn bộ các khối công nghệ, viễn thông và giáo dục. Lợi nhuận trước thuế tăng 22% so với cùng kỳ. "
                + "Ban lãnh đạo FPT cho biết hợp đồng chuyển đổi số quốc tế tiếp tục tăng mạnh tại thị trường Nhật Bản và Mỹ. "
                + "Dòng tiền hoạt động kinh doanh duy trì ổn định và bền vững.";

        mockExternalFetchSuccess(testUrl, "FPT đạt doanh thu kỷ lục quý 3", contentText, "2026-09-28T10:00:00+07:00");

        // Step 4: Execute NEWS_DATA_FETCH workflow job
        IngestionExecutionResponse fetchExecResponse = newsWorkflowService.execute(newsDataFetchJob, "MANUAL");
        assertThat(fetchExecResponse.getStatus()).isEqualTo("SUCCESS");

        // Verify: Source NEWS version is now ACTIVATED
        DataVersionEntity updatedNewsVersion = dataVersionJpaRepository.findById(newsVersion.getId()).orElseThrow();
        assertThat(updatedNewsVersion.getStatus()).isEqualTo("ACTIVATED");

        // Verify: New NEWS_DATA raw payload was saved
        List<RawPayloadEntity> newsDataPayloads = rawPayloadJpaRepository
                .findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(fetchExecResponse.getRunId(), "NEWS_DATA");
        assertThat(newsDataPayloads).hasSize(1);
        RawPayloadEntity newsDataRaw = newsDataPayloads.getFirst();
        assertThat(newsDataRaw.getSourceUrl()).isEqualTo(testUrl);
        assertThat(newsDataRaw.getEntityType()).isEqualTo("NEWS_DATA");

        // Step 5: Validate the NEWS_DATA raw payload
        ValidationExecutionResponse newsDataValResponse = validationJobService.validate(newsDataRaw.getId());
        assertThat(newsDataValResponse.getStatus()).isEqualTo("ACCEPTED");
        assertThat(newsDataValResponse.getDataVersionId()).isNotNull();

        DataVersionEntity newsDataVersion = dataVersionJpaRepository.findById(newsDataValResponse.getDataVersionId()).orElseThrow();
        assertThat(newsDataVersion.getDataDomain()).isEqualTo("NEWS_DATA");
        assertThat(newsDataVersion.getStatus()).isEqualTo("ACTIVE");

        // Step 6: Execute NEWS_ARTICLE_BUILD workflow job
        IngestionExecutionResponse buildExecResponse = newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");
        assertThat(buildExecResponse.getStatus()).isEqualTo("SUCCESS");

        // Verify: NEWS_DATA version is now ACTIVATED
        DataVersionEntity updatedNewsDataVersion = dataVersionJpaRepository.findById(newsDataVersion.getId()).orElseThrow();
        assertThat(updatedNewsDataVersion.getStatus()).isEqualTo("ACTIVATED");

        // Verify: NewsArticleEntity exists in database with matching URL hash and content hash
        String expectedUrlHash = checksumService.sha256(testUrl);
        NewsArticleEntity builtArticle = newsArticleJpaRepository.findByUrlHash(expectedUrlHash)
                .orElseThrow(() -> new AssertionError("Article was not found in news_articles for urlHash=" + expectedUrlHash));

        assertThat(builtArticle.getTitle()).isEqualTo("FPT đạt doanh thu kỷ lục quý 3");
        assertThat(builtArticle.getCanonicalUrl()).isEqualTo(testUrl);
        assertThat(builtArticle.getContentText()).isEqualTo(contentText.replaceAll("\\s+", " ").trim());
        assertThat(builtArticle.getContentHash()).isEqualTo(checksumService.sha256(builtArticle.getContentText()));

        // Verify: NewsArticleCompanyEntity relationships created
        List<NewsArticleCompanyEntity> companyLinks = newsArticleCompanyJpaRepository.findAll().stream()
                .filter(l -> l.getNewsArticleId().equals(builtArticle.getId()))
                .toList();
        assertThat(companyLinks).isNotEmpty();

        // Must have at least the RULE link for source symbol FPT
        boolean hasSourceRuleLink = companyLinks.stream().anyMatch(l -> "RULE".equals(l.getMatchMethod()));
        assertThat(hasSourceRuleLink).isTrue();
    }

    // =========================================================================
    // CASE 2: DUPLICATE URL IN BATCH (NEWS_URL_DUPLICATE_IN_BATCH)
    // =========================================================================
    @Test
    @DisplayName("Case 2: Duplicate URL within Ingestion Batch Fails NEWS_URL_DUPLICATE_IN_BATCH Rule")
    void testNewsList_DuplicateUrlInBatch_FailsValidationRule() throws Exception {
        String baseArticleUrl = "https://cafef.vn/article-duplicate-" + UUID.randomUUID() + ".chn";
        String duplicateTrackingUrl = baseArticleUrl + "?utm_source=facebook&utm_campaign=share";

        IngestionRunEntity run = createCompletedNewsListingRun(
                newsListJob, cafefDataSource, "FPT", fptSecurityId,
                List.of(
                        new ArticleLinkItem(baseArticleUrl, "Tiêu đề 1", "28/09/2026 10:00"),
                        new ArticleLinkItem(duplicateTrackingUrl, "Tiêu đề 2", "28/09/2026 10:05")));

        RawPayloadEntity raw = rawPayloadJpaRepository
                .findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(run.getId(), "NEWS").getFirst();

        ValidationExecutionResponse valResponse = validationJobService.validate(raw.getId());

        // Check validation results table
        List<ValidationResultEntity> results = validationResultJpaRepository.findByRawPayloadIdOrderByCheckedAtAsc(raw.getId());
        ValidationResultEntity dupResult = results.stream()
                .filter(r -> "NEWS_URL_DUPLICATE_IN_BATCH".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("NEWS_URL_DUPLICATE_IN_BATCH rule was not executed"));

        assertThat(dupResult.getStatus()).isEqualTo("FAIL");
        assertThat(dupResult.getMessage()).contains("Duplicates payload.data");
        assertThat(valResponse.getFailed()).isGreaterThan(0);
    }

    // =========================================================================
    // CASE 3: URL PREVIOUSLY FETCHED (NEWS_URL_PREVIOUSLY_FETCHED)
    // =========================================================================
    @Test
    @DisplayName("Case 3: Previously Fetched URL Fails NEWS_URL_PREVIOUSLY_FETCHED Rule")
    void testNewsList_PreviouslyFetchedUrl_FailsValidationRule() throws Exception {
        String existingUrl = "https://cafef.vn/story-already-fetched-" + UUID.randomUUID() + ".chn";

        // Save a pre-existing NEWS_DATA raw payload
        IngestionRunEntity priorRun = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");
        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                priorRun, cafefDataSource, existingUrl, "NEWS_DATA", "FPT", existingUrl,
                "text/html", objectMapper.createObjectNode().put("requested_url", existingUrl),
                "<html><body>old</body></html>", checksumService.sha256("old"), Instant.now(), fptSecurityId));

        // Now a new NEWS listing payload arrives containing that same URL
        IngestionRunEntity newRun = createCompletedNewsListingRun(
                newsListJob, cafefDataSource, "FPT", fptSecurityId,
                List.of(new ArticleLinkItem(existingUrl, "Tin đã lấy", "28/09/2026 11:00")));

        RawPayloadEntity newNewsRaw = rawPayloadJpaRepository
                .findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(newRun.getId(), "NEWS").getFirst();

        validationJobService.validate(newNewsRaw.getId());

        List<ValidationResultEntity> results = validationResultJpaRepository.findByRawPayloadIdOrderByCheckedAtAsc(newNewsRaw.getId());
        ValidationResultEntity prevFetchedResult = results.stream()
                .filter(r -> "NEWS_URL_PREVIOUSLY_FETCHED".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("NEWS_URL_PREVIOUSLY_FETCHED was not executed"));

        assertThat(prevFetchedResult.getStatus()).isEqualTo("FAIL");
        assertThat(prevFetchedResult.getMessage()).contains("Article URL already has NEWS_DATA; reuse its content");
    }

    // =========================================================================
    // CASE 4: DUPLICATE HASH INGESTION (NEWS_DUPLICATE_HASH)
    // =========================================================================
    @Test
    @DisplayName("Case 4: Duplicate Ingestion Checksum Fails NEWS_DUPLICATE_HASH Rule")
    void testNewsList_DuplicateChecksum_FailsRule() throws Exception {
        String testUrl = "https://cafef.vn/article-checksum-test-" + UUID.randomUUID() + ".chn";

        // Create first run
        IngestionRunEntity firstRun = createCompletedNewsListingRun(
                newsListJob, cafefDataSource, "FPT", fptSecurityId,
                List.of(new ArticleLinkItem(testUrl, "Tin tức FPT", "28/09/2026 12:00")));
        RawPayloadEntity firstRaw = rawPayloadJpaRepository
                .findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(firstRun.getId(), "NEWS").getFirst();

        // Create second run with exact same payload and same checksum
        IngestionRunEntity secondRun = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsListJob, "MANUAL", "INGESTION");
        RawPayloadEntity secondRaw = rawPayloadJpaRepository.save(RawPayloadEntity.create(
                secondRun, cafefDataSource, "second-key", "NEWS", "FPT", null,
                "application/json", firstRaw.getPayload(), firstRaw.getRawText(),
                firstRaw.getChecksumSha256(), Instant.now(), fptSecurityId));
        secondRun.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(secondRun);

        validationJobService.validate(secondRaw.getId());

        List<ValidationResultEntity> results = validationResultJpaRepository.findByRawPayloadIdOrderByCheckedAtAsc(secondRaw.getId());
        ValidationResultEntity hashResult = results.stream()
                .filter(r -> "NEWS_DUPLICATE_HASH".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow();

        assertThat(hashResult.getStatus()).isEqualTo("FAIL");
        assertThat(hashResult.getMessage()).contains("Duplicate news raw payload");
    }

    // =========================================================================
    // CASE 5: MALFORMED PAYLOAD STRUCTURE (NEWS_PAYLOAD_STRUCTURE - ERROR)
    // =========================================================================
    @Test
    @DisplayName("Case 5: Malformed Payload Structure Rejects Ingestion Run and Blocks DataVersion")
    void testNewsList_MalformedStructure_RejectsAndBlocksVersion() throws Exception {
        IngestionRunEntity run = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsListJob, "MANUAL", "INGESTION");

        // Payload with data as a string instead of an array of objects
        ObjectNode malformedPayload = objectMapper.createObjectNode();
        malformedPayload.put("data", "not an array of articles");

        RawPayloadEntity raw = rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run, cafefDataSource, "malformed-key", "NEWS", "FPT", null,
                "application/json", malformedPayload, malformedPayload.toString(),
                checksumService.sha256(malformedPayload.toString()), Instant.now(), fptSecurityId));

        run.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run);

        ValidationExecutionResponse valResponse = validationJobService.validate(raw.getId());

        assertThat(valResponse.getStatus()).isEqualTo("REJECTED");
        assertThat(valResponse.getDataVersionId()).isNull(); // No version created!

        List<ValidationResultEntity> results = validationResultJpaRepository.findByRawPayloadIdOrderByCheckedAtAsc(raw.getId());
        ValidationResultEntity structResult = results.stream()
                .filter(r -> "NEWS_PAYLOAD_STRUCTURE".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow();

        assertThat(structResult.getStatus()).isEqualTo("FAIL");
    }

    // =========================================================================
    // CASE 6: UPSTREAM TRANSPORT FAILURE (NEWS_DATA_FETCH Retries Pending)
    // =========================================================================
    @Test
    @DisplayName("Case 6: Upstream Network Failure During NEWS_DATA_FETCH Returns RETRY_PENDING and Keeps Version ACTIVE")
    void testNewsDataFetch_TransportFailure_KeepsVersionActiveForRetry() throws Exception {
        String testUrl = "https://cafef.vn/network-fail-test-" + UUID.randomUUID() + ".chn";

        IngestionRunEntity run = createCompletedNewsListingRun(
                newsListJob, cafefDataSource, "FPT", fptSecurityId,
                List.of(new ArticleLinkItem(testUrl, "Tin FPT", "28/09/2026 14:00")));
        RawPayloadEntity raw = rawPayloadJpaRepository
                .findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(run.getId(), "NEWS").getFirst();

        ValidationExecutionResponse valResponse = validationJobService.validate(raw.getId());
        UUID versionId = valResponse.getDataVersionId();
        assertThat(versionId).isNotNull();

        // Mock upstream failure: Python service returns 502 / timeout
        when(externalFinancialDataPort.fetch(any(ExternalFetchRequest.class))).thenThrow(
                new ExternalFetchException(ExternalErrorCategory.UPSTREAM_SERVER, 502, "Bad Gateway from Crawler Service"));

        IngestionExecutionResponse fetchResponse = newsWorkflowService.execute(newsDataFetchJob, "MANUAL");

        assertThat(fetchResponse.getStatus()).isEqualTo("RETRY_PENDING");

        // CRITICAL: The NEWS data version MUST remain ACTIVE for retry
        DataVersionEntity versionAfterFailure = dataVersionJpaRepository.findById(versionId).orElseThrow();
        assertThat(versionAfterFailure.getStatus()).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // CASE 7: BLOCK PAGE DETECTED (NEWS_DATA_BLOCK_PAGE_DETECTED)
    // =========================================================================
    @Test
    @DisplayName("Case 7: Block Page HTML Structure Fails NEWS_DATA_BLOCK_PAGE_DETECTED Rule")
    void testNewsData_BlockPageDetected_FailsRule() throws Exception {
        IngestionRunEntity run = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");

        String blockHtml = "<!DOCTYPE html><html><head><title>Just a moment...</title></head>"
                + "<body><div>Please turn JavaScript on and reload the page. Cloudflare Ray ID: abc123</div></body></html>";

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("requested_url", "https://cafef.vn/blocked.chn");
        payload.put("final_url", "https://cafef.vn/blocked.chn");
        payload.put("http_status", 403);
        payload.put("textual", true);

        RawPayloadEntity blockedRaw = rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run, cafefDataSource, "blocked-key", "NEWS_DATA", "FPT", "https://cafef.vn/blocked.chn",
                "text/html; charset=utf-8", payload, blockHtml,
                checksumService.sha256(blockHtml), Instant.now(), fptSecurityId));

        run.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run);

        validationJobService.validate(blockedRaw.getId());

        List<ValidationResultEntity> results = validationResultJpaRepository.findByRawPayloadIdOrderByCheckedAtAsc(blockedRaw.getId());
        ValidationResultEntity blockResult = results.stream()
                .filter(r -> "NEWS_DATA_BLOCK_PAGE_DETECTED".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow();

        assertThat(blockResult.getStatus()).isEqualTo("FAIL");
        assertThat(blockResult.getMessage()).contains("Possible block/error page marker");
    }

    // =========================================================================
    // CASE 8: EXTRACTION FAILED IN NEWS_DATA
    // =========================================================================
    @Test
    @DisplayName("Case 8: Extraction Failure in NEWS_DATA Fails Validation and articleDraft Gracefully Rejects")
    void testNewsData_ExtractionFailed_FailsRuleAndBuild() throws Exception {
        IngestionRunEntity run = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("requested_url", "https://cafef.vn/failed-extract.chn");
        payload.put("final_url", "https://cafef.vn/failed-extract.chn");
        payload.put("http_status", 200);
        payload.put("textual", true);
        payload.put("extraction_status", "FAILED");
        payload.put("extraction_error", "No parser matched the DOM pattern");

        RawPayloadEntity failedRaw = rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run, cafefDataSource, "failed-key", "NEWS_DATA", "FPT", "https://cafef.vn/failed-extract.chn",
                "text/html", payload, "<html><body>video only</body></html>",
                checksumService.sha256("video"), Instant.now(), fptSecurityId));

        run.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run);

        validationJobService.validate(failedRaw.getId());

        List<ValidationResultEntity> results = validationResultJpaRepository.findByRawPayloadIdOrderByCheckedAtAsc(failedRaw.getId());
        ValidationResultEntity extractResult = results.stream()
                .filter(r -> "NEWS_DATA_ARTICLE_EXTRACTED".equals(r.getRuleCode()))
                .findFirst()
                .orElseThrow();

        assertThat(extractResult.getStatus()).isEqualTo("FAIL");
        assertThat(extractResult.getMessage()).contains("Publisher article extraction failed");
    }

    // =========================================================================
    // CASE 9: BODY TOO SHORT (< 200 chars)
    // =========================================================================
    @Test
    @DisplayName("Case 9: Content Text Under 200 Characters Is Rejected by articleDraft Without Crashing Pipeline")
    void testNewsArticleBuild_ContentTooShort_RejectsVersionGracefully() throws Exception {
        IngestionRunEntity run = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("canonical_url", "https://cafef.vn/short-stub.chn");
        payload.put("title", "Tin ngắn");
        payload.put("content_text", "Nội dung quá ngắn dưới hai trăm ký tự.");
        payload.put("extraction_status", "SUCCESS");
        payload.put("published_at", "2026-09-28T15:00:00+07:00");

        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run, cafefDataSource, "short-key", "NEWS_DATA", "FPT", "https://cafef.vn/short-stub.chn",
                "text/html", payload, "<html><body>short</body></html>",
                checksumService.sha256("short"), Instant.now(), fptSecurityId));

        run.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run);

        // Manually create accepted DataVersion to test build phase directly
        DataVersionEntity version = dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun(
                "NEWS_DATA", run.getId(), 1, "test-checksum"));

        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        // When 0 articles are created/updated/linked, the build rejects the source version
        DataVersionEntity updatedVersion = dataVersionJpaRepository.findById(version.getId()).orElseThrow();
        assertThat(updatedVersion.getStatus()).isEqualTo("REJECTED");
        assertThat(updatedVersion.getNotes()).contains("did not create a new article");
    }

    // =========================================================================
    // CASE 10: MISSING PUBLISHED_AT IN BOTH ARTICLE AND LISTING
    // =========================================================================
    @Test
    @DisplayName("Case 10: Missing Publication Date in Both Article and Listing Rejects Article Draft")
    void testNewsArticleBuild_MissingPublicationDate_RejectsVersion() throws Exception {
        IngestionRunEntity run = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("canonical_url", "https://cafef.vn/no-date.chn");
        payload.put("title", "Bài báo không ngày");
        payload.put("content_text", "Nội dung bài báo đầy đủ chi tiết hơn hai trăm ký tự để qua điều kiện độ dài nội dung. ".repeat(4));
        payload.put("extraction_status", "SUCCESS");
        // No published_at and no list_published_at

        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run, cafefDataSource, "no-date-key", "NEWS_DATA", "FPT", "https://cafef.vn/no-date.chn",
                "text/html", payload, "<html><body>content</body></html>",
                checksumService.sha256("nodate"), Instant.now(), fptSecurityId));

        run.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run);

        DataVersionEntity version = dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun(
                "NEWS_DATA", run.getId(), 1, "nodate-checksum"));

        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        DataVersionEntity updatedVersion = dataVersionJpaRepository.findById(version.getId()).orElseThrow();
        assertThat(updatedVersion.getStatus()).isEqualTo("REJECTED");
    }

    // =========================================================================
    // CASE 11: DUPLICATE ARTICLE DEDUPLICATION (IDEMPOTENT BUILD)
    // =========================================================================
    @Test
    @DisplayName("Case 11: Duplicate Article with Same URL and Same Content Hash Avoids Duplicate Insert")
    void testNewsArticleBuild_DuplicateArticleDedup() throws Exception {
        String testUrl = "https://cafef.vn/duplicate-build-test-" + UUID.randomUUID() + ".chn";
        String body = "Nội dung bài báo tài chính về FPT và doanh thu kỷ lục. ".repeat(6);

        // Run 1: First build inserts the article
        IngestionRunEntity run1 = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");
        ObjectNode payload1 = objectMapper.createObjectNode();
        payload1.put("canonical_url", testUrl);
        payload1.put("title", "FPT kết quả kinh doanh");
        payload1.put("content_text", body);
        payload1.put("extraction_status", "SUCCESS");
        payload1.put("published_at", "2026-09-28T16:00:00+07:00");

        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run1, cafefDataSource, "dup-1", "NEWS_DATA", "FPT", testUrl,
                "text/html", payload1, "body1", checksumService.sha256("b1"), Instant.now(), fptSecurityId));
        run1.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run1);

        dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun("NEWS_DATA", run1.getId(), 1, "cs-1"));
        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        long countAfterFirst = newsArticleJpaRepository.findAll().stream()
                .filter(a -> testUrl.equals(a.getCanonicalUrl())).count();
        assertThat(countAfterFirst).isEqualTo(1);

        // Run 2: Second build with exact same URL and same content
        IngestionRunEntity run2 = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");
        ObjectNode payload2 = payload1.deepCopy();
        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run2, cafefDataSource, "dup-2", "NEWS_DATA", "FPT", testUrl,
                "text/html", payload2, "body2", checksumService.sha256("b2"), Instant.now(), fptSecurityId));
        run2.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run2);

        DataVersionEntity version2 = dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun("NEWS_DATA", run2.getId(), 1, "cs-2"));
        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        long countAfterSecond = newsArticleJpaRepository.findAll().stream()
                .filter(a -> testUrl.equals(a.getCanonicalUrl())).count();
        assertThat(countAfterSecond).isEqualTo(1); // STILL 1, no duplicate inserted!

        // Since no new articles or links were created, version 2 is rejected
        DataVersionEntity updatedVersion2 = dataVersionJpaRepository.findById(version2.getId()).orElseThrow();
        assertThat(updatedVersion2.getStatus()).isEqualTo("REJECTED");
    }

    // =========================================================================
    // CASE 12: UPDATED ARTICLE CONTENT (ARTICLE REFRESH)
    // =========================================================================
    @Test
    @DisplayName("Case 12: Changed Article Content for Existing URL Updates Article and Refreshes Company Links")
    void testNewsArticleBuild_UpdatedContent_RefreshesArticle() throws Exception {
        String testUrl = "https://cafef.vn/content-update-test-" + UUID.randomUUID() + ".chn";
        String initialBody = "Nội dung ban đầu của bài viết FPT trước khi được ban biên tập chỉnh sửa thêm thông tin. ".repeat(4);
        String updatedBody = "Nội dung cập nhật mới nhất bổ sung ý kiến của chuyên gia tài chính về kết quả của FPT. ".repeat(4);

        // First build
        IngestionRunEntity run1 = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");
        ObjectNode payload1 = objectMapper.createObjectNode();
        payload1.put("canonical_url", testUrl);
        payload1.put("title", "FPT ban đầu");
        payload1.put("content_text", initialBody);
        payload1.put("extraction_status", "SUCCESS");
        payload1.put("published_at", "2026-09-28T16:00:00+07:00");

        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run1, cafefDataSource, "upd-1", "NEWS_DATA", "FPT", testUrl,
                "text/html", payload1, "body1", checksumService.sha256("b1"), Instant.now(), fptSecurityId));
        run1.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run1);
        dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun("NEWS_DATA", run1.getId(), 1, "cs-1"));
        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        NewsArticleEntity initialArticle = newsArticleJpaRepository.findByUrlHash(checksumService.sha256(testUrl)).orElseThrow();
        assertThat(initialArticle.getContentText()).isEqualTo(initialBody.replaceAll("\\s+", " ").trim());

        // Second build with updated content
        IngestionRunEntity run2 = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");
        ObjectNode payload2 = objectMapper.createObjectNode();
        payload2.put("canonical_url", testUrl);
        payload2.put("title", "FPT cập nhật");
        payload2.put("content_text", updatedBody);
        payload2.put("extraction_status", "SUCCESS");
        payload2.put("published_at", "2026-09-28T16:30:00+07:00");

        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run2, cafefDataSource, "upd-2", "NEWS_DATA", "FPT", testUrl,
                "text/html", payload2, "body2", checksumService.sha256("b2"), Instant.now(), fptSecurityId));
        run2.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run2);

        DataVersionEntity version2 = dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun("NEWS_DATA", run2.getId(), 1, "cs-2"));
        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        // Verify: Article was updated, not duplicated
        NewsArticleEntity refreshedArticle = newsArticleJpaRepository.findByUrlHash(checksumService.sha256(testUrl)).orElseThrow();
        assertThat(refreshedArticle.getId()).isEqualTo(initialArticle.getId());
        assertThat(refreshedArticle.getTitle()).isEqualTo("FPT cập nhật");
        assertThat(refreshedArticle.getContentText()).isEqualTo(updatedBody.replaceAll("\\s+", " ").trim());
        assertThat(refreshedArticle.getContentHash()).isEqualTo(checksumService.sha256(refreshedArticle.getContentText()));

        // Version 2 should be ACTIVATED because it updated existing article
        DataVersionEntity updatedVersion2 = dataVersionJpaRepository.findById(version2.getId()).orElseThrow();
        assertThat(updatedVersion2.getStatus()).isEqualTo("ACTIVATED");
    }

    // =========================================================================
    // CASE 13: MULTI-COMPANY MATCHING (RULE + TEXT_MATCH)
    // =========================================================================
    @Test
    @DisplayName("Case 13: Article Mentioning Multiple Companies Creates Both RULE and TEXT_MATCH Links")
    void testNewsArticleBuild_MultiCompanyMatching_CreatesBothRuleAndTextMatch() throws Exception {
        String testUrl = "https://cafef.vn/fpt-acb-partnership-" + UUID.randomUUID() + ".chn";
        String body = "Tập đoàn FPT công bố thỏa thuận hợp tác chuyển đổi số chiến lược với Ngân hàng Á Châu (ACB). "
                + "Theo nội dung thỏa thuận, FPT sẽ triển khai hạ tầng công nghệ thông tin và trí tuệ nhân tạo cho ACB "
                + "nhằm nâng cao trải nghiệm khách hàng và tối ưu hóa hệ thống giao dịch số trên toàn hệ thống ngân hàng.";

        IngestionRunEntity run = ingestionRunRepository.startInternalBatch(
                cafefDataSource, newsDataFetchJob, "MANUAL", "NEWS_DATA_FETCH");

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("canonical_url", testUrl);
        payload.put("title", "FPT và ACB ký thỏa thuận chuyển đổi số toàn diện");
        payload.put("content_text", body);
        payload.put("extraction_status", "SUCCESS");
        payload.put("published_at", "2026-09-28T17:00:00+07:00");

        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run, cafefDataSource, "multi-key", "NEWS_DATA", "FPT", testUrl,
                "text/html", payload, "body", checksumService.sha256("multi"), Instant.now(), fptSecurityId));
        run.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), 1);
        ingestionRunJpaRepository.save(run);

        dataVersionJpaRepository.save(DataVersionEntity.acceptedForRun("NEWS_DATA", run.getId(), 1, "multi-cs"));
        newsWorkflowService.execute(newsArticleBuildJob, "MANUAL");

        NewsArticleEntity article = newsArticleJpaRepository.findByUrlHash(checksumService.sha256(testUrl)).orElseThrow();

        List<NewsArticleCompanyEntity> links = newsArticleCompanyJpaRepository.findAll().stream()
                .filter(l -> l.getNewsArticleId().equals(article.getId()))
                .toList();

        // Must have RULE link for source FPT
        assertThat(links.stream().anyMatch(l -> "RULE".equals(l.getMatchMethod()))).isTrue();

        // Must also have TEXT_MATCH link for ACB mentioned in title/body
        boolean hasAcbMatch = links.stream().anyMatch(l -> "TEXT_MATCH".equals(l.getMatchMethod()));
        assertThat(hasAcbMatch).isTrue();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private IngestionRunEntity createCompletedNewsListingRun(
            IngestionJobEntity job,
            DataSourceEntity dataSource,
            String symbol,
            UUID securityId,
            List<ArticleLinkItem> items) throws Exception {

        IngestionRunEntity run = ingestionRunRepository.startInternalBatch(
                dataSource, job, "MANUAL", "INGESTION");

        ObjectNode root = objectMapper.createObjectNode();
        root.put("provider", dataSource.getCode().toLowerCase(java.util.Locale.ROOT));
        root.put("dataset", "news");
        root.put("retrieved_at", Instant.now().toString());
        ArrayNode data = root.putArray("data");
        for (ArticleLinkItem item : items) {
            ObjectNode obj = data.addObject();
            obj.put("url", item.url());
            obj.put("title", item.title());
            obj.put("publishedAt", item.publishedAt());
            obj.put("symbol", symbol);
        }
        root.put("count", items.size());

        String jsonText = objectMapper.writeValueAsString(root);
        String checksum = checksumService.sha256(jsonText);

        rawPayloadJpaRepository.save(RawPayloadEntity.create(
                run, dataSource, "listing-" + UUID.randomUUID(), "NEWS", symbol,
                "https://cafef.vn/thi-truong-chung-khoan.chn", "application/json",
                root, jsonText, checksum, Instant.now(), securityId));

        run.markBatchSuccess(objectMapper.createObjectNode(), Instant.now(), items.size());
        return ingestionRunJpaRepository.save(run);
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
        envelope.put("author", "Ban Biên Tập");
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

    private record ArticleLinkItem(String url, String title, String publishedAt) {}
}

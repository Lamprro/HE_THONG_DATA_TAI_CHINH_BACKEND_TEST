# Luồng xử lý NEWS

## Mục tiêu và sơ đồ

Luồng NEWS tách rõ lấy danh sách tin, lấy nội dung nguồn và xây dựng nghiệp vụ. `DataVersion` là batch của một `ingestion_run`, còn `RawPayload` là từng bản ghi thô trong batch. `ACTIVE` nghĩa là batch đã qua validation và đang chờ job downstream; `ACTIVATED` là batch đã được job đó tiêu thụ thành công. `activated_at` chỉ là thời điểm, không thay thế `status`.

```text
SOURCE
  ↓
RAW NEWS
  ↓
EXISTING VALIDATION
  ↓
NEWS DataVersion / ACTIVE
  ↓
NEWS_DATA_FETCH
  ↓
RAW NEWS_DATA (IngestionRun mới)
  ↓
NEWS DataVersion / ACTIVATED
  ↓
EXISTING VALIDATION
  ↓
NEWS_DATA DataVersion / ACTIVE
  ↓
NEWS_ARTICLE_BUILD
  ↓
news_articles → news_article_companies
  ↓
NEWS_DATA DataVersion / ACTIVATED
```

`IngestionJob` là định nghĩa có code, cron và trạng thái `is_active`; `IngestionRun` là một lần chạy thực tế. `ingestion_run_id` trên `DataVersion` làm khóa batch: Job 1 lấy `raw_payloads` có `entity_type = NEWS`, Job 2 lấy `raw_payloads` có `entity_type = NEWS_DATA`, đều theo run của version. Luồng không dùng `data_lineage_events` để chọn candidate.

## Business Flow -> Actual Code

| Nghiệp vụ | Code thực tế |
| --- | --- |
| Scheduler động đọc cron/is_active | `IngestionJobScheduler.poll` → `IngestionJobService.executeDueJobs` |
| Dispatch hai job NEWS | `IngestionJobService.executeWithBudget` → `NewsWorkflowService.execute` |
| Chọn NEWS ACTIVE | `DataVersionJpaRepository.findByDataDomainAndStatusOrderByCreatedAtAsc` trong `NewsWorkflowService.fetchNewsData` |
| Tạo run Job 1/2 | `IngestionRunRepository.startInternalBatch` |
| Lấy RAW NEWS/NEWS_DATA của batch | `RawPayloadJpaRepository.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc` |
| Java gọi Python | `PythonExternalFinancialDataAdapter` với `ExternalOperation.FETCH_URL` |
| Python fetch URL | `app.api.v1.url_fetch.fetch_url` dùng `httpx.AsyncClient`, follow redirect |
| Ghi RAW NEWS_DATA và kích hoạt NEWS | `NewsWorkflowPersistenceService.persistFetchedNewsData` |
| Existing validation | `ValidationScheduler.validatePendingRawPayloads` → `ValidationJobService.validatePending` → `ValidationJobService.finalizeIngestionRun` |
| Tạo article/company link và kích hoạt NEWS_DATA | `NewsWorkflowPersistenceService.persistBuiltArticles` |

`NEWS_DATA_FETCH` chỉ fetch và persist. Python không đọc/ghi database, không validation và không tạo article. Với HTML/TEXT, `raw_text` giữ nguyên body; JSON được giữ ở `payload`; binary chỉ giữ `source_url`, `content_type` và metadata, không OCR/PDF parse. `NEWS_ARTICLE_BUILD` dùng parser tối thiểu hiện có trong `NewsWorkflowService.articleDraft`; vì codebase chưa có parser/matcher NEWS cũ, link công ty chỉ dùng `security_id` đã gắn với RAW NEWS, ghi theo match method database-supported `RULE`.

Dispatcher chọn NEWS workflow theo `ingestion_jobs.code`, không theo `parameters.operation`. Vì vậy Job 1 giữ `{"operation":"FETCH_URL"}` để mô tả external fetch, còn Job 2 dùng `{"workflow":"NEWS_ARTICLE_BUILD"}` để mô tả internal build và không bị hiểu nhầm là một URL fetch.

Hai commit point dùng `@Transactional` trong `NewsWorkflowPersistenceService`: Job 1 chỉ chuyển NEWS sang `ACTIVATED` sau khi toàn bộ NEWS_DATA của batch đã ghi và run thành công; Job 2 chỉ chuyển NEWS_DATA sau khi toàn bộ article/link đã ghi. Nếu fetch hoặc persist thất bại, run bị đánh dấu failed và version vẫn `ACTIVE`. `DataVersionJpaRepository.findByIdForUpdate` khóa version trong commit point. Scheduler là poller `fixedDelay`; mỗi job chỉ chạy khi cron đã đến hạn, không phải process chạy liên tục theo chu kỳ cron.

## Schema liên quan

- `data_sources`: nguồn của `IngestionJob` và `RawPayload`; database development hiện có record `NEWS_WEB` active, nên workflow catalog tham chiếu business code này và không tạo datasource NEWS mới.
- `ingestion_jobs`: `code`, `cron_expression`, `is_active`, `dataset_type`; seed có `NEWS_DATA_FETCH` và `NEWS_ARTICLE_BUILD`.
- `ingestion_runs`: run mới cho từng lần xử lý batch, có counter/status/metadata.
- `raw_payloads`: `ingestion_run_id`, `entity_type`, URL, content type, `payload`/`raw_text`, checksum và `security_id`.
- `data_versions`: batch validated, gồm `data_domain`, `status`, `ingestion_run_id`; code thêm chuyển trạng thái `ACTIVE → ACTIVATED`.
- `validation_results`: do `ValidationJobService` hiện hữu tạo; workflow không ghi trực tiếp.
- `news_articles` và `news_article_companies`: output idempotent theo `raw_payload_id`, sau đó quan hệ công ty theo source security nếu có.

## Migration từ flow cũ và trạng thái DB

Flow cũ `NEWS → NEWS_CONTENT → data_lineage_events` không được dùng để điều khiển workflow mới. Migration chỉ chạy trong transaction sau pre-flight thực tế: nó resolve chính xác một datasource NEWS generic đang active theo đặc tính `WEB`/`Multiple`, kiểm tra constraint/FK, xóa lineage `NEWS → NEWS_CONTENT`/`FETCH_NEWS_CONTENT` trước, rồi xóa `raw_payloads.entity_type = NEWS_CONTENT`. Trước `COMMIT`, migration xác nhận hai job, constraint `ACTIVATED`/`NEWS_DATA`, cleanup count và việc không thay đổi số bản ghi `NEWS`/`NEWS_DATA`; mọi điều kiện sai sẽ `RAISE EXCEPTION` và rollback.

Project hiện không có Flyway/Liquibase/SQL migration runner và `spring.jpa.hibernate.ddl-auto=none`. Migration thủ công nằm tại `db/manual/V20260912__news_workflow.sql`; nó giữ nguyên vocabulary constraint hiện hữu và chỉ thêm `ACTIVATED`/`NEWS_DATA` khi còn thiếu, upsert hai job bằng datasource đã resolve, và cleanup old NEWS data theo FK thực tế. Không thay `ACTIVATED` bằng `SUPERSEDED` hoặc `ARCHIVED`.

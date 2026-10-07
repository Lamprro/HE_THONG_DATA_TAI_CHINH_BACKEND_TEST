# Luồng xử lý NEWS

## Mục tiêu và sơ đồ

Luồng NEWS tách rõ lấy danh sách link, lấy HTML của từng trang và tạo bản ghi nghiệp vụ. `DataVersion` là batch của một `ingestion_run`, còn `RawPayload` là bản ghi thô trong batch. Vocabulary nghiệp vụ của workflow là `ACTIVATE` (đã validate, chờ job sau) và `ACTIVATED` (đã được job sau tiêu thụ). Trong code hiện tại, factory và query vẫn đang dùng `ACTIVE` cho trạng thái chờ; đây là điểm cần đồng bộ code/DB trước khi bật scheduler với vocabulary `ACTIVATE`.

```text
SOURCE
  ↓
RAW NEWS
  ↓
EXISTING VALIDATION
  ↓
NEWS DataVersion / ACTIVATE (code hiện tại: ACTIVE)
  ↓
NEWS_DATA_FETCH
  ↓
RAW NEWS_DATA (IngestionRun mới)
  ↓
NEWS DataVersion / ACTIVATED
  ↓
EXISTING VALIDATION
  ↓
NEWS_DATA DataVersion / ACTIVATE (code hiện tại: ACTIVE)
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
| Chọn NEWS version chờ xử lý | `NewsWorkflowService.fetchNewsData` query `data_domain=NEWS`, status `ACTIVE` hiện tại; nghiệp vụ đã thống nhất tên `ACTIVATE` |
| Tạo run Job 1/2 | `IngestionRunRepository.startInternalBatch` |
| Lấy RAW NEWS/NEWS_DATA của batch | `RawPayloadJpaRepository.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc` |
| Java gọi Python | `PythonExternalFinancialDataAdapter` với `ExternalOperation.FETCH_URL` |
| Python fetch và tách bài | `app.api.v1.url_fetch.fetch_url` tải một lần, rồi dùng `CafeFNewsSource.parse_article` để trả tiêu đề, sapo, thân bài, tác giả, ngày đăng và trạng thái tách bài |
| Ghi RAW NEWS_DATA và kích hoạt NEWS | `NewsWorkflowPersistenceService.persistFetchedNewsData` |
| Validate NEWS/NEWS_DATA | `ValidationScheduler.validatePendingRawPayloads` → `ValidationJobService.validatePending` → `ValidationRuleExecutionService.execute` → `ValidationJobService.finalizeIngestionRun` |
| Tạo article/company link và kích hoạt NEWS_DATA | `NewsWorkflowPersistenceService.persistBuiltArticles` dùng `NewsCompanyMatcher` để đọc title/sapo/content sạch và đối chiếu danh mục công ty, alias, chứng khoán active |

`NEWS_DATA_FETCH` chỉ fetch và persist. Python không đọc/ghi database, không validation và không tạo article. NEWS lưu từng mục trong `payload.data[]`, gồm URL và `publishedAt` riêng; `source_url` là URL API lấy danh sách. Mỗi NEWS_DATA ứng với một URL, lưu `requested_url`, `final_url`, `http_status`, `textual`, các trường bài đã tách và ngày đăng của mục NEWS trong `payload`; HTML gốc nằm trong `raw_text`. Ngày không có offset được hiểu theo `Asia/Ho_Chi_Minh`. Binary không được parse. `NEWS_ARTICLE_BUILD` chỉ dùng thân bài sạch đã tách để tạo `news_articles`/`news_article_companies`; `published_at` ưu tiên ngày trên bài, fallback ngày của mục NEWS, còn `crawled_at` là lúc tải.

Các rule được quản lý trực tiếp trong `validation_rules` và được thực thi bởi `ValidationRuleExecutionService`. Database mới khởi tạo qua `V20261004_04__news_validation_catalog.sql` (chỉ thêm luật chưa có); seed Java không còn ghi đè luật NEWS. NEWS có rule về cấu trúc danh sách, URL, title, ngày, symbol, URL trùng trong batch và checksum. NEWS_DATA có rule về metadata, URL, HTTP status, content type, raw HTML, cấu trúc HTML, trạng thái tách bài và dấu hiệu trang lỗi/chặn.

Dispatcher chọn NEWS workflow theo `ingestion_jobs.code`, không theo `parameters.operation`. Vì vậy Job 1 giữ `{"operation":"FETCH_URL"}` để mô tả external fetch, còn Job 2 dùng `{"workflow":"NEWS_ARTICLE_BUILD"}` để mô tả internal build và không bị hiểu nhầm là một URL fetch.

Hai commit point dùng `@Transactional` trong `NewsWorkflowPersistenceService`: Job 1 chỉ chuyển NEWS sang `ACTIVATED` sau khi toàn bộ NEWS_DATA của batch đã ghi và run thành công; Job 2 chỉ chuyển NEWS_DATA sau khi toàn bộ article/link đã ghi. Nếu fetch hoặc persist thất bại, run bị đánh dấu failed và version vẫn ở trạng thái chờ. HTTP 400/500 của lần gọi ingestion không tạo `raw_payload`; HTTP 200 của Python nhưng trang nguồn có `http_status` lỗi vẫn tạo NEWS_DATA raw để validation đánh FAIL. Scheduler là poller `fixedDelay`; nếu cấu hình tắt thì job không tự chạy.

## Schema liên quan

- `data_sources`: nguồn của `IngestionJob` và `RawPayload`; database development hiện có record `NEWS_WEB` active, nên workflow catalog tham chiếu business code này và không tạo datasource NEWS mới.
- `ingestion_jobs`: `code`, `cron_expression`, `is_active`, `dataset_type`; seed có `NEWS_DATA_FETCH` và `NEWS_ARTICLE_BUILD`.
- `ingestion_runs`: run mới cho từng lần xử lý batch, có counter/status/metadata.
- `raw_payloads`: `ingestion_run_id`, `entity_type`, URL, content type, `payload`/`raw_text`, checksum và `security_id`.
- `data_versions`: batch validated, gồm `data_domain`, `status`, `ingestion_run_id`; code thêm chuyển trạng thái `ACTIVE → ACTIVATED`.
- `validation_rules`: cấu hình rule theo domain, severity, executor và JSON config.
- `validation_results`: do `ValidationJobService` tạo; workflow không ghi trực tiếp.
- `news_articles` và `news_article_companies`: output của Job 2; chống trùng theo `raw_payload_id`, `url_hash`, `content_hash` của thân bài sạch. Bài mới hoặc bài trùng đều được dò tên/mã thực sự xuất hiện trong title, sapo, thân bài; chỉ gắn công ty/chứng khoán active đã có trong DB. Mỗi quan hệ lưu `relevance_score` và `match_evidence` (trường, từ khớp, trích đoạn). Mã nguồn của job không tự tạo quan hệ nếu bài không nhắc đến mã đó. Quan hệ trùng được bỏ qua; nếu toàn bộ version không thêm bài hay quan hệ mới, run `FAILED` và version `REJECTED`.

## Migration từ flow cũ và trạng thái DB

Flow cũ `NEWS → NEWS_CONTENT → data_lineage_events` không được dùng để điều khiển workflow mới. Migration chỉ chạy trong transaction sau pre-flight thực tế: nó resolve chính xác một datasource NEWS generic đang active theo đặc tính `WEB`/`Multiple`, kiểm tra constraint/FK, xóa lineage `NEWS → NEWS_CONTENT`/`FETCH_NEWS_CONTENT` trước, rồi xóa `raw_payloads.entity_type = NEWS_CONTENT`. Trước `COMMIT`, migration xác nhận hai job, constraint `ACTIVATED`/`NEWS_DATA`, cleanup count và việc không thay đổi số bản ghi `NEWS`/`NEWS_DATA`; mọi điều kiện sai sẽ `RAISE EXCEPTION` và rollback.

Project hiện không có Flyway/Liquibase/SQL migration runner và `spring.jpa.hibernate.ddl-auto=none`. Migration thủ công được tài liệu tham chiếu tại `db/manual/V20260912__news_workflow.sql`; cần kiểm tra file migration thực tế trước khi chạy vì file này hiện không có trong workspace. Việc đồng bộ rule NEWS/NEWS_DATA đã dùng `scripts/SyncNewsValidationRules.java`; script chỉ sửa `validation_rules`, không sửa raw, validation result hay data version.

# NEWS → LLM: hợp đồng, lưu trữ và vận hành

Cập nhật 04/10/2026. Java Spring Boot local; đã kiểm thử API Gemini thật.

## Phạm vi

Nguồn LLM là `news_articles` đã có nội dung, truy nguồn về `raw_payload_id` / NEWS_DATA.
Mỗi bài đủ điều kiện có hai tác vụ độc lập NEWS_SUMMARY, NEWS_DETAIL. NEWS_FINANCIAL_FACTS chỉ chạy sau khi
NEWS_DETAIL hiện hành xác nhận có số liệu tài chính hoặc nhận định dự báo. Scheduler và API dùng cùng service.
Tác vụ tài chính/thị trường sau này dùng chung catalog và log, nhưng cần schema đầu vào và FK nguồn riêng trước khi bật.
Không tự sinh template rỗng. Luồng FINANCIAL đã được triển khai riêng ngày 04/10/2026 với phạm vi và giới hạn tại [Financial Forecast Admin API](FINANCIAL_FORECAST_ADMIN_API.md); MARKET độc lập chưa triển khai.

## Ba prompt đã seed

| task_code | version | Mục đích | Đầu ra bổ sung |
|---|---|---|---|
| NEWS_SUMMARY | 1 | Tổng quan ngắn, ý chính | sections có bằng chứng |
| NEWS_DETAIL | 1 | Phân loại đa nhãn, sự kiện, bối cảnh, tác động từng công ty | document_types, contains_financial_figures, contains_forecasts, company_impacts |
| NEWS_FINANCIAL_FACTS | 3 | Trích số liệu, dự báo/kế hoạch do bài báo nêu | facts, fact_kind ACTUAL/FORECAST/TARGET, đơn vị, kỳ, người/tổ chức phát biểu |

Nội dung prompt và JSON Schema đầy đủ nằm trong `src/main/resources/llm/*.json`, được insert nguyên dạng
vào `llm_prompt_templates`. `(task_code, version)` là duy nhất; một task chỉ có một phiên bản enabled.
Seeder đối chiếu checksum và không ghi đè phiên bản đã tồn tại. Thay prompt/schema phải tăng version và chuyển enabled có kiểm soát.
Không sửa trực tiếp prompt đang được sử dụng trong DB.

## Request

`news.input.v1`: task_code và article gồm id, title, sapo, published_at (+07:00), url,
content_hash, segments [{id, text}], companies [{company_id, security_id, name, symbol, match_method, match_evidence}].
Backend chia nguyên văn thành các đoạn p1, p2... để trích dẫn; không âm thầm cắt mất phần cuối bài.
Giới hạn 150.000 ký tự, bài lớn hơn trả BODY_EXCEEDS_CONTEXT_LIMIT và chưa gọi model.
source_hash bao gồm toàn bộ snapshot bài và quan hệ công ty; input_hash còn bao gồm template checksum,
model và request thực tế (bao gồm cấu hình sinh). Đổi nội dung/tiêu đề/ngày/quan hệ làm kết quả cũ không còn phù hợp.

`source_job` là nguồn thu thập, không phải bằng chứng tác động. LLM chỉ được dùng ID công ty đã cấp;
nếu bên được nhắc chưa có trong danh sách thì mô tả bằng văn bản, không tạo ID mới.
Tin chứa chỉ dẫn bị coi là dữ liệu; model không được làm theo lệnh trong bài và không được truy cập URL/công cụ.

## Response và FE

Mọi tác vụ dùng `news.output.v1`: task_code, source_article_id, status,
overview, overview_evidence, sections, limitations và các trường riêng của task.
`sections[]` có type (KEY_FACT/EVENT/CONTEXT/RISK/OPPORTUNITY/LIMITATION), heading, content, evidence.
Mỗi evidence là {segment_id, quote}; quote phải là nguyên văn liên tục trong đoạn đã cấp.
FE dựng các section theo thứ tự mảng, xử lý content như text, không chạy HTML do LLM sinh.
Số mục linh hoạt trong giới hạn schema; không cho phép thuộc tính JSON tùy tiện ngoài hợp đồng.

company_impacts chứa company_id, direction, horizon, rationale, confidence, evidence.
confidence là tự đánh giá độ chắc chắn của model, không phải xác suất lợi nhuận đã hiệu chuẩn.
facts chứa metric_name, company_id (có thể null), value_text, unit, period, attributed_to, fact_kind, evidence.
Số liệu/dự báo trong tin chỉ được lưu trong kết quả NEWS. Không tự cập nhật financial_metrics/predictions.

Java kiểm tra JSON Schema và kiểm tra nguồn bài, ID công ty, trích dẫn, số liệu/đơn vị/kỳ trong bằng chứng,
trạng thái phù hợp, kết quả chưa lỗi thời. JSON sai, thiếu trường, thêm trường, key trùng, nhiều JSON nối nhau,
response bị cắt hoặc provider chặn đều bị từ chối. INSUFFICIENT không được xuất bản; PARTIAL cần limitations.
NOT_APPLICABLE chỉ hợp lệ cho facts rỗng và có giải thích.
Các kiểm tra này không chứng minh mọi suy luận của LLM là đúng; chất lượng nội dung cần đánh giá trên response Gemini thật khi có key.

## Lưu trữ và trạng thái

- `llm_prompt_templates`: cấu hình và schema bất biến theo version, input_domain NEWS/FINANCIAL/MARKET.
- `llm_runs`: prompt_template_id, task_code, article nguồn, model, request_metadata.input snapshot,
  request_payload body thật gửi Gemini, response_payload toàn bộ envelope, response_text nội dung model,
  validation_errors, HTTP status, token, latency, thời điểm và lỗi. Không lưu header API key.
- `llm_results`: FK article/run/template; source_hash, input_hash, schema_version, overview, result_json,
  quality_status VALID/WARNING, is_current. Một run thành công sinh một result.
- `validation_rules`: luật LLM_OUTPUT dùng chung catalog; `validation_results`: từng luật, llm_run_id, validation_round_id và rule_snapshot. Luật RAW_PAYLOAD giữ nguyên.
- `news_ai_analyses` rỗng đã được bỏ sau kiểm tra phụ thuộc và lưu DDL khôi phục; `analysis_reports` dành cho báo cáo tổng hợp tương lai.

API preview không ghi DB. Khi chưa cấu hình Gemini, execute trả CONFIGURATION_REQUIRED và không tạo run giả.
Lần gọi thật: RUNNING → PENDING_VALIDATION (response đã lưu) → SUCCESS + result, hoặc REJECTED khi output không đạt, FAILED khi transport/HTTP/cấu hình luật lỗi.
Xem [luồng validation dùng chung](LLM_SHARED_VALIDATION.md) về điều kiện xuất bản và khôi phục không gọi Gemini lại.
Hai request cùng bài/task được khóa nhận việc; không giữ transaction trong lúc chờ HTTP Gemini.
Lượt chạy quá 10 phút được đánh dấu lỗi để thu hồi; worker cũ không được công bố kết quả muộn.
Nguồn hoặc template thay đổi trong khi gọi: REJECTED, không ghi result.
Một bài/task chỉ có một result is_current. Bản trước được giữ lịch sử khi thay thế; API ẩn bản khác source_hash.
Ba lần FAILED/REJECTED cùng input/model/template thì dừng RETRY_LIMIT; cần xử lý nguyên nhân trước khi chạy lại.
Scheduler đợi ít nhất một giờ sau lỗi, có giới hạn 5 bài/lượt và dùng cùng cơ chế chống trùng.

## URL-only

`extraction_status=FAILED` kèm URL HTTP hợp lệ được dựng thành news_articles với metadata.content_status=URL_ONLY,
content_text/content_hash=null, tiêu đề/ngày từ danh sách nếu có. Giữ quan hệ nguồn. Ngày thiếu được để null,
không lấy crawled_at thay published_at. Bài dạng link bị chặn trước LLM.
URL loại utm_*, fbclid, gclid, zarsrc và fragment; giữ tham số mang ý nghĩa định danh bài.
Hai URL-only khác nhau không bị coi trùng chỉ vì đều content_hash=null.
Lần fetch lỗi không ghi đè body đầy đủ; body thành công có thể nâng cấp URL-only khi được tải lại qua luồng thu thập.
Trùng bài/quan hệ và không có thay đổi: data_version REJECTED; thêm bài hoặc quan hệ: ACTIVATED.

## API local

| Method | URL | Ý nghĩa |
|---|---|---|
| GET | /api/admin/llm/templates | Prompt + schema đang enabled |
| GET | /api/admin/llm/news/candidates?limit=5 | Bài cần xử lý |
| GET | /api/admin/llm/news/{id}/preview?task=NEWS_SUMMARY | Snapshot, điều kiện, request Gemini, schema response |
| POST | /api/admin/llm/news/{id}/execute | Chạy hai tác vụ bắt buộc và xét tác vụ thứ ba |
| POST | /api/admin/llm/news/{id}/tasks/{task}/execute | Chạy riêng một task |
| GET | /api/admin/llm/news/{id}/results | Kết quả hiện hành còn khớp nguồn |
| GET | /api/admin/llm/runs/{id} | Log chi tiết lần gọi |
| POST | /api/admin/llm/runs/{id}/revalidate | Kiểm tra response đã lưu theo luật hiện tại, không gọi Gemini |
| POST | /api/admin/llm/validation/pending?limit=5 | Hoàn tất response đang chờ validation |

Các endpoint theo cơ chế admin hiện có của dự án. Bản kiểm thử chỉ bind 127.0.0.1; chưa triển khai Internet.
Không có endpoint cho người gọi tự nộp response model để ghi vào kết quả thật.

## Cấu hình sau khi có key

Cấu hình nhiều mô hình, retry, token budget và bằng chứng gọi thật ngày 04/10/2026:
xem [GEMINI_LOCAL_SETUP.md](GEMINI_LOCAL_SETUP.md). Phần bằng chứng 30/09 bên dưới là lịch sử, không phải trạng thái hiện tại.

`LLM_ENABLED=true`, `GEMINI_API_KEY` lấy từ môi trường/file local; `GEMINI_MODEL` là tùy chọn ưu tiên; không commit key.
Model do người vận hành chọn và kiểm thử; không hard-code một model có thể hết hỗ trợ.
Gemini adapter sử dụng generateContent, JSON response schema hỗ trợ bởi provider; Java vẫn kiểm tra schema đầy đủ.
Chạy API trên vài bài trước, đánh giá chất lượng và token/latency rồi mới bật `financial.llm.scheduler.enabled=true`.

Migration: `src/main/resources/db/manual/V20260930_01__llm_news_pipeline.sql`, áp dụng tường minh trong transaction.
Seed: khởi động có `--financial.llm.catalog.seed-enabled=true`, chạy lại không insert trùng.
Không tự chạy DDL khi ứng dụng khởi động thông thường.

## Kiểm thử và bằng chứng ngày 30/09/2026

- Bộ kiểm thử chọn đúng phạm vi NEWS/LLM: **79 tests, 0 failure, 0 error, 0 skipped**;
  gồm 12 kiểm thử tích hợp HTTP/PostgreSQL trong schema riêng. `git diff --check` không có lỗi whitespace.
- API thật preview cuối: 359 bài × 3 prompt (1.077 request); 357 bài đầy đủ qua request validation;
  một bài fixture cũ và một bài URL-only bị chặn. DB có 3 prompt enabled, 0 llm_runs, 0 llm_results,
  0 nhóm trùng url_hash, 0 schema test còn sót.
- Fixture `b5d7e4db-eff7-4566-9416-8334b971f530` xác nhận có nguồn từ RealDatabaseNewsFlowVerificationTest;
  được đánh dấu is_test/llm_excluded, giữ nguyên cho truy vết. Test cũ ghi mock vào DB chung được disable.
- FPT NEWS run `3a2f78fb-3a0f-4e66-8967-4a2b8e16877b`: Python trả HTTP 200; validate rồi fetch không tạo bài trùng.
- HPG NEWS run `a5d0fd91-366a-4d4f-b218-20683bdc68ee`: HTTP 200.
  NEWS_DATA `97c7dc52-1458-4287-ade8-5eb50927b587` trả extraction FAILED; phát hiện code trước đây bỏ URL-only.
  Sau sửa, replay đúng version `53947e8f-5748-427b-8b0d-4eccf51e6fab` qua API build thành ACTIVATED.
  Article `10dc70ae-fdfd-464e-85b8-74bb32c5e8f4` giữ URL, body/hash null, một quan hệ công ty; LLM preview chặn BODY_MISSING_OR_TOO_SHORT.
- Test Java LLM dùng schema PostgreSQL ngẫu nhiên `llm_test_<uuid>`, copy nguồn thật để kiểm tra API → validation → persistence.
  Provider response là fixture chỉ trong test; schema được drop sau test. Không có kết quả AI giả trong bảng chung.
- Chưa gọi Gemini thật: chưa xác nhận model chấp nhận request, chất lượng phân tích, độ trễ hoặc chi phí thực tế.

Chạy test có DB: đặt `LLM_TEST_DB=1` rồi chạy Maven với các lớp NewsLlmContractTests,
NewsLlmIsolatedIntegrationTests, NewsWorkflowServiceTests, NewsWorkflowPersistenceServiceTests,
NewsCompanyMatcherTests, NewsValidationRulesTests, NewsUrlNormalizerTests.
Đừng chạy các test mock cũ trên DB dùng chung nếu chưa cô lập side effect.

Tham chiếu kỹ thuật: https://ai.google.dev/api/generate-content và https://github.com/networknt/json-schema-validator/tree/1.5.9.

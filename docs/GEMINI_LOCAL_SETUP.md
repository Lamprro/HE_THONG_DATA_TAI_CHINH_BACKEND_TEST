# NEWS → Gemini: cấu hình và kiểm thử local

Cập nhật 04/10/2026. Thay đổi Java chỉ ở local, chưa push/deploy.

## Dữ liệu và kết quả

Luồng chính giữ nguyên: API nguồn → raw_data → data_version NEWS_DATA → news_articles/quan hệ.
LLM đọc bài chuẩn hóa trong news_articles, không phân tích trực tiếp HTML thô hoặc mọi raw NEWS_DATA.
Bài chỉ có URL vẫn được hiển thị dạng link nhưng bị SKIPPED trước khi gọi Gemini.

Ba tác vụ: NEWS_SUMMARY (tóm tắt), NEWS_DETAIL (phân loại/phân tích/tác động có bằng chứng),
NEWS_FINANCIAL_FACTS (trích số liệu đã công bố/dự báo của nguồn; chỉ chạy khi DETAIL xác định có số liệu).
Không biến số dự báo của bài thành dự báo của hệ thống; không ghi vào bảng báo cáo tài chính gốc.

- `llm_prompt_templates`: prompt và request/response JSON Schema có phiên bản bất biến.
- `llm_runs`: một lần xử lý nghiệp vụ, snapshot đầu vào, template, raw response cuối, lỗi validation và trạng thái.
- `llm_run_attempts`: từng lần gọi mạng trong run, đúng mô hình, request/response, HTTP, latency, token.
- `llm_results`: chỉ kết quả qua validation, JSON cùng cấu trúc cho FE; liên kết bài gốc và run.
- `validation_rules` / `validation_results`: luật LLM_OUTPUT và kết quả từng luật gắn llm_run_id, round, snapshot. Xem [luồng validation](LLM_SHARED_VALIDATION.md).

Một bài/tác vụ chỉ có một kết quả hiện hành. Các phiên bản trước giữ lịch sử.
Kết quả sai schema, sai ID, quote không có trong đoạn nguồn, số liệu không có bằng chứng,
nguồn hoặc prompt thay đổi khi đang chạy: không công bố thành result.
Java kiểm tra tính cấu trúc và đối chiếu nguồn; không thể chứng minh mọi diễn giải của AI đều đúng về ngữ nghĩa.

## Cấu hình

Xem `application-local.properties.example`. Sao chép sang `application-local.properties` nếu chưa có;
KHÔNG ghi đè cấu hình DB đang dùng. File local được Git bỏ qua. Không đặt key trong application.properties được commit.

| Biến môi trường | Giá trị/mục đích |
|---|---|
| DB_URL / DB_USERNAME / DB_PASSWORD | Kết nối PostgreSQL của môi trường local |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD | Redis theo cấu hình dự án |
| LLM_ENABLED | `true` để cho phép API gọi Gemini; mặc định false |
| GEMINI_API_KEY | Khóa Google; không ghi vào log/header audit |
| GEMINI_MODEL | Mô hình ưu tiên, tùy chọn; đặt đầu danh sách và loại trùng |
| GEMINI_MODELS | Danh sách ID mô hình có thứ tự, phân tách bằng dấu phẩy |
| GEMINI_MAX_ATTEMPTS | Tối đa 8 lần gọi mạng/tác vụ |
| GEMINI_MAX_ATTEMPTS_PER_MODEL | Mặc định 1 lần/mô hình/tác vụ |
| GEMINI_ATTEMPT_TIMEOUT_MS | 45000 ms/lần gọi |
| GEMINI_TOTAL_TIMEOUT_MS | 180000 ms cho chuỗi gọi provider của một tác vụ |
| GEMINI_BACKOFF_MS / GEMINI_MAX_BACKOFF_MS | 1000 / 8000 ms; tăng dần có độ lệch ngẫu nhiên |
| GEMINI_COOLDOWN_MS | 60000 ms; kết hợp Retry-After khi 429 |
| GEMINI_MAX_OUTPUT_TOKENS | 16384, trần output gồm ngân sách suy luận của provider |
| GEMINI_THINKING_BUDGET | 1024; tránh suy luận mặc định chiếm hết output |

Ngân sách provider không bao gồm thời gian DB; endpoint execute có tối đa ba tác vụ tuần tự.
Tổng thời gian có thể xấp xỉ 9 phút cộng DB. Dùng endpoint từng task khi client timeout ngắn.

ListModels thật xác nhận tám ID: gemini-3.5-flash-lite, gemini-3.5-flash, gemini-3.8-flash,
gemini-3.1-flash-lite, gemini-3.6-flash, gemini-3.7-flash, gemini-2.5-flash, gemini-2.5-flash-lite.
Tên 3.5 Flash Lite bị lặp trong yêu cầu đã được loại trùng. Hai tên 2.0 không có trong danh sách đã kiểm tra.
Có trong ListModels không đảm bảo generateContent được phép: 2.5 Flash Lite thực tế trả 404 cho tài khoản này.
Không khẳng định đã generation-test thành công trên cả tám model.

Chuyển mô hình khi 404, 408, 429, 500, 502, 503, 504, lỗi mạng hoặc timeout, trong ngân sách đã đặt.
400/401/403 dừng vì request/quyền/key; không thử tất cả model với cùng lỗi.
Safety block, JSON bị cắt hoặc không hợp lệ không được tự sửa thành dữ liệu hợp lệ.
Output không đạt validation kết thúc REJECTED, không tự đổi model để bỏ qua validator.
Lần chạy lại thủ công/scheduler có thể gửi output trước và lỗi validation để model sửa theo nguồn.
Request thực tế được lưu đầy đủ; khóa input gốc không đổi nên phản hồi sửa lỗi không reset giới hạn ba run.
Không áp dụng phản hồi sửa lỗi cho refusal, output bị cắt, thay đổi nguồn hoặc template.
Ba run FAILED/REJECTED với cùng request/template/routing dừng RETRY_LIMIT; scheduler chờ ít nhất 1 giờ sau lỗi.
Cooldown model là trong bộ nhớ của từng tiến trình, không phải quota toàn cluster; production nhiều replica cần quản lý quota chung.

## Khởi động và API

Áp dụng tường minh các migration theo thứ tự bằng công cụ DB đang dùng:
`V20260930_01__llm_news_pipeline.sql`, `V20261004_01__llm_attempt_audit.sql`,
`V20261004_02__llm_shared_validation.sql`, `V20261004_03__remove_empty_legacy_news_ai.sql`.
Migration cuối thay view, chỉ bỏ bảng legacy nếu rỗng; không dùng CASCADE.
Khởi động một lần với `--financial.llm.catalog.seed-enabled=true` để seed/promote prompt.
Phiên bản prompt mới tắt bản cũ trong cùng transaction, giữ nguyên liên kết lịch sử; phiên bản cũ không bị sửa.

```powershell
$env:LLM_ENABLED='true'
# GEMINI_API_KEY lấy từ môi trường hoặc file local, không ghi key vào lịch sử lệnh.
mvn '-Dmaven.repo.local=target/m2repo' spring-boot:run '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --financial.llm.scheduler.enabled=false --financial.ingestion.scheduler.enabled=false --financial.validation.scheduler.enabled=false'
```

| API | Kiểm tra |
|---|---|
| GET /api/admin/llm/gemini/configuration | Danh sách, giới hạn, key_present; không lộ key |
| GET /api/admin/llm/gemini/models | Gọi ListModels thật, không generation |
| GET /api/admin/llm/templates | Prompt/schema đang active |
| GET /api/admin/llm/news/{id}/preview?task=NEWS_SUMMARY | Đầu vào thật và request Gemini |
| POST /api/admin/llm/news/{id}/execute | Chạy đầy đủ các tác vụ áp dụng được |
| POST /api/admin/llm/news/{id}/tasks/NEWS_FINANCIAL_FACTS/execute | Chạy riêng task |
| GET /api/admin/llm/news/{id}/results | JSON đã qua validation và còn khớp nguồn |
| GET /api/admin/llm/runs/{id} | Log run và mọi attempt |
| POST /api/admin/llm/runs/{id}/revalidate | Kiểm tra lại response đã lưu, không gọi Gemini |
| POST /api/admin/llm/validation/pending?limit=5 | Hoàn tất response chờ kiểm tra |

Chỉ bind localhost. Trước khi mở server ra Internet phải kiểm tra cơ chế xác thực/phân quyền admin của dự án;
các API này có thể phát sinh chi phí và trả nội dung log. Không bật scheduler hàng loạt trước khi nghiệm thu chất lượng.

## Phát hiện bằng gọi thật và sửa

1. Schema có bounds mảng lồng nhau gây HTTP 400. Thử cùng payload bài thật bỏ riêng minItems/maxItems trả 200.
   Adapter chỉ gửi schema cấu trúc phù hợp; validator Java vẫn áp dụng đầy đủ schema gốc.
2. Chia body theo số ký tự từng cắt giữa câu. Nay ưu tiên ranh giới câu, giữ nguyên từng ký tự và không cắt bỏ nội dung.
   Snapshot thay đổi nên kết quả cũ không còn được trả như kết quả khớp nguồn mới; lưu lại lịch sử.
3. Flash trả MAX_TOKENS khi suy luận chiếm phần lớn ngân sách. Đặt thinking/output budget rõ ràng.
4. Prompt số liệu v2 yêu cầu value/unit/period/attributed_to có bằng chứng ngay trong từng fact;
   v3 đưa yêu cầu này vào mô tả schema của chính các trường.
   Các output sai vẫn bị REJECTED; không tự sửa quote/số liệu, không nới validator.
5. Fallback thật đã được quan sát: run `8b25076a-1892-4846-ae25-3258a884459f` có
   2.5 Flash Lite → HTTP 404 → 3.5 Flash → HTTP 200. Output sau đó bị validator từ chối quote không khớp.
   Đây là bằng chứng chuyển model hoạt động, không phải bằng chứng output đó hợp lệ.

Các gọi chẩn đoán schema dùng payload preview của chính API, không ghi output trực tiếp vào DB.
Các kết quả lưu nghiệp vụ đều đi qua API Spring Boot → Gemini → validator → persistence.
Test mô phỏng lỗi mạng chạy ở HTTP server test; test DB chạy trong schema ngẫu nhiên và dọn sau test.
Không chèn kết quả AI giả vào bảng dùng chung.

Tài liệu tham chiếu: [Gemini structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output),
[thinking budget](https://ai.google.dev/gemini-api/docs/generate-content/thinking), [Models API](https://ai.google.dev/api/models).

## Kết quả kiểm tra trước khi bổ sung validation dùng chung

- **96 tests, 0 failures, 0 errors, 0 skipped**, gồm 14 test tích hợp schema PostgreSQL riêng,
  15 test HTTP routing/chia đoạn, và các test hợp đồng/NEWS còn lại.
- Gọi thật trên FPT `f10ad0c8-ec72-4176-a01b-a990cc4d4f02` và HPG `0cf3c566-bd31-4fbf-88c8-90d1ddcca640`:
  mỗi bài có đủ SUMMARY, DETAIL, FINANCIAL_FACTS được validator chấp nhận; API trả **6 kết quả hiện hành**.
- Số liệu thành công sau retry có phản hồi lỗi:
  FPT run `2d0efdec-6e1c-431e-b08c-2d3d063a6ddc`, HPG run `0fda54d8-fc48-42e3-afc5-e997216e914f`.
  FPT có 5 facts; HPG có 11 facts, phân biệt ACTUAL/FORECAST và giữ quote/nguồn/thời kỳ.
  Các trường phụ chưa có quote trực tiếp có thể null; không bảo đảm đã trích hết mọi số trong bài.
- Gọi lại execute cả hai bài: cả sáu tác vụ trả CACHED. Đối chiếu DB trước/sau không đổi:
  **27 runs / 35 attempts / 13 results lịch sử**, trong đó 6 results hiện hành. Không sinh gọi Gemini thêm.
- Tổng log kiểm thử thật: 13 SUCCESS, 12 REJECTED, 2 FAILED; giữ nguyên lịch sử lỗi, không xóa để làm đẹp tỷ lệ.
  Những lần từ chối gồm quote/nguồn/thời kỳ thiếu bằng chứng, thiếu sections, MAX_TOKENS.
- URL-only `10dc70ae-fdfd-464e-85b8-74bb32c5e8f4`: cả ba tác vụ SKIPPED, không tạo run/result.
- DB không có nhóm trùng kết quả hiện hành theo bài/task; không còn schema test.
- Có fallback thật từ 404 và 503; model thực sự tạo kết quả là 3.8 Flash hoặc 3.5 Flash Lite tùy run.

Cấu hình local riêng ưu tiên `gemini-3.8-flash`, rồi dùng danh sách fallback đã nêu.
Tiến trình local kiểm thử đang cho phép gọi API LLM thủ công, scheduler tắt.
Khi khởi động lại bình thường, `LLM_ENABLED` mặc định false; bật tường minh khi muốn gọi tiếp.
Không push Git, không deploy server. Bộ test và mẫu thật chứng minh các nhánh đã kiểm tra,
không phải cam kết mọi bài/model luôn trả đúng ngay lần đầu hoặc mọi phân tích AI đúng tuyệt đối.
Trước khi chạy hàng loạt nên nghiệm thu thêm mẫu đa dạng và theo dõi tỷ lệ REJECTED/chi phí.

## Bổ sung validation dùng chung ngày 04/10/2026

107 tests, 0 failures, 0 errors, 0 skipped, gồm 20 test tích hợp PostgreSQL cô lập.
Bao gồm phục hồi PENDING_VALIDATION, lỗi/thiếu luật, snapshot/round, đổi version luật,
revalidate response đã lưu và cache không gọi provider lại. JSONB thay đổi thứ tự khóa đã được xử lý
bằng chuẩn hóa JSON khi tính input_hash.

Gọi thật FPT qua luồng mới: SUMMARY run 7a71481d-9cd9-44aa-a10a-7c805aa37699,
DETAIL run b8d37060-1a18-4bc2-8918-3456949ba83b,
FINANCIAL_FACTS run d47c0f2c-c4cd-4b37-a31e-56de0a14071a đều SUCCESS.
Các response trước sai quote/attribution bị REJECTED, không tạo result và không bị xóa log.
Các kết quả Gemini thật hiện hành được revalidate theo luật DB mà không gọi lại model.

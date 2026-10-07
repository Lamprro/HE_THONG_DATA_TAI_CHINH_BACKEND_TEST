# Báo cáo luồng NEWS → LLM: code, dữ liệu, validation và các tình huống xử lý

Ngày đối chiếu: **04/10/2026**. Phạm vi: code Java Spring Boot trong project local hiện tại.

Báo cáo này mô tả chức năng đã có trong code, không phải bản thiết kế cho một hệ thống khác.
Số liệu kiểm thử bên dưới là kết quả nghiệm thu local trước khi viết báo cáo; việc viết báo cáo không gọi thêm Gemini và không sửa dữ liệu nghiệp vụ.
Chưa push Java hoặc triển khai server. Scheduler LLM đang tắt trong phiên kiểm thử local.

## 1. Kết luận để đọc trước

1. LLM đọc **bài đã được dựng trong `news_articles`**, không đọc trực tiếp HTML hoặc danh sách NEWS thô.
2. Một bài có thể có ba kết quả độc lập: tóm tắt, phân tích chi tiết, trích xuất số liệu tài chính/dự báo được bài nêu.
3. Mọi kết quả có quan hệ về bài gốc. Nội dung AI không ghi đè nội dung bài báo.
4. Response từ provider được lưu trước; chưa qua kiểm tra thì chưa được công bố trong `llm_results`.
5. Cấu hình luật nằm trong `validation_rules`; từng kiểm tra được ghi vào `validation_results`, có `llm_run_id` và lượt kiểm tra riêng.
6. Java vẫn chứa executor thực thi luật. Database không thay thế code kiểm tra và không chứa mã thực thi tùy ý.
7. `llm_results` chỉ chứa kết quả được chấp nhận. Response lỗi vẫn nằm trong log, không bị xóa để làm đẹp dữ liệu.
8. Đầu ra LLM không đi thêm một vòng `ingestion_jobs → ingestion_runs → raw_payloads → data_versions`.
9. Luồng đã có chống chạy đồng thời, cache, retry giới hạn và khôi phục response đang chờ validate. Đây không phải cam kết mọi bài đều được AI phân tích đúng tuyệt đối.

## 2. Vị trí của LLM trong toàn hệ thống

```text
Luồng thu thập hiện hữu
API nguồn/Python → raw_payloads → validation dữ liệu nguồn → data_versions NEWS_DATA
                                                        ↓
                                   NewsWorkflowService / NewsWorkflowPersistenceService
                                                        ↓
                                  news_articles + news_article_companies
                                                        │
Luồng phân tích AI                                       ↓
Admin API hoặc NewsLlmScheduler → NewsLlmService
  → chọn prompt trong llm_prompt_templates
  → NewsLlmContext: snapshot bài và quan hệ công ty
  → kiểm tra đầu vào, cấu hình luật, cache và quyền nhận việc
  → llm_runs RUNNING
  → GeminiLlmGateway: HTTP thật, fallback có giới hạn
  → llm_run_attempts: lưu từng lần gọi mạng
  → lưu response vào llm_runs: PENDING_VALIDATION
  → LlmValidationService + NewsLlmValidator
  → validation_results: từng luật và từng round
  ├─ đạt → llm_runs SUCCESS + llm_results
  ├─ output không đạt → llm_runs REJECTED, không tạo result
  └─ HTTP/transport/cấu hình/thực thi lỗi → FAILED, không tạo result
                                                        ↓
                                   API kết quả → FE hiển thị JSON hợp lệ
```

Ranh giới quan trọng: NEWS_DATA là dữ liệu tải bài; `news_articles` là bản bài đã dựng.
LLM chỉ bắt đầu sau ranh giới này. Job NEWS_ARTICLE_BUILD và job LLM không phải cùng một job.

Luồng dựng bài nằm trong `NewsWorkflowService.execute()/buildArticles()` và
`NewsWorkflowPersistenceService.persistBuiltArticles()`. Bài trùng được giải quyết ở tầng này;
luồng LLM còn kiểm tra `dedup_status` và cờ trùng trước khi gửi đi.

## 3. Những lớp và hàm tham gia

Các lớp LLM nằm tại `src/main/java/com/hethongdata/taichinh/service/llm/`.

| Lớp | Hàm chính | Vai trò thực tế |
|---|---|---|
| `NewsLlmAdminController` | `preview`, `execute`, `executeTask`, `results`, `run`, `revalidate`, `validatePending` | Điểm vào API admin, chuyển việc cho service/store; không tự phân tích bài |
| `NewsLlmScheduler` | `tick()` | Khôi phục các response chờ validate, rồi chọn bài và gọi cùng service như API |
| `NewsLlmService` | `executeAll()` | Chạy tuần tự các task đã hỗ trợ và đang enabled |
| `NewsLlmService` | `execute()` | Điều phối một task từ kiểm tra nguồn đến gọi Gemini và hoàn tất kết quả |
| `NewsLlmService` | `preview()` | Dựng đầu vào và request để xem trước; không ghi run/result |
| `NewsLlmService` | `withValidationFeedback()` | Thêm response cũ và lỗi cụ thể cho lần retry nghiệp vụ tiếp theo |
| `NewsLlmService` | `candidates()` | Lọc bài cần xử lý cho scheduler; không thay thế kiểm tra cuối trong `execute()` |
| `NewsLlmService` | `validatePending()` | Quét run PENDING_VALIDATION và gọi `store.revalidate()` |
| `LlmPromptCatalog` | `active()`, `activeTemplates()`, `seed()` | Đọc prompt/schema đang enabled từ DB; seed có kiểm soát các phiên bản prompt |
| `NewsLlmContext` | `build()`, `segmentText()` | Đọc bài và quan hệ, kiểm tra điều kiện, chia nội dung nguyên văn, tính snapshot nguồn |
| `LlmGateway` | `request()`, `call()`; records `Reply`, `Attempt` | Hợp đồng giao tiếp provider, không phải một luồng Python tự tạo |
| `GeminiLlmGateway` | `request()`, `providerSchema()`, `call()`, `parse()` | Dựng request Gemini, gọi HTTP, chuyển model, đọc envelope và nội dung response |
| `GeminiRoutingProperties` | `orderedModels()` và các thuộc tính giới hạn | Thứ tự model, timeout, số lần thử, cooldown và cấu hình sinh |
| `LlmJson` | `read()`, `canonical()` | Đọc JSON nghiêm ngặt; chuẩn hóa thứ tự khóa object để tính hash ổn định |
| `LlmValidationService` | `rules()`, `ensureComplete()`, `fingerprint()`, `evaluate()` | Đọc luật DB, kiểm tra đủ luật bắt buộc, thực thi và ghi audit từng luật |
| `NewsLlmValidator` | `schema()`, `response()`, `execute()`, `inspect()` | Executor kiểm tra JSON Schema, nguồn, công ty, trích dẫn và số liệu |
| `LlmRunStore` | `claim()`, `lock()` | Nhận việc có khóa; chống trùng/chạy đồng thời, kiểm tra cache và retry limit |
| `LlmRunStore` | `recordAttempt()` | Lưu từng lần gọi mạng vào llm_run_attempts |
| `LlmRunStore` | `stageResponse()` | Lưu response bền vững trước validation |
| `LlmRunStore` | `finish()`, `fail()` | Hoàn tất run; chỉ publish result khi đạt điều kiện |
| `LlmRunStore` | `results()`, `run()` | Trả kết quả còn phù hợp và log đầy đủ |
| `LlmRunStore` | `revalidate()`, `retireFinancial()` | Kiểm tra lại response không gọi provider; ngừng công bố kết quả financial không còn áp dụng |

## 4. Từng bước của một request xử lý bài

### Bước 1 — Nhận yêu cầu

API gọi toàn bài vào `executeAll(articleId)`. Hàm này xử lý theo thứ tự:

1. `NEWS_SUMMARY`.
2. `NEWS_DETAIL`.
3. `NEWS_FINANCIAL_FACTS` nếu điều kiện ở bước kiểm tra task cho phép.

Đây là ba task/run độc lập, không phải một response Gemini chứa cả ba kết quả.
Một task trả REJECTED/FAILED dưới dạng Outcome không tự rollback task khác đã SUCCESS.
Nếu có exception chưa xử lý, vòng gọi có thể bị ngắt; không được hiểu `executeAll()` là transaction toàn bộ bài.
Task bị tắt template trả `TEMPLATE_DISABLED` trong API executeAll.

### Bước 2 — Lấy prompt đúng tác vụ

`LlmPromptCatalog.active(task)` chỉ chấp nhận ba task NEWS đã hỗ trợ.
Template phải enabled và `input_domain='NEWS'`.

Template chứa: ID, task, version, system prompt, request schema, response schema và checksum.
Thiếu template hoặc task không hỗ trợ là lỗi tham số/cấu hình, không gọi Gemini.
Không tự tạo prompt rỗng để tiếp tục chạy.

`seed()` đọc các resource `llm/*.json` khi bật seeder riêng.
Cùng task/version nhưng checksum khác sẽ bị từ chối, yêu cầu tăng version.
Khi seed phiên bản mới, bản cũ bị tắt trong cùng transaction; log cũ vẫn giữ FK tới phiên bản cũ.
Đây là seed **prompt**, không phải file validation NEWS đã xóa.

### Bước 3 — Dựng và kiểm tra nguồn

`NewsLlmContext.build(articleId, task)` đọc `news_articles`, rồi lấy các quan hệ từ
`news_article_companies`, tên công ty từ `companies` và mã từ `securities`.

Các điều kiện chặn trước khi gọi LLM:

| Điều kiện | Issue |
|---|---|
| Nội dung null hoặc sau strip ngắn hơn 200 ký tự | `BODY_MISSING_OR_TOO_SHORT` |
| Nội dung dài hơn 150.000 ký tự | `BODY_EXCEEDS_CONTEXT_LIMIT` |
| Thiếu tiêu đề | `TITLE_MISSING` |
| Thiếu ngày đăng | `PUBLISHED_AT_MISSING` |
| dedup_status không UNIQUE hoặc có duplicate_of_news_article_id | `DUPLICATE_OR_UNRESOLVED` |
| Nguồn đã đánh dấu xóa | `DELETED_SOURCE` |
| Metadata đánh dấu test/loại LLM, hoặc URL mang dấu hiệu test | `TEST_OR_EXCLUDED_SOURCE` |
| Còn cụm menu bẩn đã biết trong thân bài | `PAGE_CHROME_IN_BODY` |
| SHA-256 của nội dung không khớp content_hash | `CONTENT_HASH_MISMATCH` |

Nếu có issue, `execute()` trả SKIPPED: không tạo run, không tạo result, không gọi Gemini.
Bài URL-only thuộc trường hợp thiếu nội dung; bài và URL vẫn được giữ để hiển thị link.
Không dùng crawled_at để bịa published_at và không bắt AI suy đoán nội dung từ URL.

Ngoài các điều kiện trên, `NewsLlmValidator.schema(requestSchema, input)` kiểm tra cấu trúc đầu vào.
Đầu vào không đạt schema cũng SKIPPED trước provider.

### Bước 4 — Điều kiện riêng của trích xuất financial

`NEWS_FINANCIAL_FACTS` lấy DETAIL đang được `store.results()` chấp nhận:

- Chưa có DETAIL hợp lệ: trả `WAITING_FOR_DETAIL`, không gọi model.
- DETAIL có cả `contains_financial_figures=false` và `contains_forecasts=false`: trả `NOT_APPLICABLE`;
  `retireFinancial()` chuyển result financial hiện hành, nếu có, thành không current.
- Có số liệu hoặc dự báo: mới tiếp tục gọi task financial.

DETAIL cũ nhưng vẫn phù hợp nguồn/prompt/luật có thể được dùng nếu lần tạo DETAIL mới thất bại.
Thất bại lần mới không tự xóa kết quả cũ vẫn còn hợp lệ.

### Bước 5 — Kiểm tra provider, luật và khóa nhận việc

Provider phải được bật và có key; nếu không, trả `CONFIGURATION_REQUIRED`, không tạo run giả.
`LlmValidationService.fingerprint(task)` kiểm tra luật trước khi phát sinh chi phí gọi Gemini.
Thiếu luật bắt buộc, executor không biết, config không hợp lệ hoặc severity sai:
`VALIDATION_CONFIGURATION_ERROR`, chưa tạo run/call.

`input_hash` = SHA-256 của checksum template + routing key + fingerprint luật + request gốc chuẩn hóa.
`LlmJson.canonical()` sắp xếp khóa object theo thứ tự ổn định, giữ thứ tự array.
Nhờ đó JSONB đổi thứ tự khóa không gây gọi provider lại cho request tương đương.

`LlmRunStore.claim()` khóa PostgreSQL theo `(articleId, task)` và xử lý:

1. Có result current với cùng input_hash: `CACHED`, không gọi provider.
2. Run RUNNING quá 10 phút: đánh dấu FAILED với `RUN_LEASE_EXPIRED`.
3. Còn RUNNING/PENDING_VALIDATION: trả `RUNNING`, không tạo một lần gọi song song.
4. Có ít nhất ba FAILED/REJECTED cùng input_hash: `RETRY_LIMIT`.
5. Được nhận việc: tạo llm_runs RUNNING, snapshot input, request và metadata.

### Bước 6 — Gọi Gemini và lưu từng attempt

Sau transaction nhận việc kết thúc mới gọi HTTP. Không giữ transaction DB mở suốt thời gian đợi Gemini.
`GeminiLlmGateway.call(request, auditCallback)` gọi `generateContent` và gửi callback cho từng attempt.
`LlmRunStore.recordAttempt()` lưu riêng model, HTTP/lỗi mạng, request, response, token và latency.

Key nằm trong header gửi provider, không nằm trong request_payload hoặc header audit.
Nếu toàn chuỗi gọi ném lỗi transport hoặc bị interrupt, service gọi `store.fail()` và không tạo result.

### Bước 7 — Parse và lưu response trước validation

Provider parse chỉ lấy text từ candidate đầu tiên nếu `finishReason='STOP'`; phần thought không đưa vào output.
Không ghép nhiều candidate thành một bài phân tích. MAX_TOKENS/refusal/thiếu output không được xem là hợp lệ.

`LlmJson.read()` từ chối JSON null/rỗng, key trùng và dữ liệu dư sau JSON.
HTTP không 2xx, output thiếu/bị chặn hoặc JSON không parse được được ghi thành readiness errors.

`stageResponse()` lưu envelope, response_text, HTTP, token, model, latency vào run,
chuyển RUNNING → PENDING_VALIDATION trong transaction riêng.
Nếu run đã hết quyền xử lý, hàm không cập nhật và service trả LEASE_LOST.

### Bước 8 — Validation và publish

`finish()` khóa lại bài/task và run, đọc lại nguồn và trạng thái template.
`LlmValidationService.evaluate()` chạy luật, lưu validation_results, trả lỗi/warning/technicalFailure.

- HTTP không 2xx hoặc technicalFailure: FAILED.
- Có lỗi chặn nghiệp vụ: REJECTED.
- Không lỗi chặn: SUCCESS.

Chỉ SUCCESS mới tạo result. Trong cùng transaction:

1. Result current trước của cùng bài/task chuyển `is_current=false`.
2. Tạo result mới, nối bài gốc/run/template, lưu JSON và validation round/policy.
3. Hoàn tất run SUCCESS cùng metadata audit.

Nếu ghi DB/transaction thất bại, không publish một result dang dở.
Response đã stage trước đó có thể còn PENDING_VALIDATION để được xử lý lại.

## 5. Đầu vào thực tế gửi sang LLM

JSON nghiệp vụ có `schema_version='news.input.v1'`, `task_code`, và `article`:

| Trường article | Nguồn / ý nghĩa |
|---|---|
| `id` | ID news_articles gốc |
| `title` | Tiêu đề bài |
| `sapo` | Phần mở đầu; thiếu thì chuỗi rỗng |
| `published_at` | Thời điểm đăng biểu diễn theo offset +07:00 |
| `url` | canonical_url của bài |
| `content_hash` | Hash thân bài nguồn |
| `segments[]` | Các đoạn `{id, text}` nguyên văn, ID p1, p2… |
| `companies[]` | company_id, security_id, name, symbol, match_method, match_evidence |

`segmentText()` ưu tiên ranh giới câu tiếng Việt, tối đa khoảng 1.800 ký tự/đoạn;
nếu không có ranh giới phù hợp thì tìm khoảng trắng, tránh cắt đôi surrogate pair.
Các đoạn nối lại giữ toàn bộ nội dung; không âm thầm cắt phần cuối bài.

`source_hash` gồm snapshot article: nội dung phân đoạn, tiêu đề, sapo, ngày, URL và quan hệ/tên/mã công ty.
Đây không chỉ là content_hash. Thay đổi quan hệ cũng có thể làm kết quả phân tích cũ không còn phù hợp.

Request HTTP Gemini bọc input này trong `contents[0].parts[0].text` và gồm:

- `systemInstruction`: system prompt của template.
- `generationConfig.responseMimeType='application/json'`.
- `responseJsonSchema`: schema đã được adapter chuyển sang tập con provider hỗ trợ.
- temperature 0,1; maxOutputTokens và thinkingBudget theo cấu hình.

Adapter bỏ một số ràng buộc provider không nhận; Java vẫn kiểm tra **schema gốc đầy đủ trong DB**.
Không được suy ra bỏ ràng buộc bên Gemini là nới validation của hệ thống.

## 6. Đầu ra và cách FE sử dụng

Các task dùng hợp đồng `news.output.v1`.

### 6.1. Các trường chung

| Trường | Ý nghĩa |
|---|---|
| `schema_version` | Phiên bản hợp đồng JSON |
| `task_code` | Task đúng với prompt đã gửi |
| `source_article_id` | ID bài gốc, phải khớp request |
| `status` | Mức đầy đủ nội dung AI: SUFFICIENT/PARTIAL/INSUFFICIENT; financial thêm NOT_APPLICABLE |
| `overview` | Tổng quan để FE hiển thị nhanh |
| `overview_evidence` | Trích dẫn làm bằng chứng tổng quan |
| `sections[]` | Khối nội dung có type, heading, content, evidence |
| `limitations[]` | Những giới hạn/thiếu dữ liệu được model khai báo |

Evidence dùng `segment_id` và `quote`: quote phải là chuỗi nguyên văn liên tục trong đúng đoạn nguồn.
FE hiển thị text, không thực thi HTML hoặc chỉ dẫn do model sinh.
Array linh hoạt theo giới hạn schema; không phải JSON tùy ý vô hạn.

### 6.2. Ba kết quả

| Task / template hiện tại | Đầu ra bổ sung | Sử dụng |
|---|---|---|
| NEWS_SUMMARY / v1 | Các section ý chính và bằng chứng | Tóm tắt, tổng quan |
| NEWS_DETAIL / v1 | document_types, contains_financial_figures, contains_forecasts, company_impacts | Phân loại, diễn giải, tác động theo công ty |
| NEWS_FINANCIAL_FACTS / v3 | facts[] | Trích số liệu thực tế/dự báo/kế hoạch bài đã nêu |

`company_impacts[]`: company_id, direction, horizon, rationale, confidence, evidence.
Một company chỉ có một impact trong response. ID phải thuộc danh sách request.
Quan hệ nguồn job không tự chứng minh tác động tích cực/tiêu cực; model phải đưa bằng chứng từ bài.
confidence là tự đánh giá của model, không phải xác suất lợi nhuận đã hiệu chuẩn.

`facts[]`: metric_name, company_id (có thể null), value_text, unit, period, attributed_to, fact_kind, evidence.
fact_kind phân biệt ACTUAL, FORECAST, TARGET. Giá trị và metadata không null phải hiện diện trong
evidence của **chính fact đó**, không lấy quote của fact khác để hợp thức hóa.
Không có bằng chứng trực tiếp thì metadata cho phép null theo schema.

Số dự báo của tác giả/công ty vẫn là thông tin NEWS, không tự ghi vào báo cáo tài chính hoặc bảng dự báo gốc của hệ thống.

## 7. Các bảng lưu gì và nối với nhau thế nào

```text
news_articles ────────────┬──── llm_runs ───── llm_run_attempts
                         │         │
                         │         ├──────── validation_results ─── validation_rules
                         │         │
                         └──── llm_results
                                   │
llm_prompt_templates ───────────────┴──── cũng được llm_runs tham chiếu
```

| Bảng | Dữ liệu chính / trách nhiệm |
|---|---|
| news_articles | Bài báo gốc, URL, ngày, title/sapo/body/hash, trạng thái nguồn/trùng |
| news_article_companies | Quan hệ bài với công ty/chứng khoán; LLM đọc để biết ID hợp lệ |
| llm_prompt_templates | Prompt và schema theo task/version; một phiên bản enabled cho mỗi task |
| llm_runs | Một lần xử lý nghiệp vụ: nguồn, template, provider/model, input_hash, snapshot input, request/response cuối, trạng thái, lỗi/token/latency |
| llm_run_attempts | Từng lần gọi HTTP/fallback trong một run; response_body giữ phản hồi attempt ngay cả khi chưa là JSON hợp lệ |
| validation_rules | Catalog luật; nhóm LLM_OUTPUT tách với NEWS/NEWS_DATA dù cùng một bảng |
| validation_results | Từng luật đã kiểm tra: llm_run_id, round, rule_snapshot, severity, PASS/FAIL/SKIP, expected/observed/message |
| llm_results | JSON được chấp nhận: article/run/template FK, source_hash, input_hash, overview, schema_version, quality_status, current, validation round/policy |

`llm_runs.request_payload` là body gửi Gemini thực tế, có thể gồm feedback sửa lỗi.
`request_metadata.input` là snapshot bài nguyên gốc dùng đối chiếu.
`response_payload` là envelope provider; `response_text` là text output được trích.
Nếu envelope không parse được, store bọc chuỗi vào object `raw_body`, không giả vờ đó là JSON phân tích hợp lệ.

Một article có nhiều runs và nhiều results lịch sử. Một run tối đa một result.
Một `(news_article_id, task_code)` tối đa một result current theo unique index.
Kết quả mới không ghi đè JSON lịch sử và không sửa news_articles.

## 8. Validation nằm ở đâu và thực hiện thế nào

### 8.1. Tách cấu hình và executor

- DB quyết định code luật, executor_key, data_domain, severity, is_active và rule_config.
- `rule_config` LLM hiện có version và danh sách tasks áp dụng.
- `LlmValidationService` yêu cầu đủ bộ luật nền bắt buộc và severity chặn.
- `NewsLlmValidator` thực thi kiểm tra. Nội dung logic mới vẫn phải thêm code.
- JSON Schema của output nằm ở template DB; không phải mọi ràng buộc output đều nằm trong rule_config.

File `src/main/resources/validation/news-rules.json` đã bỏ.
NEWS/NEWS_DATA runtime đọc validation_rules; fixture test không tham gia runtime.
Database mới dùng migration SQL chỉ insert code chưa có, không tự ghi đè cấu hình admin.

### 8.2. Mười luật LLM

| Code | Executor kiểm tra | Áp dụng |
|---|---|---|
| LLM_RESPONSE_READY | HTTP/readiness/output parse được | Cả ba task |
| LLM_RESPONSE_SCHEMA | Toàn bộ JSON Schema của template | Cả ba |
| LLM_SOURCE_ID | source_article_id khớp bài gửi | Cả ba |
| LLM_EVIDENCE_QUOTES | segment tồn tại và quote nguyên văn trong segment | Cả ba |
| LLM_COMPANY_REFERENCES | company_id không null thuộc request | Cả ba |
| LLM_ANALYSIS_COMPLETENESS | Không INSUFFICIENT; PARTIAL cần limitations; SUMMARY/DETAIL cần sections | Cả ba |
| LLM_COMPANY_IMPACT_UNIQUE | Không lặp company_id trong company_impacts | Cả ba; thực chất dùng khi có impacts |
| LLM_SOURCE_SNAPSHOT | Nguồn hiện tại còn eligible và source_hash không đổi | Cả ba |
| LLM_PROMPT_SNAPSHOT | Template còn enabled/checksum khớp và fingerprint luật không đổi trong lần gọi | Cả ba |
| LLM_FINANCIAL_FACTS | Trạng thái/facts phù hợp; value/unit/period/attributed_to có bằng chứng trong fact | Financial |

SUMMARY/DETAIL: 9 luật. FINANCIAL_FACTS: 10 luật. Mặc định seed là ERROR.
Không thể tắt/hạ luật nền thành WARNING để lách điều kiện publish: `ensureComplete()` sẽ báo thiếu luật chặn.
Luật WARNING bổ sung có thể ghi cảnh báo nhưng không chặn nếu không phải một luật nền bắt buộc.

### 8.3. Audit và quyết định

Mỗi `evaluate()` tạo validation_round_id mới. Không xóa round trước khi kiểm tra lại.
Mỗi row lưu snapshot id/code/executor/severity/config/domain lúc thực hiện.

- PASS: điều kiện luật đạt.
- FAIL: không đạt; ERROR/CRITICAL chặn, WARNING không tự chặn.
- SKIP: không chạy kiểm tra phụ vì response/schema tiền đề lỗi. Đây **không phải** chấp nhận output.
- handling_status: FAIL là OPEN; các trạng thái khác NOT_REQUIRED trong luồng hiện tại.

Nếu không đọc được cấu hình luật, evaluation trả technicalFailure; có thể không có row audit của từng luật
vì chưa xác định được catalog hợp lệ. Run vẫn không được publish. Không khẳng định mọi lỗi kỹ thuật đều sinh đủ 9/10 row.

Constraint LLM_OUTPUT yêu cầu llm_run_id, round, rule_snapshot và raw_payload_id/ingestion_run_id null.
RAW_PAYLOAD yêu cầu raw_payload_id và llm_run_id null. Unique index ngăn cùng một luật bị ghi hai lần trong cùng run/round.

## 9. Phân biệt các trạng thái

### 9.1. Trạng thái lưu trong llm_runs

| Trạng thái | Ý nghĩa |
|---|---|
| RUNNING | Đã nhận việc, đang gọi/xử lý; giữ quyền bài/task |
| PENDING_VALIDATION | Response đã lưu, chưa hoàn tất kiểm tra/publish |
| SUCCESS | Lần xử lý đã tạo kết quả được chấp nhận |
| REJECTED | Có response nhưng không đạt điều kiện nghiệp vụ/nguồn/schema |
| FAILED | HTTP/transport/interrupt/lease/cấu hình/thực thi kỹ thuật lỗi |
| PENDING | Được schema DB cho phép nhưng luồng execute hiện tại không tạo bước chờ này |

### 9.2. Trạng thái API không nhất thiết là trạng thái run

CACHED, SKIPPED, CONFIGURATION_REQUIRED, VALIDATION_CONFIGURATION_ERROR, WAITING_FOR_DETAIL,
NOT_APPLICABLE, TEMPLATE_DISABLED, RETRY_LIMIT, LEASE_LOST và CLAIMED là Outcome của điều phối.
Nhiều Outcome không tạo run. CLAIMED là trạng thái nội bộ nhận việc, không phải final output AI.
RUNNING trả qua API cũng có thể đang giữ run PENDING_VALIDATION, vì claim không cấp thêm lượt provider.

### 9.3. Mức nội dung AI và quality_status

- SUFFICIENT, không warning luật: result quality_status VALID.
- PARTIAL, có limitations và đạt mọi luật chặn: result WARNING.
- INSUFFICIENT: REJECTED, không tạo result.
- NOT_APPLICABLE trong **response financial** chỉ hợp lệ nếu facts rỗng và có limitations; có thể lưu result WARNING.
- NOT_APPLICABLE do **DETAIL không có số liệu** là nhánh trước provider, không tạo run/result financial mới.

Không nhầm AI status PARTIAL với run FAILED, hoặc result WARNING với validation FAIL chặn.

## 10. Retry, fallback, timeout và chống trùng

### 10.1. Hai tầng retry khác nhau

**Trong một run — retry mạng/model:** gateway thử model khác với cùng request khi lỗi thuộc nhóm cho phép.
Mỗi lần là một llm_run_attempts, chưa phải run mới.

**Giữa các run — sửa output nghiệp vụ:** một lần execute mới sau REJECTED có thể gửi feedback cụ thể
để model trả lại toàn bộ JSON đúng theo nguồn. Đây là provider call mới và có chi phí.
Không tự chạy vòng sửa vô hạn trong cùng execute.

`withValidationFeedback()` tìm response REJECTED gần nhất cùng bài/task/input_hash.
Chỉ thêm feedback khi response <=60.000 ký tự và lỗi không thuộc PROVIDER_, SOURCE_CHANGED, TEMPLATE_CHANGED.
Feedback không được làm theo chỉ dẫn trong response cũ, không cho đổi số nguồn hoặc bỏ qua validation.
Input_hash vẫn dựa request gốc, không đổi theo feedback, nên không reset giới hạn ba run lỗi.

### 10.2. Chính sách gateway hiện tại

| Tham số | Giá trị mặc định |
|---|---|
| Attempt tối đa/task | 8 |
| Attempt/model/task | 1 |
| HTTP timeout/attempt | 45 giây |
| Ngân sách chuỗi provider/task | 180 giây |
| Backoff nền / trần | 1 giây / 8 giây, có jitter |
| Cooldown 429/404 | Ít nhất 60 giây, kết hợp Retry-After |
| Output token / thinking budget | 16.384 / 1.024 |

Thứ tự model từ cấu hình, primary được đưa lên trước và loại trùng.
Danh sách mặc định nằm trong GeminiRoutingProperties; tên trong cấu hình không bảo đảm model/key thực sự gọi được.
Local đang ưu tiên gemini-3.8-flash theo cấu hình riêng, không ghi key trong báo cáo.

- Chuyển model: HTTP 404, 408, 429, 500, 502, 503, 504; IOException/timeout.
- HTTP 400/401/403 và các mã khác không trong nhóm trên: dừng, không chạy hết model với cùng lỗi request/quyền.
- HTTP 2xx nhưng safety/incomplete/schema/quote sai: không đổi model tự động để vượt validator.
- Hết attempt hoặc thời gian: trả response HTTP cuối nếu có; nếu không có response thì ném IOException.
- Không mở một request mới khi ngân sách còn dưới 25 ms.

Ngân sách này không gồm thời gian DB và cả ba task. Execute toàn bài có thể gần 9 phút cộng xử lý DB.
Client timeout không tự đồng nghĩa provider call được hủy; cần xem run log trước khi gọi lại.
Cooldown hiện trong bộ nhớ từng tiến trình, không phải quota dùng chung toàn cluster.

### 10.3. Các lớp chống trùng

1. Nguồn bài: dedup của news_articles và quan hệ, trước LLM.
2. Nhận việc: advisory lock theo bài/task và unique active-run index cho RUNNING/PENDING_VALIDATION.
3. Cache: cùng input_hash và result current thì không gọi provider.
4. Publish: một result/run và một result current/bài/task theo unique index.
5. JSONB: canonical JSON tránh tạo khóa khác chỉ vì đổi thứ tự trường.

Không gọi đây là exactly-once tuyệt đối với dịch vụ ngoài: nếu Gemini đã nhận request nhưng process chết
trước khi lưu response, provider có thể đã tính phí và lần xử lý sau có thể cần gọi lại.

## 11. Khôi phục và revalidate

### 11.1. Response đã lưu, process gián đoạn

`validatePending(limit)` chọn run PENDING_VALIDATION theo created_at/id, giới hạn 1–50.
`revalidate()` đọc request_metadata.input, response_text và template của run cũ.
Với pending, nó gọi `finish()` từ dữ liệu đã lưu; không gọi Gemini và không tạo attempt mới.
Pending giữ quyền bài/task cho tới khi hoàn tất, tránh vừa validate vừa cấp lại lượt provider.

### 11.2. Kiểm tra lại run đã kết thúc

Revalidate dùng luật hiện tại nhưng nguồn và template phải vẫn phù hợp snapshot lúc gọi:

- Current result đạt: cập nhật round, policy và input_hash tương ứng; giữ nguyên JSON AI.
- Current result không đạt: chuyển is_current=false, cập nhật run REJECTED/FAILED và lỗi.
- Run chưa từng có result, response nay đạt sau sửa cấu hình: có thể publish nếu chưa có current result khác của bài/task.
- Run đã có result lịch sử hoặc bài/task đang có current khác: không kích hoạt lại bản cũ để thay bản mới.
- Thiếu context/template ở run legacy: SKIPPED; RUNNING: trả RUNNING.
- Cấu hình luật không hợp lệ: VALIDATION_CONFIGURATION_ERROR trước recheck terminal.

Outcome SUCCESS của revalidate lịch sử có thể có resultId null: chỉ audit đạt, không phải đã publish mới.
Revalidate không có nghĩa sửa nội dung AI hoặc gọi AI sinh lại.
Không nên đọc mỗi Outcome rồi suy ra toàn bộ trường llm_runs lịch sử đã được viết lại; code chủ ý giữ lịch sử một số nhánh.

## 12. Điều kiện API trả kết quả cho FE

`LlmRunStore.results(articleId)` dựng lại context và chỉ trả khi nguồn eligible.
Kết quả phải current, source_hash khớp, template còn enabled, có validation round/audit,
không có FAIL ERROR/CRITICAL trong round và policy_hash khớp fingerprint luật hiện tại.
Nếu luật hiện tại không hợp lệ, kết quả bị ẩn thay vì trả dữ liệu chưa đủ bảo đảm.

Response API gồm `id`, `task_code`, `data` (result_json), `quality_status`, `created_at`.
Không trả thẳng response_payload provider để FE tự suy ra nó hợp lệ hay chưa.
Không chỉ dựa mỗi is_current trong truy vấn thủ công; trạng thái phù hợp nguồn/luật cũng quan trọng.

View `vw_company_news_latest` đã chuyển sang kết quả LLM đã kiểm tra, giữ bài URL-only với phần AI null.
Các cột sentiment không có hợp đồng tương ứng được để null, không bịa score từ direction/confidence.

## 13. Scheduler chạy ra sao

`NewsLlmScheduler` chỉ được tạo khi `financial.llm.scheduler.enabled=true`.
fixedDelay mặc định 300.000 ms: đợi 5 phút sau khi lượt trước kết thúc, không phải cron bắt đầu đúng mỗi 5 phút.

Trong `tick()`:

1. `validatePending(5)` trước, dù chưa có key để gọi generation mới.
2. Nếu gateway chưa configured thì dừng.
3. `candidates(5)` chọn tối đa năm bài, ưu tiên published_at mới.
4. Mỗi bài gọi executeAll; exception ở một bài được log và tiếp tục bài sau.

Candidate tránh bài thiếu body/test/duplicate/deleted, run đang giữ việc, lỗi gần đây một giờ,
và nhóm đã chạm ba run lỗi cùng điều kiện. Kiểm tra lần nữa trong execute vẫn là bắt buộc.
Candidate dùng truy vấn lựa chọn, không phải bằng chứng tuyệt đối mọi bài trả về đều đủ điều kiện.
Kết quả cũ, template/routing/policy hoặc quan hệ đổi có thể khiến bài được chọn lại.

## 14. Ma trận tình huống để kiểm tra

| Tình huống | Kết quả | Có run/call mới? | Có result mới? |
|---|---|---|---|
| URL-only, body ngắn/thiếu, thiếu title/date, nguồn test/trùng/bẩn/hash sai | SKIPPED | Không | Không; giữ bài nguồn |
| Request sai schema | SKIPPED | Không | Không |
| Key thiếu/LLM tắt | CONFIGURATION_REQUIRED | Không | Không |
| Luật nền thiếu/sai trước gọi | VALIDATION_CONFIGURATION_ERROR | Không | Không |
| Template tắt trong executeAll | TEMPLATE_DISABLED | Không | Không |
| Cùng request đã có current result | CACHED | Không | Không |
| Cùng bài/task đang RUNNING hoặc pending | RUNNING | Không thêm | Không thêm |
| Cùng input có ba run lỗi | RETRY_LIMIT | Không | Không |
| Financial chưa có DETAIL đạt | WAITING_FOR_DETAIL | Không | Không |
| DETAIL xác nhận không số liệu/dự báo | NOT_APPLICABLE | Không | Không; retire financial hiện hành |
| Provider 429/503… rồi model khác 200 | Gateway fallback, tiếp tục validate | Một run, nhiều attempts | Chỉ nếu validate đạt |
| HTTP cuối không 2xx | FAILED | Có run/log | Không |
| Toàn chuỗi transport/timeout hoặc interrupt | FAILED | Có run, attempts tùy thời điểm | Không |
| HTTP 200 nhưng output thiếu/cắt/chặn/non-JSON | REJECTED theo finish thông thường | Có run/response | Không |
| Sai schema/ID/quote/công ty/số liệu | REJECTED | Có run/validation | Không |
| Nguồn/prompt/luật đổi trong lúc gọi | REJECTED; lỗi config kỹ thuật có thể FAILED | Có log | Không |
| Output PARTIAL hợp lệ | SUCCESS | Có | Result WARNING |
| Output SUFFICIENT hợp lệ | SUCCESS | Có | Result VALID, trừ warning luật |
| Process chết sau stage response | PENDING_VALIDATION | Không gọi lại khi phục hồi | Publish sau validate nếu đạt |
| Worker cũ mất quyền publish | LEASE_LOST | Không cấp result từ worker cũ | Không |
| Revalidate sau đổi luật | Audit round mới, không generation | Không call/attempt | Theo điều kiện tại mục 11 |

## 15. API để tự kiểm tra

Base local: `http://127.0.0.1:8080/api/admin/llm`.

| Method | Đường dẫn sau base | Ý nghĩa |
|---|---|---|
| GET | /templates | Prompt/schema đang enabled |
| GET | /news/candidates?limit=5 | Danh sách bài scheduler sẽ xét |
| GET | /news/{articleId}/preview?task=NEWS_SUMMARY | Input, issues, provider_request và response_schema; không gọi generation |
| POST | /news/{articleId}/execute | Chạy toàn bài; có thể phát sinh chi phí nếu không cache |
| POST | /news/{articleId}/tasks/{task}/execute | Chạy một task |
| GET | /news/{articleId}/results | Kết quả current còn đạt điều kiện nguồn/prompt/luật |
| GET | /runs/{runId} | Snapshot, request/response, attempts và validations mọi round |
| POST | /runs/{runId}/revalidate | Kiểm tra response đã lưu, không gọi provider |
| POST | /validation/pending?limit=5 | Hoàn tất các run đang chờ validation |
| GET | /gemini/configuration | Cấu hình giới hạn/routing và key_present, không trả key |
| GET | /gemini/models | ListModels thật; không generation |

API dưới tên admin không tự chứng minh đã bảo vệ mọi endpoint. Cần nghiệm thu auth/phân quyền trước mở Internet.
Không công bố log request/response rộng rãi; log có nội dung đầu vào, không chỉ mã trạng thái.

## 16. Kiểm thử đã xác nhận

Bộ 107 kiểm thử NEWS/LLM/validation đã đạt, không failure/error/skipped;
trong đó 20 test tích hợp PostgreSQL chạy schema riêng và dọn sau test.

Các nhánh được kiểm tra gồm: log/publish/cache, fallback audit, output sai,
thiếu luật, snapshot và nhiều round, pending recovery không provider,
sửa cấu hình rồi chấp nhận response đã lưu, đổi version luật và revalidate,
feedback không reset retry cap, thiếu key, financial phụ thuộc DETAIL,
đổi nguồn trong khi gọi, concurrent claim, HTTP/transport lỗi, URL-only/test/duplicate,
nguồn đổi ẩn kết quả cũ, candidate/retry, run hết lease, template tắt.

Kiểm thử Gemini thực tế đi qua API Spring Boot, không insert response AI giả vào bảng dùng chung:

| Mẫu | ID |
|---|---|
| Bài FPT | f10ad0c8-ec72-4176-a01b-a990cc4d4f02 |
| Bài HPG | 0cf3c566-bd31-4fbf-88c8-90d1ddcca640 |
| URL-only | 10dc70ae-fdfd-464e-85b8-74bb32c5e8f4 |
| FPT SUMMARY SUCCESS | run 7a71481d-9cd9-44aa-a10a-7c805aa37699 |
| FPT DETAIL SUCCESS | run b8d37060-1a18-4bc2-8918-3456949ba83b |
| FPT FINANCIAL_FACTS SUCCESS | run d47c0f2c-c4cd-4b37-a31e-56de0a14071a |

Snapshot nghiệm thu: 35 runs / 49 attempts / 17 results lịch sử, 6 results current ở hai bài.
17 SUCCESS / 16 REJECTED / 2 FAILED; lịch sử lỗi được giữ nguyên.
252 validation LLM sau các round kiểm tra lại; 46.126 validation RAW_PAYLOAD không bị thay đổi.
0 lỗi chặn liên kết current; 0 nhóm current trùng; 0 run còn RUNNING/PENDING_VALIDATION tại lúc đối chiếu.
Gọi lại hai bài sau revalidate bằng code mới: sáu CACHED, counts run/attempt/result không tăng.
URL-only: ba SKIPPED, không run/result. Sau bỏ JSON runtime, gọi lại FPT vẫn ba CACHED.

Những số này là snapshot nghiệm thu, không phải số liệu sẽ đứng yên nếu bạn chạy thêm API/scheduler.
Test mô phỏng lỗi chỉ ở môi trường/schema test; các con số nghiệp vụ chung trên là lịch sử gọi thật.

## 17. Giới hạn và điểm cần biết trước vận hành hàng loạt

Những điểm sau được ghi nhận từ code; báo cáo không tự sửa thêm hành vi:

1. **Validation không chứng minh ngữ nghĩa tuyệt đối.** Quote đúng và số xuất hiện trong quote chưa bảo đảm model giải thích đúng quan hệ nhân quả, phép tính, đơn vị ngầm hay tác động thị trường. Cần nghiệm thu nội dung mẫu thực tế.
2. **Bộ lọc body bẩn có giới hạn.** Điều kiện PAGE_CHROME_IN_BODY nhận một cụm bẩn đã biết, không phải bộ nhận diện mọi quảng cáo/menu hay mọi bài chất lượng thấp.
3. **Giới hạn retry là cơ chế an toàn.** Có thể còn bài chưa có đủ ba result vì liên tục bị từ chối. Cần sửa nguyên nhân, không xóa lỗi để reset quota ngầm.
4. **Pending recovery chưa có xử lý exception riêng từng run.** `validatePending()` map tuần tự; một exception DB/context ngoài Outcome có thể ngắt lượt pending. Trong scheduler bước này ở ngoài try/catch từng bài, nên lượt chọn bài mới đó cũng có thể chưa chạy. Response vẫn lưu nếu đã stage, nhưng cần theo dõi log và xử lý run lỗi.
5. **Candidate là bộ lọc SQL trước kiểm tra nguồn đầy đủ.** Nếu nhiều bài mới liên tục SKIPPED ở bước sâu hơn, cần theo dõi việc lặp lựa chọn; không suy ra candidate đồng nghĩa ready.
6. **Đổi luật là thay đổi có tác động.** Cần revalidate kết quả đã có hoặc sinh lại khi đúng nhu cầu; không chỉ sửa DB rồi cho FE đọc trực tiếp is_current.
7. **Revalidate không cập nhật mọi trường như một generation mới.** Nó giữ nguyên nội dung AI, có thể chỉ ghi audit khi kiểm tra lịch sử; quality_status result hiện có không được tính lại trong nhánh cập nhật round/policy. Khi thay đổi chính sách warning cần kiểm tra cách FE biểu diễn chất lượng.
8. **Audit cũng phụ thuộc DB.** Nếu callback lưu attempt thất bại, chuỗi xử lý có thể dừng dù provider đã xử lý request. Không thể cam kết giữ toàn bộ response khi DB mất kết nối ngay lúc nhận dữ liệu.
9. **Không exactly-once với Gemini.** Cửa sổ process chết trước stageResponse vẫn tồn tại; lease bảo vệ publish trong DB, không hủy request provider đã nhận.
10. **Quota/cooldown nhiều replica chưa dùng chung.** Cooldown hiện process-local. Cần đánh giá thêm khi server chạy nhiều worker/replica.
11. **Phạm vi báo cáo này là NEWS.** Cập nhật 04/10/2026: FINANCIAL có service, schema, luật, FK nguồn và kiểm thử riêng tại [Financial Forecast Admin API](FINANCIAL_FORECAST_ADMIN_API.md). MARKET độc lập chưa triển khai.
12. **Local khác production.** Chưa push/deploy; server chạy thật chỉ có hành vi mới sau áp dụng migration đúng thứ tự và triển khai đúng code. Không dùng số phiên bản health một mình để khẳng định tương thích pipeline.

## 18. Migration và phần dọn dữ liệu

Thứ tự các migration manual liên quan:

1. V20260930_01__llm_news_pipeline.sql: prompt catalog, run fields, result/FK/index.
2. V20261004_01__llm_attempt_audit.sql: từng HTTP attempt.
3. V20261004_02__llm_shared_validation.sql: phân loại validation target, run/round/snapshot, luật LLM, pending state/index.
4. V20261004_03__remove_empty_legacy_news_ai.sql: chuyển view, chỉ bỏ bảng news_ai_analyses nếu rỗng.
5. V20261004_04__news_validation_catalog.sql: khởi tạo luật NEWS/NEWS_DATA thiếu, ON CONFLICT DO NOTHING.

Migration là manual, không tự chạy chỉ vì đặt file trong resources.
DB hiện tại đã có 17 luật NEWS/NEWS_DATA và 10 luật LLM_OUTPUT.
Migration cuối đã được thử trong transaction rollback: insert 0, không đổi các luật hiện có.

news_ai_analyses rỗng đã bỏ, không dùng CASCADE; DDL bảng/view cũ được lưu tại docs/db-backups.
Không xóa dữ liệu/log failed/rejected và không xóa bảng có chức năng khác chỉ vì không dùng trong NEWS→LLM.

## 19. Checklist đọc và tự nghiệm thu một bài

1. Kiểm tra news_articles: title/body/date/hash/dedup và URL-only hay full-content.
2. Kiểm tra news_article_companies: các quan hệ có đúng ID và nguồn/bằng chứng không.
3. GET preview: input có đầy đủ segments, công ty, +07:00 và issues rỗng.
4. GET templates: task/schema/version đúng; kiểm tra luật LLM_OUTPUT active và đầy đủ.
5. POST execute hoặc task execute; ghi lại từng runId và Outcome, không chỉ nhìn HTTP 200.
6. GET run: đọc provider HTTP/finishReason, request/response, attempts và lỗi.
7. Kiểm tra validation round: đủ luật áp dụng, không FAIL chặn; SKIP phải giải thích bởi tiền đề lỗi.
8. GET results: result nối đúng bài/run/template, JSON đúng task, còn current và phù hợp nguồn/luật.
9. Gọi lại khi input không đổi: phải CACHED, không tăng attempts.
10. Đối chiếu nội dung AI với quote nguyên văn: số, kỳ, ACTUAL/FORECAST/TARGET, công ty và lời giải thích tác động.
11. Chỉ bật scheduler sau khi chấp nhận mẫu đa dạng, auth, giới hạn chi phí và giám sát lỗi.

## 20. Các file nên mở khi kiểm tra code

- [Điểm vào API](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/controller/admin/NewsLlmAdminController.java)
- [Điều phối task](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmService.java)
- [Đầu vào và snapshot](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmContext.java)
- [HTTP Gemini và fallback](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/GeminiLlmGateway.java)
- [Điều phối luật DB và audit](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmValidationService.java)
- [Executor validation](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/NewsLlmValidator.java)
- [Nhận việc, lưu và revalidate](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmRunStore.java)
- [Scheduler](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/scheduler/llm/NewsLlmScheduler.java)
- [Catalog prompt](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/llm/LlmPromptCatalog.java)
- [Cấu hình vận hành](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/docs/GEMINI_LOCAL_SETUP.md)
- [Test tích hợp và các tình huống lỗi](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/test/java/com/hethongdata/taichinh/service/llm/NewsLlmIsolatedIntegrationTests.java)

**Tóm lại:** bài gốc là nguồn sự thật; run/attempt là lịch sử trao đổi; validation là cửa kiểm tra;
result là JSON phân tích được phép sử dụng. Bốn vai trò này được tách riêng để truy nguồn và kiểm soát chất lượng.

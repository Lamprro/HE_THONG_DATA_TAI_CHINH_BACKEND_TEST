# NEWS recovery: luồng thực tế, API admin và kiểm thử

> Cập nhật 08/10/2026: lịch sử từng lần gọi model nằm trong mảng JSONB `llm_runs.attempts`, không còn bảng log attempts riêng. Xem [hướng dẫn chuyển đổi](LLM_ATTEMPTS_MERGE.md). Các kết quả kiểm thử cũ bên dưới là bằng chứng của thời điểm ghi báo cáo.

> Bàn giao cập nhật 07/10/2026: đọc [PROJECT_HANDOVER.md](PROJECT_HANDOVER.md) trước. Code LLM đã push ở `feature/llm-processing` (`5343f81`), chưa merge/deploy. Các số liệu DB, kết quả API và nhận định bên dưới thuộc thời điểm kiểm tra được ghi trong tài liệu; không phải xác nhận runtime ngày 07/10. Implementation và việc còn dở cần đối chiếu với bàn giao mới.

Kiểm thử runtime được ghi nhận ngày 04/10/2026. Code đã push ngày 07/10/2026 trên `feature/llm-processing` (`5343f81`); chưa có xác nhận deploy. Không thay dự án Python và không có Python fetcher phụ. Script `scripts/test_news_recovery_live.py` chỉ gọi HTTP API Java và đọc audit DB để kiểm thử.

## 1. Phạm vi đã triển khai

- URL-only đi thẳng tới Gemini URL Context, không gọi lại Python.
- Admin có thể yêu cầu kiểm tra một bài đã có body nếu nghi thiếu nội dung và cung cấp tối đa 3 URL tài liệu thật bổ sung.
- LLM có thể phát hiện liên kết tài liệu/ảnh trong trang. Tài liệu tải bởi Java qua HTTPS được lưu Cloudinary dạng `raw`, delivery `authenticated`, public ID dựa trên SHA-256 và `overwrite=false`.
- PDF gửi dưới dạng bytes đa phương thức. DOCX/XLSX được bóc text/XML có giới hạn bằng Java, không chạy Office/macro/công thức. TXT/CSV UTF-8 được gửi dạng text. DOC/XLS cũ, archive không phải Office và định dạng khác bị đánh dấu không hỗ trợ, không đổi đuôi để giả PDF.
- PNG/JPEG mang nội dung được gửi trực tiếp sang Gemini, không upload Cloudinary. Không tải mọi ảnh trang trí; chỉ tối đa 3 liên kết do task trả về/admin cung cấp. SVG/GIF/WebP chưa có reader trong phiên bản này.
- **Mọi đề xuất khôi phục đều chờ admin**, kể cả validation pass. Không coi AI tự đánh giá COMPLETE là chứng minh độc lập.
- Sau duyệt, gọi API analyze để chạy SUMMARY → DETAIL → FINANCIAL_FACTS theo luồng hiện có. Có sơ bộ `preliminary_overview` trong proposal để admin đọc nhưng chưa công bố như phân tích chuẩn. Không tái sử dụng nhận định chưa kiểm tra thành `llm_results`.

Việc tải tài liệu có thể cần 2 lần gọi provider: đọc trang/tìm liên kết, rồi đọc bytes tài liệu. Sau admin duyệt, ba task phân tích dùng cơ chế hiện tại. Đây không phải cam kết một lần gọi AI làm xong mọi việc.

## 2. Lưu trữ: không thêm bảng/cột

| Bảng hiện có | Dữ liệu của nhánh recovery |
|---|---|
| llm_prompt_templates | NEWS_CONTENT_RECOVERY v1, schema request/response, prompt và checksum |
| llm_runs | operation NEWS_PARSE, task riêng; snapshot bài cũ trong request_metadata.source, source hash, template checksum, fingerprint luật; proposal trong response_text; manifest tài liệu và trạng thái duyệt trong response_metadata |
| llm_runs.attempts | Từng lần gọi/fallback, request, response và token; binary inline được thay bằng hash/kích thước trong log, không phải request có thể replay nguyên trạng |
| validation_rules / validation_results | 5 luật NEWS_RECOVERY; validation_target LLM_OUTPUT; mỗi lần kiểm tra có round riêng và llm_run_id |
| news_articles | Chỉ cập nhật khi approve: title/content/hash/author/date, metadata recovery và manifest tài liệu; giữ nguyên ID/URL/raw_payload gốc |
| news_article_companies | Giữ quan hệ nguồn job; xây lại TEXT_MATCH trên nội dung được duyệt |
| llm_results | Không lưu proposal vào đây. Sau duyệt, kết quả cũ được retire; các task phân tích bình thường tạo kết quả mới |

Không tạo ingestion_run/raw_payload/data_version cho response AI. Proposal là một nhánh riêng của llm_runs, không phải dữ liệu nguồn đã được công bố.

Migration `V20261004_06__news_recovery.sql` thêm 5 luật, chỉ mục danh sách chờ duyệt và unique index content_hash cho bài UNIQUE. Không xóa hay gộp bản ghi. Unique index chặn cả race với luồng ingestion; nếu có trùng sẵn migration sẽ thất bại để kiểm tra, không tự dọn.

## 3. Chuỗi lớp/phương thức

1. `NewsRecoveryAdminController.execute` nhận ExecuteRequest, Bean Validation kiểm tra kích thước danh sách/URL. `ForecastAdminAccessConfiguration` bảo vệ namespace recovery và cả admin LLM bằng Bearer token riêng.
2. `NewsRecoveryService.execute` kiểm tra Gemini, giới hạn một tác vụ đang chạy mỗi instance. `NewsRecoveryStore.article/hash` chụp dữ liệu hiện hành; `NewsRecoveryCatalog.active` lấy prompt từ DB.
3. `NewsRecoveryStore.claim` khóa bài trong transaction ngắn; từ chối nguồn bị loại/test/duplicate/deleted, kiểm tra snapshot, run đang chạy/chờ duyệt và giới hạn 3 lần thất bại cho input không đổi. Run treo quá 15 phút bị đánh FAILED khi claim lại.
4. `GeminiLlmGateway.request` dựng request, service bật `url_context`; gateway chỉ chọn Gemini 3 khi có tools và schema. `gateway.call` giữ retry/deadline có sẵn. Mỗi attempt được `LlmRunStore.recordAttempt` ghi trước khi chuyển model.
5. `NewsRecoveryService.retrieved` chỉ đọc metadata truy xuất thực của provider. Trường fetch_success do model tự viết không có giá trị xác nhận. Chỉ công nhận URL bài đã gửi và tài liệu thực tế đã tải/gửi.
6. `SafeDocumentDownloader.fetch` kiểm tra HTTPS, port/credentials, phân giải DNS rồi ghim IP đã kiểm tra cho socket thật, kiểm tra từng redirect. Không tải HTML bài bằng Python hay một fetcher thay thế. `NewsDocumentReader.inspect` kiểm tra định dạng/giới hạn; `CloudinaryDocumentStorage.upload` ký request SHA-256 và lưu restricted raw asset.
7. Nếu có tài liệu đọc được, gọi Gemini thêm một lượt với bytes/text; ghi manifest ngay sau từng upload để truy vết. Tài liệu lỗi làm proposal PARTIAL và có cảnh báo, không tự coi đã đọc đủ.
8. `NewsRecoveryStore.finish` gọi `NewsRecoveryValidation.evaluate`, ghi vòng validation rồi chuyển run sang PENDING_VALIDATION, response_metadata.review_state=PENDING_ADMIN_REVIEW. Bài gốc chưa đổi.
9. `NewsRecoveryStore.approve` khóa run/bài, kiểm tra lại snapshot/prompt/luật, chạy validation mới, kiểm tra hash trùng. Đạt mới cập nhật bài, dựng lại text matches, retire kết quả LLM cũ và đánh run SUCCESS/APPROVED trong cùng transaction. `reject` chỉ đổi trạng thái run và ghi lý do, không đổi bài.
10. `NewsRecoveryAdminController.analyze` chỉ nhận run đã APPROVED, gọi `NewsLlmService.executeAll` trên bài hiện hành. Luồng chuẩn vẫn kiểm tra source hash và schema/evidence trước khi công bố. Kết quả từ bài được admin chấp nhận với assessment PARTIAL luôn mang quality WARNING.

`NewsLlmService.validatePending` chỉ quét ba task phân tích cũ, không tự xử lý/duyệt run recovery. API revalidate cũ cũng từ chối recovery task. Scheduler recovery riêng chỉ chọn URL-only, opt-in, không auto-approve; mặc định tắt trong thử nghiệm.

## 4. API admin

Base: `/api/admin/news-recovery`. Header: `Authorization: Bearer <FINANCIAL_ADMIN_API_TOKEN>` (không dùng Gemini key). Token chưa cấu hình hợp lệ → 503; thiếu/sai token → 403. Nhãn reviewer là nhãn do người dùng nhập, không phải account ID xác thực; hiện dùng shared admin token.

| Method | Path | Chức năng |
|---|---|---|
| GET | /configuration | Gemini/Cloudinary readiness, giới hạn, yêu cầu admin review |
| POST | /template/seed | Seed version prompt bất biến; không ghi đè version đã tồn tại |
| GET | /candidates?limit=20 | Bài URL-only đủ điều kiện thử recovery |
| POST | /news/{articleId}/execute | Thực hiện URL Context và đọc tài liệu; body `{"documentUrls":[]}` |
| GET | /pending?limit=20 | Proposal chờ admin |
| GET | /runs/{runId} | Snapshot, proposal, manifest, review và lỗi validation |
| POST | /runs/{runId}/approve | Body `{"reviewer":"admin-name","reason":"Đã đối chiếu nguồn"}` |
| POST | /runs/{runId}/reject | Cùng DTO, bắt buộc lý do |
| POST | /runs/{runId}/analyze | Chạy ba task phân tích trên bài đã duyệt |

Xem toàn bộ attempts/validation của run tại GET `/api/admin/llm/runs/{runId}` (đã bảo vệ admin).

HTTP 200 không đồng nghĩa task thành công: đọc Outcome.status/issues. Các trạng thái có thể gặp: CONFIGURATION_REQUIRED, BUSY, SKIPPED, SOURCE_CHANGED, EXISTING_RUN, RETRY_LIMIT, FAILED, PENDING_ADMIN_REVIEW, VALIDATION_BLOCKED, APPROVED, REJECTED. Duyệt nguồn đã đổi, duyệt lại hoặc nội dung trùng trả HTTP 409. Lỗi validation cứng vẫn chặn dù admin bấm approve; không có nút bỏ qua mọi luật.

## 5. Luật và giới hạn

| Luật DB | Kiểm tra |
|---|---|
| RECOVERY_SCHEMA | Schema bất biến của prompt |
| RECOVERY_SOURCE | Article ID, metadata truy xuất, evidence URL thuộc nguồn được đọc |
| RECOVERY_CONTENT | Body 200–150.000 ký tự, title, quote nằm trong proposed content, ngày hợp lệ, trạng thái không UNREADABLE/UNCERTAIN |
| RECOVERY_SNAPSHOT | Snapshot nguồn chưa đổi |
| RECOVERY_POLICY | Prompt/validation fingerprint chưa đổi |

Quote khớp proposed_content vẫn chưa chứng minh model chép đúng trang: admin phải đối chiếu nguồn/ảnh/PDF, nhất là bảng số liệu. Không khẳng định nội dung báo là sự thật hay OCR chính xác tuyệt đối. PARTIAL có thể được admin chấp nhận với lý do và cảnh báo chất lượng; thiếu source/evidence/body không được override.

Giới hạn thực thi: 3 attachment, 8 MiB/file (còn chịu max-file-bytes Cloudinary), 12 MiB tổng lượt xử lý, 3 redirect; timeout đọc mặc định 45s. Office tối đa 500 zip entries, 2 MB sau giải nén và 100.000 ký tự trích xuất. Không chạy macro, công thức, external XML entities. PDF có dấu hiệu mã hoạt động/mã hóa nhúng bị chặn theo kiểm tra sơ bộ; **đây không phải antivirus hoàn chỉnh**. Chưa có dịch vụ quét malware chuyên dụng, chưa có chuyển đổi Office giữ nguyên biểu đồ/layout, chưa có xử lý PDF dài vượt ngân sách đầu ra. Đây là các giới hạn phải giữ khi đánh giá production.

Cloudinary không tự public tài liệu; backend gửi bytes trực tiếp cho Gemini. Không lưu key, không trả signed URL công khai. Hash public ID giúp retry upload không ghi đè; asset còn lại nếu crash trước checkpoint có cùng ID để truy vết, chưa có job garbage collection tự xóa tài liệu.

## 6. Chạy local và kết quả xác minh

Tại lần kiểm thử ngày 04/10/2026, API local ở `127.0.0.1:8081`; các scheduler tắt và không dừng/chỉnh instance 8080 của người dùng. Code sau đó đã được push ở nhánh LLM. Không suy ra các instance này vẫn chạy ngày 07/10/2026.

- Đã áp dụng migration additive và seed prompt qua API thật.
- GET configuration có admin → 200; không token → 403.
- Gọi thật bài URL-only `10dc70ae-fdfd-464e-85b8-74bb32c5e8f4`: run `8f9f5823-1c2e-4656-a49d-8aed777d3b7f`, provider trả response nhưng không có nội dung/bằng chứng truy xuất đủ; run chờ review với các lỗi BODY/EVIDENCE/RETRIEVAL. Không cập nhật bài.
- Gọi thật bài `f10ad0c8-ec72-4176-a01b-a990cc4d4f02`: run `85a4ca58-8816-4aa3-990c-e38f14c31cdf` FAILED, Gemini trả 403 PERMISSION_DENIED: “Your project has been denied access. Please contact support.” Không dùng response giả để thay thế.
- Cloudinary readiness ở instance thử nghiệm hiện false; file local trên đĩa lúc kiểm tra không có thuộc tính Cloudinary. Chưa thể xác nhận upload thật thành công.
- 7 safety tests + 4 configuration tests pass. 4 integration tests HTTP/DB ở schema recovery_test_* riêng pass: approve, reject, stale source, duplicate, missing evidence, disabled rule, duplicate execute và quyền truy cập. Provider ở bộ này được mock có đánh dấu test, không phải bằng chứng provider thật thành công. Schema test được dọn sau chạy, không đưa mẫu giả vào bảng nghiệp vụ public.
- Lần chạy toàn suite mặc định có 17 lỗi khởi tạo context ở hai lớp legacy dùng DB do môi trường không cho kết nối DB; không được ghi nhận là toàn suite pass. Bộ regression loại hai lớp đó: 136 pass, 37 opt-in skip, không lỗi. Sau đó bật riêng hai bộ integration cô lập: NEWS LLM cũ 20/20 pass, recovery 4/4 pass. Tổng có 160 test thực sự chạy thành công qua hai lượt, không bao gồm 17 test legacy chưa xác nhận. Log: `target/news-recovery-regression.log`, `target/news-recovery-db-tests.log`.
- Đã khởi động lại instance 8081 từ bản code cập nhật và kiểm tra lại GET configuration/admin protection. Lúc 23:37 giờ Việt Nam, Cloudinary readiness vẫn false và API admin LLM ẩn danh trả 403. Hai run thật vẫn giữ nguyên; bài URL-only vẫn content_text=null.

**Chưa kết luận end-to-end thành công** cho tải/upload/đọc PDF thật → admin approve → phân tích thật: cần cấu hình Cloudinary được runtime nhận và Gemini hết lỗi quyền truy cập. Các run thật đã ghi được vẫn giữ trong DB để admin kiểm tra. Không tự duyệt đề xuất lỗi trong DB nghiệp vụ.

## 7. Cấu hình cần kiểm tra

Trong file private `application-local.properties` (không commit):

```properties
financial.documents.cloudinary.enabled=true
financial.documents.cloudinary.cloud-name=<cloud-name>
financial.documents.cloudinary.api-key=<api-key>
financial.documents.cloudinary.api-secret=<api-secret>
financial.news-recovery.scheduler.enabled=false
```

Kiểm tra GET configuration sau khi khởi động lại. Không gửi key qua chat. Xử lý quyền Gemini project trong Google AI Studio/Cloud; gateway không retry vô hạn lỗi 403. Khi kiểm thử thật còn lỗi quyền, giữ scheduler tắt.

Nguồn kỹ thuật: [Gemini URL Context](https://ai.google.dev/gemini-api/docs/generate-content/url-context), [PDF](https://ai.google.dev/gemini-api/docs/document-processing), [Cloudinary signatures](https://cloudinary.com/documentation/authentication_signatures), [Cloudinary access control](https://cloudinary.com/documentation/control_access_to_media).

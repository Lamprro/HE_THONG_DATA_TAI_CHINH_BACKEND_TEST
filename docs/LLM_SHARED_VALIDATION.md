# NEWS → LLM → validation dùng chung

Cập nhật 04/10/2026; thay đổi Java và database đã áp dụng ở môi trường local, chưa push/deploy.

## Luồng

news_articles → prompt có version → llm_runs / llm_run_attempts → response lưu bền vững
→ PENDING_VALIDATION → validation_rules (LLM_OUTPUT) → validation_results → llm_results nếu hợp lệ.

Không tạo thêm ingestion_job, ingestion_run, raw_payload hoặc data_version cho đầu ra AI.
llm_results giống data_version ở vai trò kết quả đã được kiểm tra, không thay thế lịch sử gọi trong llm_runs.

## Điều kiện xuất bản

Chín luật chung kiểm tra response hoàn chỉnh, JSON Schema, ID nguồn, trích dẫn nguyên văn,
quan hệ công ty, tính đầy đủ phân tích, không trùng tác động công ty, snapshot nguồn và snapshot prompt/luật.
NEWS_FINANCIAL_FACTS có luật thứ mười kiểm tra số liệu, đơn vị, kỳ và bên phát biểu trong bằng chứng.

Các luật bắt buộc phải active và có severity ERROR/CRITICAL; thiếu luật, executor không biết hoặc lỗi kỹ thuật
đều chặn xuất bản. DB lưu cấu hình, phạm vi task, severity và version; executor Java thực hiện luật,
không thực thi mã tùy tiện từ database. Schema request/response vẫn thuộc phiên bản prompt trong DB.

validation_results phân biệt RAW_PAYLOAD và LLM_OUTPUT bằng CHECK constraint.
LLM_OUTPUT bắt buộc llm_run_id, validation_round_id, rule_snapshot; không có raw_payload_id/ingestion_run_id.
Mỗi lượt kiểm tra có round riêng, giữ nguyên các lượt trước. PASS/FAIL/SKIP được ghi cho từng luật;
SKIP khi response/schema lỗi không đồng nghĩa hợp lệ, vì luật tiền đề đã chặn xuất bản.

Chỉ publish trong cùng transaction với validation, có khóa bài/task và unique index chống trùng.
Mỗi bài/task có tối đa một kết quả current, mỗi run tối đa một result. Kết quả đổi nguồn/prompt/luật bị ẩn
cho tới khi kiểm tra lại. Hash request chuẩn hóa thứ tự khóa JSON để không lệ thuộc thứ tự của JSONB.

## Khôi phục và kiểm tra lại

POST /api/admin/llm/validation/pending?limit=5 hoàn tất response đã lưu nếu tiến trình gián đoạn.
Scheduler gọi cùng bước này trước xử lý bài mới. PENDING_VALIDATION không bị cấp lại cho Gemini.
POST /api/admin/llm/runs/{id}/revalidate kiểm tra response đã lưu, không gọi provider.
Run log API trả cả attempts và validations của mọi round.

Sau sửa cấu hình luật, response cũ hợp lệ có thể được xuất bản nếu chưa có result của run đó
và chưa có kết quả current khác. Không kích hoạt lại kết quả lịch sử để ghi đè bản mới.
Nếu kết quả current không còn đạt luật, nó bị ngừng công bố. Revalidate không sửa nội dung AI gốc.

## Dọn database

Chỉ bỏ news_ai_analyses: bảng rỗng, không có luồng ghi đang dùng; view vw_company_news_latest đã chuyển
sang llm_results, giữ tên/kiểu cột và các bài URL-only. Không tự suy diễn sentiment_score từ impact.
Migration từ chối DROP nếu bảng có dữ liệu và không dùng CASCADE.
DDL khôi phục bảng và view trước thay đổi nằm trong docs/db-backups/20261004_*.sql.
Các bảng có dữ liệu, phụ thuộc hoặc tính năng khác không bị xóa chỉ vì không dùng trong NEWS→LLM.

Migration: V20261004_02__llm_shared_validation.sql và V20261004_03__remove_empty_legacy_news_ai.sql.

## Kiểm thử

Các nhánh lỗi, phục hồi, đổi luật, chống trùng được test trong schema PostgreSQL riêng rồi dọn.
Không chèn response AI giả vào bảng nghiệp vụ chung. Gọi Gemini thật qua API Spring Boot với bài FPT
đã có SUCCESS cho SUMMARY, DETAIL và FINANCIAL_FACTS; output quote/attribution sai bị REJECTED,
giữ nguyên lịch sử và chỉ chấp nhận response sửa đáp ứng luật. Không nới validation để nhận dữ liệu sai.
Đây là kiểm tra cấu trúc và bằng chứng nguồn, không bảo đảm mọi diễn giải của AI đúng tuyệt đối.

Nghiệm thu: 107 tests qua, không lỗi/bỏ qua. Hai bài FPT/HPG có 6 kết quả current,
mỗi round hiện hành đủ 9/10 luật; 0 lỗi chặn liên kết current, 0 nhóm current trùng,
0 schema test còn sót, 0 run RUNNING/PENDING_VALIDATION.
API execute lại cả hai bài trả sáu CACHED, counts giữ nguyên 35 runs / 49 attempts / 17 results lịch sử.
252 bản validation LLM (nhiều round), 46.126 validation RAW_PAYLOAD giữ nguyên.
View còn 505 quan hệ bài/công ty, 2 summary hợp lệ ở hai bài mẫu.
17 SUCCESS / 16 REJECTED / 2 FAILED là lịch sử gọi thật, không xóa các lỗi để làm đẹp số liệu.
URL-only thử qua API trả SKIPPED, không sinh run/result. Scheduler vẫn tắt để tránh chạy hàng loạt.

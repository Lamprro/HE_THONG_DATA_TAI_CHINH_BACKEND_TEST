# Gộp log gọi model vào llm_runs — 08/10/2026

## Cấu trúc sau thay đổi

Chỉ còn **một bảng log `llm_runs`**. Một dòng là một lượt xử lý nghiệp vụ;
cột JSONB `attempts` là mảng các lần gọi HTTP/retry/fallback của lượt đó.
Không tạo thêm dòng `llm_runs` chỉ vì đổi model. `llm_results` vẫn chứa kết quả
đã được kiểm tra; `validation_results` vẫn chứa từng luật/vòng kiểm tra.
Hai bảng này không phải log provider nên không gộp vào log.

```text
NEWS / forecast / recovery
  → tạo llm_runs với snapshot, prompt version, source hash
  → gọi model (không giữ transaction DB trong lúc chờ HTTP)
  → callback: thêm một phần tử vào llm_runs.attempts
  → fallback nếu cần: tiếp tục thêm vào cùng mảng
  → lưu phản hồi cuối trong llm_runs
  → validation_rules → validation_results
  → llm_results nếu đủ điều kiện (recovery vẫn cần admin duyệt)
```

Mỗi phần tử giữ: `id`, `llm_run_id`, `attempt_no`, `model_name`, `http_status`,
`error_category`, `request_payload`, `response_body`, `response_text`,
`input_tokens`, `output_tokens`, `latency_ms`, `created_at`.
Giữ request riêng cho từng attempt vì recovery có thể gọi lần thứ hai với tài liệu
khác. Với binary, tiếp tục dùng audit đã che binary bằng hash/kích thước;
không đưa API key vào log. Đây là giữ nguyên mức audit hiện có, không khẳng định
request recovery đã che binary có thể replay nguyên trạng.

## Code và API

- `LlmAttemptAudit.append()`: dùng một UPDATE nguyên tử để nối mảng, không đọc rồi
  ghi đè toàn bộ mảng. Từ chối attempt_no trùng trong run hoặc run không tồn tại.
- `LlmAttemptAudit.read()`: trả các phần tử theo attempt_no.
- `LlmRunStore.recordAttempt()` và `ForecastRunStore.attempt()` gọi chung helper.
  `NewsRecoveryService` tiếp tục ghi qua `LlmRunStore`, giữ bộ đếm cho nhiều lần gọi.
- `NewsRecoveryStore.totals()`: tính tổng từ JSONB, giữ token null nếu chưa biết;
  chưa có attempt thì độ trễ bằng 0, không nhầm thành số nguyên tối đa.
- API đọc run vẫn trả `attempts[]` với các trường cũ; FE không phải đổi sang API mới.
  Không thay đổi source_domain, quan hệ bài gốc, prompt hoặc quy tắc công bố kết quả.

Mảng log chỉ được ghi trong callback của cuộc gọi provider, không phải nội dung do
LLM tự khai báo. Retry vẫn có giới hạn hiện có. Log lớn làm tăng kích thước dòng
JSONB; cần theo dõi dung lượng và chính sách lưu trữ, không bật retry vô hạn.

## Migration và triển khai

File: `src/main/resources/db/manual/V20261008_01__merge_llm_attempts_into_runs.sql`.
Các migration lịch sử giữ nguyên; cài mới vẫn chạy thứ tự cũ rồi migration này cuối cùng.

1. Dừng toàn bộ ứng dụng/worker dùng DB đích; không chạy code cũ sau migration.
2. Sao lưu log. Script `scripts/migrate_llm_attempts.py --check` chỉ kiểm tra;
   `--apply` tạo backup riêng tư rồi thực hiện migration trên public của DB local config.
   Không dùng script này cho một DB đích khác mà chưa xác định config/backup.
3. Migration thêm cột mặc định `[]`, kiểm tra kiểu mảng, khóa hai bảng trong
   transaction ngắn, chặn RUNNING, chuyển toàn bộ log theo số thứ tự.
4. Kiểm tra từng JSON của từng dòng cũ tồn tại nguyên vẹn trong mảng mới,
   sau đó mới DROP bảng cũ, không CASCADE. Xung đột/lỗi khiến transaction rollback.
5. Deploy code mới rồi mở lại worker. Migration có thể chạy lại mà không nhân đôi log.

Nếu cần quay lại code cũ: dừng worker, khôi phục bảng cũ và lịch sử từ backup/mảng
JSON trong một transaction đã kiểm tra, rồi mới chạy code cũ. Không chỉ checkout
code cũ vì code đó vẫn truy vấn bảng đã xóa. Backup không commit lên Git.

## Bằng chứng DB thật ngày 08/10/2026

- Trước: 42 llm_runs, 59 llm_run_attempts, 22 llm_results, 0 RUNNING.
- Sau: 42 llm_runs, 59 phần tử attempts, 22 llm_results; bảng cũ không còn.
- Script đối chiếu toàn bộ trường JSON của 59 dòng cũ bằng giá trị, không chỉ đếm.
- Backup riêng tư: `docs/db-backups/llm-attempts-20261008T160720Z.json`, đã loại khỏi Git.

## Kiểm thử và phạm vi nghiệm thu

- Unit/contract tests kiểm tra các luồng hiện có.
- PostgreSQL thật trong schema cô lập: migration giữ mọi trường/rerun; xung đột
  hoặc RUNNING phải rollback; 8 append đồng thời không mất lịch sử; trùng số bị chặn.
- HTTP/service integration NEWS, forecast, recovery: dùng dữ liệu nguồn sao chép
  từ DB thật vào schema riêng, **provider Gemini mock**, không chèn response giả vào public.
- Không gọi Gemini thật trong lần thay đổi này: nghiệm thu tập trung vào lưu log,
  migration và tính tương thích các pipeline, không chứng nhận chất lượng dự báo
  hoặc khả năng đọc URL của Gemini. Nhánh LLM chưa được chứng nhận production.

Kết quả chạy cuối ngày 08/10/2026: **185 tests, 0 failures, 0 errors, 0 skipped**
(144 unit/contract + 41 integration DB cô lập: migration 4, NEWS 20, forecast 12,
recovery 5). Đã chạy lại sau khi DB public chuyển cấu trúc.

Lệnh Maven dùng `LLM_TEST_DB=1`, `RECOVERY_TEST_DB=1` và bộ chọn
`-Dtest=*,!FinancialDataApplicationTests,!NewsPipelineIntegrationFlowTests,!RealDatabaseNewsFlowVerificationTest`.
Ba nhóm loại trừ không phải bằng chứng nghiệm thu của lần thay đổi này.
Log local: `target/merge-attempts-final.log`, kết quả JUnit: `target/surefire-reports/`.
Hướng dẫn PostgreSQL best practices được áp dụng cho transaction ngắn và migration
nguyên tử; không giữ khóa DB trong lúc đợi model.

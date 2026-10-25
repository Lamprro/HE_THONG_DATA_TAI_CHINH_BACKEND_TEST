# Tra cứu nghiên cứu và kiểm soát worker admin — 25/10/2026

Sửa trực tiếp trên main theo yêu cầu. Checkout main được fast-forward từ 081b079
đến remote 8a65bc5 trước khi sửa; không đưa các thay đổi chưa commit ở checkout
LLM/Local AI vào main. FE được sửa riêng trên main từ e589248. Không deploy.

## Đã sửa

- Admin browser nhận diện thêm local_ai_packages, local_ai_package_inputs,
  local_ai_package_runs, local_ai_package_predictions và financial_disclosure_documents.
  Bảng chưa cài trả available=false; không tự tạo schema hoặc import gói model.
- Catalog có exactFilters theo bảng. Lọc được package_id/model_id/ticker/role,
  run_id/request_id/readiness và symbol/scope/revision/source_raw_payload_id.
  Input dài hơn 200 ký tự bị từ chối; các giá trị được bind vào SQL.
- Manifest, feature input và request/response payload của suite không đưa vào bảng
  tra cứu. Prediction/checks đã lưu được xem bởi admin, luôn giữ readiness/role/run
  identity; không coi SUCCEEDED/CACHED là đạt production hoặc accuracy.
- Search chỉ dùng cột scalar hiển thị và các nhãn mã/chỉ số liên quan. Không tìm
  trong payload/JSON ẩn; tránh việc đếm kết quả làm lộ nội dung đã lược bỏ và giảm
  đọc các payload lớn. Search vẫn là contains, không phải full-text index/SLA.
- Inputs phân trang theo khóa package/model/ticker; runs theo started_at+id,
  package theo registered_at+id, document theo observed_at+id. Không sort bằng
  toàn bộ feature payload. Cùng truy vấn không có concurrent writes có thứ tự ổn định;
  offset paging không phải snapshot xuyên nhiều HTTP request.
- FE hiển thị các cột model/mã/role/trạng thái và kỳ/scope/revision của tài liệu;
  phân biệt bộ lọc đang nhập với đã áp dụng, có xóa bộ lọc/reset trang.

## Worker trên instance

Hai cấu hình mới mặc định true để giữ hành vi cũ:

```
financial.admin.commands.enabled=false
financial.admin.schedule.enabled=false
```

False ngăn command worker khởi động, claim và hết hạn lease; submit/retry mới
trả 503. Worker lịch không heartbeat/sweep; bật policy từ instance tắt trả 503.
GET runtime trả adminWorkers, GET schedule trả workerEnabled. FE cảnh báo và
không cho xác nhận bật lịch ở instance này. Vẫn có thể tắt policy đã lưu.

Đây là cấu hình khi khởi động, không phải nút hủy job đang chạy hoặc khóa toàn
hệ thống. Instance khác vẫn có thể làm việc trên DB chung. Muốn runtime chỉ đọc
phải đồng thời dùng datasource read-only, tắt các scheduler và seed khác; API
mutation quản trị cũ không biến thành read-only chỉ vì hai cờ worker này.

## Bằng chứng

- 202 Java unit/contract, 13 PostgreSQL integration schema cô lập đạt; verify/package
  đạt. Test kiểm disabled worker không chạm DB/jobs, idempotency/retry/cancel và
  filter/paging/precision/payload omission. Domain service dùng mock trong ledger test.
- FE build + 11 tests + 3 Chrome suite fixture đạt. Không coi fixture là provider thật.
- Chrome thật → Java8094 → DB thật: 17 request, đăng nhập token chỉ giữ trong bộ
  nhớ, đọc 5 dataset mới, lọc FPT/model, empty state, reset, trang2, chi tiết và logout.
  Token không có trả 403. Runtime báo cả hai worker false. Không mutation/model call.
- Xem được 702 tài liệu đang lưu trong DB tại lượt test; số hàng là lịch sử quan
  sát/phiên bản, không kết luận 702 tài liệu duy nhất hay canonical đã được xác minh.
- Log private/local: target/oct25-unit.log, oct25-db.log, oct25-browser.log.
  Screenshot được rà; không commit ảnh dữ liệu thật/token. Test runtime đã dừng.

Không gọi train, inference, provider, không migration/backfill hoặc sửa DB public.
Chưa tích hợp API execute native Local AI từ checkout đang phát triển vào main;
browser đọc audit nghiên cứu hiện có. Các API tài liệu refresh ở nhánh sửa nguồn
chưa tự xuất hiện trong main này. Không giả lập nút chạy endpoint chưa được merge.
User 37 GET giữ trên feature/user-api; không merge sang main cùng sửa admin.

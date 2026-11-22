# Tra cứu nghiên cứu và kiểm soát worker admin — 25/10/2026

Sửa trực tiếp trên main theo yêu cầu. Checkout main được fast-forward từ 081b079
đến remote 8a65bc5 trước khi sửa; không đưa các thay đổi chưa commit ở checkout
LLM/Local AI vào main. FE được sửa riêng trên main từ e589248. Không deploy.

## Phạm vi hiện tại

- Admin browser dùng danh sách dataset cho phép, lọc theo cột và phân trang. Ví dụ tài liệu công bố có bộ lọc symbol, nguồn, phạm vi và phiên bản.
- Dữ liệu bí mật và payload ẩn không xuất hiện hoặc được đem tìm kiếm trong trình duyệt dữ liệu.
- Các màn theo dõi model nghiên cứu trước đây đã được gỡ khỏi backend.

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

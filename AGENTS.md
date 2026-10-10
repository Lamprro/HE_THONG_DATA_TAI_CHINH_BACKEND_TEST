# Ngữ cảnh cho AI tiếp quản backend

Đọc `docs/PROJECT_HANDOVER.md` trước khi sửa code. Dùng code và bằng chứng có ngày
để xác định trạng thái; tài liệu cũ có thể mô tả snapshot chưa push hoặc DB lịch sử.

- Backend Java ở repo này; nguồn Python là repo độc lập được gọi qua adapter hiện có.
  Dùng pipeline thật để nghiệm thu tích hợp; không thay nó bằng fetcher/script Python tự dựng.
- Nhánh chính hiện là `master`; công việc còn dở nằm trên `feature/llm-processing`.
  Bản nhánh LLM được push để tiếp tục sửa, chưa được chứng nhận production.
- Người dùng muốn tên branch/commit thông thường, không dùng tên `codex`.
- Validation raw và validation response LLM dùng chung catalog luật nhưng có target/audit riêng.
  Giữ provenance, phiên bản prompt, source hash, attempts và validation rounds.
- URL không có nội dung vẫn có giá trị để hiển thị link. Không cho task phân tích NEWS
  bình thường bịa nội dung; recovery là luồng riêng và cần admin duyệt.
- Bài trùng không tạo bài thứ hai. Chỉ có quan hệ company/security mới thực sự được thêm
  thì bản xử lý trùng có thể ACTIVATED; trùng hoàn toàn cần REJECTED hoặc không tạo version.
- Giữ quan hệ nguồn job dù body không nhắc mã đó; phân biệt SOURCE_JOB với TEXT_MATCH.
- Ngày nghiệp vụ dùng Asia/Ho_Chi_Minh; Instant có thể lưu UTC tương đương. Phân biệt
  published_at, crawled_at, trading date và thời điểm snapshot.
- Công thức xác định tính ở Java. Kết quả LLM là phân tích/kịch bản có bằng chứng,
  không tự biến giả định thành số liệu nguồn hay dự báo đã kiểm chứng độ chính xác.
- Không commit khóa, file cấu hình private, backup hoặc mẫu response giả vào DB nghiệp vụ.
  Kiểm thử dùng provider mock phải được báo rõ; test cần DB phải chạy ở schema/môi trường cô lập.
- Sau thay đổi, cập nhật bàn giao với việc đã sửa, test thực sự chạy và việc còn tồn đọng.
  Không ghi lỗi cũ đã xử lý chỉ vì unit test pass; nghiệm thu đúng loại bằng chứng cần thiết.

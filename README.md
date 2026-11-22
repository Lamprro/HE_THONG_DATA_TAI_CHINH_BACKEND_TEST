# Backend hệ thống dữ liệu tài chính

Java 21 / Spring Boot 3.5.0, PostgreSQL và Redis. Backend thu thập dữ liệu qua
Python API, kiểm định, lưu dữ liệu nghiệp vụ và gọi Gemini để phân tích NEWS
hoặc xây dựng kịch bản tài chính.

**Dev hoặc AI tiếp quản đọc trước [bàn giao hiện tại](docs/PROJECT_HANDOVER.md).**
Tài liệu này ghi trạng thái Git, luồng code, bằng chứng kiểm thử, lỗi còn tồn đọng
và thứ tự công việc tiếp theo. Không coi số liệu DB trong báo cáo cũ là số liệu hiện tại.

- Nhánh chính: `main`.
- Bản hợp nhất và kiểm thử: [MAIN_CONSOLIDATION_20261010.md](docs/MAIN_CONSOLIDATION_20261010.md).
- Cấu hình mẫu: [application-local.properties.example](application-local.properties.example).
- Tổng quan kiến trúc: [BACKEND_DEVELOPMENT_REPORT.md](BACKEND_DEVELOPMENT_REPORT.md).
- Luồng code NEWS và financial LLM: [LLM_FLOW_DEV_BA.md](docs/LLM_FLOW_DEV_BA.md).
- Sơ đồ request và UML NEWS → LLM: [NEWS_LLM_CODE_DIAGRAMS.md](docs/NEWS_LLM_CODE_DIAGRAMS.md).
- Rủi ro đã được ghi nhận: [PRODUCTION_DATA_FLOW_REVIEW.md](docs/PRODUCTION_DATA_FLOW_REVIEW.md).

CI chạy unit/contract và build trên PR/main. Deploy chỉ qua thao tác thủ công
`Deploy Spring Boot` trên `main`; migration DB và nghiệm thu server là bước riêng.

Không commit `application-local.properties`, khóa API, backup DB hoặc output trong `target/`.

## Đăng nhập Admin

Admin và người dùng thường đăng nhập bằng username và mật khẩu qua `POST /api/auth/login`. Spring Security đọc tài khoản `ACTIVE` trong `accounts`, kiểm tra `password_hash` và cấp JWT. Vai trò `ADMIN` mở các API quản trị.

Ở production, đặt một chuỗi bí mật ổn định vào `FINANCIAL_JWT_SECRET` trong GitHub Actions. Dùng chuỗi thường có ít nhất 32 byte UTF-8. Backend dùng chuỗi này để ký JWT và dẫn xuất khóa riêng để băm mật khẩu trước BCrypt. Tài khoản BCrypt cũ tự cập nhật `password_hash` sau lần đăng nhập đúng đầu tiên. Dùng cùng một chuỗi cho mọi instance kết nối cùng DB. Khi thay chuỗi, các tài khoản đã cập nhật hash cần được đặt lại mật khẩu. Local có thể để trống để dùng BCrypt thông thường và khóa JWT ngẫu nhiên theo tiến trình.

Deploy kiểm tra health và quyền truy cập HTTP. CI kiểm thử đăng nhập thành công bằng tài khoản mẫu.

Luồng macro Việt Nam: [MACRO_PIPELINE.md](docs/MACRO_PIPELINE.md).

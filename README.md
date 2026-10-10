# Backend hệ thống dữ liệu tài chính

Java 21 / Spring Boot 3.5.0, PostgreSQL và Redis. Backend thu thập dữ liệu qua
Python API, kiểm định, lưu dữ liệu nghiệp vụ và gọi Gemini để phân tích NEWS
hoặc xây dựng kịch bản tài chính.

**Dev hoặc AI tiếp quản đọc trước [bàn giao hiện tại](docs/PROJECT_HANDOVER.md).**
Tài liệu này ghi trạng thái Git, luồng code, bằng chứng kiểm thử, lỗi còn tồn đọng
và thứ tự công việc tiếp theo. Không coi số liệu DB trong báo cáo cũ là số liệu hiện tại.

- Nhánh chính: `master`.
- Nhánh đang hoàn thiện: `feature/llm-processing`.
- Cấu hình mẫu: [application-local.properties.example](application-local.properties.example).
- Tổng quan kiến trúc: [BACKEND_DEVELOPMENT_REPORT.md](BACKEND_DEVELOPMENT_REPORT.md).
- Luồng code NEWS và financial LLM: [LLM_FLOW_DEV_BA.md](docs/LLM_FLOW_DEV_BA.md).
- Sơ đồ request và UML NEWS → LLM: [NEWS_LLM_CODE_DIAGRAMS.md](docs/NEWS_LLM_CODE_DIAGRAMS.md).
- Rủi ro đã được ghi nhận: [PRODUCTION_DATA_FLOW_REVIEW.md](docs/PRODUCTION_DATA_FLOW_REVIEW.md).

Không commit `application-local.properties`, khóa API, backup DB hoặc output trong `target/`.

Luồng macro Việt Nam: [MACRO_PIPELINE.md](docs/MACRO_PIPELINE.md).

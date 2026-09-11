# BÁO CÁO PHÁT TRIỂN HỆ THỐNG BACKEND TÀI CHÍNH
> **Dự án:** `HE_THONG_DATA_TAI_CHINH_TEST`  
> **Nhánh phát triển hiện tại:** Backend API — Thu thập, Kiểm định, Phân tích dữ liệu tài chính  
> **Mục đích tài liệu:** Cung cấp ngữ cảnh đầy đủ để Developer hoặc BA mới đọc và tiếp tục phát triển hệ thống  

---

## MỤC LỤC

1. [Tổng Quan Hệ Thống](#1-tổng-quan-hệ-thống)
2. [Kiến Trúc Tổng Thể](#2-kiến-trúc-tổng-thể)
3. [Ngữ Cảnh Tích Hợp Bên Thứ Ba (Python Service)](#3-ngữ-cảnh-tích-hợp-bên-thứ-ba-python-service)
4. [Trạng Thái Phát Triển Hiện Tại](#4-trạng-thái-phát-triển-hiện-tại)
5. [Các Module Đã Hoàn Thành](#5-các-module-đã-hoàn-thành)
6. [Các Module Đang Phát Triển](#6-các-module-đang-phát-triển)
7. [Các Module Chưa Bắt Đầu (Kế Hoạch Tiếp Theo)](#7-các-module-chưa-bắt-đầu-kế-hoạch-tiếp-theo)
8. [Cấu Trúc Package](#8-cấu-trúc-package)
9. [Danh Sách API Endpoints Hiện Có](#9-danh-sách-api-endpoints-hiện-có)
10. [Hướng Dẫn Cấu Hình & Khởi Chạy](#10-hướng-dẫn-cấu-hình--khởi-chạy)
11. [Lộ Trình Phát Triển Tiếp Theo](#11-lộ-trình-phát-triển-tiếp-theo)

---

## 1. TỔNG QUAN HỆ THỐNG

Hệ thống backend tài chính này được xây dựng trên nền tảng **Spring Boot 3.x** với Java 21, đóng vai trò là **trung tâm thu thập và kiểm định dữ liệu tài chính Việt Nam**. Hệ thống kết nối đến:

- **Upstream Python Service** (FastAPI / Vercel) — lớp trung gian duy nhất có thể trực tiếp gọi các thư viện `vnstock`, `cafef`, `vndirect` để thu thập giá cổ phiếu, báo cáo tài chính, tin tức.
- **PostgreSQL** — lưu trữ toàn bộ dữ liệu gốc (raw), dữ liệu đã kiểm định, và master data.
- **Redis** — quản lý ngân sách retry (retry budget) theo từng Ingestion Job.

### Vai Trò Hệ Thống Trong Luồng Dữ Liệu Lớn

```
[Nguồn tài chính bên ngoài]
  VnStock / CafeF / VNDirect
         |
         | (vnstock Python lib, HTTP Crawl, RSS)
         v
[Python FastAPI Service]     <- UPSTREAM (không nằm trong repo này)
  https://he-thong-data-tai-chinh-test.vercel.app
         |
         | HTTP REST (JSON)
         v
[HE_THONG_DATA_TAI_CHINH_TEST]   <- DAY LA HE THONG NAY (Spring Boot Backend)
  - Thu thập và lưu raw data
  - Kiểm định chất lượng dữ liệu
  - Phiên bản hóa dữ liệu sạch
         |
         v
[PostgreSQL Database]  <->  [Redis Cache]
         |
         v
[AI/ML Models / Dashboard / API Người Dùng]    <- DOWNSTREAM (chưa triển khai)
```

---

## 2. KIẾN TRÚC TỔNG THỂ

Hệ thống áp dụng kiến trúc **Hexagonal Architecture (Ports & Adapters)** kết hợp **Immutable Raw Storage Pattern**:

### Nguyên tắc cốt lõi:

| Nguyên Tắc | Mô Tả |
|---|---|
| **Immutable Raw** | Dữ liệu thô từ upstream **không bao giờ bị xóa hay ghi đè** |
| **SHA-256 Dedup** | Phát hiện duplicate ngay lập tức qua checksum |
| **Validation Engine** | Mọi raw payload phải qua bộ quy tắc kiểm định trước khi được phục vụ |
| **Retry Budget** | Mỗi job có ngân sách retry trong Redis, tự động vô hiệu khi cạn |
| **Port Abstraction** | Adapter Python (HTTP) có thể thay bằng adapter khác mà không đổi service |

### Luồng dữ liệu chính:

```
[API Request / Scheduler Trigger]
        |
        v
[IngestionService] --> [PythonExternalFinancialDataAdapter]
        |                      |
        |              HTTP GET Upstream Python
        |                      |
        v                      v
[IngestionRunEntity]    [ExternalFetchResponse]
  (PENDING->RUNNING)           |
        |              SUCCESS?
        |         YES |        NO |
        v             v           v
  [RawPayloadEntity]  [markFailed]
  (lưu SHA-256)   [RetryBudget--]
        |
        v
  [ValidationEngine]
   (ValidationJobService)
        |
  +-----+------+
  |            |
 PASS         FAIL (CRITICAL)
  |            |
  v            v
[DataVersion]  [QuarantinedRecord]
 (ACTIVE)       (OPEN)
```

---

## 3. NGỮ CẢNH TÍCH HỢP BÊN THỨ BA (PYTHON SERVICE)

### Python Service là gì?

Đây là một **microservice FastAPI riêng biệt** deploy trên Vercel, đóng vai trò proxy/adapter để:
- Gọi thư viện `vnstock` (Python) lấy giá cổ phiếu, OHLCV, tỷ số tài chính
- Crawl dữ liệu từ `CafeF` (quản lý, công ty con, tin tức, sự kiện)
- Tích hợp `VNDirect` API
- Thu thập tin tức tài chính từ các nguồn báo điện tử qua RSS/crawl

### Cấu hình kết nối (trong `application.properties`):
```properties
financial.python-service.base-url=https://he-thong-data-tai-chinh-test.vercel.app
financial.python-service.connect-timeout=5s
financial.python-service.read-timeout=45s
```

### Các Operations được hỗ trợ (`ExternalOperation` enum):

| Operation | Endpoint Python | Mô Tả |
|---|---|---|
| `HEALTH` | `/api/v1/health` | Kiểm tra Python service còn sống |
| `PROVIDERS` | `/api/v1/providers` | Danh sách provider hỗ trợ |
| `QUOTE` | `/api/v1/{provider}/equities/{symbol}/quote` | Giá cổ phiếu tức thời |
| `OHLCV` | `/api/v1/{provider}/equities/{symbol}/ohlcv` | Lịch sử giá OHLCV |
| `COMPANY` | `/api/v1/{provider}/companies/{symbol}` | Thông tin công ty |
| `FINANCIAL_STATEMENT` | `/api/v1/{provider}/equities/{symbol}/financials/{type}` | BCTC (balance_sheet/income_statement/cash_flow) |
| `RATIO` | `/api/v1/{provider}/equities/{symbol}/ratio` | Tỷ số tài chính |
| `NEWS` | `/api/v1/cafef/{symbol}/news` | Tin tức theo mã |
| `EVENTS` | `/api/v1/cafef/{symbol}/events` | Sự kiện doanh nghiệp |
| `NEWS_LATEST` | `/api/v1/vnstock-news/latest` | Tin tức mới nhất |
| `NEWS_HISTORY` | `/api/v1/vnstock-news/history` | Lịch sử tin tức |
| `NEWS_COMPANY` | `/api/v1/vnstock-news/company/{symbol}` | Tin tức theo công ty |
| `RAW_PROXY` | `/api/v1/proxy/{provider}/{upstream_path}` | Proxy thô cho dev test |

> **Lưu ý:** Python Service là bên thứ ba, không nằm trong repo này. Khi Python service gặp lỗi,
> Backend ghi lại trong `ingestion_runs.error_message` và tiêu hao retry budget.

---

## 4. TRẠNG THÁI PHÁT TRIỂN HIỆN TẠI

### Tổng quan tiến độ:

| Module | Trạng Thái | Ghi Chú |
|---|---|---|
| Thu thập dữ liệu (Ingestion Engine) | HOÀN THÀNH | Hoạt động đầy đủ |
| Retry Budget (Redis) | HOÀN THÀNH | Tự vô hiệu job khi cạn ngân sách |
| Master Data (Company/Security) | HOÀN THÀNH | CRUD + tự provision jobs |
| Scheduler (Ingestion/Validation/Reconciliation) | HOÀN THÀNH | Cần bật trong config |
| Validation Engine (Kiểm định dữ liệu) | ĐANG PHÁT TRIỂN | Có một số rules, cần mở rộng |
| Tin tức (News Ingestion) | ĐANG PHÁT TRIỂN | Chỉ có rule kiểm định, chưa có controller riêng |
| Logging & Observability | HOÀN THÀNH | Logback rolling, MDC traceId |
| Authentication / Authorization | CHƯA BẮT ĐẦU | Cần Spring Security + JWT |
| AI/ML Integration | CHƯA BẮT ĐẦU | Entity có sẵn (llm_runs, predictions) |
| Dashboard API | CHƯA BẮT ĐẦU | |

---

## 5. CÁC MODULE ĐÃ HOÀN THÀNH

### 5.1 Thu thập dữ liệu (Data Ingestion Engine)

**Package:** `service/ingestion/`, `integration/python/`, `scheduler/ingestion/`

Quy trình xử lý hoàn chỉnh:
1. `IngestionJobScheduler` — polls active jobs theo `fixedDelay`
2. `IngestionJobService.executeDueJobs()` — đánh giá cron expression, quyết định job nào đến hạn
3. `IngestionService.execute()` — gọi `PythonExternalFinancialDataAdapter.fetch()`, lưu kết quả
4. `ChecksumService.sha256()` — phát hiện duplicate
5. `RetryBudgetService` — quản lý ngân sách trong Redis, tự tắt job khi hết

**Các class chính:**

| Class | Vai Trò |
|---|---|
| `IngestionService` | Orchestrator: resolve source -> call adapter -> persist raw |
| `IngestionJobService` | CRUD job + executeDueJobs + retry budget consumption |
| `PythonExternalFinancialDataAdapter` | HTTP client gọi Python service, log duration |
| `IngestionCompletionService` | Lưu SUCCESS run + raw payload vào DB |
| `RetryBudgetService` | Redis DECR/RESET ngân sách retry |
| `ChecksumService` | SHA-256 hash raw body để dedup |

### 5.2 Master Data (Công ty & Chứng khoán)

**Package:** `service/master/`, `controller/master/`

- CRUD Company (tạo, cập nhật, thêm alias)
- CRUD Security (khi tạo/bật security -> **tự động provision ingestion jobs**)
- `SecurityJobProvisioningService` — sinh ra các job QUOTE, OHLCV, FINANCIAL_STATEMENT cho mã mới
- `SecurityJobReconciliationScheduler` — rà soát định kỳ (24h) để đảm bảo job đồng bộ

### 5.3 Validation Engine

**Package:** `service/validation/`, `controller/admin/`, `scheduler/validation/`

Hiện có **11 executor keys** (quy tắc kiểm định):

| Executor Key | Domain | Mô Tả |
|---|---|---|
| `PRICE_NON_NEGATIVE` | MARKET_PRICE | Giá không âm |
| `PRICE_OHLC_VALID` | MARKET_PRICE | Low <= High, Open/Close nằm trong [Low,High] |
| `MARKET_VOLUME_NON_NEGATIVE` | MARKET_PRICE | Khối lượng không âm |
| `STATEMENT_REQUIRED_KEYS` | FINANCIAL_STATEMENT | Payload BCTC không được rỗng |
| `STATEMENT_ITEM_CODE_REQUIRED` | FINANCIAL_STATEMENT | Mỗi dòng BCTC phải có itemCode |
| `NEWS_TITLE_REQUIRED` | NEWS | Tin tức phải có tiêu đề |
| `NEWS_URL_REQUIRED` | NEWS | Tin tức phải có link hợp lệ (http/https) |
| `NEWS_DUPLICATE_HASH` | NEWS | Phát hiện tin tức trùng lặp qua SHA-256 |
| `RAW_ENVELOPE_REQUIRED` | ALL | Payload phải có provider, dataset, retrieved_at, data |
| `DATA_COUNT_MATCH` | ALL | `count` phải khớp với `data.size()` |
| `RAW_ERROR_MESSAGE` | ALL | Payload không chứa "error", "errors", "failed" |

---

## 6. CÁC MODULE ĐANG PHÁT TRIỂN

### 6.1 Validation Engine — Mở rộng quy tắc

**Trạng thái:** Rules cơ bản đã có, cần thêm:
- Rule kiểm định cho `RATIO` (tỷ số tài chính): P/E, ROE, ROA phải hợp lý
- Rule kiểm định cross-field (BCTC: Tổng tài sản = Tổng nợ + Vốn chủ)
- Rule time-series: không có gap quá lớn trong chuỗi giá

**File cần sửa:**
- `ValidationJobService.java` — thêm `case` mới vào `evaluate()` switch
- `ValidationRuleCatalogService.java` — thêm rule definition

### 6.2 News Ingestion (Thu thập tin tức)

**Trạng thái:** Python service đã hỗ trợ các endpoint tin tức. Backend đã có:
- `ExternalOperation` enum với các news operations
- `ValidationJobService` có rule `NEWS_TITLE_REQUIRED`, `NEWS_URL_REQUIRED`, `NEWS_DUPLICATE_HASH`
- Entity `NewsArticleEntity`, `NewsAiAnalysisEntity` trong repository

**Còn thiếu:**
- Controller riêng cho News (`/api/news/...`)
- Service riêng để xử lý sau khi ingest news: lưu vào `news_articles` table
- Hiện tại news chỉ được lưu dạng raw JSON vào `raw_payloads`, chưa parse thành `news_articles`

**TODO cho News Module:**
```
src/main/java/com/hethongdata/taichinh/
  service/news/
    NewsIngestionService.java         <- xử lý parse raw -> news_articles
    NewsService.java                  <- CRUD + query
  controller/news/
    NewsController.java               <- GET /api/news, GET /api/news/{id}
```

---

## 7. CÁC MODULE CHƯA BẮT ĐẦU (KẾ HOẠCH TIẾP THEO)

### 7.1 Authentication & Authorization
- Cần thêm Spring Security với JWT
- Phân quyền: `ROLE_ADMIN` (toàn bộ API admin), `ROLE_USER` (chỉ đọc)
- Entity `users`, `roles` đã có sẵn trong schema

### 7.2 AI/ML Integration Layer
- Entity `llm_runs`, `predictions`, `model_versions`, `dataset_samples` đã có trong schema
- Cần xây dựng service gọi LLM để phân tích tin tức và dự đoán xu hướng
- Kết quả dự đoán lưu vào `predictions` table

### 7.3 Market Price & Financial Statement Read API
- Hiện tại chỉ có API thu thập (write). Cần thêm API đọc dữ liệu đã kiểm định:
  - `GET /api/securities/{symbol}/prices?from=...&to=...`
  - `GET /api/securities/{symbol}/financials?period=...`
  - `GET /api/securities/{symbol}/ratios`

### 7.4 Watchlist & User Features
- Entity `watchlists`, `watchlist_items` đã có
- Cần service + controller cho người dùng quản lý danh mục theo dõi cá nhân

### 7.5 System Settings
- Entity `system_settings` đã có nhưng chưa có service/controller
- Dùng để cấu hình động qua API thay vì restart app

---

## 8. CẤU TRÚC PACKAGE

```
src/main/java/com/hethongdata/taichinh/
|
+-- FinancialDataApplication.java          <- Entry point, log startup summary
|
+-- application/port/                      <- Hexagonal Port interface
|   +-- ExternalFinancialDataPort.java     <- Interface gọi upstream
|   +-- error/                             <- Exception types
|   +-- model/                             <- Request/Response DTOs cho port
|
+-- bootstrap/                             <- Khởi tạo dữ liệu ban đầu
|   +-- RetryBudgetCatalogSeeder.java
|   +-- RetryBudgetInitializer.java
|
+-- common/                                <- Constants, helpers dùng chung
|   +-- AppParams.java
|
+-- config/                                <- Spring configuration beans
|
+-- controller/                            <- REST API layer
|   +-- ExternalFinancialDataController.java  <- Proxy sang Python service
|   +-- HealthController.java
|   +-- admin/
|   |   +-- ValidationAdminController.java
|   +-- exception/
|   |   +-- ApiExceptionHandler.java
|   +-- ingestion/
|   |   +-- IngestionController.java
|   |   +-- IngestionJobController.java
|   |   +-- RawPayloadController.java
|   +-- master/
|   |   +-- CompanyController.java
|   |   +-- SecurityController.java
|   |   +-- SecurityJobReconciliationController.java
|   +-- source/
|       +-- DataSourceController.java
|
+-- dto/                                   <- API Request/Response objects
|
+-- entity/                                <- JPA Entities (database tables)
|   +-- ingestion/
|   +-- master/
|   +-- validation/
|
+-- integration/python/                    <- Adapter gọi Python service
|   +-- PythonExternalFinancialDataAdapter.java
|
+-- repository/                            <- Data access layer
|   +-- jpa/                               <- Spring Data JPA repositories
|   +-- ingestion/, master/, validation/  <- Domain repositories
|
+-- scheduler/                             <- Scheduled jobs
|   +-- ingestion/IngestionJobScheduler.java
|   +-- master/SecurityJobReconciliationScheduler.java
|   +-- validation/ValidationScheduler.java
|
+-- service/                               <- Business logic layer
    +-- ingestion/                          <- Thu thập dữ liệu
    +-- master/                             <- Master data
    +-- source/                             <- Data source management
    +-- validation/                         <- Kiểm định dữ liệu
```

---

## 9. DANH SÁCH API ENDPOINTS HIỆN CÓ

### Ingestion & Job Management

| Method | Path | Mô Tả |
|---|---|---|
| `POST` | `/api/ingestions/manual` | Kích hoạt ingestion thủ công |
| `GET` | `/api/ingestions/{runId}` | Xem kết quả một run |
| `GET` | `/api/ingestions?limit=20` | Danh sách run gần nhất |
| `GET` | `/api/ingestion-jobs` | Danh sách job đang active |
| `POST` | `/api/ingestion-jobs` | Tạo job mới |
| `POST` | `/api/ingestion-jobs/{jobId}/run` | Chạy job ngay (manual) |
| `PATCH` | `/api/ingestion-jobs/{jobId}/activation` | Bật/tắt job |

### Master Data

| Method | Path | Mô Tả |
|---|---|---|
| `POST` | `/api/companies` | Tạo công ty mới |
| `PUT` | `/api/companies/{id}` | Cập nhật công ty |
| `GET` | `/api/companies` | Danh sách công ty |
| `POST` | `/api/companies/{id}/aliases` | Thêm alias cho công ty |
| `POST` | `/api/securities` | Tạo mã chứng khoán (tự provision jobs) |
| `PUT` | `/api/securities/{id}` | Cập nhật mã chứng khoán |
| `GET` | `/api/securities` | Danh sách mã chứng khoán |
| `PATCH` | `/api/securities/{id}/active` | Bật/tắt mã (trigger provision/deprovision) |

### Validation & Admin

| Method | Path | Mô Tả |
|---|---|---|
| `POST` | `/api/admin/validation/rules/seed` | Nạp rules mặc định |
| `GET` | `/api/admin/validation/rules` | Xem tất cả rules |
| `POST` | `/api/admin/validation/raw-payloads/{id}` | Validate một payload |
| `POST` | `/api/admin/validation/pending?limit=50` | Validate hàng loạt pending |
| `GET` | `/api/admin/validation/results` | Xem kết quả validation |
| `GET` | `/api/admin/validation/data-versions` | Xem data versions |
| `GET` | `/api/admin/validation/quarantined-records` | Xem bản ghi bị cách ly |

### External Financial Data Proxy

| Method | Path | Mô Tả |
|---|---|---|
| `GET` | `/api/external-financial-data/health` | Health check Python service |
| `GET` | `/api/external-financial-data/providers` | Danh sách providers |
| `GET` | `/api/external-financial-data/{provider}/equities/{symbol}/quote` | Giá cổ phiếu |
| `GET` | `/api/external-financial-data/{provider}/equities/{symbol}/ohlcv` | OHLCV |
| `GET` | `/api/external-financial-data/fetch?operation=...` | Generic fetch |

### Health & Diagnostics

| Method | Path | Mô Tả |
|---|---|---|
| `GET` | `/api/health` | Health check backend |
| `GET` | `/api/raw-payloads?...` | Xem raw payloads (diagnostic) |

---

## 10. HƯỚNG DẪN CẤU HÌNH & KHỞI CHẠY

### Yêu cầu môi trường

- **Java:** 21+
- **Maven:** 3.9+
- **PostgreSQL:** 15+ (database `postgres`, schema `public`)
- **Redis:** 7+ (port 6380 theo mặc định của docker-compose.redis.yml)

### Khởi chạy local (development)

**1. Tạo file `application-local.properties`** (đã được gitignore, không commit):
```properties
# Ghi đè cấu hình database cho máy local
spring.datasource.url=jdbc:postgresql://localhost:5432/postgres
spring.datasource.username=postgres
spring.datasource.password=your_password

# Ghi đè Redis nếu cần
spring.data.redis.port=6379
```

**2. Bật Redis với Docker:**
```bash
docker compose -f docker-compose.redis.yml up -d
```

**3. Chạy ứng dụng:**
```bash
mvn spring-boot:run
```

**4. Nạp dữ liệu ban đầu** (nếu cần, bật trong `application.properties`):
```properties
financial.ingestion.catalog.seed-enabled=true
financial.master-data.catalog.seed-enabled=true
financial.validation.catalog.seed-enabled=true
```

### Bật Scheduler trên server

Các scheduler mặc định **tắt** để tránh chạy tự động khi dev.
Khi deploy lên server, bật qua biến môi trường hoặc `application.properties`:

```properties
financial.ingestion.scheduler.enabled=true
financial.validation.scheduler.enabled=true
financial.security-job-reconciliation.scheduler.enabled=true
```

### Xem log trên server

```bash
# Log chính (INFO và trở lên)
tail -f logs/financial-app.log

# Chỉ xem ERROR
tail -f logs/financial-error.log

# Lọc theo runId để debug một ingestion cụ thể
grep "runId=<UUID>" logs/financial-app.log
```

---

## 11. LỘ TRÌNH PHÁT TRIỂN TIẾP THEO

### Phase 2 — Hoàn thiện News & Validation (Ưu tiên cao)

- [ ] Tạo `NewsIngestionService` — parse raw JSON news từ `raw_payloads` -> `news_articles`
- [ ] Tạo `NewsController` — `GET /api/news`, `GET /api/news/{id}`
- [ ] Mở rộng Validation Engine: thêm rules cho `RATIO` domain
- [ ] Thêm validation cross-field cho BCTC (cân bằng bảng cân đối kế toán)

### Phase 3 — Authentication & Read APIs (Ưu tiên trung bình)

- [ ] Tích hợp Spring Security + JWT
- [ ] Tạo Read APIs cho giá lịch sử, BCTC đã kiểm định
- [ ] API phân trang đầy đủ với filter theo ngày, symbol

### Phase 4 — AI/ML & Analytics (Ưu tiên thấp / Tương lai)

- [ ] Service gọi LLM để phân tích sentiment từ tin tức
- [ ] Kết quả phân tích lưu vào `news_ai_analysis`
- [ ] Xây dựng feature engineering pipeline cho `dataset_samples`, `feature_sets`
- [ ] Prediction API: `GET /api/predictions/{symbol}`

### Debts kỹ thuật cần giải quyết

- [ ] Thêm unit tests cho `ValidationJobService` (các rule executor)
- [ ] Thêm integration tests cho Ingestion flow end-to-end
- [ ] Implement pagination chuẩn cho tất cả GET APIs (hiện đang dùng `limit` đơn giản)
- [ ] Thêm traceId vào MDC từ request filter để track log end-to-end

---

> **Tài liệu liên quan:**
> - `SYSTEM_STATES_AND_DATA_LIFECYCLE.md` — Y nghia chi tiet cac trang thai va vong doi du lieu
> - `src/main/resources/application.properties` — Toan bo cau hinh he thong voi chu thich
> - `application-local.properties.example` — Mau cau hinh local

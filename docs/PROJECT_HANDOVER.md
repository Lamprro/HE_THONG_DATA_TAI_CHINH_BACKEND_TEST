# 22/11/2026 — Hợp nhất nhánh vá PDF

- PR #21 hợp nhất lịch sử nhánh vá PDF. Bản code hợp nhất giữ Spring Security và JWT, API kiểm tra dữ liệu thiếu `/api/admin/financial-data/gaps` và cấu hình deploy của `main`.
- Migration `V20261122_04` đã áp dụng trong đợt gỡ PDF bên dưới. Workflow trên `main` tiếp tục chạy thủ công. Bản Linux hiện được ghi nhận ở đợt gỡ PDF.

# 22/11/2026 — Gỡ kho bằng chứng PDF

- Bỏ API `disclosures`, `bindings` và `reconciliation` dưới `/api/admin/financial-data` cùng các màn quản trị tương ứng. `status`, `coverage`, `gaps`, rà soát dữ liệu cũ và tính lại chỉ số sử dụng dữ liệu tài chính hiện có. Admin tiếp tục duyệt chênh lệch nguồn qua **Tác vụ & lịch sử → Validation & version → Phiên bản dữ liệu**. Version chờ duyệt nằm trong `data_versions` và cảnh báo nằm trong `validation_results`.
- Migration thủ công `V20261122_04` xóa đúng 5 bảng bằng chứng và 2 view PDF. Chạy trên PostgreSQL tạm dựng từ định nghĩa schema dùng chung. Chạy hai lần thành công. Khi có view khác tham chiếu dữ liệu PDF, migration từ chối và hoàn tác cả giao dịch. Cần xác nhận API và FE đã ngừng gọi PDF trước khi áp dụng vào DB dùng chung.
- Bản sao 819 bản ghi PDF và 2 view đã được kiểm tra bằng `pg_restore`, lưu cục bộ trong thư mục bị Git bỏ qua `docs/db-backups/`. Các bảng raw/canonical, view dùng cho dữ liệu huấn luyện chung và repo Local AI độc lập nằm ngoài phạm vi migration. Script nghiên cứu cũ nào đọc trực tiếp bảng PDF cần dùng bản sao nếu chạy lại lịch sử.

- Đã merge Java PR #20 và FE PR #7. Build Java của PR có 259 test đạt và 120 test theo điều kiện bị bỏ qua. Bản vá production có 12 test đạt và smoke HTTP qua PostgreSQL tạm. Admin có 15 test đơn vị và 3 bộ kiểm thử trình duyệt dùng fixture.
- Linux đang chạy bản vá `2f2bce0` từ `4ea75d1` để giữ các API Local AI vốn đang phục vụ. Workflow deploy #44984832721 đạt, gồm các API coverage và kiểm tra route PDF trả 404. Đã xác nhận bản Admin trên Vercel không gọi các API PDF.
- Đã áp dụng `V20261122_04` cho DB dùng chung sau khi triển khai và kiểm tra bản sao 819 dòng lúc chuyển đổi. PostgreSQL xác nhận đủ 5 bảng và 2 view PDF đã bị gỡ. Sau migration, health và API BCTC FPT trả HTTP 200. Hai API Local AI công khai hiện có vẫn trả HTTP 200. Luồng duyệt lệch nguồn và đăng nhập JWT đã có trên `main`; bản vá production chỉ mang thay đổi PDF.

# 22/11/2026 — Đơn giản hóa cấu hình đăng nhập

- Spring Security dùng `AccountPasswordEncoder` để kiểm tra mật khẩu trong `accounts`. Khi có chuỗi cấu hình, backend dẫn xuất khóa băm riêng và dùng HMAC-SHA256 trước BCrypt. Tài khoản cũ chỉ cập nhật hash sau khi kiểm tra đúng mật khẩu. Hàm ghi kiểm tra hash cũ và trạng thái tài khoản để tránh ghi đè một lần đổi mật khẩu đồng thời.
- Giữ `FINANCIAL_JWT_SECRET` là chuỗi text duy nhất ở production. Khi để trống trong môi trường local, backend dùng BCrypt thường và tự tạo khóa JWT theo tiến trình. Tài khoản đã nâng cấp hash cần cùng chuỗi cấu hình để đăng nhập.
- Deploy kiểm tra health, một request login thiếu thông tin trả 400 và API Admin thiếu JWT trả 401. CI giữ bài đăng nhập và cấp token thành công bằng tài khoản mẫu. Workflow deploy đã bỏ các biến username và password kiểm tra riêng.

- Java đóng gói thành công với 269 bài đạt. Kiểm thử JAR qua HTTP với PostgreSQL loopback xác nhận đăng nhập sai giữ hash cũ, đăng nhập đúng nâng cấp hash đúng một lần và JWT mở được API Admin. Workflow được kiểm tra cú pháp và chạy với Docker/HTTP giả lập. DB dùng chung giữ nguyên.

# 22/11/2026 — Dọn bảng và duyệt lệch nguồn

- DB dùng chung đã áp dụng `V20261122_01`. `roles` chỉ còn `ADMIN` và `USER`, tài khoản Admin hiện có vẫn đăng nhập bằng JWT. Đã bỏ `audit_logs` (0), `system_settings` (4), `data_lineage_events` (0), `news_crawl_controls` (1) và `data_correction_audit` (6.920). Hai trigger ghi bảng correction được gỡ trước khi bỏ bảng. Cấu hình mặc định đã nằm trong Java và cấu hình job. Bản sao cục bộ được kiểm tra danh mục trước khi xóa, ở `.local-handover/schema-clean-legacy-20261122.dump` và bị Git bỏ qua.
- DB dùng chung đã áp dụng migration mở rộng `V20261122_03`. Mã mới đưa version vào `PENDING_REVIEW` trước khi thay dữ liệu canonical nếu khác giá đóng cửa ngày trên cùng cơ sở giá hoặc lệch quá 0,1% doanh thu/lợi nhuận sau thuế khi cùng loại báo cáo, kỳ, phạm vi và tiền tệ. Giá đóng cửa trùng từ nguồn ưu tiên thấp hơn được bỏ qua. Giữ quy tắc nguồn chính thức rồi priority để chọn canonical mặc định. Một version duyệt chấp nhận cho phép builder dùng nguồn được duyệt, còn từ chối giữ dữ liệu hiện tại.
- Kết quả lệch nằm trong `validation_results`. Mở **Tác vụ & lịch sử → Validation & version → Phiên bản dữ liệu** và chọn **Duyệt lệch nguồn**. Chấp nhận mở thao tác build version đã chọn. Phân hệ giá so sánh ngày khi xác định được giá đóng cửa chưa điều chỉnh. Báo cáo thiếu thông tin để xác định cùng cơ sở so sánh vẫn theo luồng validation hiện tại.
- Java và FE đã bỏ worker lịch Admin riêng. Sử dụng cron/trạng thái bật trong `ingestion_jobs`, lịch sử `ingestion_runs` và cấu hình bật scheduler của Java. Ghi chú mới nhất lưu trên kết quả `validation_results`. Lịch sử lệnh vẫn nằm trong `admin_commands`. Sau khi phát hành Java và FE đồng bộ, áp dụng `V20261122_02` để chép ghi chú cũ mới nhất rồi xóa `admin_operation_events` và `admin_schedule_policy`. Migration có guard để dừng nếu phát hiện lịch riêng đang bật hoặc còn phạm vi job đã đặt.
- Giữ 5 bảng chứng cứ PDF trong đợt này. API coverage/disclosures/bindings/reconciliation và các truy vấn Local AI vẫn dùng chúng. Số dòng khi kiểm tra lần lượt là documents 702, facts 37, bindings 37, reconciliation 6, review 37. Quy tắc mới làm việc trên dữ liệu canonical và raw/version thông thường, không dùng danh sách 6 mã của luồng PDF.
- Đóng gói Java với 266 bài đạt và 121 bài theo điều kiện bị bỏ qua. Chạy thêm 16 bài Admin trên PostgreSQL loopback, ba bộ browser FE dùng fixture và thử cả ba migration trên PostgreSQL tạm. Bản Java đóng gói xác nhận schema và gọi thành công login, summary, data versions và trạng thái tài chính FPT bằng kết nối chỉ đọc tới DB dùng chung. Ca lệch nguồn và quyết định duyệt dùng fixture, không gọi nhà cung cấp bên ngoài.

# 21/11/2026 — Đăng nhập bằng tài khoản và JWT

- `POST /api/auth/login` nhận `username` và `password`. `AccountSecurityService` đọc `accounts` và `roles` qua JPA, xác thực hash BCrypt bằng Spring Security. Chỉ tài khoản `ACTIVE` có username duy nhất được đăng nhập. `ADMIN` hoặc `ROLE_ADMIN` trong DB cho quyền Admin.
- `JwtService` phát JWT 15 phút. `security/SecurityConfiguration` xác minh chữ ký, issuer, audience và hạn dùng ở API bảo vệ. Token tĩnh cũ bị từ chối. API dữ liệu công khai dưới `/api/v1/**` vẫn dùng được như trước. Audit Admin ghi username, worker nền ghi `SYSTEM_WORKER`.
- FE Admin và Swagger đều đăng nhập bằng tài khoản. JWT của FE giữ trong bộ nhớ. Hết phiên thì đăng nhập lại. Thay đổi vai trò hoặc trạng thái tài khoản áp dụng cho lần đăng nhập tiếp theo. JWT đã cấp có hiệu lực tối đa đến thời điểm hết hạn.
- Production cấu hình một chuỗi bí mật `FINANCIAL_JWT_SECRET` dạng text. Spring Security dùng tài khoản và vai trò hiện có. GitHub Actions kiểm tra health và phản hồi từ chối trên API bảo vệ. Bài đăng nhập thành công dùng tài khoản fixture trong CI.
- Java đóng gói thành công, 260 test đạt trong lần chạy chung. Có 122 test theo điều kiện bị bỏ qua ở lần này và hai suite dùng DB chung được loại khỏi lệnh chạy. Chạy riêng 17 test trên PostgreSQL 17.12 tạm. Java đóng gói gọi HTTP thành công qua login, session và OpenAPI bằng bản ghi tài khoản trong PostgreSQL loopback. Mật khẩu sai và token tĩnh bị từ chối, tài khoản USER nhận 403 tại API Admin. FE build thành công, 34 test đơn vị và ba bộ trình duyệt Admin đạt với fixture.

# 21/11/2026 — Admin dùng JPA và DTO

- Ba bảng `admin_commands`, `admin_schedule_policy` và `admin_operation_events` có entity và Spring Data JPA repository. Các truy vấn tổng hợp, metadata và duyệt bảng Admin dùng JPA EntityManager. Các API commands, summary và schedule có DTO trong `AdminConsoleDtos`; tên trường JSON phía FE giữ nguyên. Dữ liệu xem theo cột động vẫn theo danh sách bảng và bộ lọc cho phép.
- Worker khóa hàng chờ bằng PostgreSQL `FOR UPDATE SKIP LOCKED` rồi chuyển sang RUNNING trong cùng transaction. Lệnh trùng dựa vào các ràng buộc unique hiện có. Lease và kết quả vẫn kiểm tra chủ sở hữu. Lịch vẫn cập nhật theo version. Khóa advisory theo phiên kết nối của job được giữ ở lớp dùng chung.
- Java đóng gói thành công; 259 test đạt và 121 test theo điều kiện được bỏ qua trong lần chạy chung, ngoài hai suite mặc định trỏ DB dùng chung đã loại riêng. Chạy thêm 16 test Admin trên PostgreSQL 17.12 tạm qua loopback, tất cả đạt. Các bài kiểm tra xác nhận hai transaction lấy hai lệnh khác nhau, gửi cùng idempotency key chỉ ghi một lệnh và một audit, retry kế thừa run, replay giữ nguồn, kiểm tra version lịch và số liệu tổng hợp. Các service gọi provider dùng mock, DB và transaction dùng PostgreSQL thật.
- Chạy lại suite Admin bằng `DATA_ENRICHMENT_TEST_DB=1` và `mvn -Dtest=AdminCommandIsolatedIntegrationTests -Dfinancial.test.config=<file-test-properties> test`. Dùng cấu hình PostgreSQL dành riêng cho kiểm thử; suite tạo và dọn schema riêng. Bản thay đổi dùng các bảng và migration hiện có.

# 21/11/2026 — Bảo mật và tổ chức lại luồng Admin

- Request Admin đi qua `SecurityConfiguration` → controller → service nghiệp vụ → repository. API health và API User dưới `/api/v1/**` vẫn công khai. CORS được xử lý trong Spring Security. Các route Admin cũ, kể cả route ngoài `/api/admin`, vẫn yêu cầu quyền `ROLE_ADMIN`.
- Giữ cấu hình `FINANCIAL_ADMIN_API_TOKEN` với tên audit `DEDICATED_ADMIN_TOKEN`. Có thể cấp token theo định danh `financial.admin.credentials.<tên>` với `token`, `roles=ADMIN` và `email` tùy chọn. Xem file cấu hình mẫu. Quyền `AUDITOR` chưa được cấp route. Session và audit dùng danh tính của thông tin xác thực. Worker nền dùng `SYSTEM_WORKER`.
- `AdminConsoleController` trả DTO cho `/session` và `/runtime`. `AdminRuntimeService` đọc trạng thái DB, cấu hình lịch và worker. Cờ bật cho biết cấu hình được bật. Nhịp chạy và lịch sử thực tế vẫn xem ở luồng lịch và log.
- Mở `/admin-api-docs/index.html` trên origin Java để dùng Swagger. Đặc tả `/v3/api-docs/admin` cần token Admin. Swagger trên trình duyệt cho thử GET. Các thao tác ghi thường dùng có màn hình riêng trong FE. Catalog reflection `/api/admin/console/catalog` đã được gỡ.
- Nạp danh mục từ **Hệ thống → Danh mục & tài liệu API** hoặc POST API tương ứng khi cần. Đã bỏ năm tác vụ seed lúc khởi động. Bộ SQL hiện có dưới `db/manual/` vẫn dùng theo quy trình hiện tại. Còn thiếu một baseline tái dựng schema hoàn chỉnh và đã kiểm thử trước khi đưa SQL nền sang Flyway hoặc Liquibase.
- Java đã đóng gói thành công. Bản chạy cuối có 258 test đạt và 117 test cần điều kiện riêng bị bỏ qua. Hai suite trỏ DB dùng chung được loại khỏi lần chạy cuối. Kiểm thử Spring MVC đã xác nhận token theo định danh, từ chối auditor, Swagger, endpoint nạp và tham số audit với service/repository giả lập. Chưa nghiệm thu các thao tác ghi mới trên PostgreSQL riêng.
- FE Admin và User build thành công. Có 19 test đơn vị và ba bộ browser Admin đạt với fixture. Nhánh cả hai repo là `clean-admin-architecture`. Khi phát hành, cần đồng bộ thay đổi FE Admin với backend mới. Không có thay đổi Python, dự án huấn luyện Local AI hay dữ liệu production trong bản này.

# Ranh giới backend

Local AI chạy ở dự án huấn luyện riêng. Backend Java giữ API tài chính, vận hành và LLM news/forecast. Gỡ script huấn luyện, mô hình Suite, API Admin/User, cấu hình và migration riêng của Local AI khỏi source. Việc gỡ file trong Git không chạy SQL xóa bảng. Các mục lịch sử bên dưới mô tả phiên bản trước thay đổi này.

Kiểm thử mặc định sau khi gỡ: 272 bài đạt, 117 bài cần cấu hình riêng bị bỏ qua. Client API: 4 bài đạt. PostgreSQL cô lập: 26 bài Admin/User/market đạt. Sau sửa ánh xạ lỗi thiếu route, 6 bài MVC/kiến trúc đạt và các route Local AI cũ trả 404. Giữ migration chất lượng thị trường đang tạo cột giá, bảng khối ngoại/bản ghi tham chiếu và audit phục vụ backend.

# Cập nhật 19/11/2026 — tinh gọn lớp backend (nhánh `refactor/backend-consolidation`, chưa push)

- `src/main/java` 234 → 177 file: gộp seeder/config/scheduler, controller ingestion/master/prompt, DTO master/source thành record,
  API user 7 → 5 controller và 14 → 6 service, read service market, wrapper repository thừa, helper financial/forecast/LLM/news.
  Không đổi URL, JSON, schema, SQL, cờ cấu hình hay thuộc tính transaction. Bảng tên cũ → mới, các cặp cố ý giữ (ranh giới
  transaction) và phát hiện cần quyết: [BACKEND_CONSOLIDATION_20261119](BACKEND_CONSOLIDATION_20261119.md).
- Sửa thêm: catalog `/api/admin/console/catalog` trả đúng tên tham số path/query (trước là `"body"`, làm hỏng 47/90 form API
  nâng cao của FE Admin); route không tồn tại trả 404 `NOT_FOUND` thay vì 500.
- Kiểm thử: `mvn -o test` + `DATA_ENRICHMENT_TEST_DB=1 LLM_TEST_DB=1` (schema cô lập): 401 test, chỉ còn 2 lỗi cũ
  `NewsPipelineIntegrationFlowTests` và 6 lỗi cũ `ForecastPipelineIntegrationTests` (chạy trên commit gốc 6147774 cũng hỏng
  đúng 6 test này; cần `LLM_TEST_CONFIG=application-local.properties`). Jar mới trên localhost 8080 + DB thật:
  `scripts/user_api_smoke.py` PASS 38 route/60 request; admin GET 200, thiếu token 403. Chưa deploy.

# Cập nhật 12/11/2026 — dọn lớp admin (nhánh `refactor/admin-cleanup`)

- Xóa 20 entity + 20 JPA repository không dùng (account/user/watchlist/prediction/dataset…),
  `FinancialMetricBuildService` (wrapper rỗng), `JsonDatabaseSupport`, `LegacyQuoteIntervalRepairRunner` (sửa dữ liệu một lần, đã xong).
- Truy cập: `AdminAccessConfiguration` (đổi tên từ ForecastAdminAccessConfiguration) bảo vệ toàn bộ `/api/**`
  trừ `/api/health*` và `/api/v1/**` ở mọi profile; bỏ cờ `protect-all-api`. Trước đó profile mặc định để hở
  `POST /api/security-job-reconciliation/run` và `/api/external-financial-data/**` (gồm `/fetch`).
- Service không còn gọi ngược controller: `AdminAiActionService` gọi thẳng service domain; logic
  analyze recovery chuyển vào `NewsRecoveryService.analyzeApproved`. SQL trong `AdminConsoleController`
  chuyển vào `AdminConsoleReadService`/`AdminCommandService`; bỏ field injection.
- Gộp controller: `FinancialDataQualityAdminController` → `FinancialDataAdminController` (bỏ kiểm token trùng),
  `GeminiAdminController` + `NewsLlmAdminController` → `LlmAdminController`. URL giữ nguyên.
- Kiểm thử: 395 test, 2 lỗi có sẵn từ trước ở `NewsPipelineIntegrationFlowTests` (fixture HTTP 403 nay được phân loại
  URL_ONLY → SKIP theo P08; test chưa cập nhật, không đổi logic). Localhost 8094 + DB thật: 28 GET admin 200,
  route bảo vệ trả 403 khi thiếu token; lệnh `INGEST_VALIDATE_BUILD` FPT OHLCV 02–12/11 → ACTIVATED;
  `FORECAST_PREVIEW` SUCCEEDED (không gọi model). Chưa deploy.
- Hiệu năng: `GET /api/ingestions` (lượt chạy gần nhất) tải cả response_snapshot/response_text — 20 run ≈ 27 MB nén,
  mất 26–61 s nên FE timeout 8 s. Nay select đúng cột DTO: 0,09–0,5 s, nội dung khớp từng trường với bản dựng từ entity.
  Walk Chrome thật 9 mục/18 màn hình FE qua DB thật: 0 lỗi, 0 request ghi.

# Cập nhật 28/10/2026 — hợp nhất toàn bộ nhánh backend vào main

Đã merge API user và các nhánh còn lại theo yêu cầu mới. Mọi ref nhánh local/origin
đã nằm trong lịch sử main; các cảnh báo “chưa merge” bên dưới là snapshot cũ.
259 unit/contract + 4 client tests đạt, package thành công. Kiểm thử DB/HTTP thật
chưa nghiệm thu vì database cấu hình local timeout. Không deploy, không migration
public, không lấy file Local AI chưa commit. Xem [chi tiết merge và giới hạn](MAIN_MERGE_20261028.md).

## Cập nhật 26/10/2026 — tinh gọn user sau main

Main `046fdaf` đã push trước. Nhánh user nhận main một chiều (`17c12d4`), gom
12 controller user thành 6, giữ nguyên toàn bộ hợp đồng 37 API và service nghiệp vụ.
213 unit/contract tests, clean package, 59 HTTP requests DB thật chỉ đọc và 16
requests Chrome đều đạt. Không train/model call, ghi DB hoặc deploy; không merge
user về main. Xem [bản đồ lớp và giới hạn nghiệm thu](CONTROLLER_CLEANUP_20261026.md).

# Cập nhật 26/10/2026 — tinh gọn main trước

Gom 5 controller nhỏ thành MarketDataAdminController và FinancialDataAdminController;
admin 12 → 9 class, giữ 10 URL và hành vi cũ. [Chi tiết](CONTROLLER_CLEANUP_20261026.md).
203 unit/contract + clean build/package pass; 12 HTTP localhost thật, DB read-only,
không gọi mutation/provider. Không đổi service transaction/worker hoặc folder train.
Commit/push main trước khi chỉnh nhánh user; không deploy.

# Cập nhật 25/10/2026 — tra cứu Local AI và worker admin

Sửa trực tiếp main; [bàn giao và nghiệm thu](ADMIN_RESEARCH_BROWSER_20261025.md).
Thêm 5 dataset audit/tài liệu, exact filter, paging composite, search bỏ payload ẩn;
cấu hình tắt riêng command/schedule worker trên instance. FE main nối catalog/filter.
202 unit/contract + 13 PostgreSQL cô lập pass; FE build/11 unit/3 suite fixture và
Chrome thật 17 request qua DB read-only pass. Không sửa train, không deploy.
User API vẫn riêng feature/user-api, không merge vào main.

# Cập nhật 25/10/2026 — nguồn chi tiết cho user

`feature/user-api` tiếp tục riêng, không merge/deploy. [Bàn giao 37 GET](USER_PROVENANCE_20261025.md)
bổ sung lineage đúng ID giá và metric, client và OpenAPI giữ bigint string.
209 unit/contract + 16 PostgreSQL schema tạm + 4 client pass; 59 HTTP và Chrome
16 request trên DB thật read-only pass. Không sửa train Local AI. Admin được sửa
riêng trên main/FE main; không đưa API user lên main.

# Cập nhật 23/10/2026 — reader gói Local AI mới và client FE

Tiếp tục `feature/user-api`, chỉ push, **chưa PR/merge/deploy**.
Đọc 35 GET, registry Local AI thật và luồng browser (tài liệu cũ).
Thêm model catalog/per-security progress từ suite registry, nối availability/overview;
không xuất prediction nghiên cứu hoặc sửa thư mục train/checkout gốc.
Client FE có UUID/envelope/decimal string, chỉ GET không credential, TypeScript và
browser harness. 209 Java unit + 15 PostgreSQL cô lập + 4 client test pass;
54 HTTP bao phủ 35 GET và Chrome thật 12 request qua DB read-only pass.
Đối chiếu 11 model/218 input/218 result lưu trong DB; chưa gọi lại PKL hoặc nghiệm thu
UI user hoàn chỉnh. FE demo vẫn cần chuyển ID số/POST cũ sang hợp đồng mới.

# Cập nhật 23/10/2026 — ghép API user từ các dự án workspace

Tiếp tục `feature/user-api`, chỉ push, **chưa PR/merge/deploy**.
Đọc [33 API user, đối chiếu các dự án và giới hạn tích hợp](USER_API_INTEGRATION_20261023.md).
Thêm coverage/lineage/periods, financial matrix, screener/compare, company directory,
foreign/reference observations, security overview, AI availability và OpenAPI public.
209 unit/contract + 12 PostgreSQL schema tạm pass; 48 HTTP trên DB read-only pass,
bao phủ 33 GET và CORS. Chưa nghiệm thu UI user hoàn chỉnh hoặc production.
Local AI đang do chat khác sửa: chỉ đọc registry, chưa có reader công bố dự đoán
trong build này; không đưa output nghiên cứu/NPAT RESEARCH_ONLY ra user.
Không sửa checkout gốc, FE hoặc Python; không gọi job/model và không sửa DB public.

# Cập nhật 20/10/2026 — API đọc cho user, chỉ trên nhánh

Nhánh `feature/user-api` từ main 8a65bc5; chỉ push, **chưa PR/merge/deploy**.
Đọc [hợp đồng 19 API user và nghiệm thu](USER_API_20261020.md).
Gồm danh mục/hồ sơ mã, tổng quan, quote/chart, chỉ số/rổ, macro, báo cáo, metrics,
tin và kết quả phân tích đã được reader gốc chấp nhận. Không đưa code Local AI
checkout gốc vào nhánh. 206 unit/contract + 8 PostgreSQL schema tạm pass;
29 HTTP localhost dùng DB read-only pass, không token admin, không gọi model/job.
Snapshot và giới hạn dữ liệu được ghi trong báo cáo; không coi là chứng nhận đủ data.

# Cập nhật 20/10/2026 — luồng thao tác admin

Java #12 và FE #2 đã merge, runtime 874bb24 đã deploy và nghiệm thu nguồn thật,
API + Chrome desktop/mobile pass. [Bằng chứng và sửa tiến trình cuối](ADMIN_ACCEPTANCE_20261020.md)
ghi rõ run/version, 24 version chờ giữ nguyên, lỗi mixed-worker đã giữ audit và
sửa stage kết thúc. Bộ unit cuối 200 pass + 12 PostgreSQL cô lập đã pass.

Đọc [luồng admin, migration và bằng chứng](ADMIN_FLOWS_20261020.md).
Thêm pipeline nguồn → validation → build đúng version, tiếp tục nguồn đã lưu,
hủy lệnh chờ/retry có audit, lịch cron theo phạm vi mặc định tắt, ghi chú lỗi,
prompt version bất biến và màn hình forecast/news/recovery riêng. Java 210 test
pass (12 PostgreSQL schema cô lập), FE 11 unit + 3 suite Chrome mô phỏng pass.
Luồng nguồn FPT thật localhost đã ACTIVATED, resume không fetch lại, giữ nguyên
24 version chờ khác. Không chạy model, không đụng code Local AI checkout gốc.
Migration bổ sung đã áp dụng sau backup sổ lệnh. Khi nâng cấp phải tránh worker
hai phiên bản cùng nhận lệnh mới từ DB chung; xem sự cố nghiệm thu trong báo cáo.
FE chỉ merge, chưa tạo hosting. Bằng chứng server sau deploy được bổ sung riêng.

# Cập nhật 19/10/2026 — điều hành admin

Đọc [API, kiểm thử và giới hạn admin](ADMIN_CONTROL_20261019.md).
Đã bổ sung job đã tắt/chỉnh sửa job, lệnh nền có lịch sử/chống trùng,
tra cứu DB phân trang, catalog API theo bản đang chạy và build đúng một
market-price version. Toàn bộ đường quản trị dùng token riêng.
FE độc lập có màn hình vận hành/LLM/forecast/recovery theo hợp đồng API.
Không đưa code Local AI đang sửa ở checkout gốc vào release này.
Người dùng chọn chỉ merge FE, chưa tạo hosting mới.
Đã merge Python #7, Java #10, FE #1. Java runtime faa187b đã deploy thành công
qua workflow 39272419130 ngày 19/10, 14:50 UTC. Kiểm tra trình duyệt thật
và source → validation → targeted build trên server pass; version dbf17bf6
đã ACTIVATED, 6 luật PASS, giữ nguyên version chờ khác. Không chạy LLM.
Commit bàn giao sau đó chỉ cập nhật tài liệu; không thay code runtime này.

# Cập nhật 17/10/2026 — nghiệm thu dữ liệu nguồn cho Local AI

Đọc [báo cáo nghiệm thu](TRAINING_DATA_ACCEPTANCE_20261017.md) và
[hợp đồng/giới hạn dữ liệu](TRAINING_DATA_QUALITY_20261017.md) trước khi train.
Java/Python đã deploy và chạy source → validation → bảng cuối trên localhost/server.
Không chạy NEWS/LLM. Chưa đủ bằng chứng công bố lịch sử để chứng nhận point-in-time;
không thay ngày công bố bằng cuối kỳ + 90 ngày. Người dùng đã duyệt cấu hình secret
cho API quality; bản `4c978b1` đã deploy thành công. Nghiệm thu ngày 17/10 lúc
06:56 UTC: token hợp lệ trả 200, thiếu/sai token trả 403; NEWS/LLM và dữ liệu giữ nguyên.

# Cập nhật 13/10/2026 — fix phân loại chất lượng forecast trên main

FINANCIAL_SCENARIOS không còn gán cố định quality_status=WARNING. Kết quả
VALID khi các luật đều PASS; WARNING khi chỉ luật severity WARNING FAIL;
ERROR/CRITICAL FAIL chặn publish. Giữ 7 luật bắt buộc có severity chặn lỗi;
hỗ trợ luật bổ sung, gồm FORECAST_DATA_LIMITATIONS chọn limitationPrefixes
từ catalog DB. Không tự seed cảnh báo hoặc đổi catalog public.

Writer INSERT và revalidation UPDATE lấy nhãn từ evaluation; reader/cache
kiểm tra đủ audit của đúng vòng thay vì count(PASS)=7. Thiếu/FAIL audit chặn lỗi
không đọc/cache như hợp lệ. Fingerprint engine v2 ngăn dùng cache policy cũ;
nhãn WARNING lịch sử cần revalidate qua API sau deploy, không sửa bằng SQL hàng
loạt. VALID xác nhận response đạt luật, không xác nhận accuracy/backtest.

235 tests pass (172 unit/contract, 38 forecast PostgreSQL integration, 20 NEWS
LLM và 5 recovery integration), 0 failures/errors/skipped; verify/package pass.
Provider mock, schema DB cô lập; không gọi Gemini thật, sửa DB public hoặc deploy
server. Fix riêng từ main 8babf09, không đưa P02/L05/P06/P08/L03 chưa merge vào
main. Chi tiết: [FORECAST_VALIDATION_QUALITY_20261013.md](FORECAST_VALIDATION_QUALITY_20261013.md).
# Cập nhật 12/10/2026 — P06, P08 và L03

Java tiếp tục nhánh `feature/forecast-periods-and-health`; Python main đã push
`b33dff1` và Vercel production READY đúng commit. P06 thêm contract metadata
tài chính có evidence ở Python và provenance/period_basis ở Java; không đoán
ngày công bố hoặc đơn vị khi nguồn không cung cấp. P08 tách validation URL_ONLY,
lưu link khi fetch lỗi, cho phép nâng cấp nội dung cùng bài. L03 thêm coverage
macro thiếu/cũ theo series trong input/hash, forecast prompt version 6.
Không triển khai L01/L02/L04/L06; giữ P03 và chưa thêm khóa P05.

Migration P06 áp public 00:13:01 giờ VN: 3.929 báo cáo, 281.097 items và 50 luật
validation cũ giữ nguyên; thêm cột/constraints/luật mới, có backup private và
đối chiếu idempotency. 250 test Java khác nhau đã pass qua regression và chạy
bổ sung recovery, Python 16 pass. Adapter Java gọi Python Vercel thật và đi qua
raw validation/build trong schema cô lập; NEWS failure/success và LLM dùng mock.
Chưa deploy Java server hoặc kích hoạt prompt v6 ở DB thật; seed prompt sau
deploy runtime mới. Không chứng nhận đầy đủ metadata lịch sử hay accuracy.
Chi tiết: [FINANCIAL_METADATA_NEWS_MACRO_20261012.md](FINANCIAL_METADATA_NEWS_MACRO_20261012.md).

# Cập nhật 11/10/2026 — health DB và kỳ dự báo

Nhánh mới `feature/forecast-periods-and-health` xuất phát từ main `8babf09`;
triển khai riêng P02/L05 theo yêu cầu. `/api/health` kiểm tra DB với deadline,
`/api/health/live` kiểm tra liveness. Dự báo có kỳ cố định và target scope; bản
mới đạt validation thay bản cùng kỳ/scope, giữ lịch sử và các quý khác. Request
ngày chốt cũ hơn không thay bản mới. Các cải tiến P05/P06/P08/L01/L02/L03 vẫn là
đề xuất, không tự triển khai. Giữ writer nguồn P03 và context L04 hiện tại.

Migration `V20261011_01__forecast_result_periods.sql` đã áp DB thật 23:23:58 giờ VN:
23 results/45 runs giữ nguyên, backfill 6 financial results, nội dung response và
audit không đổi; có backup private và đối chiếu idempotency. Trigger tương thích
INSERT từ runtime cũ. Runtime cũ vẫn có logic UPDATE theo as_of_date; chỉ runtime
mới bảo đảm đầy đủ việc giữ các kỳ độc lập khi cùng ngày chốt.

Kiểm thử 23:30: 176 unit/contract + 40 forecast PostgreSQL integration schema cô lập
pass, không failed/errors/skipped; provider mock. Không deploy server hoặc đổi
active prompt DB thật. Sau deploy nhánh mới mới seed forecast prompt v5 để dùng
explicit forecastPeriodEnd; request mặc định vẫn dự báo cuối quý tương lai theo
horizonQuarters. Chưa thêm scheduler theo quý hoặc kiểm chứng accuracy/backtest.
Chi tiết: [FORECAST_PERIODS_AND_HEALTH_20261011.md](FORECAST_PERIODS_AND_HEALTH_20261011.md).

# Bàn giao hiện hành — nhánh main, hợp nhất 10/10/2026

Nhánh mặc định GitHub là **main**. Bản source hợp nhất giữ MACRO, NEWS/LLM,
enrichment, metrics và STOCK_PRICE; xem [MAIN_CONSOLIDATION_20261010.md](MAIN_CONSOLIDATION_20261010.md)
để đọc PRs, xung đột đã xử lý, 244 kiểm thử pass và smoke localhost của bản tổng hợp.
Deploy là thao tác thủ công từ main; chưa deploy/áp migration server trong đợt này.
Các phần dưới giữ snapshot lịch sử theo ngày/nhánh, không ghi đè trạng thái hiện hành.

> Tích hợp metrics 10/10/2026: giữ một writer provider và một calculator actual có provenance; job legacy dùng chung HistoricalFinancialMetricService. Bản tổng hợp pass 172 unit/contract và 72 PostgreSQL integration schema cô lập (provider mock), 0 failed/errors/skipped; chưa deploy server.

> Tích hợp 10/10/2026: giữ pipeline MACRO từ master và luồng LLM. Bằng chứng macro: [MACRO_HANDOVER_20261009.md](MACRO_HANDOVER_20261009.md). Bản hợp nhất đang kiểm thử, chưa triển khai server.

# Bàn giao dự án và ngữ cảnh công việc đang tiếp tục

> **09/10/2026 — làm giàu dữ liệu nguồn, nhánh `fix/data-enrichment`:** DB thật đã
> tăng 10→25 mã, 66.006 nến ngày canonical; mỗi mã 42 quý × 3 báo cáo đến Q2/2026
> và 10 năm × 3 báo cáo năm 2016–2025. Có 6.660 chỉ số mới canonical/VALID,
> tính/ghi bằng Java qua API và pipeline Python thật; không chạy NEWS/LLM.
> Còn gap giá GAS/VND 2019, TCB bắt đầu IPO 2018 và Q3/2026 chưa có nguồn.
> Sửa item_code dài, daily backfill và nối FINANCIAL_METRIC_BUILD/API tỷ lệ lịch sử.
> 153 unit/contract + 5 PostgreSQL schema cô lập pass (integration dùng provider mock,
> backfill live dùng provider thật). Chưa merge/deploy; không coi các lỗi LLM hoặc
> metadata/replay cũ đã được xử lý. Chi tiết/nghiệm thu: [DATA_ENRICHMENT_20261009](DATA_ENRICHMENT_20261009.md).

> Kiểm nghiệm localhost mở rộng 10/10/2026: 32 integration schema cô lập (Gemini
> mock) + 146 regression pass, 4 skipped. API thêm validationRoundId; nghiệm thu
> đúng 7 luật ở vòng hiện hành khi audit có nhiều round. FPT thật đã lưu revalidate
> SUCCESS, 8 concurrent cache requests đều CACHED, không thêm provider attempt.
> Kiểm horizons 1–8, cutoff, stale/provenance, response lỗi và claim đồng thời đạt.
> Chỉ chạy localhost theo yêu cầu, chưa đo accuracy/backtest. Xem
> [biên bản kiểm nghiệm mở rộng](FORECAST_LOCALHOST_VERIFICATION_20261010.md).

> Bổ sung 10/10/2026 trên `feature/stock-price-scenarios` (checkout riêng): thêm
> STOCK_PRICE vào forecast, sửa horizon thành cuối quý tương lai thứ n, chứng minh
> đơn vị/giá nền từ raw, thêm SMA20/SMA60/return20 và schema v2/prompt version 4.
> Regression trước đó 146 pass; đợt sửa prompt đã chạy lại 18 integration schema
> cô lập pass (Gemini mock). Live sau cập nhật private config: Gemini HTTP 200,
> đủ 6 targets, 7 luật PASS, Java projections đúng và replay CACHED; không mock.
> Run `c1d02bfd-5caa-4268-b603-cd11a865bedf`, result WARNING/PARTIAL; 10 macro có
> trong input nhưng output không cite macro nên macro_used=false. Version 4 làm
> rõ cờ này sau một response v3 bị REJECTED; giữ nguyên validator và audit cũ. Xem
> [biên bản chạy và lý do WARNING](FORECAST_LIVE_CHECK_20261010.md).
> Không merge master hoặc restart NEWS; Java vẫn chỉ chạy localhost test. Xem [STOCK_PRICE_SCENARIOS](STOCK_PRICE_SCENARIOS.md)
> và [đề xuất dữ liệu nhà phân tích](ANALYST_DATA_REQUIREMENTS.md). Bằng chứng cũ dưới đây giữ lịch sử.

> Cập nhật 08/10/2026: lịch sử từng lần gọi model nằm trong mảng JSONB `llm_runs.attempts`, không còn bảng log attempts riêng. Xem [hướng dẫn chuyển đổi](LLM_ATTEMPTS_MERGE.md). Các kết quả kiểm thử cũ bên dưới là bằng chứng của thời điểm ghi báo cáo.

Đây là điểm đọc đầu tiên cho dev, BA hoặc AI tiếp quản. Snapshot nền ngày
**07/10/2026, giờ Việt Nam** chỉ đọc code/Git và tests, không truy vấn lại DB.
Đợt **08/10/2026** đã gộp log model vào `llm_runs.attempts` cho NEWS, forecast và
recovery, thực hiện migration DB thật: giữ nguyên 42 runs, 59 attempts, 22 results,
đối chiếu toàn bộ nội dung từng log trước khi bỏ bảng log cũ. Backup riêng tư ngoài Git.
185 tests pass (144 unit/contract, 41 integration PostgreSQL schema cô lập); provider
trong integration là mock, không gọi Gemini/Python thật hoặc triển khai server.
Code mới chỉ cập nhật `feature/llm-processing`; không đổi `master`.
Chi tiết migration, giới hạn nghiệm thu và cách deploy: [LLM_ATTEMPTS_MERGE.md](LLM_ATTEMPTS_MERGE.md).

## 1. Git và phạm vi công việc

- Repo Java: `https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST`.
- Nhánh chính GitHub là `master`; đã xác nhận không có `main` ngày 07/10/2026.
- Nhánh tiếp tục phát triển: `feature/llm-processing`, tạo từ `aaa7d8d` trên master.
- Commit code snapshot: `5343f81` — `feat(llm): gửi dữ liệu news và tài chính sang LLM để xử lý`.
  Gồm 131 file source, prompt/schema, migration thủ công, tests, scripts và tài liệu.
  Đã push; chưa merge. Các sửa còn tồn đọng được chủ động lưu ở nhánh để sửa tiếp.
- Những phần đã push lên master trước đó: chọn API source `eb3bd7c`, chuẩn hóa giá
  `7c8bec3`, ngày chỉ số `6b99e43`, đọc nhãn báo cáo `0300054`, release note `aaa7d8d`.
- Python upstream là repo riêng: `https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_TEST`.
  Các thư mục checkout Python/FE có trong workspace không thuộc commit backend này.
- `docs/db-backups/`, `test.txt`, khóa và `application-local.properties` được giữ ngoài Git.
  Không có backup riêng tư trong clone mới; chuẩn bị backup môi trường đích trước migration.

Yêu cầu hiện tại là lưu đầy đủ code/tài liệu để tiếp tục sửa trên nhánh LLM.
Chưa có kết luận các phần còn dở đủ điều kiện merge hoặc bật scheduler trên server.

## 2. Mục tiêu và quy ước nghiệp vụ đã thống nhất

Phạm vi dữ liệu ban đầu gồm FPT, ACB, BID, HPG, VCB; mục tiêu backfill là năm năm
cho các dataset được cấu hình. Đây là yêu cầu về coverage, không phải khẳng định
mọi bảng đã đủ năm năm. NEWS không triển khai thêm job tin chung trong phạm vi này.

Nguồn dữ liệu đi qua API Python mà Java đang tích hợp. Không thay pipeline bằng
script/fetcher độc lập để tạo cảm giác đã test. Hợp đồng response thật quan trọng hơn
so sánh chuỗi version health với code local. Phải đọc HTTP trong envelope, dữ liệu,
mã chứng khoán, đơn vị và metadata nguồn, không lấy bản ghi đầu tiên thay toàn bộ batch.

Giữ URL hợp lệ khi không lấy được body để người dùng đọc link. Trùng URL/body không
tạo news_article thứ hai. Bài đã có có thể nhận thêm quan hệ company/security nguồn;
chỉ khi có đóng góp quan hệ mới thì ACTIVATED, trùng hoàn toàn thì REJECTED hoặc
không tạo data_version. Đây là tiêu chuẩn cần giữ và kiểm thử, không suy từ tên status.
SOURCE_JOB phải giữ kể cả body không nhắc mã nguồn; TEXT_MATCH lưu bằng chứng riêng.

Ngày đăng là `published_at` của từng bài; `crawled_at` là lúc tải. Ngày nghiệp vụ dùng
Việt Nam; lưu Instant UTC tương đương không phải sai múi giờ. QUOTE là snapshot,
OHLCV là nến ngày; không coi nhiều snapshot một ngày là bản ghi daily bị trùng.

## 3. Kiến trúc và các điểm vào code

Package gốc: `src/main/java/com/hethongdata/taichinh/`.

| Luồng | Điểm vào và lớp chính | Bảng/kết quả |
| --- | --- | --- |
| Thu thập API | controller ingestion → IngestionJobService/IngestionService → PythonExternalFinancialDataAdapter → IngestionCompletionService | ingestion_runs, raw_payloads |
| Kiểm định raw | ValidationJobService → ValidationRuleExecutionService → finalize run | validation_results; một data_version cho run đủ điều kiện |
| Giá cổ phiếu | MarketPriceWorkflowService → MarketPriceWorkflowPersistenceService → MarketPricePayloadParser | market_prices, canonical theo source và khóa nghiệp vụ |
| Chỉ số/rổ | MarketIndexWorkflowPersistenceService → MarketIndexPayloadParser | index_prices, security_index_memberships |
| Báo cáo tài chính | FinancialStatementBuildService → FinancialStatementBuildPersistenceService | financial_periods, financial_statements, financial_statement_items |
| NEWS nguồn | NewsWorkflowService → NewsWorkflowPersistenceService, NewsUrlNormalizer, NewsCompanyMatcher | news_articles, news_article_companies |
| NEWS LLM | NewsLlmAdminController → NewsLlmService → NewsLlmContext → LlmRunStore/GeminiLlmGateway → LlmValidationService | llm_runs/attempts, validation_results, llm_results |
| Financial LLM | FinancialForecastAdminController → FinancialForecastService → ForecastContextService/FinancialRatioCalculator → ForecastPromptService/ForecastRunStore → GeminiLlmGateway/ForecastValidationService | financial_metrics tính bằng Java; llm_results dạng kịch bản |
| Recovery NEWS | NewsRecoveryAdminController → NewsRecoveryService/Store/Catalog/Validation; SafeDocumentDownloader, NewsDocumentReader, CloudinaryDocumentStorage | proposal và manifest trong llm_runs; bài gốc chỉ đổi sau approve |

Validation raw chặn cả ingestion_run nếu có lỗi blocking; không tách run để lách lỗi.
Ingestion SUCCESS chỉ chứng minh đã lưu raw, không chứng minh builder/coverage hoàn tất.

## 4. Đầu vào/đầu ra LLM và cách lưu

Bổ sung 08/10/2026: [NEWS_LLM_JSON_FIELD_MAP](NEWS_LLM_JSON_FIELD_MAP.md) ghi rõ
tất cả trường input/output NEWS, nguồn DB, prompt/schema và mapping lưu trữ.
Các ví dụ cấu trúc là minh họa, không phải dữ liệu chạy thật; không đổi code/runtime.

Cập nhật tài liệu 08/10/2026: xem [sơ đồ code NEWS → LLM](NEWS_LLM_CODE_DIAGRAMS.md).
Sơ đồ phân biệt JSON bài báo với request Gemini đầy đủ, có prompt và schema output;
đồng thời nối API/scheduler → claim → call → audit → validate → publish. Chỉ sửa tài liệu,
không chạy lại DB/Gemini và không coi các lỗi tồn đọng dưới đây đã được xử lý.

NEWS thường đọc `news_articles` có body đủ điều kiện cùng title, ngày đăng, quan hệ,
provenance và hash nguồn. SUMMARY trả tổng quan/sections; DETAIL trả loại văn bản,
sự kiện và tác động công ty; FINANCIAL_FACTS trích số liệu thực tế/dự báo/kế hoạch
được bài nêu. Task FACTS cần DETAIL hiện hành chỉ ra có số liệu hoặc forecast.
URL-only trả SKIPPED ở luồng phân tích thường; không sinh phân tích từ body rỗng.

Financial context đọc báo cáo current/canonical theo cutoff, giá đóng cửa ngày gần nhất,
metrics đủ điều kiện và macro nếu có. Chưa đưa NEWS/index/memberships vào context forecast.
Năm target hiện có: NET_PROFIT, PRETAX_PROFIT, TOTAL_ASSETS, OWNERS_EQUITY, LIABILITIES.
Java tính tỷ lệ/công thức và projections; LLM tạo giả định growth và giải thích ba
kịch bản bear/base/bull. Đây chưa phải forecast được backtest hoặc xác suất thống kê.

| Thành phần | Trách nhiệm |
| --- | --- |
| llm_prompt_templates | task/version, prompt, request/response schema, checksum và enabled |
| llm_runs | input snapshot, nguồn, template, output, trạng thái và lỗi |
| llm_runs.attempts | từng lần gọi/fallback model, HTTP, latency, token, request/response audit |
| validation_rules | catalog cấu hình; executor Java thực hiện các loại luật hỗ trợ |
| validation_results | audit từng round; LLM_OUTPUT có llm_run_id/validation_round_id, tách RAW_PAYLOAD |
| llm_results | kết quả JSON đã validate, liên kết nguồn NEWS hoặc company/security; lịch sử và current |

Không dựng ingestion/raw/data_version lần nữa cho response LLM. Luật trong DB không
thay thế executor Java, JSON schema và kiểm tra nghiệp vụ trong code. Prompt/schema
thay đổi phải tăng version; không ghi đè âm thầm version đang dùng. Response được lưu
trước validation; pending/revalidate tái dùng output đã lưu thay vì gọi provider lại.
Không publish khi source/prompt/luật đã đổi hoặc có lỗi blocking. CACHED là tái sử dụng
kết quả phù hợp fingerprint, không phải một lần gọi Gemini mới.

Recovery URL-only đi qua Gemini URL Context, không gọi Python fetch lại. Tài liệu được
tải có giới hạn và lưu Cloudinary; PDF/Office/text được xử lý theo reader hiện có.
Code hiện có nhánh đọc PNG/JPEG trực tiếp; yêu cầu trước của người dùng là bỏ qua ảnh,
nên cần chốt/sửa lại phạm vi này trước nghiệm thu. Proposal luôn chờ admin, dù validation
pass; approve kiểm lại nguồn/hash/luật, cập nhật body và retire kết quả cũ. Sau approve,
analyze mới chạy các task NEWS bình thường. Không khẳng định một lần gọi AI xử lý hết.

## 5. Bằng chứng kiểm thử và giới hạn

Ngày 07/10/2026, đã xuất đúng index vào `target/release-llm-20261007/` rồi chạy
Maven trên bản source chuẩn bị commit: **144 tests, 0 failures, 0 errors, 0 skipped**
trong 22 test suites. Build source và test source thành công. Đây là kiểm thử unit/
regression, có mock; không phải live provider hoặc DB end-to-end.

Lệnh tái chạy nhóm này từ checkout có JDK 21/Maven:

```powershell
mvn '-Dtest=*,!FinancialDataApplicationTests,!NewsPipelineIntegrationFlowTests,!RealDatabaseNewsFlowVerificationTest,!NewsLlmIsolatedIntegrationTests,!ForecastPipelineIntegrationTests,!NewsRecoveryIsolatedIntegrationTests' test
```

Các lớp bị loại cần context/DB hoặc môi trường opt-in; không được ghi chúng là đã pass.
Test DB opt-in dùng `LLM_TEST_DB=1` hoặc `RECOVERY_TEST_DB=1`; phải đọc setup từng lớp
và chuẩn bị schema cô lập trước chạy. RealDatabaseNewsFlowVerificationTest hiện disabled
vì có nguy cơ ghi mock vào DB dùng chung. Không chạy toàn suite vào DB nghiệp vụ tùy tiện.

Bằng chứng live **lịch sử ngày 04/10/2026**, xem NEWS_RECOVERY_FLOW_AND_TESTS:
URL-only có response nhưng thiếu body/evidence/retrieval; bài khác Gemini 403 bị từ chối
quyền project; Cloudinary runtime chưa ready. Chưa xác nhận chuỗi upload PDF thật →
Gemini đọc → admin approve → phân tích thành công. Các số đếm DB ở báo cáo cũ đều là
snapshot lịch sử, cần query lại trước báo cáo tình trạng hiện tại.

## 6. Các việc còn dở và thứ tự tiếp tục

| Việc | Trạng thái/bước cần làm |
| --- | --- |
| Hợp đồng URL lỗi | Code articleDraft chỉ fallback FAILED; báo cáo thật từng có SKIPPED/404. Đồng bộ raw validation và builder để URL hợp lệ vẫn được giữ; test response thật từ Python. |
| Kỳ forecast | Đã sửa trên feature/stock-price-scenarios: quý tương lai thứ n; test biên quý/năm nhuận và API preview pass. Chưa merge sang các nhánh/runtime khác. |
| Financial metadata | Scope/unit/currency/riêng quý-lũy kế/publication cần xác minh nguồn; không lấy created_at thay ngày công bố để tuyên bố backtest point-in-time. |
| Stale replay financial | Persistence có nguy cơ raw cũ thay current mới; bổ sung kiểm tra thứ tự và test replay trong DB cô lập. |
| Kịch bản dự báo | Cần chốt sensitivity độc lập hay financial projection cân đối; kiểm tra assets = liabilities + equity và cách hiểu bear/bull theo ngành. |
| Admin toàn hệ thống | Code hiện bảo vệ forecasts/llm/news-recovery bằng token. Chưa có bằng chứng mọi ingestion/master/validation/admin khác có RBAC tập trung. |
| Provider/config | Xác nhận runtime nhận khóa và model thật hỗ trợ; không coi danh sách model mặc định là danh sách khả dụng. Lỗi quota/quyền phải có giới hạn retry. |
| Recovery ảnh/tài liệu | Đối chiếu yêu cầu bỏ qua ảnh với reader PNG/JPEG hiện có; nghiệm thu upload, tài liệu thật, admin review và validation. |
| Nhiều worker | Nghiệm thu claim/retry, run treo, unique/canonical, pending sweep và race bằng DB staging. |
| Coverage | Đo lại theo symbol/dataset/kỳ, nêu gap; macro/rổ/metrics và raw-only datasets không mặc nhiên đầy đủ. |
| Release | Migration có thứ tự trên schema staging, quyền/cấu hình, smoke thật từng dataset rồi mới đánh giá PR/merge và bật scheduler. |

Đợt tiếp theo nên bắt đầu bằng contract URL lỗi và kỳ forecast vì đã có phát hiện
cụ thể; sau đó metadata/replay và quyền admin. Đây là kế hoạch, **chưa được triển khai
bởi lần cập nhật tài liệu này**. Không tự đánh dấu lỗi đã sửa chỉ vì 144 tests hiện pass.

## 7. Cấu hình, migration và tài liệu đọc tiếp

Dùng `application-local.properties.example` làm mẫu, điền private local/env.
Các nhóm chính: DB/Redis, Python adapter, `financial.llm.*`, token admin riêng,
`financial.documents.cloudinary.*` và scheduler allowlist. Không dùng Gemini key làm
token admin. LLM, seeder và scheduler là opt-in; đọc cấu hình profile trước khởi động.
File `application-five-symbols.properties` là profile giới hạn, không bật theo suy đoán.

Migration nằm trong `src/main/resources/db/manual/`, tên ngày/version tăng dần từ
V20260919 đến V20261004_06. Đó là script **thủ công**, không bằng chứng mọi database
đã được migrate. Kiểm tra precondition/constraint, backup và schema thật; migration
bỏ bảng legacy yêu cầu bảng rỗng. Không chạy SQL cleanup tùy tiện trên môi trường chung.

| Tài liệu | Dùng để |
| --- | --- |
| [LLM_FLOW_DEV_BA](LLM_FLOW_DEV_BA.md) | chuỗi lớp/phương thức, request/response và xử lý trường hợp |
| [LLM_NEWS_PIPELINE](LLM_NEWS_PIPELINE.md), [LLM_SHARED_VALIDATION](LLM_SHARED_VALIDATION.md) | hợp đồng NEWS, prompt, publish/revalidate và audit |
| [FINANCIAL_FORECAST_ADMIN_API](FINANCIAL_FORECAST_ADMIN_API.md) | target, công thức và DTO/API forecast |
| [NEWS_RECOVERY_FLOW_AND_TESTS](NEWS_RECOVERY_FLOW_AND_TESTS.md) | proposal, approve/reject, tài liệu và giới hạn kiểm thử live |
| [PRODUCTION_DATA_FLOW_REVIEW](PRODUCTION_DATA_FLOW_REVIEW.md) | phát hiện theo bằng chứng ngày 04/10, cần đối chiếu với code mới |
| [RELEASE_NON_LLM_20261004](RELEASE_NON_LLM_20261004.md) | các commit đã có trên master và phạm vi kiểm chứng |
| [GEMINI_LOCAL_SETUP](GEMINI_LOCAL_SETUP.md) | cấu hình và vận hành local |

Các tài liệu chi tiết giữ lịch sử để truy vết. Khi mâu thuẫn, dùng code hiện hành
để xác định implementation và bằng chứng đúng môi trường/ngày để xác định đã chạy
thành công hay chưa; ghi lại chênh lệch thay vì chọn kết luận thuận lợi hơn.

### 24/10/2026 — sửa CafeF, VNStock và metadata FPT

Đọc [FINANCIAL_DATA_REPAIR_20261024.md](FINANCIAL_DATA_REPAIR_20261024.md) để nối FE.
Python main đã lên Vercel 0.7.1, build 25fdde9. Đã sửa parser CafeF, cài đúng
VNStock/vnai và khóa dependencies, bổ sung nguồn tài liệu công bố FPT.
Java giữ metadata có bằng chứng, bỏ qua dòng VNStock toàn ô trống, lưu PDF/SHA
và lỗi upstream, thêm ba API admin để xem chất lượng, danh sách và refresh tài liệu.

Migration đã qua kiểm tra schema riêng, rollback và bốn constraint âm rồi mới áp
DB thật. Đã thêm 88 tài liệu; refresh lặp không thêm bản ghi trùng. Chín request
Spring -> Vercel -> provider -> DB đều HTTP 200, ACCEPTED và parse thành công.
HTTP Java thật: thiếu quyền trả 403; đọc và refresh có quyền trả 200.
32 Java tests và 36 Python tests PASS; Python được kiểm trên 3.12 với lockfile và
3.13. Unit tests dùng fixture/mock; các lần nghiệm thu nguồn và DB là dữ liệu thật.

Đã tải lại tám PDF, SHA không đổi; tám giá trị VNDirect vẫn khác tài liệu của chính
kỳ đó. Đọc PDF xác nhận số vendor của 2022Q2, 2024Q2, 2024Q4 và 2025Q2 khớp cột so sánh
ở báo cáo năm sau. Chi tiết đối chiếu tiếp theo xem báo cáo ở trên. Không gán ngày
bản cũ cho giá trị cập nhật sau, không chạy build canonical hoặc train/promote model.
FPT còn 156 báo cáo canonical thiếu ngày/scope/revision được xác minh; 88 tài liệu
mới không tự chứng nhận phiên bản số liệu vendor. Bằng chứng nằm trong
`artifacts/financial-provider-repair-20261024`.

### 24/10/2026 — liên kết phiên bản và nghiệm thu production cục bộ

Đợt mới thay thế kết luận snapshot FPT ở mục trên. Đọc
[FINANCIAL_PRODUCTION_REVIEW_20261024.md](FINANCIAL_PRODUCTION_REVIEW_20261024.md)
để xem API, migration, cấu hình và giới hạn còn lại.

Đã mở registry chính thức FPT/HPG/VNM/ACB; DB có 392 tài liệu duy nhất
(121/173/78/20), 37 liên kết giá trị và sáu bằng chứng đối chiếu bổ sung.
FPT có 5/8 giải thích khớp số, ba trường hợp còn dư 11/1/1 đồng. Không sửa số vendor
hoặc backdate bản cập nhật. ACB 2020–2023 chưa lấy được ngày công bố xác minh;
21 mã khác chưa có adapter nguồn công bố chính thức. 3.900 canonical statements
vẫn thiếu ngày/scope gắn đúng giá trị; chưa chứng nhận dataset point-in-time.

Đã sửa thiếu kỳ khi lưu forecast, phân biệt kỳ và nhóm target, chặn bản as-of cũ
thay kết quả mới, sửa biên cuối quý. Profile production bảo vệ mọi API trừ health,
kiểm token/CORS/schema; đồng bộ mapping CHAR theo DB. Thêm CI và Docker non-root.

Bằng chứng mới: 45 Python tests; 221 Java tests đạt gồm 44 tích hợp schema cô lập
(LLM mock). 18 request CafeF/VNStock/VNDirect cho ba mã đều ACCEPTED và parse được.
Production profile cục bộ validate schema đạt, HTTP 403/200 đúng quyền, bốn nguồn
Spring -> Vercel -> DB thành công. HPG trang 1–24 và VNM 2020–2026 đã gọi;
bốn request ACB lịch sử bị chặn, không ghi thành metadata đủ chuẩn. Migration
02/03/04 và import evidence đã rehearsal riêng trước khi áp DB thật.

Python main/Vercel 0.7.2 a5bb3c4. Java commit 0013434/d47606b/9be189a trên
fix/financial-metadata-flow, PR #14 vào feature/llm-processing. Chưa merge master,
chưa deploy Java production, chưa bật shared token Vercel, chưa test Docker/load/
backup restore, chưa có auth người dùng cho FE. Các phần local AI/KBS/FE dở ở
checkout gốc không được gom vào release này. Không train/promote model trong đợt này.

### 24/10/2026 lúc 12 giờ 30 — EIB HSG và kiểm tra nội dung nguồn

Đọc bản cập nhật FINANCIAL_PRODUCTION_REVIEW_20261024.md. Python main/Vercel
0.7.4, SHA 8259a5d. Java vẫn ở fix/financial-metadata-flow, PR #14 vào
feature/llm-processing. Các commit b1c9bd4, d472ead và e9fe76c bổ sung nguồn
EIB/HSG, kiểm tra CafeF/niên độ và sửa lưu PDF lớn. Chưa merge master.

DB hiện có 613 tài liệu duy nhất: FPT121, HPG173, VNM78, ACB20, EIB111, HSG110.
HSG đã gọi trang 1–20; các trang sau chưa nghiệm thu. HSG dùng niên độ tháng10–9;
không tự dịch chuyển quý vendor khi chưa biết cách đánh số của nguồn. EIB dùng
published_start; ngày đăng lại trên các trang lưu trữ cũ không chứng nhận vintage
gốc. Còn19/25 mã chưa có adapter công bố. Canonical vẫn3900 báo cáo thiếu metadata
đúng phiên bản; không promote canonical hoặc train model trong đợt này.

Phát hiện CafeF EIB/ACB trả số ngân hàng dưới tên chỉ tiêu doanh nghiệp thường.
Kết luận18 request trước đạt cấu trúc không đồng nghĩa đúng ngữ nghĩa. Python chặn
mẫu sai; Spring kiểm công thức trên raw cũ trước build, có replay dữ liệu thật.
Lượt mới18 request:13 parse ngay,4 bị chặn đúng,1 lỗi HSG tạm thời đã gọi lại đạt.
VNStock HSG ban đầu502 nhưng lần gọi lại qua pipeline thật thành công.

PDF EIB thiếu intermediate TLS, đã bổ sung CA công khai được kiểm chain; không
tắt verify. Tệp15.427.322 byte vượt giới hạn chuỗi JSON mặc định sau base64;
đã tách mapper tài liệu nội bộ và cấu hình Hibernate đọc35MiB, PDF vẫn giới hạn25MiB.
Spring -> Vercel -> nguồn -> DB -> đọc lại đạt với PDF EIB và HSG, SHA khớp.

53 Python tests và230 Java tests đạt;44 integration tests ở schema cô lập dùng
LLM mock. Migration05/06 có rehearsal rollback trước apply. Đã sao lưu và dọn
đúng3 metadata HSG trùng do URL encode dấu ngoặc; raw giữ nguyên. Sáu refresh
cuối0.7.4 đều200/inserted0; HTTP production403/200 đúng quyền. Bằng chứng tại
artifacts/financial-production-followup-20261024. FPT vẫn5/8 khớp bằng chứng,
ba số dư11/1/1 đồng chưa xác minh; không tuyên bố đã xử lý hết chênh lệch.

Chưa bật shared token trên Vercel, chưa deploy Java production, chưa kiểm tải/
backup restore hoặc auth người dùng FE. Không gọi code hiện tại là production-ready.


### 19/11/2026 — Thu thập dữ liệu Local AI qua pipeline thật

- `MarketPriceWorkflowPersistenceService` dùng JDBC batch 100 hàng chỉ khi toàn bộ khoảng daily chưa có dữ liệu. Giữ khóa security, transaction chung, factory metadata/provenance, quy tắc sửa giá/chọn canonical cũ cho khoảng đã có dữ liệu.
- Đã chạy 12 test: `MarketBackfillIsolatedJpaIntegrationTests`, `MarketPriceDailyBackfillTests`, `MarketPricePayloadParserTests`, `MarketPriceWorkflowServiceTests`: 12 pass/0 fail. Integration dùng PostgreSQL schema tạm riêng và provider mock, có lỗi CHECK hàng cuối để chứng minh rollback toàn bộ rồi retry. Không chèn mock vào public.
- Nghiệm thu thật qua Spring manual ingestion → app Python chính → VNDirect → raw validation → exact-version builder: DCM 2021–2026 build 1,69 giây; DGC 1,25 giây. Receipt nằm repo HE_THONG_DATA_TAI_CHINH_TEST/output/local-ai-review/data-20261119. BCTC quý gần nhất AAA/AAM/AAT income/balance đã build, nhưng metadata công bố/scope/vintage vẫn thiếu; không chứng nhận PIT.
- Vercel VNDirect có connection reset; app Python nguyên bản chạy local làm upstream của Spring hoạt động. Các raw OHLC sai vẫn REJECTED, không ép canonical. KBS VNINDEX/VN30 block dài bị Invalid index OHLC range.
- Chưa mở rộng đủ 100 mã. Theo yêu cầu tránh tạo danh mục dư, dừng tạo master mới; helper mặc định không cho tạo master, chỉ thử cửa sổ theo năm trên hai mã đã có master. Xem final coverage/resume report bên thư mục trên; không xóa raw audit hay master hiện hữu.
- Không commit/push/deploy. JAR target đã được dựng runnable lại; bản classifier ingestion dùng cho chạy thử. Các service tạm được runner quản lý và dừng sau mỗi lượt.


### 19/11/2026 — Native CafeF index contract

Java adapter now allows CafeF only for INDEX_OHLCV, routed to `/api/v1/cafef/indices/{symbol}/ohlcv`.
Other CafeF index operations remain rejected. `MarketIndexPayloadParserTests` and
`MarketIndexWorkflowPersistenceTests`: 10 tests passed. A separate classifier `index` JAR
was built to keep the active ingestion JAR unchanged during data acceptance.

Live ingestion uses a separate staging schema first, validates and builds exact versions,
compares source seams, then refetches through a public Spring instance and checks source
content fingerprints before build. Only empty historical/recent ranges are eligible;
known HOSE closures 23–24 January 2018 are excluded even if KBS provides an OHLC-valid bar.
No index rows are copied directly with SQL. Existing public rows are fingerprinted for an
append-only audit. Results/remaining gaps are recorded in the final index collection report
under `HE_THONG_DATA_TAI_CHINH_TEST/output/local-ai-review/index-staging-20261119`.


Index acceptance completed 19/11/2026 22:59:43 ICT: VNINDEX and VN30 each have 2,716
canonical sessions from 2016-01-04 through 2026-11-19. Published 2,910 new rows through 33
staging-matched refetch receipts; all 2,522 prior index rows stayed unchanged. Final checks:
zero duplicate dates, invalid OHLC, missing raw/version, non-ACTIVATED versions, or bars on
the two verified HOSE closure dates. Existing gaps 2021-12-10 and 2023-05-15 in VNINDEX were
filled by validated CafeF single-day requests. Preserve source distinctions: close seams
match, historical VN30 volume across CafeF/KBS does not. Services 8237/8239/8240/8242 stopped.
See `INDEX_COLLECTION_REPORT.md` and `public-index-final-quality.json` in the evidence folder.

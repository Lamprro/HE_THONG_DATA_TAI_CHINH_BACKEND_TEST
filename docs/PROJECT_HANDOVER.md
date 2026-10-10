# Bàn giao dự án và ngữ cảnh công việc đang tiếp tục

> Bổ sung 10/10/2026 trên `feature/stock-price-scenarios` (checkout riêng): thêm
> STOCK_PRICE vào forecast, sửa horizon thành cuối quý tương lai thứ n, chứng minh
> đơn vị/giá nền từ raw, thêm SMA20/SMA60/return20 và schema v2/prompt version 3.
> 146 regression + 16 integration schema cô lập pass; preview FPT thật đủ điều kiện.
> Live 10/10/2026 đã được người dùng cho phép và gọi qua API Spring Boot: Gemini
> trả 403 PERMISSION_DENIED (project denied access), run FAILED, không publish.
> Chưa nghiệm thu thành công response/projections/cache của price; xem
> [biên bản chạy và lý do WARNING](FORECAST_LIVE_CHECK_20261010.md).
> không merge master hoặc restart NEWS. Xem [STOCK_PRICE_SCENARIOS](STOCK_PRICE_SCENARIOS.md)
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

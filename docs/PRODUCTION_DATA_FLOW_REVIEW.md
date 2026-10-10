# Đánh giá luồng dữ liệu trước production

> Bàn giao cập nhật 07/10/2026: đọc [PROJECT_HANDOVER.md](PROJECT_HANDOVER.md) trước. Code LLM đã push ở `feature/llm-processing` (`5343f81`), chưa merge/deploy. Các số liệu DB, kết quả API và nhận định bên dưới thuộc thời điểm kiểm tra được ghi trong tài liệu; không phải xác nhận runtime ngày 07/10. Implementation và việc còn dở cần đối chiếu với bàn giao mới.

Ngày 04/10/2026, khoảng 16:51 giờ Việt Nam. Đối tượng: working tree Java Spring Boot tại nhánh `master`, HEAD `918a253`, gồm nhiều thay đổi local chưa commit và file chưa được Git theo dõi. Báo cáo đánh giá trạng thái đang thấy trong workspace và DB cấu hình local; không chứng nhận một commit release hay toàn bộ hạ tầng production.

**Kết luận: chưa nên mở nhánh này trực tiếp ra production.** Có lỗi bảo vệ API đã tái hiện, lỗi ngày mục tiêu dự báo đã tái hiện và khoảng trống xử lý URL lỗi từ Python đang chạy. Luồng có nhiều cơ chế tốt về audit, chống trùng và transaction, nhưng các phép kiểm tra đó chưa giải quyết đầy đủ quyền truy cập, độ tin cậy nghiệp vụ dự báo và vận hành nhiều worker.

## 1 Phạm vi và bằng chứng

- Đọc các controller, service, repository, scheduler, adapter Python, gateway Gemini, prompt/schema và migration liên quan. Đọc các nhánh dispatch toàn bộ operation đang được Java khai báo.
- Kết nối DB ở chế độ `default_transaction_read_only=on`, timeout truy vấn 20 giây. Bằng chứng máy đọc tại `target/report-review/evidence.json`; script tái kiểm tra tại `scripts/audit_llm_readonly.py`.
- Gọi GET localhost không credential; gọi forecast preview có credential tại ngày cuối quý; gọi Python health và url-fetch với một URL đã có trong DB.
- Không thực hiện POST tạo run, ingestion, revalidate, sửa DB hoặc gọi sinh nội dung Gemini trong đợt review này. Các response AI được đánh giá là dữ liệu thật đã lưu trước đó.
- Bộ kiểm thử trước đó đạt 123 tests cho NEWS/LLM/forecast. Đây không phải bằng chứng đã load test, chaos test, chạy nhiều instance hoặc kiểm chứng độ chính xác dự báo. Các test khác trong thư mục báo cáo có thời điểm cũ nên không cộng thành kết quả mới.
- Chỉ thêm chú thích flow vào NewsLlmService.execute và FinancialForecastService.execute; chưa sửa các phát hiện dưới đây.

## 2 Tiêu chí đánh giá

| Tiêu chí | Điều kiện chấp nhận |
|---|---|
| Quyền truy cập | Mọi API quản trị và log nhạy cảm cần xác thực, quyền ADMIN và audit người thao tác |
| Hợp đồng nguồn | Kiểm tra HTTP ngoài, HTTP bên trong envelope, schema, mã chứng khoán, đơn vị, kỳ |
| Tính đầy đủ | Phân biệt đã fetch raw, đã validate, đã dựng bảng cuối và dữ liệu đủ để phân tích |
| Chống trùng | Idempotency theo business key, unique constraint và khóa phù hợp nhiều worker |
| Thời gian | Publication, observation, fetched time, cutoff, revision và timezone có ý nghĩa rõ |
| Transaction | Lỗi không để bản ghi nửa chừng; retry không ghi đè dữ liệu mới bằng dữ liệu cũ |
| Validation | Luật tối thiểu bắt buộc; thất bại không được publish; có snapshot và vòng audit |
| Dự báo | Tách actual/derived/assumption; kiểm tra kỳ, đơn vị, quan hệ kế toán; có baseline/backtest trước quảng bá accuracy |
| Vận hành | Timeout, retry, claim, recovery, backlog, quota và giám sát có giới hạn |
| Release | Build từ checkout sạch, migration có thứ tự, cấu hình production và rollback được nghiệm thu |

P0: chặn mở truy cập production. P1: sửa trước khi bật chức năng liên quan trên production. P2: hạn chế vận hành/phạm vi cần kế hoạch xử lý. Các mục rủi ro suy từ code được ghi rõ, không coi là sự cố đã quan sát.

## 3 Bản đồ các luồng hiện tại

### 3.1 Lấy dữ liệu và validation dùng chung

`IngestionController.manual` gọi trực tiếp `IngestionService.ingest`. Đường job là `IngestionJobController.runNow` → [IngestionJobService.runNow dòng 113](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/ingestion/IngestionJobService.java:113) → `executeWithBudget` → [IngestionService.ingestJob dòng 101](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/ingestion/IngestionService.java:101) hoặc workflow nội bộ. Scheduler dùng [IngestionJobService.executeDueJobs dòng 142](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/ingestion/IngestionJobService.java:142). Adapter [PythonExternalFinancialDataAdapter.fetch dòng 68](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/integration/python/PythonExternalFinancialDataAdapter.java:68) gọi Python; [IngestionCompletionService.persistSuccess dòng 30](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/ingestion/IngestionCompletionService.java:30) lưu raw và kết thúc run trong transaction.

[ValidationJobService.validate dòng 67](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/validation/ValidationJobService.java:67) đọc luật DB và [ValidationRuleExecutionService.execute dòng 56](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/validation/ValidationRuleExecutionService.java:56) chạy executor theo domain/entity. finalizeIngestionRun khóa run, đợi mọi raw có validation, chặn cả run nếu bất kỳ ERROR/CRITICAL thất bại, rồi tạo một data_version. Cơ chế chặn cả ingestion run là chủ đích nghiệp vụ đã xác nhận, không đề xuất tự tách batch để lách lỗi.

Checksum raw có thể đánh dấu response trùng nhưng persistSuccess vẫn lưu raw. Chống trùng kết quả cuối phải được từng builder thực hiện. Không được dùng ingestion SUCCESS để kết luận mọi bảng nghiệp vụ đã đầy đủ.

### 3.2 Danh mục và provisioning

[MasterDataService.createCompany dòng 38](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/master/MasterDataService.java:38), createSecurity, updateSecurity và reconcileActiveSecurities quản lý company/security, tạo job theo cấu hình. [SecurityJobProvisioningService.provision dòng 44](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/master/SecurityJobProvisioningService.java:44) định nghĩa QUOTE/OHLCV/financial/ratio và các job theo mã. Các giá trị cron seed được đặt rõ UTC, trong khi ngày dữ liệu sử dụng Việt Nam.

Đánh giá: đã có mapping securityId/sourceSymbol và provisioning; cần bảo vệ quyền quản trị và chứng minh nhiều request cùng tạo mã/job chỉ tạo một bản. Việc có bảng users/accounts/roles chưa có nghĩa đã có kiểm soát truy cập HTTP.

### 3.3 Giá chứng khoán QUOTE và OHLCV

Sau raw validation, [MarketPriceWorkflowService.execute dòng 40](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/market/MarketPriceWorkflowService.java:40) chọn version MARKET_PRICE ACTIVE. [MarketPriceWorkflowPersistenceService.build dòng 56](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/market/MarketPriceWorkflowPersistenceService.java:56) gọi [MarketPricePayloadParser.parse dòng 24](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/market/MarketPricePayloadParser.java:24), khóa security, ghi hoặc sửa theo security/time/interval/source, bỏ correction cũ hơn raw hiện tại và chọn canonical theo official/priority/sourceId.

QUOTE được parser đặt interval `snapshot`, còn OHLCV có interval chuẩn hóa. Các bản intraday khác thời điểm có thể cùng một ngày; không đồng nghĩa duplicate. Dữ liệu daily phục vụ biểu đồ ngày; snapshot là ảnh chụp lúc gọi, không chứng minh đã có đầy đủ tick realtime hoặc nến phút tổng hợp. Nhánh storageTimestamp cho 15m có logic gộp riêng cần kiểm thử biên trước khi dùng.

Đánh giá: có kiểm tra OHLC, volume, symbol, count, scale và khóa master row. Cần nghiệm thu đơn vị theo provider, lịch giao dịch, corporate actions và chart semantics. Forecast hiện chỉ lấy 60 daily close; chưa dùng full OHLCV, volume hay chỉ báo kỹ thuật.

### 3.4 Chỉ số thị trường và thành phần rổ

INDEX_OHLCV/INDEX_LATEST → raw validation → [MarketIndexWorkflowPersistenceService.buildPrices dòng 64](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/market/MarketIndexWorkflowPersistenceService.java:64) → index_prices. INDEX_MEMBERS → [MarketIndexWorkflowPersistenceService.buildMemberships dòng 111](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/market/MarketIndexWorkflowPersistenceService.java:111) → security_index_memberships có effective_from/effective_to. Membership builder khóa index, yêu cầu security tồn tại, đóng bản cũ khi trọng số/đối tượng đổi và từ chối snapshot cũ.

Đánh giá: index_prices có 3736 bản; membership hiện rỗng. Java chỉ hỗ trợ provider vnstock cho indexPath; nếu nguồn này tạm dừng thì chưa có đường dự phòng index trong adapter. Chưa thể khẳng định đã đủ dữ liệu rổ hoặc khả năng cập nhật chỉ số đang hoạt động chỉ từ dữ liệu lịch sử. Các bảng này cũng chưa được forecast đọc.

### 3.5 Báo cáo tài chính

FINANCIAL_STATEMENT → raw validation → [FinancialStatementBuildService.execute dòng 63](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/financial/FinancialStatementBuildService.java:63) parse đầy đủ các kỳ/dòng → [FinancialStatementBuildPersistenceService.persist dòng 62](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/financial/FinancialStatementBuildPersistenceService.java:62) tạo period, statement revision, items, chọn canonical rồi ACTIVATED. Hỏng ghi thì rollback; [DataVersionLifecycleService.rejectBuildFailure dòng 22](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/validation/DataVersionLifecycleService.java:22) chỉ chuyển version còn ACTIVE thành REJECTED bằng transaction riêng.

Đánh giá: bảo toàn revision và raw provenance là điểm tốt. Cần xử lý unit_scale/currency theo metadata nguồn, scope, kỳ riêng/lũy kế, ngày công bố và thứ tự replay. Các nguồn official/priority chỉ quyết định ưu tiên, không kiểm tra phương trình kế toán hoặc tính hợp lý số liệu.

### 3.6 Ratio và các dataset bổ trợ

RATIO được adapter gọi, raw được phân loại FINANCIAL_METRIC. Dispatch hiện không có builder riêng biến mọi raw RATIO thành financial_metrics. Luồng mới `ForecastContextService.recalculate` tính năm tỷ lệ từ bảng cân đối và lưu có provenance; nó không phải builder chung cho response RATIO của provider.

COMPANY, MANAGEMENT, SUBSIDIARIES, EVENTS, HEALTH, PROVIDERS, NEWS_STATUS và các operation proxy/feed có API lấy raw. Việc rà service hiện tại không thấy workflow chuyên biệt cho từng loại chuyển raw sang mọi bảng nghiệp vụ tương ứng. Không suy ra kết quả tự động đầy đủ từ các record lịch sử hoặc từ việc enum có tên operation. NEWS_COMPANY cũng không mặc nhiên cùng parser với NEWS CafeF; cần contract test riêng trước bật lại nguồn.

Macro hiện có schema macro_series/macro_observations và đường đọc trong forecast, chưa có luồng thu thập macro tương ứng trong ExternalOperation. Không có bảng macro_data trong DB kiểm tra.

### 3.7 NEWS và NEWS LLM

NEWS list → URL riêng có metadata → NEWS_DATA → news_articles → quan hệ nguồn và TEXT_MATCH → NEWS_SUMMARY/DETAIL/FINANCIAL_FACTS. Khóa version và unique nghiệp vụ hỗ trợ chống trùng; hash nội dung được tính trên body sạch. URL_ONLY được giữ ở nhánh extraction_status FAILED, còn LLM chỉ nhận bài có body đủ điều kiện.

Đánh giá: đúng hướng về giữ URL, liên kết nhiều công ty, quote evidence và phân biệt crawler date. Còn contract gap SKIPPED/HTTP nguồn lỗi, tình huống chạy đồng thời và recovery; xem phát hiện cụ thể.

### 3.8 Financial LLM

FinancialForecastService → ForecastContextService và FinancialRatioCalculator → ForecastPromptService → ForecastRunStore.claim → GeminiLlmGateway → stage → validate → Java projections → llm_results. Input hiện không gồm NEWS, index_prices hay memberships. Bảy luật kiểm tra cấu trúc và nguồn; chưa có backtest hay kiểm tra cân đối chung giữa năm chỉ tiêu.

## 4 Snapshot dữ liệu được kiểm tra

| Nhóm | Quan sát |
|---|---|
| NEWS | 359 bài; 358 body từ 200 ký tự; một không body; 0 thiếu published_at; 0 nhóm url_hash trùng |
| Dấu hiệu body bẩn đã biết | 0 bài còn nguyên cụm menu từng được báo; chưa phải kiểm duyệt ngữ nghĩa toàn bộ |
| Financial statements | 328 tổng; 300 current/canonical; tất cả report_scope UNKNOWN; 0 published_at |
| Financial derived | 95 tỷ lệ FPT có calculation_key duy nhất và input snapshot từ lần triển khai trước |
| Index | 3736 giá chỉ số; 0 membership |
| Macro | 0 series và 0 observation |
| LLM | 40 runs, 57 attempts, 22 results lịch sử; 6 NEWS và 5 FINANCIAL current |
| Forecast current | FPT, ACB, HPG, BID, VCB SUCCESS/WARNING; mỗi vòng hiện hành 7 PASS |
| Version tồn | RAW ACTIVE 22; FINANCIAL_STATEMENT REJECTED 65; MARKET_PRICE REJECTED 4; NEWS REJECTED 2; NEWS_DATA REJECTED 23 |

REJECTED không mặc nhiên là dữ liệu rác cần xóa; đây có thể là bản audit đúng của dữ liệu trùng hoặc sai. Không dọn mất provenance chỉ để số lượng lỗi về 0. Hai version FINANCIAL_METRIC ACTIVATED trong lịch sử không chứng minh hiện có automated builder cho raw RATIO mới.

## 5 Phát hiện cần xử lý

### P0 01 API quản trị ngoài forecast chưa được bảo vệ

**Đối chiếu code ngày 07/10/2026:** `ForecastAdminAccessConfiguration` hiện đã bảo vệ
`/api/admin/forecasts/**`, `/api/admin/llm/**` và `/api/admin/news-recovery/**` bằng
token admin riêng. Vì vậy quan sát ẩn danh 200 cho admin LLM bên dưới là bằng chứng
trước thay đổi, không phải kết luận về code `5343f81`. Chưa có bằng chứng RBAC tập trung
cho toàn bộ endpoint ingestion/master/validation và các API quản trị khác; phát hiện
chỉ được xử lý một phần, không đánh dấu toàn hệ thống đã đạt quyền truy cập production.

**Bằng chứng:** [ForecastAdminAccessConfiguration.addInterceptors dòng 28](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/config/ForecastAdminAccessConfiguration.java:28) chỉ áp dụng `/api/admin/forecasts/**`. Không tìm thấy SecurityFilterChain hoặc PreAuthorize bảo vệ các controller còn lại. GET không Authorization trả 200 cho `/api/admin/llm/templates`, `/api/admin/llm/gemini/configuration`, `/api/ingestion-jobs`; forecast/configuration trả 403.

**Tác động:** nếu publish trực tiếp cùng cấu hình, người ngoài có thể đọc metadata quản trị; các controller POST/PATCH lấy dữ liệu, chạy AI và sửa job cũng không có cơ chế quyền tương ứng trong code. Không gọi thử mutation trái quyền để tránh phát sinh dữ liệu; nhận định về mutation dựa trên mapping và cấu hình bảo vệ.

**Cần sửa:** xác thực tập trung và ADMIN cho toàn bộ namespace quản trị, ingestion/master/source/validation/proxy liên quan; bảo vệ log/request/response. Khóa bearer hiện tại chỉ phù hợp kiểm thử local, chưa có danh tính người dùng/audit phiên. Thu hẹp CORS wildcard `https://*.vercel.app` theo frontend được quản lý.

**Nghiệm thu:** anonymous và user không quyền không đọc/ghi được từng endpoint; admin đúng quyền thực hiện được; preflight vẫn chạy; không đưa khóa admin cố định vào bundle FE.

### P1 02 URL lỗi thực tế có trạng thái chưa được giữ như yêu cầu

**Bằng chứng tái hiện:** Python `/api/v1/url-fetch` gọi URL có sẵn trả HTTP ngoài 200, `http_status=404`, `extraction_status=SKIPPED`, `content_type=application/octet-stream`, body bài rỗng. [NewsWorkflowService.articleDraft dòng 305](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/news/NewsWorkflowService.java:305) chỉ tạo URL_ONLY khi FAILED; SKIPPED đi vào exception. Các executor HTTP_SUCCESS/content type/raw text cũng có khả năng chặn trước builder theo severity DB. Migration seed đặt các luật này ERROR.

**Tác động:** URL có thể còn trong raw/list, nhưng không được bảo đảm hiện thành news_articles URL_ONLY. Đây là khác biệt với yêu cầu “không tải được nội dung vẫn hiển thị link”. Không phải tất cả lỗi fetch đều đã được handle.

**Cần sửa:** định nghĩa một contract thống nhất SUCCESS/URL_ONLY/RETRYABLE_FAILURE hoặc mapping rõ FAILED/SKIPPED; có luật riêng cho URL hợp lệ nhưng thiếu body; vẫn chặn URL nguy hiểm hoặc metadata sai. HTTP vận chuyển 502 cần retry có giới hạn, không suy thành bài có nội dung.

**Nghiệm thu:** contract test với response thật gồm bài 200, nguồn 404, anti-bot, nontext, timeout và thiếu ngày; URL_ONLY được lưu duy nhất, không gửi LLM, quan hệ nguồn vẫn đúng. Health 0.4.0 không tự chứng minh sai phiên bản nghiệp vụ; phải nghiệm thu response.

### P1 03 Sai kỳ mục tiêu khi asOfDate là ngày cuối quý

**Bằng chứng tái hiện qua API preview thật:** asOfDate `2026-09-30` cho horizon 1 → `2026-12-31`, horizon 2 → cũng `2026-12-31`, horizon 3 → `2027-03-31`. [ForecastContextService.build dòng 49](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastContextService.java:49) cộng horizon-1 từ quý chứa asOfDate và chỉ dịch thêm khi target không lớn hơn asOfDate.

**Tác động:** hai kỳ yêu cầu khác nhau trở thành cùng kỳ; forecast và dashboard hiểu sai horizon.

**Cần sửa:** chốt định nghĩa “quý tương lai thứ n” từ kỳ kết thúc đầu tiên strictly after asOfDate rồi cộng n-1 quý; ghi rõ khác biệt ngày giữa quý và ngày cuối quý.

**Nghiệm thu:** ngày 30/9, 31/12, năm nhuận, giữa quý; horizon 1 đến 8 tăng đơn điệu đúng ba tháng và luôn sau asOfDate.

### P1 04 Financial vẫn thiếu ngữ nghĩa kỳ và đơn vị đáng tin cậy

**Bằng chứng:** cả 328 báo cáo scope UNKNOWN, published_at null. [FinancialStatementEntity.create dòng 88](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/entity/FinancialStatementEntity.java:88) gán currency VND và unitScale 1. [FinancialStatementBuildService.item dòng 177](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/financial/FinancialStatementBuildService.java:177) lấy value và item code nhưng chưa truyền một hợp đồng đầy đủ về unit/cumulative/scope. Forecast có cảnh báo các hạn chế này nhưng vẫn cho chạy PARTIAL.

**Tác động:** metadata mặc định có thể làm số liệu provider đổi đơn vị vẫn mang nhãn VND; lợi nhuận riêng quý và lũy kế chưa được chứng minh so sánh tương đương. Chưa kết luận 328 số tiền hiện sai; vấn đề là code chưa bảo đảm cho dữ liệu mới.

**Cần sửa:** metadata đơn vị, scope và period basis phải được map/xác minh, lưu raw provenance; nguồn không xác định phải cách ly hoặc chặn target chịu ảnh hưởng. Không dùng created_at thay publication để quảng bá backtest point-in-time.

**Nghiệm thu:** fixture từ provider thật cho đồng/nghìn/triệu, riêng quý/lũy kế, hợp nhất/riêng lẻ và restatement; so sánh cùng cơ sở trước khi tính tăng trưởng.

### P1 05 Kịch bản chưa nhất quán về kế toán và ý nghĩa tốt xấu

**Bằng chứng DB:** kết quả HPG hiện hành có tài sản trừ nợ trừ vốn ở bear khoảng -41,02 tỷ đồng và bull khoảng +41,02 tỷ đồng. Các công ty khác cũng có kịch bản lệch đáng kể. [ForecastValidationService.evaluate dòng 67](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/forecast/ForecastValidationService.java:67) không kiểm tra phương trình tài sản = nợ + vốn; projections tính từng target độc lập. Code áp growth bear ≤ base ≤ bull cho cả LIABILITIES.

**Tác động:** đủ bảy PASS vẫn có thể cho bộ số không cân đối. “Bull” cho nợ cao hơn không mặc nhiên tốt hơn. Ngân hàng và doanh nghiệp sản xuất cần cách diễn giải khác nhau.

**Cần sửa:** nếu chỉ cung cấp sensitivity từng chỉ tiêu, đổi nhãn low/base/high và nói rõ độc lập. Nếu sản phẩm gọi là báo cáo tài chính dự phóng, cần model ràng buộc các đại lượng và tolerance theo đơn vị; Java tính các biến phụ thuộc. Chọn một ngữ nghĩa trước phát hành.

**Nghiệm thu:** kiểm tra cross-metric theo cùng scenario, nhất quán ngành và nhãn FE; không dùng SUCCESS làm chứng nhận accuracy. Backtest và baseline là bước riêng trước công bố độ chính xác.

### P1 06 Replay báo cáo cũ có thể thay thế bản mới

**Bằng chứng code:** [FinancialStatementBuildPersistenceService.persist dòng 62](D:/HeThong_PhanTichTaiChinh/HE_THONG_DATA_TAI_CHINH_TEST/src/main/java/com/hethongdata/taichinh/service/financial/FinancialStatementBuildPersistenceService.java:62) tìm current cùng nguồn/kỳ/scope, tăng revision rồi supersede; không so fetched_at hoặc publication revision để loại raw cũ. MarketPriceWorkflowPersistenceService.build đã có kiểm tra stale tương ứng.

**Tác động suy từ code:** replay khác raw ID nhưng dữ liệu cũ có thể trở thành current/canonical. Chưa thực hiện replay trên DB thật để tạo lỗi.

**Cần sửa:** policy xác định thứ tự revision/source publication và xử lý raw đến muộn; khóa business key trước tạo revision, không chỉ khóa từng data_version.

**Nghiệm thu:** mới → cũ → mới, cùng source/kỳ; hai worker khác version; dữ liệu cũ không ghi đè current, lịch sử vẫn truy được.

### P1 07 Claim job và retry NEWS chưa đủ cho nhiều worker

**Bằng chứng code:** executeDueJobs đọc last run rồi quyết định due, không claim job atomically. NEWS_DATA_FETCH gọi mạng trước khi persist khóa version. Một response RETRY_PENDING hoặc COMPLETED_WITH_REJECTIONS vẫn đi qua resetAfterSuccess của executeWithBudget; Redis budget chỉ giảm khi ném IngestionExecutionException.

**Tác động suy từ code:** nhiều instance/manual + scheduler có thể fetch cùng batch, tăng chi phí hoặc tạo race; lỗi URL kéo dài có thể lặp mà không dùng budget như lỗi ingestion thông thường. Unique/khóa builder hạn chế duplicate cuối nhưng không ngăn được mọi request trùng và run lỗi.

**Cần sửa:** claim/lease bền theo job/version trước mạng, budget và backoff theo URL lỗi, phân biệt workflow success/partial/retry. Bổ sung distributed scheduling nếu chạy nhiều replica.

**Nghiệm thu:** hai worker tranh cùng job chỉ một fetch hữu ích; retry partial có giới hạn và tiếp tục URL chưa xong; không làm mất link công ty mới.

### P1 08 Release chưa có một đơn vị triển khai tái lập được

**Bằng chứng:** working tree nhiều sửa đổi và untracked service/resource; migration nằm trong db/manual; ddl-auto none, không thấy Flyway/Liquibase được khai báo trong pom. profiles.active mặc định local, import application-local.properties; các scheduler và seed tắt mặc định.

**Tác động:** push thiếu file hoặc deploy thiếu migration có thể làm ứng dụng khởi động được nhưng lỗi query/publish; cấu hình Redis localhost:6380 và local profile không tự phù hợp server.

**Cần sửa:** xác lập commit release đầy đủ, manifest migration có thứ tự/checksum, backup và diễn tập khôi phục, secret bằng cấu hình server, profile production, health/readiness kiểm tra phụ thuộc cần thiết. Không bật mọi scheduler chỉ để chứng minh “đang chạy”.

**Nghiệm thu:** checkout sạch trên DB staging → migration → startup → contract test thật → một job giới hạn; rollback schema tương thích hoặc kế hoạch restore có kiểm thử.

### P2 09 RATIO và các dataset bổ trợ chưa có pipeline hoàn chỉnh

**Bằng chứng:** RATIO có fetch và domain FINANCIAL_METRIC nhưng dispatch không có builder RATIO tương ứng; macro thiếu ingestion operation; COMPANY/MANAGEMENT/SUBSIDIARIES/EVENTS có raw nhưng chưa xác nhận builder từng bảng. DB có 22 version RAW ACTIVE.

**Cần cập nhật:** lập catalog trạng thái mỗi dataset: raw-only, validated, built, exposed. Bổ sung builder/luật/schema theo nhu cầu; không tạo đủ bảng hình thức rồi coi đã hoàn thành dữ liệu.

**Nghiệm thu:** một raw mới của mỗi dataset được truy đến bảng cuối và API đọc, hoặc được đánh dấu rõ raw-only. Luật cho FINANCIAL_METRIC phải tồn tại và đủ trước khi nhận raw RATIO mới.

### P2 10 Chỉ số thị trường và macro chưa tham gia dự báo

**Bằng chứng:** ForecastContextService đọc market_prices, không join index_prices/membership; macro rỗng. Membership builder từ chối security chưa có trong master, nên phạm vi chỉ năm mã không đủ để dựng toàn bộ rổ VN30 nếu rổ chứa mã khác.

**Cần cập nhật:** tách yêu cầu stock/market index, định nghĩa feature Java như return/volatility/relative performance khi đủ đơn vị và adjusted prices. Nạp macro có nguồn/release/vintage, coverage theo từng series; không lấy 120 dòng mới nhất toàn bộ làm bảo đảm đủ mọi nhóm macro.

**Nghiệm thu:** input preview thể hiện ID và kỳ index/macro thực sự dùng; không có source thì không giả số hoặc tuyên bố đã phân tích thị trường toàn diện.

### P2 11 Revalidate NEWS chưa so checksum template gốc đầy đủ

**Bằng chứng:** LlmRunStore.claim lưu template_checksum trong metadata. revalidate đọc template hiện tại từ DB và kiểm tra checksum với chính template vừa đọc, chưa so metadata.template_checksum. ForecastRunStore.validate đã có đối chiếu checksum gốc hoặc legacy input hash.

**Tác động có điều kiện:** nếu sửa trực tiếp DB cùng template ID, revalidate NEWS có thể xác nhận một response theo template khác mà không báo đổi prompt. Seed thông thường có quy tắc immutable nên giảm khả năng gặp.

**Cần cập nhật:** dùng checksum snapshot gốc và bảo vệ immutable DB, có migration/version mới khi thay prompt. Nghiệm thu sửa checksum trong schema test thì revalidate phải từ chối; không thử làm trên public DB.

### P2 12 Recovery NEWS và batch validation có thể bị một bản lỗi chặn

**Bằng chứng:** NewsLlmService.validatePending dùng stream.map(store::revalidate) không bắt exception theo run; NewsLlmScheduler.tick gọi nó trước vòng article. ValidationJobService.validatePending có transaction bao cả batch và gọi validate nội bộ. Forecast pending đã catch theo run.

**Tác động suy từ code:** một lỗi kỹ thuật có thể kết thúc sweep hoặc rollback nhiều kết quả validation cùng batch, dù các raw/run khác xử lý được.

**Cần cập nhật:** cô lập từng đơn vị kiểm tra bằng transaction riêng, ghi recovery error có run ID, tiếp tục các run độc lập; vẫn giữ policy cả ingestion run bị chặn khi có lỗi nghiệp vụ.

### P2 13 Log lỗi nguồn và kích thước response

**Bằng chứng:** PythonExternalFinancialDataAdapter.fetch ném ExternalFetchException ngay khi non-2xx. Vì vậy nhánh markFailedResponse của IngestionService cho non-2xx không nhận được envelope qua adapter này; chỉ lưu category/status/message. readBody dùng readAllBytes, không có trần byte ở đây. Gateway Gemini dùng BodyHandlers.ofString.

**Cần cập nhật:** giữ diagnostic body có giới hạn/redaction khi lỗi, giới hạn bytes đầu vào, theo dõi response quá lớn. Không log token, credential hoặc URL có secret. Nghiệm thu upstream 429/500/malformed/oversize vẫn có audit hữu ích và memory bị chặn.

### P2 14 Hạn mức AI và tác vụ đồng bộ

**Bằng chứng:** tối đa 180 giây mỗi task, executeAll chạy tuần tự ba task; thread scheduler pool 2; cooldown Gemini nằm trong memory. Chưa thấy giới hạn concurrency/token budget toàn cluster hoặc queue cho AI.

**Cần cập nhật:** async job + API theo dõi nếu dashboard/proxy có timeout thấp, cap request đồng thời, quota theo tài khoản/provider, giám sát pending age và cost. Phần này càng quan trọng khi sửa P0 và bắt đầu cho nhiều admin dùng.

### P2 15 Current forecast không phân biệt horizon và bộ target

**Bằng chứng:** publish retire theo security/task/as_of_date. Một request thành công cho horizon khác cùng ngày thay current cũ; result lịch sử còn nhưng API current không trả đồng thời.

**Cần cập nhật:** BA chốt một bản phân tích tổng hợp mỗi ngày hay nhiều forecast theo kỳ/target; nếu cần nhiều, mở rộng business key, index và DTO đọc. Nghiệm thu hai horizon cùng cutoff không vô tình che nhau.

### P2 16 Timezone và chất lượng ngữ nghĩa cần thể hiện trên giao diện

Cron hiện chạy UTC và seed đã ghi rõ UTC, không kết luận giờ seed hiện sai. Tuy nhiên admin nhập cron tưởng giờ Việt Nam sẽ lệch bảy giờ. API/FE cần hiển thị timezone hoặc lưu zone riêng.

Quote khớp nguyên văn trong NEWS chưa kiểm tra được entailment của kết luận. Evidence ID có thật trong forecast chưa chứng minh giả định kinh tế được nguồn hỗ trợ; response HPG còn có risks về nguyên liệu/cạnh tranh trong khi context không đưa tin ngành định tính. Cần tách fact đã có nguồn với assumption của model, bổ sung review và benchmark ngữ nghĩa; confidence 0 đến 1 không hiển thị thành xác suất chắc chắn.

## 6 Những cơ chế cần giữ khi sửa

- Giữ URL_ONLY và quan hệ nguồn job theo yêu cầu nghiệp vụ; chỉ chặn chúng ở cửa vào LLM nếu thiếu body.
- Giữ chống trùng bài cuối theo URL/content và chỉ ACTIVATED khi có thay đổi hữu ích; không gán REJECTED mọi URL từng thấy.
- Giữ tính toàn vẹn theo ingestion_run và dữ liệu raw để truy vết, không bỏ validation để fill bảng nhanh.
- Giữ short transaction quanh claim/stage/publish, không giữ khóa DB trong khi gọi Gemini.
- Giữ response trước validation, attempt audit, rule snapshot, source hash và template version.
- Giữ source actual, derived ratios và AI scenario phân biệt rõ; Java tính mọi công thức xác định.

## 7 Thứ tự xử lý và tiêu chuẩn mở production

1. Khóa bề mặt quản trị theo P0 01; nghiệm thu cả GET/POST/PATCH và log.
2. Sửa hợp đồng URL lỗi và kiểm thử end to end Python thật → Java → URL_ONLY → không gửi LLM.
3. Sửa kỳ forecast; chốt ngữ nghĩa kịch bản, đơn vị và kỳ tài chính. Nếu chưa đủ, giữ forecast ở phạm vi thử nghiệm admin có cảnh báo rõ và chưa mở cho khách hàng.
4. Sửa replay stale, claim/retry nhiều worker; thử race trong DB staging, kiểm tra invariants không chỉ status.
5. Chốt commit/migration/config production; thử từ checkout sạch và backup phục hồi.
6. Chạy một đợt smoke giới hạn từng dataset với nhà cung cấp thực tế; đối chiếu raw → validation → version → bảng cuối → API đọc → LLM nếu có.
7. Bật từng scheduler với allowlist; theo dõi freshness, lag, reject rate theo lý do, provider errors, pending age, duplicate conflicts, token/cost. Tăng phạm vi khi các chỉ số ổn định.

Điều kiện đạt phải đo được: không truy cập admin trái quyền; không mất URL lỗi hợp lệ; period đúng; đơn vị và cơ sở kỳ có nguồn; current/canonical duy nhất; không stale overwrite; LLM không publish khi nguồn/luật/prompt sai; lỗi một run không làm kẹt toàn sweep; backup và migration được chạy thử. Các phát hiện P1 chưa được giải quyết cần có quyết định tắt chức năng liên quan thay vì ghi chung “pipeline sẵn sàng”.

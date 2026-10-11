# P06, P08 và L03 — nghiệm thu 12/10/2026

Java tiếp tục trên `feature/forecast-periods-and-health`. Python đã push `main`
commit `b33dff18327eb1ac20f42652308c42156a23baf0` (`feat: expose source-backed
financial statement metadata`). Không triển khai L01/L02/L04/L06, không sửa
writer nguồn P03, không thêm khóa chống chạy trùng P05 hoặc lịch chạy dự báo quý.

## P06 — metadata báo cáo tài chính ở cả hai dự án

Python bổ sung `financial_metadata` cho balance sheet, income statement và cash
flow ở VNDirect, CafeF, VnStock. Contract `financial.metadata.v1` gồm provider,
report_scope, period_basis, currency, unit_scale, published_at, evidence và
limitations; VNDirect có source_url thật của request nguồn. Metadata khác nhau
giữa các ngày fiscalDate nằm trong `periods[YYYY-MM-DD]`.

Chỉ các trường/nhãn nguồn rõ nghĩa được chuẩn hóa. Nhãn `triệu đồng` chứng minh
VND và hệ số 1.000.000; không đổi số numericValue Python trả về. Hợp nhất/riêng
lẻ cần trường hoặc tiêu đề báo cáo tương ứng. Không dùng formType=ALL để suy hợp
nhất. CafeF không dùng link điều hướng trong trang để suy scope. Balance sheet
là POINT_IN_TIME theo ý nghĩa của dataset; dòng báo cáo năm là ANNUAL. Quý
không đủ bằng chứng riêng quý/lũy kế vẫn UNKNOWN. Metadata xung đột không chọn
tùy ý một giá trị. published_at chỉ lấy trường công bố rõ nghĩa, giữ múi giờ;
fiscalDate, createdDate và modifiedDate không phải ngày công bố.

Java giữ nguyên pipeline:

`Python adapter → raw_payloads → ValidationJobService → data_versions ACTIVE
→ FINANCIAL_STATEMENT_BUILD → financial_statements/financial_statement_items`.

Luật FINANCIAL_METADATA_VALID kiểm tra schema/provider, loại giá trị, hệ số
dương, ngày công bố có múi giờ và bằng chứng normalized tương ứng. Không có
contract được audit LEGACY_UNVERIFIED; metadata UNKNOWN/null vẫn được lưu,
không biến thành lỗi chỉ vì nguồn không cung cấp. Sai metadata explicit bị
rejected ở raw validation. Build không retry lỗi parse/mapping nội bộ.

financial_statements lưu period_basis và metadata_provenance mới, cùng
report_scope/currency/unit_scale/published_at hiện có. Item giữ value/raw_value
nguồn; đơn vị tiền thiếu bằng chứng được để null. Công thức Java đọc số
`value × unit_scale`, không nhân lại ở writer. Forecast và calculator lịch sử
chỉ đọc các monetary point có VND/scale hợp lệ; input forecast thêm periodBasis
và metadataProvenance. Không tự đổi YTD thành quý riêng hoặc TTM. Writer vẫn
tạo revision và inactive bản cũ theo bucket nguồn hiện có.

**Giới hạn nguồn đã kiểm tra:** FPT Q2/2026 từ VNDirect production trả metadata
UNKNOWN cho scope, null cho currency/unit_scale/published_at, có limitations
UNVERIFIED tương ứng. Nguồn hiện trả ngày tạo/sửa nhưng không chứng minh các
trường đó. P06 hoàn thiện contract, provenance và cách xử lý thiếu; không có
bằng chứng để tuyên bố đã điền đầy đủ metadata tất cả báo cáo lịch sử. Bản mới
thiếu đơn vị vẫn lưu làm dữ liệu nguồn, nhưng không được gửi như giá trị tiền
đã xác minh. Các giá trị legacy được migration giữ nguyên, không chứng nhận
lại đơn vị/scope lịch sử.

## P08 — URL_ONLY qua validation rồi lưu được bài link

Chỗ chặn trước sửa là NEWS_DATA: HTTP success, content type HTML, raw HTML body
và HTML structure chạy trước builder. Builder cũ đã có nhánh FAILED lưu URL,
nhưng raw bị rejected thì nhánh đó không nhận được version ACTIVE. SKIPPED
cũng chưa được builder nhận như URL_ONLY. Lỗi transport/API fetch chưa lưu raw
link, chỉ giữ NEWS version để retry. Các điểm này khác với việc task LLM bình
thường không nhận một bài chưa có nội dung.

NewsContentClassification nhận URL_ONLY khi có HTTP(S) URL hợp lệ và status
FAILED/SKIPPED, hoặc status chưa có và không có content_text. Status SUCCESS
luôn đi qua validation nội dung đầy đủ. Status lạ, URL lỗi, userinfo trong URL,
HTTP metadata ngoài 100–599 hoặc textual sai kiểu vẫn bị chặn.

Với URL_ONLY, các luật HTTP_SUCCESS, CONTENT_TYPE_VALID, RAW_TEXT_REQUIRED,
HTML_STRUCTURE, ARTICLE_EXTRACTED, BLOCK_PAGE_DETECTED và RAW_ERROR_MESSAGE
được audit SKIP. Luật URL/metadata/dedup vẫn chạy. HTTP 403, file PDF hoặc body
không có không làm mất link khi nó được phân loại URL_ONLY; không coi PDF hay
trang lỗi là body bài báo đã trích xuất.

Lỗi endpoint/transport tạo raw NEWS_DATA với SKIPPED, CONTENT_FETCH_UNAVAILABLE,
category lỗi, URL/title/ngày nguồn danh sách nếu có. fetch_endpoint_status được
tách khỏi HTTP status của publisher; không bịa HTTP publisher bằng 0. Envelope
và kiểu http_status/textual được giữ để validation kiểm tra. Nếu body là JSON,
JSON nguồn nằm trong source_json thay vì ghi đè envelope URL/status.

Builder lưu news_articles metadata.content_status=URL_ONLY, body/hash nội dung
null. Giữ quan hệ SOURCE_JOB với công ty/mã chứng khoán; không cần body nhắc mã.
NEWS version lỗi fetch vẫn ACTIVE để job thực hiện retry như trước. Bài URL_ONLY
không làm điều kiện hasSourceRelationship chặn lần fetch sau. Khi lấy lại được
body hợp lệ, writer cập nhật bài cùng URL và các quan hệ, không tạo bài thứ hai.
Lần gặp trùng hoàn toàn vẫn theo quy tắc REJECTED/no new version hiện có.

NEWS_SUMMARY và task NEWS phân tích thông thường vẫn báo BODY_MISSING_OR_TOO_SHORT
khi chỉ có link. Recovery là task riêng và cần admin duyệt như trước. Việc lưu
URL để giao diện mở link không phụ thuộc việc bài đủ điều kiện phân tích LLM.

## L03 — coverage macro trước khi gọi LLM

Validation ingestion macro hiện có bảo đảm contract/numeric/country/frequency.
Điều đó không chứng minh mọi series có dữ liệu mới trong cửa sổ context của một
lần forecast. Không đổi hoặc làm lại luồng raw → data_versions → macro tables.

ForecastMacroCoverageService đọc toàn bộ catalog active của Việt Nam (VNM),
tìm ngày quan sát cuối không sau as_of_date và được ingest trước cutoff. Input
thêm `macro_coverage` theo series: code, frequency, latest_date,
observation_count (trong cửa sổ 12 tháng), sent_observation_count, status,
missing, stale. Nó không thêm quan sát giả vào macro_observations.

Trạng thái: MISSING, STALE, OUTSIDE_WINDOW, TRUNCATED_CONTEXT,
UNSUPPORTED_FREQUENCY hoặc AVAILABLE. Thiếu catalog có MACRO_CATALOG_MISSING.
Cửa sổ points và limit 120 hiện có giữ nguyên; coverage cho biết rõ khác biệt
giữa dữ liệu trong DB, đủ mới và thực tế đã gửi. latest_date là ngày kỳ quan
sát, không phải ngày công bố/vintage. Series cũ vẫn được mô tả là cũ, không
được giả định có GDP/lãi suất quý mới hoặc nội suy thành quan sát quý.

Ngưỡng độ cũ cấu hình được bằng `financial.forecast.macro.max-age.*`:
yearly-months=24, quarterly-months=9, monthly-months=4, daily-days=15. Đây là
ngưỡng cảnh báo kỹ thuật, không phải lịch công bố chính thức. requireMacro giữ
chính sách cũ: không có observation nào thì chặn; thiếu một phần được nêu trong
limitations. Coverage và metadata nằm trong input hash để không dùng cache
của snapshot khác. FINANCIAL_SCENARIOS prompt version 6 hướng dẫn LLM phản ánh
series thiếu/cũ, không cite coverage như evidence_id, chỉ cite MACRO point thật.
Schema output và công thức/growth âm không đổi.

## Bằng chứng thực sự chạy

- Python: 16 unittest pass. Metadata/provider response test dùng fixture/mock;
  không ghi các fixture vào DB nghiệp vụ.
- Java regression: 250 test được khám phá, 245 pass và 5 recovery chưa bật;
  chạy bổ sung đúng biến RECOVERY_TEST_DB=1: 59 pass, 0 failures/errors/skipped,
  bao gồm 5 recovery, NEWS unit, URL-only pipeline và call Python thật.
  Tổng 250 test khác nhau đã được chạy thành công qua hai đợt; không cộng số
  test chạy lặp để tăng tổng. Có 44 forecast integration, 20 NEWS LLM integration,
  5 recovery integration; schema riêng được xóa sau khi chạy. LLM dùng mock,
  không gọi Gemini thật trong đợt này.
- P06 tích hợp thật: IngestionService gọi PythonExternalFinancialDataAdapter lấy FPT Q2/2026 trên
  Vercel, response HTTP 200 có contract mới; validate/build bằng các service
  hiện có, lưu raw/provenance URL request thật và UNKNOWN/null đúng nguồn trong schema riêng.
  Luồng mới cũng pass khi dùng toàn bộ catalog validation sao chép từ DB thật
  thay vì chỉ seed các luật test. Test
  đơn vị triệu dùng fixture rõ ràng: raw 12 giữ nguyên, context Java 12.000.000.
- P08: lỗi fetch mock → raw URL_ONLY accepted → news_articles/link FPT; normal
  NEWS LLM bị chặn thiếu body; fetch success mock tiếp theo nâng cấp đúng bài,
  số bài theo URL vẫn là 1. Unit kiểm HTTP 403/PDF, status/URL/type sai.
- L03: catalog/observations sao chép từ DB thật; VN_LENDING_RATE_YEAR có ngày
  cuối 2023-12-31 được cảnh báo STALE, không giả quan sát mới. Xóa quan sát chỉ
  trong schema test chuyển MISSING và đổi snapshot hash.
- Java package/verify thành công. Log riêng nằm dưới target, không commit
  private config, backup, response model giả hoặc credentials.

## Database và trạng thái deploy

Migration `V20261012_01__financial_metadata.sql` áp public lúc **00:13:01
12/10/2026 giờ Việt Nam**, có transaction/lock timeout, backup private và đối
chiếu idempotency. Giữ nguyên **3.929 financial_statements và 281.097 items**:
so sánh toàn bộ trường lịch sử của statement và fingerprint nội dung items
trước/sau; 50 luật validation hiện có không đổi, thêm FINANCIAL_METADATA_VALID.
Chỉ thêm cột, constraints/nullable metadata và luật mới; không chạy backfill
đoán thông tin, không re-fetch lịch sử DB public hoặc thêm fixture nghiệp vụ.

Python Vercel production **READY**, deployment `dpl_F3UnHduMAdmg7mr1kbpVGfqBb9NL`
đúng main b33dff1. `/api/v1/health` HTTP 200, catalog World Bank VNM HTTP 200
với 10 series; balance sheet FPT được adapter Java gọi thành công sau deploy.
Không có bằng chứng live CafeF thành công trong đợt này; request nguồn CafeF
đã timeout, chỉ contract/unit test được kiểm tra.

Java được push lên nhánh đang sửa, **chưa deploy runtime server**. Prompt
version 6 nằm trong code, **chưa đổi active prompt DB public** để tránh runtime
cũ nhận request schema mới. Sau deploy runtime mới, dùng API
`POST /api/admin/forecasts/template/seed` để kích hoạt version 6;
provenance/hash/attempts/validation rounds cũ giữ
nguyên. Các scenario vẫn chưa được backtest/calibrate về độ chính xác.

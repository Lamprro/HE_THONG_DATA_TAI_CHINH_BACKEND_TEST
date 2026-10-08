# Làm giàu dữ liệu tài chính 08–09/10/2026

Nhánh `fix/data-enrichment` được tách từ `010dd89` của `feature/llm-processing`.
Không sửa NEWS, LLM, prompt, migration attempts hoặc checkout dùng chung của AI khác.
Java gọi Python service thật qua `PythonExternalFinancialDataAdapter`; client vận hành
chỉ gọi API admin và truy vấn DB chỉ đọc, không tự fetch nguồn hay ghi bảng nghiệp vụ.

## Phạm vi và trạng thái nghiệm thu

Ngày chốt dữ liệu giao dịch: **08/10/2026, Asia/Ho_Chi_Minh**. Mục tiêu giá ngày
01/01/2016–08/10/2026, ba loại báo cáo quý Q1/2016–Q3/2026 và báo cáo năm 2016–2025.
Kỳ không có dữ liệu nguồn bị validation từ chối; không tạo báo cáo/số liệu thay thế.

Mười mã đã có: FPT, ACB, BID, HPG, VCB, VNM, VIC, MSN, TCB, MBB. Bổ sung **15 mã**:
CTG, STB, SSI, VND, HCM, SHB, EIB, REE, DPM, GAS, GMD, PVD, PVS, HSG, PNJ.
Master company/security được đăng ký từ hồ sơ COMPANY thật của VNDirect. Các job
mới được provision bởi hệ thống rồi vô hiệu hóa qua API để không phát sinh NEWS.

Trước đợt này DB có 10 securities, giá ngày khoảng 09/2021–09/2026 cho 10 mã;
chỉ 5 mã đầu có 20 quý × 3 báo cáo = 300 headers current/canonical.
Tổng bảng: 16.972 market_prices (gồm snapshot/history), 328 statement headers,
19.938 items, 107 metrics. NEWS 359, LLM runs 42, results 22.

Snapshot sau backfill, đối chiếu **09/10/2026 khoảng 01:00 giờ Việt Nam**:

| Bảng/độ phủ | Trước | Sau |
| --- | ---: | ---: |
| Companies/securities | 10 | 25 |
| Nến ngày canonical | 12.459 | 66.006 |
| Market_prices tổng, gồm snapshot/source history | 16.972 | 71.762 |
| Statement headers current/canonical | 300 | 3.900 |
| Statement headers tổng, giữ revisions cũ | 328 | 3.929 |
| Financial_statement_items tổng | 19.938 | 281.097 |
| Financial_metrics tổng | 107 | 6.767 |

Mỗi mã có **42 quý × 3 loại báo cáo**, Q1/2016–Q2/2026, và **10 năm × 3 loại**
báo cáo năm 2016–2025: 3.150 headers quý + 750 headers năm. Q3/2026 chưa có dữ
liệu nguồn cho cả 25 mã; 75 raw rỗng bị validation chặn đúng, không có header giả.
Năm lỗi vận chuyển trong backfill quý đã lấy lại thành công qua API; raw/run lỗi
cũ giữ audit. Version HCM lỗi item_code giữ REJECTED, bản lấy mới đã ACTIVATED.

24 mã có giá nguồn từ 04/01/2016 đến 08/10/2026; TCB từ 04/06/2018. GAS và VND
thiếu cả năm 2019 sau khi cả VNDirect và CafeF bị OHLC validation chặn. Có thêm
các ngày nguồn không trả dữ liệu; không forward-fill hoặc coi số ngày là bằng chứng
đã đủ mọi ngày giao dịch. VNStock thử qua adapter cho GAS 2019 trả upstream 503,
Java 502; danh sách INDEX_LIST tĩnh vẫn trả 200, không chứng minh OHLCV khả dụng.

Chỉ số mới: **150 RATIO snapshot** (6 × 25) và **6.510 tỷ lệ actual lịch sử**,
tất cả canonical/VALID; 107 metrics cũ giữ nguyên noncanonical (95 WARNING).
Chín ngân hàng có 3 tỷ lệ balance-sheet mỗi quý; 16 mã còn lại có đủ 8 tỷ lệ được
calculator hỗ trợ mỗi quý. Không tạo tỷ lệ khi báo cáo không có các đầu vào tương ứng.

Nghiệm thu chỉ đọc: duplicate canonical theo ngày Việt Nam/statement/metric = 0;
OHLC sai = 0; nến cuối tuần = 0; lineage/unit/audit mới thiếu = 0. Đối chiếu 261.159
item mới với raw_value, 150 RATIO với transformation theo numeric(38,10), 6.510
công thức và 13.020 source points với IDs/giá trị/period/raw/version: **0 sai lệch**.
Replay API FPT trả sourceStatements=84, inserted=0, unchanged=336.
Không còn ACTIVE version thuộc MARKET_PRICE/FINANCIAL_STATEMENT/FINANCIAL_METRIC;
22 ACTIVE RAW legacy và NEWS run treo từ 27/09 được giữ, ngoài phạm vi đợt này.

NEWS/LLM giữ 359 articles, 42 runs, 59 attempts, 22 results; không gọi các flow đó.
360 jobs mới inactive, FINANCIAL_METRIC_BUILD được bật tạm để run API rồi trả về
inactive. Không bật tự động cập nhật 15 mã mới; cần vận hành/allowlist sau khi review
code và cấu hình nguồn. Không có fixture trong public hoặc schema test còn sót lại.

## Luồng đã dùng

1. `POST /api/ingestions/manual` với operation OHLCV, FINANCIAL_STATEMENT hoặc RATIO.
2. `POST /api/admin/validation/raw-payloads/{id}`: giữ toàn bộ raw, checksum,
   ingestion run và kết quả từng luật. Lỗi blocking chặn cả batch nguồn.
3. `POST /api/ingestion-jobs/{id}/run` cho MARKET_PRICE_BUILD,
   FINANCIAL_STATEMENT_BUILD, FINANCIAL_METRIC_BUILD. Chỉ gọi một builder mỗi domain.
4. Kiểm tra data_version ACTIVATED và bảng cuối; SUCCESS ingestion chưa đủ nghiệm thu.
5. Tính tỷ lệ lịch sử qua API admin Java, từ báo cáo actual current/canonical có
   data_version FINANCIAL_STATEMENT/ACTIVATED và lineage khớp ingestion run.

Đã smoke luồng thật trên ngrok với FPT balance sheet Q1/2016: raw
`7b03c6bf-e2df-4fe3-86cd-bc99d26cd109`, version
`f16809f8-4687-486c-8ff0-0edb3acc87b8`, build run
`a821e072-ee38-49ce-b7db-0f2f90d8bbae` SUCCESS. Bulk chạy localhost gọi cùng Python
service thật. Instance do đợt này tạo tắt ingestion/validation/reconciliation/LLM/
recovery schedulers và catalog seeds; không triển khai code lên server.

## Sửa trong Java

- Chặn item_code vượt varchar(100): prefix và SHA-256 suffix ổn định, kể cả hậu tố
  phân biệt collision; vẫn giữ source item code/name/raw value. Lỗi thật gặp ở
  cash-flow HCM Q1/2016 đã rollback/reject version cũ; lấy raw mới qua API và build lại.
- Giá ngày batch lớn tải buckets một lần, cache nguồn/thời điểm raw và chỉ đổi
  canonical khi cần. Giữ lock security, natural key, source priority và stale replay.
- Thêm dispatcher/persistence cho FINANCIAL_METRIC_BUILD. Chỉ hỗ trợ hợp đồng
  VNDirect RATIO đã đọc thật: PE, PB, EPS_TR giữ đơn vị nguồn; ROAE_TR_AVG5Q,
  ROAA_TR_AVG5Q, DIVIDEND_YIELD đổi fraction ×100 sang phần trăm theo catalog.
  Giữ source row/value/checksum, transformation, raw/version, calculation key;
  idempotent, atomic batch và ưu tiên nguồn/raw mới cho canonical.
- `POST /api/admin/financial-data/metrics/recalculate` yêu cầu Bearer token riêng
  `financial.admin.api-token` tối thiểu 32 ký tự. Request:

```json
{"symbol":"FPT","startDate":"2016-01-01","endDate":"2026-10-08"}
```

  Không ghi token vào Git/log. Java tính 5 tỷ lệ balance-sheet bằng calculator sẵn
  có và NET_MARGIN/GROSS_MARGIN/OPERATING_MARGIN từ các item của **cùng một báo cáo**.
  Thiếu/nhập nhằng đầu vào hoặc denominator không dương thì bỏ qua, không biến thành 0.
  Lưu formula, source point IDs/values, scope, dates và availability trong input_snapshot.
  Không gọi forecast/LLM; không tạo lịch sử ROE/ROA/PE/EPS giả từ snapshot hiện tại.

## Giới hạn nguồn cần giữ khi đọc dữ liệu

- TCB có giá giao dịch từ IPO 04/06/2018, không thể có 10 năm trước niêm yết.
- VNDirect có một số bar thật open/close ngoài high/low. Giữ raw bị reject và dùng
  nguồn CafeF qua adapter. CafeF cần cửa sổ năm do thời gian tải cửa sổ 5 năm vượt
  timeout; từng cửa sổ CafeF vẫn validation đầy đủ, không bỏ bar lỗi.
- GAS và VND năm 2019 cũng bị CafeF từ chối; không chia nhỏ run đó để né validation.
  Đây là gap nguồn cần xử lý bằng nguồn hợp lệ khác hoặc sửa dữ liệu upstream.
- VNStock ở Python runtime đang unavailable vì thiếu optional package; không có
  nghiệm thu bổ sung index/rổ qua nguồn này. Macro chưa có pipeline ghi hoàn chỉnh;
  không tự chèn số liệu SQL để tạo coverage.
- VNDirect RATIO trả snapshot mới nhất, không phải lịch sử ratio 10 năm.
- Metadata report scope, currency/unit declaration, publication và riêng quý/lũy kế
  còn giới hạn nguồn/implementation cũ. Giữ UNKNOWN và hạn chế trong snapshot; không
  tuyên bố point-in-time/backtest hay audited economic completeness vì validation pass.
- 107 metrics cũ và các raw/history cũ được giữ lại. Không cleanup DB lần nữa.

## Kiểm thử code

153 unit/contract tests pass và 5 kiểm thử PostgreSQL schema cô lập pass, không skip.
Hai nhóm integration dùng **provider mock/fixtures chỉ trong schema test**, khác với
backfill live gọi provider thật. Không chạy mock vào public; không chạy lại LLM suites.

- DataEnrichmentIsolatedIntegrationTests (4): đơn vị RATIO/canonical/idempotency/
  stale replay, atomic rollback, 8 công thức từ actual + audit + rerun, chặn lineage
  chưa ACTIVATED. Dùng JdbcTemplate/transaction/constraints PostgreSQL thật.
- MarketBackfillIsolatedJpaIntegrationTests (1): ingestion → validation → build JPA
  thực, 100 daily bars × 4 batch, hai nguồn, replay và canonical; schema riêng,
  sequence riêng để không dùng public sequence; cleanup schema sau test.
- Unit bổ sung: mã item dài ổn định, envelope RATIO/symbol/date/value/unit,
  tỷ lệ với profit âm/missing/zero/ambiguous denominator, daily cache/replay,
  token admin missing/wrong không gọi service.

Chạy integration opt-in trong môi trường cho phép tạo schema test:

```powershell
$env:DATA_ENRICHMENT_TEST_DB='1'
mvn '-Dfinancial.test.config=ABSOLUTE_PRIVATE_CONFIG_PATH' '-Dtest=DataEnrichmentIsolatedIntegrationTests,MarketBackfillIsolatedJpaIntegrationTests' test
```

Private config không commit. Các lỗi LLM/forecast/recovery/financial stale replay đã
ghi trong bàn giao trước vẫn cần đúng loại nghiệm thu riêng; tests đợt này không
chứng minh đã giải quyết chúng. Chưa nghiệm thu nhiều worker cùng domain.

Bằng chứng backfill nằm ngoài Git trong thư mục artifact của chat: snapshots
coverage_before/after, quality_enrichment, enrichment_final, admin_api_audit,
backfill_ledger, metric_recalculation_audit, metric_live_replay và runtime_health_final.
Không commit private config, raw response samples, token, DB backup hoặc log runtime.
Source hiện tại chỉ push trên nhánh fix; chưa merge `master` hoặc deploy lên ngrok.

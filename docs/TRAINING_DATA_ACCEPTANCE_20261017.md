# Nghiệm thu dữ liệu nguồn cho Local AI — 17/10/2026

Thời điểm đo: 2026-10-17T05:15:23.309618+00:00 (UTC; ngày giao dịch dùng Asia/Ho_Chi_Minh).
DB đã có **25 mã trước lượt xử lý này**. Lượt này mở rộng lịch sử và nhóm dữ liệu;
không tuyên bố bổ sung thêm 25 mã hay đã có universe lịch sử không thiên lệch.

| Phạm vi | Trước | Sau nghiệm thu production |
|---|---:|---:|
| Giá ngày canonical | 66.006 | 66,156 |
| Giá canonical thiếu adjusted close | 4.157 | 0 |
| Chỉ số tài chính canonical, gồm chỉ số nguồn và công thức cũ | 6.660 | 159,545 |
| Khối ngoại canonical có nguồn riêng | 0 | 6,000 |
| Quan sát vĩ mô | 137 | 263, gồm 126 FX tháng bản địa |
| Công ty có snapshot nguồn quan sát được | 0 trong bảng mới | 25 |
| BCTC current/canonical | 3.900 | 3.900; không tạo thêm kỳ khi nguồn rỗng |

Giá mới cập nhật đến 16/10/2026. Việc hết thiếu adjusted close là kết quả đối chiếu
raw và chọn bản nguồn có bằng chứng, không phải điền adjusted close bằng close.
Vẫn chưa chứng nhận phương pháp điều chỉnh, total return hay lịch sử sự kiện doanh nghiệp.

## Coverage theo mã

| Mã | Giá đầu | Giá cuối | Phiên giá canonical | Quan sát chỉ số canonical | Phiên khối ngoại |
|---|---|---|---:|---:|---:|
| ACB | 2016-01-04 | 2026-10-16 | 2689 | 6231 | 240 |
| BID | 2016-01-04 | 2026-10-16 | 2692 | 6231 | 240 |
| CTG | 2016-01-04 | 2026-10-16 | 2692 | 6231 | 240 |
| DPM | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| EIB | 2016-01-04 | 2026-10-16 | 2686 | 6231 | 240 |
| FPT | 2016-01-04 | 2026-10-16 | 2692 | 7024 | 240 |
| GAS | 2016-01-04 | 2026-10-16 | 2429 | 6441 | 240 |
| GMD | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| HCM | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| HPG | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| HSG | 2016-01-04 | 2026-10-16 | 2690 | 6441 | 240 |
| MBB | 2016-01-04 | 2026-10-16 | 2692 | 6231 | 240 |
| MSN | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| PNJ | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| PVD | 2016-01-04 | 2026-10-16 | 2692 | 6382 | 240 |
| PVS | 2016-01-04 | 2026-10-16 | 2694 | 6441 | 240 |
| REE | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| SHB | 2016-01-04 | 2026-10-16 | 2691 | 6231 | 240 |
| SSI | 2016-01-04 | 2026-10-16 | 2692 | 6441 | 240 |
| STB | 2016-01-04 | 2026-10-16 | 2692 | 6231 | 240 |
| TCB | 2018-06-04 | 2026-10-16 | 2092 | 6229 | 240 |
| VCB | 2016-01-04 | 2026-10-16 | 2692 | 6231 | 240 |
| VIC | 2016-01-04 | 2026-10-16 | 2692 | 6439 | 240 |
| VND | 2016-01-04 | 2026-10-16 | 2424 | 6331 | 240 |
| VNM | 2016-01-04 | 2026-10-16 | 2689 | 6441 | 240 |

TCB chỉ có giá từ khi niêm yết năm 2018; không tạo giá trước niêm yết. Khoảng giá
năm 2019 của GAS/VND đã bị chặn từ đợt trước do nguồn không đạt validation, vẫn
chưa được lấp bằng giá giả hoặc bằng cách chia nhỏ batch lỗi. Khối ngoại nguồn
hiện có từ 16/10/2025 đến 16/10/2026; thiếu lịch sử xa hơn không được đổi thành 0.

## Kết quả kiểm tra thực tế

- Python: 23 unit/transport tests pass với mock upstream, không ghi DB nghiệp vụ.
- Java: 176 unit/contract tests pass; 7 PostgreSQL integration tests pass trong
  schema cô lập. Ca ingest/validate/build kiểm tra cả dữ liệu đến khác thứ tự,
  replay cũ, idempotency, batch trên 500 dòng và rollback toàn batch.
- Native localhost Java → Python production → DB: đã chạy source/validation/build.
- Java production → Python production → DB: đã chạy giá 25 mã, tỷ số FPT,
  khối ngoại, company snapshot, BCTC FPT quý 2, HNXINDEX và hai flow macro.
- So sánh 159.496 chỉ số nguồn mới với raw và phép đổi đơn vị: không lệch.
- Không trùng canonical giá/chỉ số/khối ngoại; không sai lineage được kiểm tra.
- NEWS giữ 359 bài; LLM giữ 45 runs và 23 results. Không gọi NEWS/LLM trong task.

## Những điểm còn chặn đánh giá tài chính đúng thời điểm

1. Không có ngày công bố BCTC lịch sử được chứng minh. `known_at` bảo thủ dùng lúc
   hệ thống quan sát, có tên riêng; không gọi đó là ngày công bố. Không dùng giả
   định cuối kỳ + 90 ngày làm dữ liệu nguồn. View strict hiện có **0 item đủ điều kiện**.
2. Đã xác định 1.300 bảng cân đối là POINT_IN_TIME và 500 báo cáo dòng tiền/kết quả
   năm là ANNUAL. 2.100 báo cáo dòng quý vẫn UNKNOWN về riêng quý/lũy kế; scope,
   đơn vị legacy và bản gốc/điều chỉnh chưa có đủ bằng chứng. Không tự gắn hợp nhất
   hoặc suy ra quý riêng từ nhãn QUARTER.
3. Đã gọi đủ 75 yêu cầu BCTC quý 3/2026 cho 25 mã: nguồn trả data rỗng, validation
   từ chối. Thêm một lần smoke FPT nguồn rỗng, tổng 76 lần FAIL cùng lý do. Kỳ cuối
   đủ hiện tại vẫn quý 2/2026; số raw/version không được hiểu là thêm kỳ báo cáo.
4. HNXINDEX đã có lịch sử từ 04/01/2016 đến 16/10/2026. VNINDEX/VN30 còn thiếu
   lịch sử trước 2021 và các phiên mới: KBS có OHLC mâu thuẫn; CafeF/VCI được
   đối chiếu cũng có lỗi/thiếu, VNDirect chart trả 406. Các batch lỗi không vào
   bảng cuối. Đây vẫn là hạn chế đáng kể đối với biến lợi suất tương đối VNINDEX.
5. PE/PB và EPS/ROA/ROE đã có chuỗi lịch sử nguồn; `reportDate` không chứng minh
   lúc công bố hay vintage lịch sử. Không lấy giá trị hiện tại để thay năm cũ.
6. Lãi suất World Bank vẫn đến 2023, tỷ giá năm đến 2024. BIS tháng đến 07/2026
   theo bản nguồn thực tế; không nội suy dữ liệu năm. Chưa có lịch sử công bố/revision.
7. Snapshot ngành/cổ phiếu/niêm yết là thông tin quan sát hiện tại. Chưa có universe
   lịch sử gồm mã hủy niêm yết hay lịch sử ngành/corporate actions được chứng nhận.

## Cách đọc cho Local AI

Các view `vw_training_statement_inputs_v1`, `vw_training_market_observations_v1`,
`vw_training_metric_observations_v1`, `vw_training_macro_observations_v1` giữ raw/version,
ngày nghiệp vụ, nguồn, known_at và hạn chế availability. Đọc cả confidence/provenance;
không coi bảng có nhiều dòng là bằng chứng đủ điều kiện kiểm định point-in-time.
Lượt này không sửa feature engineering, tham số train hoặc kết quả mô hình.

## Bản triển khai và bàn giao

- Python production: `13a362217e17b1bb0f3693ac1f5a3bebef5a87b5`, version 0.7.0,
  Vercel deployment `dpl_6fnzFXHJMpoRuPZssAyyFSSfvT7e`, READY.
- Java production: `e50c9f304ac02cd59b29852f88c408ca501ca0f9`.
- [Java CI](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/actions/runs/39005168185),
  [Java deployment](https://github.com/Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST/actions/runs/39005372090): success.
- Mã sửa của cả hai repo nằm ở `fix/training-data-quality`; code runtime đã có trên main.
- Đã backup riêng trước migration; không commit backup, private config hay token.
- Cấu hình token admin được chuẩn bị riêng, chưa deploy: auto-review chặn chuyển
  token local lên GitHub Actions Secret, đang chờ người dùng cho phép rõ nguồn/đích.
  Production ingestion/build đã nghiệm thu; endpoint quality có bảo vệ hiện trả 503
  vì thiếu token. Không tắt kiểm tra quyền và không gửi token qua cách khác.

Audit metric_source_math: `[{"checked": 159496, "mismatched": 0}]`

Audit foreign_final_overlay: `[{"expected_price_rows": 5998, "missing_overlay": 0}]`

Audit canonical_duplicates: `[{"dataset": "foreign", "duplicate_buckets": 0}, {"dataset": "prices", "duplicate_buckets": 0}, {"dataset": "metrics", "duplicate_buckets": 0}]`

Audit raw_lineage: `[{"invalid": 0}]`

Audit lineage_foreign: `[{"invalid": 0}]`

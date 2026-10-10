# Bàn giao backend — macro Việt Nam

Cập nhật **09/10/2026, giờ Việt Nam**. Đọc code và bằng chứng theo ngày;
không suy trạng thái production từ unit test hoặc tài liệu ở nhánh khác.

## Repo và phạm vi

- Java: `Lamprro/HE_THONG_DATA_TAI_CHINH_BACKEND_TEST`, nhánh chính `master`.
  GitHub chưa có nhánh `main` khi kiểm tra ngày 09/10/2026. Bản macro được xây
  từ `aaa7d8d`, tách khỏi công việc NEWS/LLM trên `feature/llm-processing`.
- Python: `Lamprro/HE_THONG_DATA_TAI_CHINH_TEST`, nhánh `main`, commit
  `40337aa52ee23f406028c10d88aba0f21f729b24`.
- Python production: `https://he-thong-data-tai-chinh-test.vercel.app`.
  Vercel deployment `dpl_N9F1aDd5wdgyoRmm1DjmJnnK8WwB`, READY, đúng commit trên.
- Không đưa phần NEWS/LLM chưa nghiệm thu trên nhánh feature vào release macro.
  Các tài liệu LLM ở nhánh đó mô tả phạm vi riêng, không phải chứng nhận master.

## Những gì đã hoàn thiện

Java ingestion job → adapter hiện có → Python đã deploy → World Bank/BIS →
raw_payloads → validation_results → data_versions → macro_series/macro_observations.
Có kiểm định country, nguồn, mã chỉ số, đơn vị, kỳ, giá trị và bằng chứng nguồn;
transaction toàn batch, chống trùng, xử lý correction và chặn raw cũ ghi đè raw mới.

Hai job Việt Nam chạy quý, lấy lại lịch sử từ 2016 theo coverage financial/market
thật. Giữ nguyên tần suất công bố: World Bank theo năm, tỷ giá BIS theo quý.
Macro là dữ liệu vĩ mô nhập từ nguồn độc lập; không tính GDP/lãi suất từ báo cáo
doanh nghiệp hoặc giá cổ phiếu.

Chi tiết API, sơ đồ, migration, coverage, lịch chạy, kiểm thử và cách nghiệm thu:
[MACRO_PIPELINE.md](MACRO_PIPELINE.md).

## Bằng chứng nghiệm thu ngày 09/10/2026

- Python: 10 tests pass, gồm 6 macro và 4 NEWS regression; provider trong unit
  tests là mock. Kiểm tra endpoint production riêng trả 95 World Bank + 42 BIS.
- Java: 90 tests khác nhau pass qua lượt regression và lượt bổ sung macro;
  gồm 5 integration PostgreSQL schema cô lập với provider mock. Không chạy
  `FinancialDataApplicationTests` vì test cũ dùng DB nghiệp vụ và giá giả.
  Maven package thành công.
- Luồng Java thật gọi Python production đã nạp **11 series / 137 observations**
  vào DB hiện có; chạy lại hai job trả NO_CHANGE. 137/137 giá trị đối chiếu khớp raw,
  không trùng khóa, sai quốc gia hoặc đứt liên kết raw/version.
- Trong lượt hai vòng đầu: 2 macro versions ACTIVATED; 2 bản gọi lại REJECTED vì không đóng góp dữ liệu.
  Mỗi batch có bốn kết quả validation PASS.
- Trước/sau: financial_statements 3929, market_prices 71762, news_articles 359,
  llm_runs 42. Đây là snapshot nghiệm thu, không phải cam kết count bất biến sau đó.
- Migration đã chạy trên DB thật sau kiểm thử schema cô lập. Backup riêng tư và
  báo cáo chi tiết nằm trong `target/macro-release-20261009/`, được ignore khỏi Git.

## Runtime và giới hạn cần giữ

Java nghiệm thu ở `127.0.0.1:8181`, dùng DB cấu hình private và Python production;
scheduler chỉ macro được bật trên tiến trình này. Đây là tiến trình local, không
phải Windows service tự khởi động hay bằng chứng đã deploy Java lên server khác.
Nếu chuyển runtime, phải áp migration rồi bật `financial.macro.scheduler.enabled`.

Redis local port 6380 chưa kết nối được trong lượt nghiệm thu: có WARN khi reset
retry budget. Fetch/validate/build đã thành công; phần giới hạn số lần retry bằng
Redis chưa được nghiệm thu trên runtime này. Không sửa hạ tầng Redis/NEWS/LLM.

Nguồn chưa có GDP theo quý và lãi suất điều hành trong catalog này. World Bank
lãi suất mới nhất 2023, tỷ giá năm 2024, các chuỗi còn lại 2025; BIS đến 2026-Q2.
Không điền giá trị thiếu bằng nội suy hoặc chia số năm thành quý.
`observation_date` là ngày cuối kỳ, không phải ngày công bố. Nguồn chưa cung cấp
lịch sử vintage/release date; không dùng dữ liệu này để tuyên bố backtest biết
trước thông tin tại ngày lịch sử.

Không commit khóa, config private, backup hoặc response mock vào DB nghiệp vụ.
Nghiệm thu tích hợp luôn đi qua job Java và Python thật, không thay bằng fetcher riêng.

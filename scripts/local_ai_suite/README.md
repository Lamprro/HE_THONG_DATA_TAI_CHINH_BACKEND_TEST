# Pipeline train bộ mô hình local AI

## Xếp hạng cổ phiếu H1 (19/11/2026)

`MARKET_STAGING.md` hướng dẫn kiểm nguồn và build thử qua backend trước khi mở rộng danh sách dữ liệu huấn luyện. Lượt train trên danh sách đã duyệt có thể nhận `-ReadinessJson` để khóa danh sách mã và lưu SHA của phép kiểm.

`run_rank.ps1` tạo snapshot PostgreSQL chỉ đọc, gọi Java để dựng dữ liệu quý/tháng, rồi so V0–V3 bằng Logistic, HistGradientBoosting, RandomForest và ba baseline. Kế hoạch kiểm định, kết quả chọn, audit từng fold, dự đoán lịch sử và model `.joblib` được lưu theo thư mục phiên chạy.

```powershell
& ./scripts/local_ai_suite/run_rank.ps1 -BusinessDate 2026-11-19 -Output ./output/local-ai-review/rank-new
python -B scripts/local_ai_suite/predict_rank.py --run ./output/local-ai-review/rank-new/V1 --symbols FPT --out ./output/local-ai-review/FPT-rank.json
```

`-SnapshotRun` chỉ định thư mục snapshot đã xuất và có manifest để chạy lại minh bạch. Java tính hạng từ toàn rổ đủ giá ở ngày dự báo. Rổ dùng danh sách chứng khoán hiện có trong DB và điều kiện lịch sử giá, nên còn rủi ro do thiếu thành viên đã hủy niêm yết và lịch sử thành viên chỉ số. Nhãn tương đối chỉ mở khi đủ lợi suất đích cho cả rổ đã chốt. V3 kiểm 45 ngày với quý và 90 ngày với báo cáo năm có bằng chứng kiểm toán. Các giá trị canonical hiện tại vẫn có thể chứa bản sửa về sau, nên V3 chỉ dùng nghiên cứu.

Gọi `build_rank_release.py --run <V1> --out <gói>` để niêm phong model và rổ đã chốt. Tuỳ chọn `--risk-release <gói đã xác minh>` lấy dự báo biến động và sụt giảm từ gói rủi ro có cùng quý gốc và quý đích. Kết quả có hash riêng của nguồn rủi ro. Mã chưa có dữ liệu đi kèm nhận trạng thái thiếu rủi ro.

Registry dùng `SuiteRegistryStore ... isolate` và smoke thật dùng `accept_release.py --root <gói>`. Spring trả `local.stock.rank.output.v3` qua endpoint suite, với score chưa hiệu chuẩn, percentile và nhóm. Đợt này hỗ trợ đăng ký rank trong schema kiểm thử. Với dự báo đã phát hành, dùng `evaluate_rank_forward.py --run <V1 cũ> --observed <V1 mới> --out <kết quả>`. Dự báo phát hành trong quý đích được ghi `PARTIAL_HORIZON_RESEARCH`.

**Cập nhật 23/10/2026:** Đã train từ DB mới, bổ sung NPAT H1/H2 và nghiệm thu gói 11 tác vụ qua Spring thật, lưu 218 dự đoán vào DB chính. Đọc [hướng dẫn gói mới](../../docs/LOCAL_AI_RELEASE_20261023.md). Nội dung bên dưới mô tả phạm vi bộ thị trường ngày 21/10, không phải trạng thái tích hợp mới nhất.

Đây là nguồn có thể quản lý bằng Git của đợt hoàn thiện ngày 21/10/2026. Dữ liệu và pickle ở `artifacts/local-ai/`, được ignore. Không đổi routing Spring, không mở public API, không tự gọi nhà cung cấp bằng fetcher mới.

## Phạm vi đã thực hiện

- Hướng giá H1, H2, H3, H4: Logistic Regression và mô hình xác suất theo trạng thái, đối chiếu historical rate, momentum và LR15.
- Return H1: Ridge, hồi quy trung vị, boosting nhỏ, so zero/mean/median/persistence.
- P10/P50/P90 H1: phân vị lịch sử, điều kiện theo rủi ro/trạng thái và hồi quy phân vị.
- Volatility và drawdown H1: hồi quy trung vị, shrinkage, state/kernel, đối chiếu baseline đúng đơn vị.
- Tổng hợp H1: Logistic Regression học từ bốn dự đoán ngoài mẫu của model con. Có walk-forward và outer evaluation theo thời gian. Tổng hợp không thắng thì giữ baseline.

Tổng cộng 9 task, không phải 28 model độc lập: 28 file pkl gồm 3 role mỗi task và 1 gói model con. `selected` là lựa chọn development, có thể là baseline; không phải model được phép production. Chưa train model 5 chỉ tiêu tài chính do metadata nguồn chưa đủ. Không tạo pickle rỗng để coi hoàn tất.

## Chạy đầy đủ

Yêu cầu Java 21/javac, Python 3.13 và các version thư viện đúng `requirements.txt`. Dùng environment ML có sẵn của dự án.

```powershell
& ./scripts/local_ai_suite/run.ps1 -BusinessDate 2026-10-21
```

Mặc định đọc `application-local.properties` của backend, chỉ đọc DB trong transaction repeatable-read. Không in credentials. Xuất snapshot xong mới tính đặc trưng, train, đóng gói và kiểm tra. Bất kỳ bước nào lỗi thì dừng, ghi status FAILED; không tái dùng CSV dở. Output directory phải mới.

Các tham số `-BackendRoot`, `-Python`, `-Jdbc`, `-Output`, `-BusinessDate` có thể cấu hình. Khi nguồn được chép sang AI_LOCAL, truyền `-BackendRoot` là đường dẫn repo Java thật. Script Java dựng đặc trưng market15 trực tiếp từ source backend; mọi nguồn tính được giữ kèm SHA trong plan.

### Chạy lại snapshot cũ có chủ đích

```powershell
& ./scripts/local_ai_suite/run.ps1 -BusinessDate 2026-10-21 -SnapshotFrom ./artifacts/local-ai/market-regime-20261020
```

Chế độ replay chỉ hỗ trợ snapshot market-regime đã có bằng chứng và checksums. Đây là lựa chọn tường minh, không tự fallback khi DB lỗi. Provenance giữ thời gian snapshot thị trường và audit tài chính riêng. Đợt21/10 đã dùng chế độ này sau SQLSTATE08001 và TCP timeout; chưa xác nhận DB hiện tại.

## Bộ dữ liệu và so baseline

H1–H4 cùng đầu vào tại origin, target là giá cuối quý đích so cùng giá origin. Không phải tăng/giảm giữa hai quý tương lai. Giá adjusted cùng provider, nhãn rủi ro cần đủ đường giá theo phiên; không bù dữ liệu hoặc trộn provider để tăng coverage.

Provider order dùng chung `QuarterlyMarketFeatures` của backend. Bản nghiên cứu trước ưu tiên CafeF cho EIB/HSG/VNM; đợt này dùng thứ tự chung VNDIRECT rồi CafeF của backend và giảm 1.016 còn 1.014 origin. Eligibility query vẫn có ngoại lệ CafeF12 đã xác minh basis cho 3 mã; **không đồng nghĩa query hoặc integration Spring cho model mới đã được nghiệm thu**. Source/code/hash đều được lưu; so phiên bản trước bằng paired_previous_comparison.json trên dòng có cùng nhãn.

Development 2020–2024; retrospective 2025Q1–2026Q3 đã được xem trước. Nhãn train/OOF calibration phải kết thúc trước cutoff. Giữ nguyên cổng đã chốt: direction hơn baseline mạnh nhất 2 điểm BA, hai recall≥50%; loss task khác giảm ≥2%; thắng≥nửa số quý; quantile còn phải đạt coverage70–90% và khác nhau theo mã ở≥80%development quarters. Mọi thay đổi future phải tạo version/plan mới.

## Chạy suy luận

Xem README trong runtime_package. Một batch nhận tickers và cutoff, dùng đặc trưng đã đóng băng; không tự đọc DB hoặc cập nhật feature khi gọi. `--role challenger` dành nghiên cứu; `selected` của quantile hiện là phân vị lịch sử cố định, challenger mới cho khoảng khác nhau từng mã. Không gọi score momentum 0/1 là độ tin cậy0/100%.

API loopback có Bearer token và SQLite audit với RUNNING/SUCCEEDED/CACHED/INPUT_REJECTED/PROVIDER_FAILED. Idempotency theo request_id, dữ liệu, role và registry hash. SQLite là log local riêng, không phải các bảng local_ai_runs của Spring. Các request không qua auth hoặc JSON/HTTP hỏng được trả lỗi ở lớp HTTP; không có inference/run nghiệp vụ giả. Không train trong predict; không tạo outcome trước khi quý kết thúc.

## Đối chiếu thực tế sau quý

```powershell
python scripts/local_ai_suite/evaluate_forward.py --issued forecasts.json --forecast-run ORIGINAL_RUN --observed-run LATER_VERIFIED_RUN --out NEW_EVALUATION_FOLDER
```

Kiểm tra package/artifact identity, checksum snapshot sau, kỳ thực tế đã kết thúc và provider. Nếu chưa đến hạn thì PENDING. Dự báo phát hành sau khi quý bắt đầu là PARTIAL_HORIZON_RESEARCH; không lẫn vào chứng nhận dự báo trước toàn quý. Sai lệch origin do dữ liệu được sửa phải có cờ. Kết quả hiện là đánh giá local theo current vintage, chưa ghi DB nghiệp vụ.

## Tài chính và các model chưa thể train hợp lệ

Snapshot audit 20/10: 279.374 item canonical, 0 strict-PIT; thiếu ngày công bố và scope, 98.353 item chưa rõ riêng quý/lũy kế, 142 lỗi đơn vị, 279.232 chưa có contract metadata đủ bằng chứng. Cần ngày công bố thật, source/evidence, scope, currency/unit_scale, fiscal/period basis, first release/revision. Không dùng fiscal end hoặc created_at làm publication. Đơn vị phải cùng trước khi kiểm assets=liabilities+equity. NEWS cần nhãn duyệt, macro cần release vintages. Chi tiết `data_requirements.json`.

Các probe nguồn và sửa metadata trước đây không chứng minh provider đã trả đủ publication/scope. Khi DB và pipeline nguồn hoạt động lại, thu thập qua adapter Spring hiện hữu, kiểm định raw rồi build. Chỉ mở train tài chính sau khi có đủ coverage theo target, không chỉ một dòng hợp lệ.

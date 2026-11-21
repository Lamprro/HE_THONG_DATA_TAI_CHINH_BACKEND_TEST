# Staging dữ liệu giá cho Local AI

Luồng staging chạy một instance Spring Boot riêng với PostgreSQL search path chỉ trỏ đến schema `market_stage_…`. Worker và lịch tự chạy được tắt. Backend dùng adapter Python thật để lấy nguồn, lưu dữ liệu thô, chạy luật validation và dựng đúng version. Danh mục ứng viên chỉ được tạo trong staging ở bước này.

Danh sách ứng viên lấy từ kết quả directory của VNDirect đã lưu qua Spring. Bộ lọc chọn cổ phiếu HOSE đang niêm yết, bắt đầu niêm yết trước ngày đầu của cửa sổ. Đây là rổ nghiên cứu theo directory hiện thấy, có giới hạn thiên lệch sống sót.

```powershell
python -B scripts/local_ai_suite/market_staging.py --out ./output/local-ai-review/stage-new
python -B scripts/local_ai_suite/market_stage_batch.py --out ./output/local-ai-review/stage-new --limit 8 --workers 2
python -B scripts/local_ai_suite/market_stage_batch.py --out ./output/local-ai-review/stage-new --limit 0 --repair-build-conflicts --promote --target 100
```

Lệnh cuối mới mở backend phạm vi DB nghiệp vụ. Dùng `--directory` nếu cần cung cấp biên nhận directory mới. Các tham số `--from-date`, `--through-date` chuyển cửa sổ đánh giá. Script cần `psycopg2`, Java và Spring JAR nghiệp vụ có classifier `ingestion`. Có thể chỉ định đường dẫn qua `MARKET_STAGE_BACKEND_ROOT`, `MARKET_STAGE_CONFIG` và `MARKET_STAGE_PROVIDER_HOME`.

Điều kiện chuyển là tối thiểu 1.200 phiên, bao phủ 95% lịch tham chiếu và có dữ liệu ở hai đầu. Chỉ dữ liệu đã build ở staging, có giá điều chỉnh và liên kết raw/version mới tham gia tính độ phủ. Với lô OHLC bị từ chối, runner có thể cắt cửa sổ quanh phiên sai và gọi lại backend. Các lô con đi qua cùng bộ luật. Giá lỗi vẫn được lưu trong raw staging với lý do từ chối. Lỗi 409 từ khóa job được retry có giới hạn bằng raw ID, giữ nguyên nguồn.

Khi chuyển, runner đối chiếu version đã kích hoạt trong staging, lấy lại dữ liệu qua backend, rồi so hash của nội dung nguồn. Dấu vân tay chỉ bỏ các trường thời gian truy xuất và độ trễ của phản hồi. Nếu dữ liệu thay đổi, runner dừng trước khi tạo danh mục hay build. Backend nghiệp vụ lưu biên nhận raw của lần gọi lại và chạy validation lần nữa. Nhật ký lô giữ raw ID, version và build ở cả hai phía.

Staging sao chép cấu trúc bảng, index và danh mục cần thiết. Một số ràng buộc quan hệ và trigger thuộc schema vận hành không được tạo lại bởi PostgreSQL `LIKE`. Bước công bố chạy lại toàn bộ luồng trên schema vận hành để kiểm các ràng buộc này. Không dùng schema staging để kết luận thay cho kiểm tra dữ liệu sau khi chuyển.

Trước khi train lại, chạy kiểm tra chỉ đọc trên DB nghiệp vụ:

```powershell
python -B scripts/local_ai_suite/verify_market_expansion.py --out ./output/local-ai-review/stage-new/readiness.json
if ($LASTEXITCODE -ne 0) { throw 'Dữ liệu chưa qua điều kiện mở rộng' }
& ./scripts/local_ai_suite/run_rank.ps1 -BusinessDate 2026-11-19 -Output ./output/local-ai-review/rank-expanded-new -Workers 3 -ReadinessJson ./output/local-ai-review/stage-new/readiness.json
```

Biên nhận readiness ghi danh sách mã đạt ngưỡng và chi tiết ngày thiếu của VNINDEX/VN30. Hai chuỗi phải bắt đầu trong tháng 01/2016, đạt tỷ lệ bao phủ yêu cầu và qua kiểm tra OHLC, lineage và trùng phiên.

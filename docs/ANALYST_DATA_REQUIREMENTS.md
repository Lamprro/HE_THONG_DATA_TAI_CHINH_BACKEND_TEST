# Dữ liệu và báo cáo nên bổ sung cho phân tích cổ phiếu Việt Nam

Đây là đề xuất phân tích ngày 10/10/2026, **không phải danh sách đã được triển khai**.
Năm balance ratios và sáu target kịch bản hiện tại chưa tạo thành bộ phân tích đầy đủ.

## Đầu vào ưu tiên

| Nhóm | Dữ liệu/chỉ số nên có | Điều kiện trước khi tính |
| --- | --- | --- |
| Hiệu quả kinh doanh | Doanh thu, lợi nhuận gộp, lợi nhuận hoạt động, biên gộp/hoạt động/ròng, tăng trưởng doanh thu/lợi nhuận YoY/QoQ | Xác nhận riêng quý/lũy kế, scope, loại lợi nhuận và kỳ so sánh |
| Hiệu quả sử dụng vốn | ROE, ROA, ROIC, vòng quay tài sản, DuPont | Dùng vốn/tài sản bình quân phù hợp với kỳ; ROIC cần định nghĩa invested capital/NOPAT |
| Chất lượng lợi nhuận và tiền | CFO, capex, CFO/lợi nhuận, FCF, FCFF/FCFE | Xác minh capex và dấu dòng tiền; phân biệt tiền cho doanh nghiệp và cho cổ đông |
| Nợ và thanh khoản | Nợ vay ngắn/dài hạn, net debt, coverage lãi vay, quick/current/cash ratio, kỳ hạn và covenant | Không đổi tổng liabilities thành nợ vay; EBITDA chỉ tính khi đủ depreciation/amortization |
| Giá và định giá | Shares outstanding/diluted, EPS, BVPS, PE/PB, EV/EBITDA, dividend yield, DCF và peer multiples | Chuẩn đơn vị giá, loại EPS, TTM xác minh, số cổ phiếu cùng kỳ, điều chỉnh chia tách/phát hành/cổ tức |
| Giao dịch và rủi ro | OHLCV, trading value, thanh khoản, adjusted return, volatility, drawdown, beta với benchmark | Lịch giao dịch, corporate actions, dữ liệu đồng bộ và không dùng thông tin tương lai |
| Ngành/vĩ mô | Benchmark/rổ, đối thủ, động lực ngành, lãi suất điều hành, tỷ giá, CPI, GDP, tín dụng | Country, frequency, đơn vị, ngày phát hành/vintage; không forward-fill tương lai |

Phân tích tỷ lệ nên kết hợp xu hướng, so sánh ngành và chất lượng dữ liệu; lựa chọn
tỷ lệ phụ thuộc mục tiêu, ngành và chuẩn kế toán. Tham khảo
[CFA — Financial Analysis Techniques](https://www.cfainstitute.org/insights/professional-learning/refresher-readings/2026/financial-analysis-techniques).
FCFF/FCFE phục vụ định giá dòng tiền với giả định chiết khấu riêng, không thể dùng
LLM growth thay cho dữ liệu dòng tiền hoặc tự sinh WACC. Tham khảo
[CFA — Free Cash Flow Valuation](https://www.cfainstitute.org/insights/professional-learning/refresher-readings/2026/free-cash-flow-valuation).

Ngân hàng cần bộ chỉ số ngành riêng (NIM, chất lượng tín dụng/NPL, dự phòng, CAR,
chi phí hoạt động/thu nhập); không áp máy móc capex/EBITDA doanh nghiệp sản xuất.
Triển khai cụ thể phải đối chiếu công bố ngân hàng và định nghĩa nguồn từng chỉ số.

## Báo cáo nguồn cần thu thập

1. Báo cáo tài chính quý và năm: bảng cân đối, kết quả kinh doanh, lưu chuyển tiền tệ;
   đối chiếu riêng/hợp nhất, kỳ báo cáo và lịch sử điều chỉnh.
2. Thuyết minh: doanh thu phân khúc, giao dịch bên liên quan, kỳ hạn nợ, cam kết,
   chính sách kế toán, thay đổi cổ phiếu/cổ tức và sự kiện sau kỳ báo cáo.
3. Báo cáo biến động vốn chủ, báo cáo kiểm toán/soát xét và ý kiến ngoại trừ.
4. Báo cáo thường niên, giải trình kết quả, tài liệu ĐHĐCĐ/kế hoạch kinh doanh;
   giữ planned/forecast tách khỏi actual.
5. Công bố corporate actions, nguồn giá/volume, benchmark, dữ liệu ngành và macro.

Thuyết minh và biến động vốn là phần quan trọng ngoài ba báo cáo chính; tham khảo
[IFRS — IAS 1](https://www.ifrs.org/issued-standards/list-of-standards/ias-1-presentation-of-financial-statements.html/)
cho cấu trúc tài chính đầy đủ. Đây là tham khảo cấu trúc, không khẳng định mọi doanh
nghiệp Việt Nam áp dụng IFRS hoặc thay thế quy định công bố tại Việt Nam.

## Báo cáo đầu ra nên có

- Tổng quan doanh nghiệp/ngành, các động lực kết quả và so sánh peer.
- Xu hướng financial/ratios, chất lượng lợi nhuận, dòng tiền và lịch nghĩa vụ nợ.
- Định giá với assumptions, sensitivity, phương pháp và khoảng giá trị; tách khỏi
  kịch bản giá do LLM giả định hiện tại.
- Rủi ro, stress scenarios, catalysts và trường hợp luận điểm phân tích không còn đúng.
- Bảng coverage/chất lượng: nguồn, ngày công bố, phiên bản, dữ liệu thiếu và kỳ so sánh.
- Báo cáo nghiệm thu dự báo: walk-forward, baseline, sai số, calibration và leakage
  audit trước khi công bố độ chính xác/xác suất. Các dạng này chưa được triển khai.

Thứ tự đề xuất: chuẩn metadata/corporate actions → bổ sung revenue/cashflow/shares
→ ratio/valuation Java có công thức → benchmark/industry → backtest/stress/report.

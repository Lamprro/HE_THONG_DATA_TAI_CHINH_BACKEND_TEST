# Luồng FINANCIAL_METRIC

`FINANCIAL_METRIC` có hai luồng độc lập cùng ghi vào `financial_metrics`:

```text
RATIO -> raw_payloads -> validation -> DataVersion FINANCIAL_METRIC/ACTIVE
      -> FINANCIAL_METRIC_BUILD -> financial_metrics (is_derived=false)

financial_statement_items (current, source version ACTIVATED)
      -> FINANCIAL_METRIC_CALCULATE -> financial_metrics (is_derived=true)
```

`MARKET_PRICE` chỉ nhận `QUOTE` và `OHLCV`; RATIO không đi qua `MarketPriceWorkflow` và không ghi vào `market_prices`.

## Provider metric

Build chỉ đọc raw `RATIO` thuộc DataVersion `FINANCIAL_METRIC/ACTIVE`, không gọi Python/provider hay validation. VNDIRECT hỗ trợ `PRICE_TO_EARNINGS`, `PRICE_TO_BOOK`, `EPS_TR`, `ROAE_TR_AVG5Q`, `ROAA_TR_AVG5Q`, `DIVIDEND_YIELD`. ROAE, ROAA, Dividend Yield được đổi từ fraction sang percentage points theo code provider, không dùng heuristic giá trị nhỏ hơn 1.

Provider metric có `financial_period_id = NULL`; `as_of_date` là `reportDate`. Identity là company/security/definition/date/source/is_derived=false: cùng ngày là correction (UPDATE), ngày mới là observation mới (INSERT). Build thành công chuyển source DataVersion sang `ACTIVATED`; lỗi rollback toàn bộ output của version đó rồi reject version.

## Derived metric

Calculator group theo company, security, period và data source; chỉ chọn một report scope với ưu tiên `CONSOLIDATED > SEPARATE > UNKNOWN`. Không trộn source hoặc scope. Output gắn `financial_period_id`, `as_of_date = financial_periods.end_date`, `is_derived=true`, `calculation_version=FS_ITEMS_V1`.

MVP tính GROSS_MARGIN, OPERATING_MARGIN, NET_MARGIN, CURRENT_RATIO, CASH_RATIO và LIABILITIES_TO_EQUITY bằng `BigDecimal`. Hai OCF metrics chỉ được seed catalog; chưa tính vì runtime chưa chứng minh cash-flow và income có period basis tương thích. Thiếu/null input hoặc mẫu số 0 chỉ skip metric đó.

Mỗi lần job chạy đều tính lại input hiện hành. Cùng identity mà giá trị đổi sẽ UPDATE, không tạo duplicate; giá trị không đổi thì giữ nguyên.

`scripts/repair_ratio_data_versions.sql` là repair guarded cho lịch sử `MARKET_PRICE/ACTIVE` sai domain: chỉ sửa run có job dataset `FINANCIAL_METRIC` và tất cả raw là RATIO; không sửa status hoặc market price rows.

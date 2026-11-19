# Local AI research preview — 19/11/2026

Nhánh `feature/local-ai-research-preview` (tách từ `refactor/admin-cleanup` @ `091f5eb`). Chỉ commit local, chưa push/PR/merge/deploy.

## Mục đích

FE user cần phát triển giao diện hiển thị dự đoán Local AI, nhưng gói `1d5b62a8-…` vẫn `RESEARCH_ONLY` và chưa có chính sách
công bố. Thêm một endpoint **xem trước nghiên cứu, mặc định tắt**, không thay thế reader công bố chính thức.

```
GET /api/v1/securities/{symbol}/local-ai/research-preview?exchange=HOSE[&packageId=UUID]
```

- Tắt (mặc định, `financial.local-ai.research-preview.enabled=false`): trả **404** "Local AI research preview is disabled", không truy vấn DB.
- Bật: chạy `--financial.local-ai.research-preview.enabled=true` (hoặc đặt trong `application-local.properties`). **Không bật trên production** khi chưa có chính sách công bố.

## Điều kiện đọc (giống bộ đếm `storedSelectedResearchResults`)

Gói `RESEARCH_ONLY`, schema `local_ai.registered.release.v1`, `production_eligible=false`, `replace_llm=false`; prediction `role=selected`,
`readiness=RESEARCH_ONLY`; run `SUCCEEDED`, HTTP 200, `completed_at` có, `checks.passed=true`; có input đăng ký cùng package/model/ticker.
Ticker trùng nhiều listing → `TICKER_IDENTITY_AMBIGUOUS`, không ghép. Mỗi model lấy bản `issued_at` mới nhất.

## Chiếu dữ liệu

Chỉ trả metadata (modelId, kind, horizonQuarters, targetPeriod, issuedAt, completedAt, dataAsOf, selectedAlgorithm, developmentGatePassed)
và `values` theo whitelist hợp đồng đầu ra:

| kind | values |
| --- | --- |
| h3 (direction) | direction, score_up, threshold |
| quantile | return_p10, return_p50, return_p90 |
| npat | prediction_vnd |
| return / volatility / drawdown | prediction |

Số được đọc bằng BigDecimal và trả dạng chuỗi (giữ chính xác). Kind lạ hoặc JSON hỏng bị bỏ. Không trả input_payload, input_sha256,
feature, đường dẫn PKL, request/response run, checks hay outcome hồi cứu. `readiness=RESEARCH_ONLY`, `publicResultsAvailable=false`.

## Kiểm thử đã chạy

- `UserLocalAiServiceTests` (3): tắt → 404 không chạm DB; whitelist field + decimal string (test phát hiện và đã sửa lỗi mất chính xác khi Jackson đọc double); kind lạ/JSON hỏng bị loại.
- Toàn bộ `mvn -o test`: 398 test, 2 fail, 121 skipped (DB-isolated). 2 fail thuộc `NewsPipelineIntegrationFlowTests` và **đã fail y hệt trên `refactor/admin-cleanup` trước thay đổi** — không liên quan.
- `scripts/user_api_smoke.py` trên DB thật (localhost:8080, chỉ đọc): PASS 38 route / 60 request khi bật preview; script chấp nhận 404 và miễn coverage route này khi tắt, và kiểm không lộ field riêng tư khi bật.
- Đọc thật FPT: 11 kết quả selected (direction H1–H4, drawdown, ensemble, npat H1/H2, quantile, return, volatility).

## Còn tồn đọng

Đây không phải reader công bố. Khi chat Local AI chốt chính sách (vintage/scope/metadata, outcome forward), cần reader chính thức + nghiệm thu riêng
và có thể bỏ endpoint preview. `UserLocalAiService.installed()/packageRow()` đổi từ private sang package-private để dùng lại.

# Client cho FE user

ES module không có dependency runtime: `user-api.mjs` và declaration `user-api.d.mts`.
Copy cả hai file vào FE hoặc import từ package nội bộ. Client có đủ **37 GET**
hiện tại, dùng UUID và envelope thực của Java. Không tự chuyển DTO sang kiểu ID số
của FE demo `FINANCIAL_FRONTEND_TEST_V1` và không chứa API admin/train/execute.

```ts
import { createUserApi, UserApiError } from './user-api.mjs';

const api = createUserApi({ baseUrl: 'http://localhost:8093', timeoutMs: 8000 });
const controller = new AbortController();
try {
  const { data: security } = await api.security('FPT', { exchange: 'HOSE' }, { signal: controller.signal });
  const { data: company } = await api.company(security.companyId);
  const reports = await api.statements('FPT', { exchange: 'HOSE', size: 1 });
  if (reports.items.length) {
    const { data: report } = await api.statement('FPT', reports.items[0].id, { exchange: 'HOSE' });
    if (report.items.length) {
      const matrix = await api.financials('FPT', {
        exchange: 'HOSE', type: report.statementType, period: report.periodType,
        codes: [report.items[0].code], periodLimit: 10,
      });
      console.log(company.legalName, matrix.data.series);
    }
  }
  const progress = await api.securityLocalAi('FPT', { exchange: 'HOSE' });
  // RESEARCH_ONLY != có dự đoán công khai. Render state và các mốc dữ liệu.
  console.log(progress.data.state, progress.data.models);
} catch (error) {
  if (error instanceof UserApiError) console.log(error.kind, error.status, error.message);
  else throw error;
}
```

`baseUrl` nhận HTTPS origin, hoặc HTTP localhost; có thể có hậu tố `/api` hay
`/api/v1`. Không nhận URL có credential/query/fragment. Query `codes` và `symbols`
nhận CSV string hoặc string array. Ngày truyền ISO string; UUID luôn là string.

Client chỉ gửi GET, `credentials=omit`, không Authorization/cookie và không tự retry.
Nó không gọi admin client của FE, không yêu cầu token và không ghi vào localStorage.
Chỉ thêm header bỏ trang cảnh báo khi hostname là ngrok-free.dev/app.
Decimal và bigint ID giữ nguyên string, `null` giữ nguyên, không dùng `Number` để
xử lý số tiền. Tách các trạng thái `HTTP`, `NETWORK`, `TIMEOUT`, `ABORTED`,
`INVALID_RESPONSE` để FE xử lý mảng rỗng và lỗi kết nối khác nhau.

`UserApi` khai báo kiểu cho envelope, UUID, business decimal, ma trận, báo cáo,
progress Local AI và các field chính. Những projection nguồn linh hoạt vẫn là
`Record<string, unknown>`, không giả thành schema cố định của mọi provider/model.
Đọc `meta.notes` và tài liệu backend khi render/so sánh đơn vị hoặc kỳ dữ liệu.

## Hợp đồng với luồng Local AI

User chỉ gửi `symbol`, `exchange`, tùy chọn `packageId` khi đọc progress/catalog.
Không gửi feature vector, giá trị dự đoán, vai trò model hoặc đường dẫn PKL từ trình duyệt.

Luồng thực thi hiện có thuộc **admin**, do nhánh Local AI quản lý: request gồm
`packageId`, `modelId`, `ticker`, `role=selected|challenger|baseline`, `requestId` UUID.
Spring tìm snapshot đã đăng ký và SHA, rồi gọi Python. Đây không phải operation
của client user này. Đổi quý cần gói/snapshot mới qua pipeline native, không sửa
input hay file train cũ. Không có nút “predict” giả trả output demo.

Nguồn hợp đồng: `/api/v1/openapi.json`, các controller user, native
`LocalAiSuiteContract` v1 và dữ liệu registry thật. Danh mục mới đọc độc lập với
engine train, không cần chạy Python để xem trạng thái đã lưu.

## Kiểm thử

```text
node --test clients/user-api.test.mjs
tsc --noEmit --strict --module NodeNext --target ES2022 --lib ES2022,DOM clients/user-api.types.mts
```

4 test client dùng fetch mock để kiểm precision, credential omission, input,
timeout/cancel và phân loại lỗi. Typecheck kiểm UUID string và chặn phương thức ghi.

`user-api.browser.mjs` kiểm **trình duyệt → client → Java → DB → text render**,
không mock network. Cần Java localhost 8093 đã tắt worker/model và dùng DB read-only,
Chrome cài sẵn, Playwright và port FE 5173 đang trống. Script chạy web server tạm
trên 127.0.0.1:5173 và đóng browser/server của nó trong finally; không dùng profile
Chrome đang mở của người dùng. FE origin này phải nằm trong cấu hình CORS Java.

```text
node clients/user-api.browser.mjs
```

Nếu Playwright nằm ở dự án khác, đặt `USER_API_PLAYWRIGHT_MODULE` thành file URL
của `playwright/index.mjs`. `USER_API_BASE`, `USER_API_BROWSER_CHANNEL` (mặc định
chrome) và `USER_API_BROWSER_PORT` tùy chỉnh môi trường nghiệm thu. Không tự tải
browser hoặc tạo hosting. Browser test yêu cầu dữ liệu thật FPT/HOSE, báo cáo có
item; kiểm UUID, financial matrix, lineage, Local AI, lỗi 400 và phủ client của
toàn bộ OpenAPI. Đây là integration harness, chưa thay cho nghiệm thu UI user đầy đủ.

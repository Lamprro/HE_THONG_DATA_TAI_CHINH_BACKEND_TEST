const form = document.getElementById('connect-docs');
const username = document.getElementById('admin-username');
const password = document.getElementById('admin-password');
const panel = document.getElementById('swagger-ui');
const statusLine = document.getElementById('docs-status');
const disconnect = document.getElementById('clear-docs');
const connect = form.querySelector('button[type="submit"]');
let connection;
let accessToken;

disconnect.disabled = true;
disconnect.addEventListener('click', async () => {
  if (!accessToken) return;
  disconnect.disabled = true;
  try {
    const response = await fetch('/api/auth/logout', {
      method: 'POST', credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(10000),
      headers: { Authorization: 'Bearer ' + accessToken, 'ngrok-skip-browser-warning': 'true' },
    });
    if (!response.ok && response.status !== 401) throw new Error();
    connection?.abort();
    accessToken = undefined;
    password.value = '';
    panel.replaceChildren();
    connect.disabled = false;
    statusLine.textContent = 'Đã đăng xuất.';
  } catch {
    disconnect.disabled = false;
    statusLine.textContent = 'Chưa thể đăng xuất. Hãy thử lại.';
  }
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (connect.disabled) return;
  connect.disabled = true;
  connection?.abort();
  panel.replaceChildren();
  const credentials = JSON.stringify({ username: username.value.trim(), password: password.value });
  password.value = '';
  const attempt = new AbortController();
  connection = attempt;
  statusLine.textContent = 'Đang tải OpenAPI…';
  try {
    const login = await fetch('/api/auth/login', {
      method: 'POST', body: credentials, credentials: 'omit', redirect: 'error', signal: attempt.signal,
      headers: { 'Content-Type': 'application/json', 'ngrok-skip-browser-warning': 'true' },
    });
    if (!login.ok) throw new Error(login.status === 401 ? 'Tên đăng nhập hoặc mật khẩu không đúng.'
      : 'Không thể đăng nhập (HTTP ' + login.status + ').');
    const { accessToken: token } = await login.json();
    accessToken = token;
    disconnect.disabled = false;
    const response = await fetch('/v3/api-docs/admin', {
      headers: { Authorization: 'Bearer ' + token, 'ngrok-skip-browser-warning': 'true' },
      credentials: 'omit', redirect: 'error', signal: attempt.signal
    });
    if (!response.ok) throw new Error('Không thể mở OpenAPI (HTTP ' + response.status + ').');
    const spec = await response.json();
    if (attempt.signal.aborted) return;
    const ui = SwaggerUIBundle({
      spec, domNode: panel, supportedSubmitMethods: ['get'], validatorUrl: null,
      persistAuthorization: false, docExpansion: 'none',
      requestInterceptor: request => {
        if (attempt.signal.aborted || new URL(request.url, location.origin).origin !== location.origin) {
          throw new Error('Swagger chỉ gửi yêu cầu đến backend hiện tại.');
        }
        request.headers['ngrok-skip-browser-warning'] = 'true';
        request.redirect = 'error';
        return request;
      },
      onComplete: () => queueMicrotask(() => {
        if (!attempt.signal.aborted) ui.preauthorizeApiKey('adminBearer', token);
      })
    });
    statusLine.textContent = 'Đã kết nối. Swagger chỉ cho phép thử GET.';
  } catch (error) {
    if (!attempt.signal.aborted) statusLine.textContent = error.message;
  } finally {
    if (!accessToken) connect.disabled = false;
  }
});

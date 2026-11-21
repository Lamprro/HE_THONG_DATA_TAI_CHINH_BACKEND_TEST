const form = document.getElementById('connect-docs');
const username = document.getElementById('admin-username');
const password = document.getElementById('admin-password');
const panel = document.getElementById('swagger-ui');
const statusLine = document.getElementById('docs-status');
let connection;

document.getElementById('clear-docs').addEventListener('click', () => {
  connection?.abort();
  password.value = '';
  panel.replaceChildren();
  statusLine.textContent = 'Đã ngắt kết nối.';
  window.location.reload();
});

form.addEventListener('submit', async event => {
  event.preventDefault();
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
  }
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createUserApi, userOperations, UserApiError } from './user-api.mjs';

test('client builds safe public GET paths, keeps precision/null and omits credentials', async () => {
  let captured;
  const client = createUserApi({ baseUrl: 'https://example.invalid/api/v1', fetch: async (url, options) => {
    captured = { url, options }; return new Response(JSON.stringify({ data: { amount: '9007199254740993', missing: null } }));
  } });
  const result = await client.compare({ symbols: ['FPT', 'VCB'], codes: ['ROE', 'PE'], page: 0, activeOnly: false });
  assert.equal(result.data.amount, '9007199254740993'); assert.equal(result.data.missing, null);
  assert.equal(captured.options.credentials, 'omit'); assert.equal(captured.options.method, 'GET'); assert.equal(captured.options.redirect, 'error');
  assert.equal(captured.options.headers.Authorization, undefined); assert.equal(captured.options.headers['Content-Type'], undefined);
  const url = new URL(captured.url); assert.equal(url.pathname, '/api/v1/securities/compare'); assert.equal(url.searchParams.get('symbols'), 'FPT,VCB'); assert.equal(url.searchParams.get('activeOnly'), 'false');
  assert.equal(Object.keys(client).length, Object.keys(userOperations).length);
});
test('invalid origins, path traversal and complex query values never call the network', async () => {
  for (const baseUrl of ['https://user:secret@example.org', 'http://example.org', 'https://example.org/admin', 'https://example.org?token=secret']) assert.throws(() => createUserApi({ baseUrl }), TypeError);
  const client = createUserApi({ baseUrl: 'http://localhost:8080', fetch: () => { throw new Error('Must not run'); } });
  await assert.rejects(client.security('../admin'), TypeError); await assert.rejects(client.article('..'), TypeError);
  await assert.rejects(client.article(' .. '), TypeError);
  await assert.rejects(client.securities({ page: NaN }), TypeError); await assert.rejects(client.securities({ search: { sql: 'x' } }), TypeError);
});
test('HTTP validation errors, HTML tunnel pages and network failures remain distinguishable', async () => {
  let response = new Response(JSON.stringify({ message: 'Missing parameter codes' }), { status: 400 });
  const client = createUserApi({ baseUrl: 'https://example.invalid', fetch: async () => response });
  await assert.rejects(client.financials('FPT'), error => error instanceof UserApiError && error.kind === 'HTTP' && error.status === 400);
  response = new Response('<html>Login</html>'); await assert.rejects(client.securities(), error => error.kind === 'INVALID_RESPONSE');
  const offline = createUserApi({ baseUrl: 'https://example.invalid', fetch: async () => { throw new TypeError('offline'); } });
  await assert.rejects(offline.securities(), error => error.kind === 'NETWORK');
});
test('caller cancellation and timeout abort GET without retries', async () => {
  const delayed = async (url, { signal }) => new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException('Aborted', 'AbortError'));
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
  });
  const client = createUserApi({ baseUrl: 'http://127.0.0.1:8080', timeoutMs: 10, fetch: delayed });
  await assert.rejects(client.securities(), error => error.kind === 'TIMEOUT');
  const controller = new AbortController(); controller.abort();
  await assert.rejects(client.securities({}, { signal: controller.signal }), error => error.kind === 'ABORTED');
});

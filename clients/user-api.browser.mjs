/** Real browser -> public client -> Java -> existing DB. No route mocks or admin credentials. */
import assert from 'node:assert/strict';
import http from 'node:http';
import { readFile } from 'node:fs/promises';

const { chromium } = await import(process.env.USER_API_PLAYWRIGHT_MODULE || 'playwright');
const baseUrl = process.env.USER_API_BASE || 'http://127.0.0.1:8093';
const port = Number(process.env.USER_API_BROWSER_PORT || 5173);
const source = await readFile(new URL('./user-api.mjs', import.meta.url));
const server = http.createServer((request, response) => {
  if (request.url === '/user-api.mjs') { response.writeHead(200, { 'Content-Type': 'text/javascript' }); response.end(source); return; }
  if (request.url !== '/') { response.writeHead(404); response.end(); return; }
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  response.end('<!doctype html><html lang="vi"><meta charset="utf-8"><title>Public API integration check</title><body><main><h1>API user — kiểm tra tích hợp</h1><pre id="result">Đang đọc dữ liệu</pre></main></body></html>');
});
let browser;
try {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  browser = await chromium.launch({ headless: true, channel: process.env.USER_API_BROWSER_CHANNEL || 'chrome' });
  const page = await browser.newPage(); const failures = []; const requests = [];
  page.on('pageerror', error => failures.push(error.message));
  page.on('request', request => { if (request.url().startsWith(baseUrl)) requests.push({ url: request.url(), method: request.method(), headers: request.headers() }); });
  await page.goto(`http://127.0.0.1:${port}/`);
  const result = await page.evaluate(async base => {
    const { createUserApi, UserApiError, userOperations } = await import('/user-api.mjs');
    const api = createUserApi({ baseUrl: base, timeoutMs: 20000 });
    const listings = await api.securities({ search: 'FPT', exchange: 'HOSE', size: 1 });
    if (!listings.items.length) throw new Error('FPT fixture must exist in the real business database');
    const profile = await api.security('FPT', { exchange: 'HOSE' });
    const company = await api.company(profile.data.companyId);
    const reports = await api.statements('FPT', { exchange: 'HOSE', size: 1 });
    const statement = await api.statement('FPT', reports.items[0].id, { exchange: 'HOSE' });
    const matrix = await api.financials('FPT', { exchange: 'HOSE', type: statement.data.statementType, period: statement.data.periodType, codes: [statement.data.items[0].code] });
    const lineage = await api.lineage('FPT', reports.items[0].id, { exchange: 'HOSE' });
    const local = await api.securityLocalAi('FPT', { exchange: 'HOSE' });
    const models = await api.localAiModels({ size: 100 });
    const overview = await api.overview('FPT', { exchange: 'HOSE' });
    const openapi = await api.contract();
    const missingClientOperations = Object.keys(openapi.paths).filter(path => !Object.values(userOperations).some(route => '/api/v1' + route === path));
    let errorStatus;
    try { await api.financials('FPT', {}); } catch (error) { if (!(error instanceof UserApiError)) throw error; errorStatus = error.status; }
    const text = { company: company.data.legalName, symbol: profile.data.symbol, source: lineage.data.sourceCode, publication: lineage.data.publishedAt, researchState: local.data.state, modelCount: models.total, errorStatus };
    document.getElementById('result').textContent = JSON.stringify(text, null, 2);
    return { ...text, companyIdMatches: company.data.id === profile.data.companyId, overviewIdMatches: overview.data.profile.data.id === profile.data.id,
      matrixHasValues: matrix.data.series.some(series => Object.values(series.values).some(point => point.value === null || typeof point.value === 'string')),
      publicResultsAvailable: local.data.publicResultsAvailable, missingClientOperations, routeCount: Object.keys(openapi.paths).length };
  }, baseUrl);
  assert.equal(result.companyIdMatches, true); assert.equal(result.overviewIdMatches, true); assert.equal(result.matrixHasValues, true);
  assert.equal(result.publicResultsAvailable, false); assert.equal(result.errorStatus, 400); assert.deepEqual(result.missingClientOperations, []);
  assert.deepEqual(failures, []); assert.ok(requests.length > 10);
  assert.ok(requests.every(request => request.method === 'GET' && !request.headers.authorization && !request.headers.cookie));
  assert.ok((await page.locator('#result').textContent()).includes(result.company));
  console.log(JSON.stringify({ status: 'PASS', browserRequests: requests.length, ...result }));
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }

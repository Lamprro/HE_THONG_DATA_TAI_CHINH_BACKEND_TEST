/** Dependency-free browser/Node client. Only public GET operations; never sends admin credentials. */
export class UserApiError extends Error {
  constructor(message, kind, status) { super(message); this.name = 'UserApiError'; this.kind = kind; this.status = status; }
}

export const userOperations = Object.freeze({
  securities: '/securities', security: '/securities/{symbol}', companies: '/companies', company: '/companies/{id}',
  marketOverview: '/market/overview', quote: '/securities/{symbol}/quote', prices: '/securities/{symbol}/prices',
  indices: '/indices', indexPrices: '/indices/{code}/prices', indexMembers: '/indices/{code}/members',
  macroSeries: '/macro/series', macroObservations: '/macro/series/{id}/observations',
  statements: '/securities/{symbol}/statements', statement: '/securities/{symbol}/statements/{id}',
  metrics: '/metrics', securityMetrics: '/securities/{symbol}/metrics', news: '/news', article: '/news/{id}',
  newsAnalysis: '/news/{id}/analysis', forecasts: '/securities/{symbol}/forecasts', marketFilters: '/market/filters',
  dataStatus: '/securities/{symbol}/data-status', periods: '/securities/{symbol}/periods',
  lineage: '/securities/{symbol}/statements/{id}/lineage', statementItems: '/securities/{symbol}/statement-items',
  priceLineage: '/securities/{symbol}/prices/{id}/lineage', metricLineage: '/securities/{symbol}/metrics/{id}/lineage',
  financials: '/securities/{symbol}/financials', screener: '/market/screener', compare: '/securities/compare',
  foreignTrades: '/securities/{symbol}/foreign-trades', referenceObservations: '/securities/{symbol}/reference-observations',
  analysisAvailability: '/analysis/availability', overview: '/securities/{symbol}/overview',
  localAiModels: '/local-ai/models', securityLocalAi: '/securities/{symbol}/local-ai', contract: '/openapi.json',
});

function apiRoot(value) {
  const url = new URL(value);
  if (url.username || url.password || url.search || url.hash ||
    (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))) ||
    !['', '/', '/api', '/api/', '/api/v1', '/api/v1/'].includes(url.pathname))
    throw new TypeError('Use an HTTPS backend origin (or HTTP localhost), optionally ending in /api/v1, without credentials/query/fragment.');
  return new URL('/api/v1/', url.origin);
}

function segment(value, name) {
  if (typeof value !== 'string' || !value.trim() || ['.', '..'].includes(value.trim()) || value.length > 100 || /[\/\\?#]/.test(value))
    throw new TypeError(`Invalid path parameter ${name}`);
  return encodeURIComponent(value.trim());
}

function queryString(query) {
  const result = new URLSearchParams();
  for (const [key, original] of Object.entries(query ?? {})) {
    if (original === undefined || original === null) continue;
    let value = original;
    if (Array.isArray(value)) {
      if (!['codes', 'symbols'].includes(key) || value.some(item => typeof item !== 'string')) throw new TypeError(`Invalid array parameter ${key}`);
      value = value.join(',');
    }
    if (!['string', 'number', 'boolean'].includes(typeof value) || (typeof value === 'number' && !Number.isFinite(value))) throw new TypeError(`Invalid query parameter ${key}`);
    result.set(key, String(value));
  }
  return result;
}

export function createUserApi({ baseUrl, fetch: fetcher = globalThis.fetch, timeoutMs = 8000 }) {
  const root = apiRoot(baseUrl);
  if (typeof fetcher !== 'function' || !Number.isFinite(timeoutMs) || timeoutMs < 1 || timeoutMs > 120000) throw new TypeError('A fetch function and timeoutMs between 1 and 120000 are required.');
  async function request(path, query, options = {}) {
    const url = new URL(path.replace(/^\//, ''), root); url.search = queryString(query).toString();
    const controller = new AbortController(); let timedOut = false;
    const cancel = () => controller.abort();
    if (options.signal?.aborted) cancel();
    else options.signal?.addEventListener('abort', cancel, { once: true });
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    try {
      const headers = { Accept: 'application/json' };
      if (/\.(ngrok-free\.dev|ngrok-free\.app)$/.test(root.hostname)) headers['ngrok-skip-browser-warning'] = 'true';
      const response = await fetcher(url.href, { method: 'GET', credentials: 'omit', redirect: 'error', cache: 'no-store', signal: controller.signal, headers });
      const text = await response.text(); let data;
      try { data = JSON.parse(text); } catch {
        throw new UserApiError(response.ok ? 'The API returned an empty or invalid JSON response.' : `HTTP ${response.status}`, response.ok ? 'INVALID_RESPONSE' : 'HTTP', response.status);
      }
      if (!response.ok) throw new UserApiError(typeof data?.message === 'string' ? data.message.slice(0, 1000) : `HTTP ${response.status}`, 'HTTP', response.status);
      if (data === null || typeof data !== 'object') throw new UserApiError('Expected an API object or envelope.', 'INVALID_RESPONSE', response.status);
      return data; // Preserve decimal/ID strings and nulls exactly; never coerce business values to Number.
    } catch (error) {
      if (error instanceof UserApiError) throw error;
      if (controller.signal.aborted) throw new UserApiError(timedOut ? 'Request timed out.' : 'Request cancelled.', timedOut ? 'TIMEOUT' : 'ABORTED');
      throw new UserApiError('Unable to reach the public API. Check backend URL and CORS.', 'NETWORK');
    } finally {
      clearTimeout(timer); options.signal?.removeEventListener('abort', cancel);
    }
  }
  const client = {};
  for (const [name, pattern] of Object.entries(userOperations)) {
    const placeholders = [...pattern.matchAll(/\{(\w+)\}/g)].map(match => match[1]);
    client[name] = async (...args) => {
      let path = pattern;
      placeholders.forEach((placeholder, i) => { path = path.replace(`{${placeholder}}`, segment(args[i], placeholder)); });
      return request(path, args[placeholders.length], args[placeholders.length + 1]);
    };
  }
  return Object.freeze(client);
}

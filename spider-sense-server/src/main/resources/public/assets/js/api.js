// Fetch wrappers for api.adoc, the shared top-bar state, and the window it implies.

export const RANGES = [
  { id: '5m', label: 'Last 5 min', ms: 5 * 60 * 1000 },
  { id: '15m', label: 'Last 15 min', ms: 15 * 60 * 1000 },
  { id: '1h', label: 'Last hour', ms: 60 * 60 * 1000 },
  { id: '6h', label: 'Last 6 hours', ms: 6 * 60 * 60 * 1000 },
  { id: 'all', label: 'All data', ms: null },
];

/** The top-bar state every page reads. Written only by app.js from the hash query. */
export const state = {
  service: '',
  range: '15m',
  live: false,
  status: null,
  /** The marks the shell keeps fresh, so every chart can draw them for free. */
  marks: [],
};

/** The part of the hash query every internal link carries. */
export function sharedQuery() {
  const q = {};
  if (state.service) q.service = state.service;
  if (state.range !== '15m') q.range = state.range;
  if (state.live) q.live = '1';
  return q;
}

export function rangeOf(id) {
  return RANGES.find((r) => r.id === id) || RANGES[1];
}

export function rangeIndex(id) {
  const i = RANGES.findIndex((r) => r.id === id);
  return i < 0 ? 1 : i;
}

/** The window the current range implies. `all` starts at /api/status.oldest.span. */
export function windowFor(range = state.range, now = Date.now()) {
  const r = rangeOf(range);
  if (r.ms == null) {
    const oldest = state.status && state.status.oldest ? state.status.oldest.span : null;
    return { from: oldest || now - 15 * 60 * 1000, to: now };
  }
  return { from: now - r.ms, to: now };
}

export function windowMs(range = state.range) {
  const w = windowFor(range);
  return w.to - w.from;
}

/** from/to/service plus whatever the caller adds; undefined and '' are dropped. */
export function params(extra = {}, opts = {}) {
  const w = opts.window || windowFor();
  const out = { from: w.from, to: w.to };
  if (opts.service !== null) {
    const service = opts.service !== undefined ? opts.service : state.service;
    if (service) out.service = service;
  }
  for (const [k, v] of Object.entries(extra)) {
    if (v === undefined || v === null || v === '') continue;
    out[k] = v;
  }
  return out;
}

function qs(obj) {
  const usp = new URLSearchParams();
  for (const [k, v] of Object.entries(obj || {})) {
    if (v === undefined || v === null || v === '') continue;
    usp.set(k, String(v));
  }
  const s = usp.toString();
  return s ? '?' + s : '';
}

export class ApiError extends Error {
  constructor(message, status) { super(message); this.name = 'ApiError'; this.status = status; }
}

const inflight = new Map();

/** GET with in-flight de-duplication: the same URL asked twice at once is fetched once. */
export function getJSON(path, query) {
  const url = path + qs(query);
  const pending = inflight.get(url);
  if (pending) return pending;
  const promise = fetch(url, { headers: { accept: 'application/json' } })
    .then(async (res) => {
      let body = null;
      try { body = await res.json(); } catch (e) { body = null; }
      if (!res.ok) throw new ApiError((body && body.error) || res.status + ' ' + res.statusText, res.status);
      return body;
    })
    .finally(() => { inflight.delete(url); });
  inflight.set(url, promise);
  return promise;
}

/** GET a text rendering (api.adoc#text-rendering): format=text, the body as it came. */
export function getText(path, query) {
  return fetch(path + qs({ ...query, format: 'text' }), { headers: { accept: 'text/markdown' } })
    .then(async (res) => {
      const body = await res.text();
      if (!res.ok) {
        let message = res.status + ' ' + res.statusText;
        try { message = JSON.parse(body).error || message; } catch (e) { /* the text is the message */ }
        throw new ApiError(message, res.status);
      }
      return body;
    });
}

// --- status and control -------------------------------------------------

/** POST with a JSON body; the agent-facing endpoints are the only writers. */
export function postJSON(path, body) {
  return fetch(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify(body || {}),
  }).then(async (res) => {
    let parsed = null;
    try { parsed = await res.json(); } catch (e) { parsed = null; }
    if (!res.ok) throw new ApiError((parsed && parsed.error) || res.status + ' ' + res.statusText, res.status);
    return parsed;
  });
}

export function status() { return getJSON('/api/status'); }

export async function refreshStatus() {
  state.status = await status();
  return state.status;
}

export function clearData() { return fetch('/api/data', { method: 'DELETE' }); }

export function exportUrl(extra = {}) {
  return '/api/export' + qs(extra.traceId ? { traceId: extra.traceId } : params(extra));
}

export function eventsUrl() { return '/api/events'; }

// --- reads --------------------------------------------------------------

export function overview(opts) { return getJSON('/api/overview', params({}, { ...opts, service: null })); }

export function services(opts) { return getJSON('/api/services', params({}, { ...opts, service: null })); }

export function service(name, opts) { return getJSON('/api/services/' + encodeURIComponent(name), params({}, { ...opts, service: null })); }

export function endpoints(extra, opts) { return getJSON('/api/endpoints', params(extra, opts)); }

export function endpoint(id, opts) { return getJSON('/api/endpoints/' + encodeURIComponent(id), params({}, { ...opts, service: null })); }

export function traces(extra, opts) { return getJSON('/api/traces', params(extra, opts)); }

export function trace(id) { return getJSON('/api/traces/' + encodeURIComponent(id)); }

export function scatter(extra, opts) { return getJSON('/api/scatter', params(extra, opts)); }

export function map(opts) { return getJSON('/api/map', params({}, { ...opts, service: null })); }

export function queries(extra, opts) { return getJSON('/api/queries', params(extra, opts)); }

export function query(id, opts) { return getJSON('/api/queries/' + encodeURIComponent(id), params({}, { ...opts, service: null })); }

export function errors(extra, opts) { return getJSON('/api/errors', params(extra, opts)); }

export function errorGroup(id, opts) { return getJSON('/api/errors/' + encodeURIComponent(id), params({}, { ...opts, service: null })); }

export function logs(extra, opts) { return getJSON('/api/logs', params(extra, opts)); }

export function metricCatalog(extra) {
  const q = {};
  const service = extra && extra.service !== undefined ? extra.service : state.service;
  if (service) q.service = service;
  return getJSON('/api/metrics', q);
}

export function metricSeries(extra, opts) { return getJSON('/api/metrics/series', params(extra, opts)); }

export function jvm(extra, opts) { return getJSON('/api/jvm', params(extra, opts)); }

// --- the agent-facing endpoints the UI also shows (api.adoc#agent-endpoints) ----------

export function findings(extra, opts) { return getJSON('/api/findings', params({ limit: 100, ...extra }, opts)); }

/** Accepts a known finding, so the list stays about what is new (findings.adoc#acknowledgements). */
export function ackFinding(id, note) {
  return postJSON('/api/findings/' + encodeURIComponent(id) + '/ack', { note: note || null });
}

/** Withdraws that; 404 means there was nothing to withdraw. */
export function unackFinding(id) {
  return fetch('/api/findings/' + encodeURIComponent(id) + '/ack', { method: 'DELETE' })
    .then((res) => {
      if (!res.ok) throw new ApiError(res.status + ' ' + res.statusText, res.status);
      return null;
    });
}

/** Marks a finding fixed; if it comes back it is a regression (findings.adoc#resolutions). */
export function resolveFinding(id, note) {
  return postJSON('/api/findings/' + encodeURIComponent(id) + '/resolve', { note: note || null });
}

/** Withdraws that; 404 means there was nothing to withdraw. */
export function unresolveFinding(id) {
  return fetch('/api/findings/' + encodeURIComponent(id) + '/resolve', { method: 'DELETE' })
    .then((res) => {
      if (!res.ok) throw new ApiError(res.status + ' ' + res.statusText, res.status);
      return null;
    });
}

export function acks(limit = 200) { return getJSON('/api/acks', { limit }); }

export function marks(limit = 50) { return getJSON('/api/marks', { limit }); }

export function createMark({ name, note, service } = {}) {
  return postJSON('/api/marks', { name, note: note || null, service: service || null });
}

/** The two windows are named by selectors, not by from/to, so the window is not sent. */
export function compare({ before, after, until } = {}, opts = {}) {
  const query = { before, after, until };
  const service = opts.service !== undefined ? opts.service : state.service;
  if (service) query.service = service;
  return getJSON('/api/compare', query);
}

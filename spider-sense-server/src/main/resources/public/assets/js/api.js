// Fetch wrappers for api.adoc, the shared top-bar state, and the window it implies.

export const RANGES = [
  { id: '5m', label: 'Last 5 min', ms: 5 * 60 * 1000 },
  { id: '15m', label: 'Last 15 min', ms: 15 * 60 * 1000 },
  { id: '1h', label: 'Last hour', ms: 60 * 60 * 1000 },
  { id: '6h', label: 'Last 6 hours', ms: 6 * 60 * 60 * 1000 },
  { id: 'all', label: 'All data', ms: null },
];

/** The range a hash without `range` means, and the one a link leaves out. */
export const DEFAULT_RANGE = '15m';

/**
 * The top-bar state every page reads. app.js writes it from the hash query and /api/status and
 * keeps `marks` fresh; the Compare page refreshes `marks` too when it opens (pages/compare.js).
 */
export const state = {
  service: '',
  range: DEFAULT_RANGE,
  live: false,
  /** The Requests | Load choice, '' when no page has made one (pages.adoc#services). */
  chart: '',
  status: null,
  /** The marks the shell keeps fresh, so every chart can draw them for free. */
  marks: [],
};

/** The part of the hash query every internal link carries. */
export function sharedQuery() {
  const q = {};
  if (state.service) q.service = state.service;
  if (state.range !== DEFAULT_RANGE) q.range = state.range;
  if (state.live) q.live = '1';
  if (state.chart) q.chart = state.chart;
  return q;
}

/** The range with this id, or the default range. */
export function rangeOf(id) {
  return RANGES[rangeIndex(id)];
}

/** The position of the range with this id in RANGES, or of the default range. */
export function rangeIndex(id) {
  const i = RANGES.findIndex((r) => r.id === id);
  return i < 0 ? RANGES.findIndex((r) => r.id === DEFAULT_RANGE) : i;
}

/**
 * The window the current range implies. `all` starts at /api/status.oldest.span, which the
 * shell re-reads before every route change and Live refresh under `all`; an empty store
 * answers 0 there, and then `all` is the default range.
 */
export function windowFor(range = state.range, now = Date.now()) {
  const r = rangeOf(range);
  if (r.ms == null) {
    const oldest = state.status && state.status.oldest ? state.status.oldest.span : 0;
    return { from: oldest > 0 ? oldest : now - rangeOf(DEFAULT_RANGE).ms, to: now };
  }
  return { from: now - r.ms, to: now };
}

/**
 * The service a read names: none with `omitService` (a read of one thing, which names its own),
 * `opts.service` when the caller gives one, and the top bar's otherwise.
 */
function serviceFor(opts = {}) {
  if (opts.omitService) return '';
  return opts.service !== undefined && opts.service !== null ? opts.service : state.service;
}

/** from/to/service plus whatever the caller adds; empty values are dropped (compactQuery). */
export function params(extra = {}, opts = {}) {
  const w = opts.window || windowFor();
  const out = { from: w.from, to: w.to };
  const service = serviceFor(opts);
  if (service) out.service = service;
  return { ...out, ...compactQuery(extra) };
}

/**
 * The entries that carry a value: undefined, null, '' and false are left out, so a query built
 * from optional filters names only the ones that are set. The hash and the API follow one rule.
 */
export function compactQuery(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj || {})) {
    if (v === undefined || v === null || v === '' || v === false) continue;
    out[k] = v;
  }
  return out;
}

/** `a=1&b=x`, the entries compactQuery keeps, in their order. */
export function queryString(obj) {
  return new URLSearchParams(Object.entries(compactQuery(obj)).map(([k, v]) => [k, String(v)])).toString();
}

function qs(obj) {
  const s = queryString(obj);
  return s ? '?' + s : '';
}

export class ApiError extends Error {
  constructor(message, status) { super(message); this.name = 'ApiError'; this.status = status; }
}

/**
 * One request and its answer's text. `query` goes into the URL as compactQuery leaves it and
 * `body` is sent as JSON. A status outside 2xx throws an ApiError whose message is the server's
 * `error` sentence when the answer carries one, and the status line otherwise, whatever the verb.
 */
async function request(method, path, { query, body, accept = 'application/json' } = {}) {
  const init = { method, headers: { accept } };
  if (body !== undefined) {
    init.headers['content-type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path + qs(query), init);
  const text = await res.text();
  if (!res.ok) throw errorFrom(res, text);
  return text;
}

function errorFrom(res, text) {
  let message = res.status + ' ' + res.statusText;
  try { message = JSON.parse(text).error || message; } catch (e) { /* no JSON body: the status line says it */ }
  return new ApiError(message, res.status);
}

/** The answer as JSON, or null for an empty or unreadable one. */
function parsed(text) {
  try { return JSON.parse(text); } catch (e) { return null; }
}

const inflight = new Map();

/** GET with in-flight de-duplication: the same URL asked twice at once is fetched once. */
export function getJSON(path, query) {
  const url = path + qs(query);
  const pending = inflight.get(url);
  if (pending) return pending;
  const promise = request('GET', url).then(parsed).finally(() => { inflight.delete(url); });
  inflight.set(url, promise);
  return promise;
}

/**
 * One stream of a page's loads, newest wins. Each call starts a request and returns a check that
 * stays true only until the next call starts one (page.js#pageLoader is the one user):
 *
 *   const startRequest = api.requestSequence();
 *   async function load() { const isNewest = startRequest(); const res = await api.x(); if (!isNewest()) return; ... }
 *
 * The window's `to` moves with the clock, so two loads of the same view are two URLs, and a slow
 * answer to an older range or filter would otherwise paint over the newer one.
 */
export function requestSequence() {
  let last = 0;
  return () => {
    const mine = ++last;
    return () => mine === last;
  };
}

/** GET a text rendering (api.adoc#text-rendering): format=text, the body as it came. */
export function getText(path, query) {
  return request('GET', path, { query: { ...query, format: 'text' }, accept: 'text/markdown' });
}

// --- status and control -------------------------------------------------

/** POST with a JSON body; the agent-facing endpoints are the only writers. */
export function postJSON(path, body) {
  return request('POST', path, { body: body || {} }).then(parsed);
}

/** DELETE, answering null; a refusal throws an ApiError as every other request does. */
function del(path) {
  return request('DELETE', path).then(() => null);
}

export function status() { return getJSON('/api/status'); }

export async function refreshStatus() {
  state.status = await status();
  return state.status;
}

/** DELETE /api/data; a refusal (a lock on the shared file, a Host check) throws an ApiError. */
export function clearData() {
  return del('/api/data');
}

export function exportUrl(extra = {}) {
  return '/api/export' + qs(extra.traceId ? { traceId: extra.traceId } : params(extra));
}

export function eventsUrl() { return '/api/events'; }

/** Where an application sends: the collector /api/status names, or this page's own origin before it answers. */
export function collectorBase() {
  return (state.status || {}).endpoint || location.origin;
}

// --- reads --------------------------------------------------------------

export function overview(opts) { return getJSON('/api/overview', params({}, { ...opts, omitService: true })); }

export function services(opts) { return getJSON('/api/services', params({}, { ...opts, omitService: true })); }

export function service(name, opts) { return getJSON('/api/services/' + encodeURIComponent(name), params({}, { ...opts, omitService: true })); }

export function endpoints(extra, opts) { return getJSON('/api/endpoints', params(extra, opts)); }

export function endpoint(id, opts) { return getJSON('/api/endpoints/' + encodeURIComponent(id), params({}, { ...opts, omitService: true })); }

export function traces(extra, opts) { return getJSON('/api/traces', params(extra, opts)); }

export function trace(id) { return getJSON('/api/traces/' + encodeURIComponent(id)); }

export function scatter(extra, opts) { return getJSON('/api/scatter', params(extra, opts)); }

export function map(opts) { return getJSON('/api/map', params({}, { ...opts, omitService: true })); }

export function queries(extra, opts) { return getJSON('/api/queries', params(extra, opts)); }

export function query(id, opts) { return getJSON('/api/queries/' + encodeURIComponent(id), params({}, { ...opts, omitService: true })); }

export function errors(extra, opts) { return getJSON('/api/errors', params(extra, opts)); }

export function errorGroup(id, opts) { return getJSON('/api/errors/' + encodeURIComponent(id), params({}, { ...opts, omitService: true })); }

export function logs(extra, opts) { return getJSON('/api/logs', params(extra, opts)); }

export function metricCatalog(extra) {
  return getJSON('/api/metrics', { service: serviceFor(extra) });
}

export function metricSeries(extra, opts) { return getJSON('/api/metrics/series', params(extra, opts)); }

export function jvm(extra, opts) { return getJSON('/api/jvm', params(extra, opts)); }

// --- the agent-facing endpoints the UI also shows (api.adoc#agent-endpoints) ----------

export function findings(extra, opts) { return getJSON('/api/findings', params({ limit: 100, ...extra }, opts)); }

const findingPath = (id, decision) => '/api/findings/' + encodeURIComponent(id) + '/' + decision;

/** Accepts a known finding, so the list stays about what is new (findings.adoc#acknowledgements). */
export function ackFinding(id, note) {
  return postJSON(findingPath(id, 'ack'), { note: note || null });
}

/** Withdraws that; 404 means there was nothing to withdraw. */
export function unackFinding(id) {
  return del(findingPath(id, 'ack'));
}

/** Marks a finding fixed; if it comes back it is a regression (findings.adoc#resolutions). */
export function resolveFinding(id, note) {
  return postJSON(findingPath(id, 'resolve'), { note: note || null });
}

/** Withdraws that; 404 means there was nothing to withdraw. */
export function unresolveFinding(id) {
  return del(findingPath(id, 'resolve'));
}

export function acks(limit = 200) { return getJSON('/api/acks', { limit }); }

export function marks(limit = 50) { return getJSON('/api/marks', { limit }); }

export function createMark({ name, note, service } = {}) {
  return postJSON('/api/marks', { name, note: note || null, service: service || null });
}

/** The two windows are named by selectors, not by from/to, so the window is not sent. */
export function compare({ before, after, until } = {}, opts = {}) {
  return getJSON('/api/compare', { before, after, until, service: serviceFor(opts) });
}

// The demo page's fetch and EventSource shim: every GET /api/… is answered from the
// DoltHub database that holds the recording (design.adoc#the-published-demo;
// ui.adoc#recording), named by <html data-dolthub="owner/database@ref"> or ?dolthub=….
//
// The database has the tables of storage.adoc#schema and one table of answers: what
// scripts/demo-site.mjs capture asked a Spider Sense loaded with those rows. The
// aggregations come from the answers; the lists that are rows of one table (the
// traces, one trace, the logs, the marks, the acknowledgements) are queried from the
// tables here, with the filters the API takes, so every trace of the recording opens
// and nothing of it is a substitute.
import { RANGES, DEFAULT_RANGE } from '../api.js';

const named = document.documentElement.dataset.dolthub
  || new URLSearchParams(location.search).get('dolthub') || 'benelog/spider-sense-demo@main';
const [database, ref = 'main'] = named.split('@');
const endpoint = 'https://www.dolthub.com/api/v1alpha1/' + database + '/' + ref + '?q=';
const page = 'https://www.dolthub.com/repositories/' + database;

const lit = (v) => "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'";
const num = (v) => (v === null || v === undefined || v === '' ? null : Number(v));
const bool = (v) => v === '1' || v === 'true' || v === true;
function json(text, fallback) {
  try { return text ? JSON.parse(text) : fallback; } catch (e) { return fallback; }
}

/** One read over the ref; DoltHub answers at most 1,000 rows. */
async function sql(query) {
  const res = await realFetch(endpoint + encodeURIComponent(query), { headers: { accept: 'application/json' } });
  const body = await res.json().catch(() => null);
  if (!res.ok || !body || body.query_execution_status === 'Error') {
    throw new Error('DoltHub: ' + ((body && body.query_execution_message) || res.status + ' ' + res.statusText));
  }
  return body.rows || [];
}

const realFetch = globalThis.fetch.bind(globalThis);

// --- the answers -------------------------------------------------------------------

const answers = new Map();

/** The answer's body as it was captured: the text, or null when it was not. */
async function answerText(key) {
  if (answers.has(key)) return answers.get(key);
  const rows = await sql('SELECT body FROM answer WHERE `key` = ' + lit(key));
  const value = rows.length ? rows[0].body : null;
  answers.set(key, value);
  return value;
}

async function answer(key) {
  return json(await answerText(key), null);
}

const manifest = await answer('/manifest');
if (!manifest) throw new Error('no /manifest in the answer table of ' + named);
const keys = new Set(manifest.keys || []);

// The default range becomes the recording's window, and is the window when the manifest names none.
const preset = RANGES.find((r) => r.id === DEFAULT_RANGE);
const frozenNow = (manifest.window && manifest.window.to) || Date.now();
const frozenFrom = (manifest.window && manifest.window.from) || frozenNow - preset.ms;
Date.now = () => frozenNow;

const minutes = Math.max(1, Math.round((frozenNow - frozenFrom) / 60000));
if (preset) {
  preset.ms = frozenNow - frozenFrom;
  preset.label = 'The recording (' + minutes + ' min)';
}

const FALLBACK = ['beforeId', 'before', 'q', 'minMs', 'maxMs', 'traceId', 'severity', 'status', 'endpointId',
  'step', 'rate', 'until', 'hideAcked', 'sort', 'limit', 'service'];

function keyOf(path, params) {
  const names = [...params.keys()].sort();
  const parts = [];
  for (const name of names) parts.push(name + '=' + params.get(name));
  return path + (parts.length ? '?' + parts.join('&') : '');
}

/** The answer's key: as asked, or the nearest one asked with a filter fewer. */
function resolve(u) {
  const params = new URLSearchParams(u.search);
  params.delete('from');
  params.delete('to');
  for (const key of [...params.keys()]) if (key.startsWith('attr.')) params.delete(key);
  const asked = keyOf(u.pathname, params);
  let key = asked;
  if (keys.has(key)) return { asked, key };
  for (const name of FALLBACK) {
    if (!params.has(name)) continue;
    params.delete(name);
    key = keyOf(u.pathname, params);
    if (keys.has(key)) {
      console.info('[demo] ' + asked + ' answered by ' + key);
      return { asked, key };
    }
  }
  return { asked, key: null };
}

// --- the rows: what the API lists from one table, queried as it queries it ------------

function windowOf(params) {
  const from = num(params.get('from'));
  const to = num(params.get('to'));
  return { from: from === null ? frozenFrom : from, to: to === null ? frozenNow : to };
}

const limitOf = (params, fallback) => Math.min(Math.max(num(params.get('limit')) || fallback, 1), 1000);

async function marks(params) {
  const rows = await sql('SELECT id, at_ms, name, service, note FROM mark ORDER BY at_ms DESC, id DESC LIMIT ' + limitOf(params, 50));
  return { marks: rows.map((r) => ({ id: num(r.id), at: num(r.at_ms), name: r.name, service: r.service, note: r.note })) };
}

async function acks(params) {
  const rows = await sql('SELECT finding_id, at_ms, note FROM ack WHERE NOT resolved ORDER BY at_ms DESC LIMIT ' + limitOf(params, 200));
  return { acks: rows.map((r) => ({ findingId: r.finding_id, at: num(r.at_ms), note: r.note })) };
}

function traceWhere(params, paging) {
  const w = windowOf(params);
  const where = ['t.start_ms BETWEEN ' + w.from + ' AND ' + w.to];
  const before = num(params.get('before'));
  const beforeId = params.get('beforeId');
  if (paging && before !== null) {
    where.push(beforeId
      ? '(t.start_ms < ' + before + ' OR (t.start_ms = ' + before + ' AND t.trace_id < ' + lit(beforeId) + '))'
      : 't.start_ms < ' + before);
  }
  const minMs = num(params.get('minMs'));
  if (minMs !== null) where.push('t.duration_ns >= ' + minMs + ' * 1000000');
  const maxMs = num(params.get('maxMs'));
  if (maxMs !== null) where.push('t.duration_ns <= ' + maxMs + ' * 1000000');
  if (params.get('status') === 'error') where.push('t.error');
  else if (params.get('status') === 'ok') where.push('NOT t.error');
  if (params.get('service')) {
    where.push('EXISTS (SELECT 1 FROM span s WHERE s.trace_id = t.trace_id AND s.service = ' + lit(params.get('service')) + ')');
  }
  if (params.get('endpointId')) {
    where.push('EXISTS (SELECT 1 FROM span s WHERE s.trace_id = t.trace_id AND s.endpoint_id = ' + lit(params.get('endpointId')) + ')');
  }
  const q = (params.get('q') || '').trim().toLowerCase();
  if (q) {
    const like = lit('%' + q + '%');
    where.push('EXISTS (SELECT 1 FROM span s WHERE s.trace_id = t.trace_id AND (LOWER(s.name) LIKE ' + like + ' OR LOWER(s.attributes) LIKE ' + like + '))');
  }
  return where.join(' AND ');
}

function traceSummary(r) {
  return {
    traceId: r.trace_id,
    start: num(r.start_ms),
    durationMs: num(r.duration_ns) / 1e6,
    rootName: r.root_name,
    rootService: r.root_service,
    rootKind: r.root_kind,
    services: json(r.services, []),
    spanCount: num(r.span_count),
    errorCount: num(r.error_count),
    dbCount: num(r.db_count),
    httpStatus: num(r.http_status),
    slow: bool(r.slow),
    error: bool(r.error),
  };
}

async function traces(params) {
  const [rows, count] = await Promise.all([
    sql('SELECT * FROM trace t WHERE ' + traceWhere(params, true) + ' ORDER BY t.start_ms DESC, t.trace_id DESC LIMIT ' + limitOf(params, 50)),
    sql('SELECT COUNT(*) AS n FROM trace t WHERE ' + traceWhere(params, false)),
  ]);
  return { traces: rows.map(traceSummary), total: num(count[0] && count[0].n) || 0, window: windowOf(params) };
}

/** SpanRecord.summary: the one line the waterfall shows. */
function summary(r) {
  if (r.category === 'db') {
    if (r.db_operation && r.db_table) return r.db_operation + ' ' + r.db_table;
    if (r.db_statement) return r.db_statement.length <= 120 ? r.db_statement : r.db_statement.slice(0, 120) + '…';
  } else if (r.category === 'http') {
    const url = r.kind === 'CLIENT' ? json(r.attributes, {})['url.full'] : null;
    const head = url ? r.name + ' ' + url : r.name;
    return r.http_status ? head + ' → ' + r.http_status : head;
  }
  return r.name;
}

function span(r) {
  return {
    spanId: r.span_id,
    parentSpanId: r.parent_span_id,
    service: r.service,
    name: r.name,
    kind: r.kind,
    start: num(r.start_ms),
    startNs: num(r.start_ns),
    durationMs: num(r.duration_ns) / 1e6,
    durationNs: num(r.duration_ns),
    status: r.status,
    statusMessage: r.status_message,
    attributes: json(r.attributes, {}),
    events: json(r.events, []).map((e) => ({ name: e.name, time: Math.floor((e.timeNs || 0) / 1e6), attributes: e.attributes || {} })),
    scope: r.scope,
    category: r.category,
    summary: summary(r),
    slow: bool(r.slow),
    error: bool(r.error),
  };
}

function log(r) {
  return {
    id: num(r.id),
    at: num(r.at_ms),
    service: r.service,
    severity: r.severity,
    severityNumber: num(r.severity_number),
    body: r.body,
    logger: r.logger,
    traceId: r.trace_id,
    spanId: r.span_id,
    attributes: json(r.attributes, {}),
  };
}

/** Parents before children, each group by start: the order the waterfall draws in. */
function sorted(spans) {
  const ids = new Set(spans.map((s) => s.spanId));
  const children = new Map();
  const roots = [];
  for (const s of spans) {
    if (!s.parentSpanId || !ids.has(s.parentSpanId)) roots.push(s);
    else {
      if (!children.has(s.parentSpanId)) children.set(s.parentSpanId, []);
      children.get(s.parentSpanId).push(s);
    }
  }
  const byStart = (a, b) => a.startNs - b.startNs;
  roots.sort(byStart);
  const ordered = [];
  const append = (s) => {
    ordered.push(s);
    for (const child of (children.get(s.spanId) || []).sort(byStart)) append(child);
  };
  for (const root of roots) append(root);
  return ordered;
}

async function trace(id) {
  const rows = await sql('SELECT * FROM span WHERE trace_id = ' + lit(id) + ' ORDER BY start_ns LIMIT 1000');
  if (!rows.length) return null;
  const logs = await sql('SELECT * FROM log WHERE trace_id = ' + lit(id) + ' ORDER BY at_ms, id LIMIT 1000');
  const spans = rows.map(span);
  let start = Infinity;
  let end = -Infinity;
  const services = [];
  for (const s of spans) {
    start = Math.min(start, s.startNs);
    end = Math.max(end, s.startNs + s.durationNs);
    if (!services.includes(s.service)) services.push(s.service);
  }
  return {
    traceId: id,
    start: Math.floor(start / 1e6),
    end: Math.floor(end / 1e6),
    durationMs: Math.max(0, end - start) / 1e6,
    services,
    spans: sorted(spans),
    logs: logs.map(log),
  };
}

const SEVERITY_FLOOR = { TRACE: 1, DEBUG: 5, INFO: 9, WARN: 13, ERROR: 17, FATAL: 21 };

function logWhere(params, paging) {
  const w = windowOf(params);
  const where = ['at_ms BETWEEN ' + w.from + ' AND ' + w.to];
  const before = num(params.get('before'));
  const beforeId = num(params.get('beforeId'));
  if (paging && before !== null) {
    where.push(beforeId !== null
      ? '(at_ms < ' + before + ' OR (at_ms = ' + before + ' AND id < ' + beforeId + '))'
      : 'at_ms < ' + before);
  }
  if (params.get('service')) where.push('service = ' + lit(params.get('service')));
  if (params.get('traceId')) where.push('trace_id = ' + lit(params.get('traceId')));
  const floor = SEVERITY_FLOOR[(params.get('severity') || '').toUpperCase()];
  if (floor) where.push('severity_number >= ' + floor);
  const q = (params.get('q') || '').trim().toLowerCase();
  if (q) {
    const like = lit('%' + q + '%');
    where.push('(LOWER(body) LIKE ' + like + ' OR LOWER(attributes) LIKE ' + like + ')');
  }
  return where.join(' AND ');
}

async function logs(params) {
  const [rows, count] = await Promise.all([
    sql('SELECT * FROM log WHERE ' + logWhere(params, true) + ' ORDER BY at_ms DESC, id DESC LIMIT ' + limitOf(params, 200)),
    sql('SELECT COUNT(*) AS n FROM log WHERE ' + logWhere(params, false)),
  ]);
  return { logs: rows.map(log), total: num(count[0] && count[0].n) || 0 };
}

// --- the shim ----------------------------------------------------------------------

function reply(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

globalThis.fetch = async (input, init) => {
  const url = typeof input === 'string' ? input : input.url;
  const u = new URL(url, location.href);
  if (!u.pathname.startsWith('/api/')) return realFetch(input, init);
  const method = ((init && init.method) || (typeof input !== 'string' && input.method) || 'GET').toUpperCase();
  if (method !== 'GET') return reply({ error: 'This is a recording of the demo; nothing on it can be changed.' }, 405);
  const params = u.searchParams;
  const path = u.pathname;
  try {
    if (path === '/api/marks') return reply(await marks(params));
    if (path === '/api/acks') return reply(await acks(params));
    if (path === '/api/traces') return reply(await traces(params));
    const one = /^\/api\/traces\/([0-9a-f]{32})$/.exec(path);
    if (one && !params.has('diff')) {
      const body = await trace(one[1]);
      return body ? reply(body) : reply({ error: 'No trace ' + one[1] + ' in the recording' }, 404);
    }
    if (path === '/api/logs') return reply(await logs(params));
    // The source files are on the machine the recording was made on, not in it.
    if (path === '/api/source') return reply({ error: 'No source in the recording' }, 404);
    const found = resolve(u);
    if (!found.key) {
      console.warn('[demo] not recorded: ' + found.asked);
      return reply({ error: 'Not in the recording: ' + found.asked }, 404);
    }
    // A text rendering (format=text, what Copy as Markdown asks for) is replied as it was captured.
    if (params.get('format') === 'text') {
      const text = await answerText(found.key);
      return text === null
        ? reply({ error: 'Not in the recording: ' + found.asked }, 404)
        : new Response(text, { status: 200, headers: { 'content-type': 'text/markdown; charset=utf-8' } });
    }
    const body = await answer(found.key);
    return body === null ? reply({ error: 'Not in the recording: ' + found.asked }, 404) : reply(body);
  } catch (e) {
    console.warn('[demo] ' + path + ': ' + e.message);
    return reply({ error: e.message }, 502);
  }
};

class SnapshotEventSource {
  constructor() { this.readyState = SnapshotEventSource.CLOSED; this.onerror = null; }
  addEventListener() {}
  removeEventListener() {}
  close() {}
}
SnapshotEventSource.CONNECTING = 0;
SnapshotEventSource.OPEN = 1;
SnapshotEventSource.CLOSED = 2;
globalThis.EventSource = SnapshotEventSource;

const note = document.createElement('div');
note.className = 'snapshot-note';
note.setAttribute('role', 'note');
const when = new Date(manifest.recordedAt || frozenNow);
note.append('A recording of the demo, captured ' + when.toISOString().slice(0, 16).replace('T', ' ')
  + ' UTC: the four example applications under one Spider Sense. Nothing here is live, and nothing can be changed.');
const link = document.createElement('a');
link.href = page;
link.target = '_blank';
link.rel = 'noopener';
link.textContent = 'this DoltHub database';
note.append(' Every page reads ', link, ' as you open it.');
const content = document.getElementById('content');
if (content) content.insertBefore(note, content.firstChild);
document.title = 'Spider Sense demo';

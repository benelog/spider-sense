#!/usr/bin/env node
// The recorded demo: `capture` asks a running Spider Sense every question the
// UI asks over one window and writes the answers as JSON files; `assemble`
// puts the UI and those files together as a static site whose fetch layer is
// assets/js/dev/replay.js. scripts/demo-site.sh runs the demo and both steps.
//
//   node scripts/demo-site.mjs capture [--url=http://127.0.0.1:4000] [--out=demo/data] [--minutes=15]
//   node scripts/demo-site.mjs assemble [<out dir>=build/demo-site] [--data=demo/data]

import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PUBLIC = join(root, 'spider-sense-server/src/main/resources/public');

const QUERY_SORTS = ['total', 'calls', 'avg', 'p95', 'max'];
const SEVERITIES = ['INFO', 'WARN', 'ERROR'];   // TRACE and DEBUG fall back to the unfiltered list, which they equal
const MAX_TRACES = 250;
const MAX_TRACE_BYTES = 300_000;   // a trace of thousands of spans is left out; the page says so
const MAX_TRACE_LOGS = 40;
const MAX_METRICS_PER_SERVICE = 120;
const CONCURRENCY = 4;

function options(args) {
  const out = { _: [] };
  for (const arg of args) {
    const m = /^--([^=]+)=(.*)$/.exec(arg);
    if (m) out[m[1]] = m[2];
    else out._.push(arg);
  }
  return out;
}

// --- capture -------------------------------------------------------------

async function capture(opts) {
  const url = (opts.url || 'http://127.0.0.1:4000').replace(/\/$/, '');
  const out = resolve(root, opts.out || 'demo/data');
  const minutes = Number(opts.minutes || 15);
  const to = Date.now();
  const from = to - minutes * 60 * 1000;
  const home = homedir();

  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });

  const files = {};
  let count = 0;
  const seenTraces = new Set();

  // The key is what replay.js computes from a request: the path plus the
  // query parameters other than the window, sorted by name.
  function keyOf(path, params) {
    const names = Object.keys(params).filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== '').sort();
    return path + (names.length ? '?' + names.map((k) => k + '=' + params[k]).join('&') : '');
  }

  async function get(path, params = {}, windowed = true, maxBytes = 0) {
    const key = keyOf(path, params);
    if (files[key]) return files[key].body;
    const usp = new URLSearchParams();
    if (windowed) { usp.set('from', String(from)); usp.set('to', String(to)); }
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined || v === null || v === '') continue;
      usp.set(k, String(v));
    }
    const full = url + path + (usp.size ? '?' + usp.toString() : '');
    const res = await fetch(full, { headers: { accept: 'application/json' } });
    let text = await res.text();
    if (!res.ok) {
      console.warn('skip ' + key + ': ' + res.status);
      return null;
    }
    if (maxBytes && text.length > maxBytes) {
      console.warn('skip ' + key + ': ' + text.length + ' bytes');
      return null;
    }
    text = text.split(home).join('/home/me');
    for (const id of text.matchAll(/\b[0-9a-f]{32}\b/g)) seenTraces.add(id[0]);
    const name = createHash('sha1').update(key).digest('hex').slice(0, 16) + '.json';
    writeFileSync(join(out, name), text);
    let body = null;
    try { body = JSON.parse(text); } catch (e) { body = null; }
    files[key] = { name, body };
    count++;
    return body;
  }

  async function each(items, fn) {
    const queue = [...items];
    const workers = [];
    for (let i = 0; i < CONCURRENCY; i++) {
      workers.push((async () => {
        while (queue.length) await fn(queue.shift());
      })());
    }
    await Promise.all(workers);
  }

  console.log('capturing ' + url + ' over the last ' + minutes + ' min into ' + out);

  const status = await get('/api/status', {}, false);
  if (!status) throw new Error('no Spider Sense at ' + url);
  await get('/api/marks', { limit: 50 }, false);
  await get('/api/acks', { limit: 200 }, false);
  const servicesRes = await get('/api/services');
  const services = (servicesRes && servicesRes.services || []).map((s) => s.name);
  await get('/api/overview');
  await get('/api/map');
  for (const name of services) await get('/api/services/' + encodeURIComponent(name));

  // The lists, unfiltered and then for each service, as the top bar's
  // service select sends them.
  const scopes = ['', ...services];
  const endpoints = new Map();
  const queries = new Set();
  const errors = new Set();
  const metrics = new Map();
  for (const service of scopes) {
    const s = { service };
    await get('/api/findings', { ...s, limit: 100 });
    await get('/api/findings', { ...s, limit: 5, hideAcked: 'true' });
    const eps = await get('/api/endpoints', s);
    for (const e of (eps && eps.endpoints) || []) endpoints.set(e.endpointId, e.service);
    for (const status of ['', 'ok', 'error']) await get('/api/traces', { ...s, limit: 50, status });
    await get('/api/scatter', { ...s, limit: 5000 });
    for (const sort of QUERY_SORTS) {
      const q = await get('/api/queries', { ...s, sort, limit: 100 });
      for (const row of (q && q.queries) || []) queries.add(row.queryId);
    }
    const errs = await get('/api/errors', { ...s, limit: 100 });
    for (const row of (errs && errs.errors) || []) errors.add(row.errorId);
    for (const severity of ['', ...SEVERITIES]) await get('/api/logs', { ...s, limit: 200, severity });
    await get('/api/jvm', s);
    const catalog = await get('/api/metrics', s, false);
    metrics.set(service, ((catalog && catalog.metrics) || []).map((m) => m.name).slice(0, MAX_METRICS_PER_SERVICE));
  }

  await each([...endpoints], async ([id, service]) => {
    await get('/api/endpoints/' + encodeURIComponent(id));
    await get('/api/traces', { service, endpointId: id, limit: 50 });
  });
  await each([...queries], (id) => get('/api/queries/' + encodeURIComponent(id)));
  await each([...errors], (id) => get('/api/errors/' + encodeURIComponent(id)));

  const series = [];
  for (const [service, names] of metrics) {
    for (const name of names) {
      series.push({ service, name });
      series.push({ service, name, rate: '1' });
    }
  }
  await each(series, (p) => get('/api/metrics/series', p));

  // Compare, for every ordered pair of marks the compare page can pick.
  const marksRes = files['/api/marks?limit=50'] && files['/api/marks?limit=50'].body;
  const marks = ((marksRes && marksRes.marks) || []).map((m) => m.name);
  const selectors = [...new Set([...marks, 'start'])];
  for (const service of scopes) {
    for (const before of selectors) {
      for (const after of selectors) {
        if (before === after) continue;
        await get('/api/compare', { before, after, service }, false);
      }
    }
  }

  // Every trace any answer mentioned, then the logs of the ones the lists show.
  const traceIds = [...seenTraces].slice(0, MAX_TRACES);
  await each(traceIds, (id) => get('/api/traces/' + id, {}, false, MAX_TRACE_BYTES));
  await each(traceIds.slice(0, MAX_TRACE_LOGS), (id) => get('/api/logs', { traceId: id, limit: 200 }));

  const manifest = {
    capturedAt: to,
    window: { from, to },
    version: status.version || null,
    services,
    files: Object.fromEntries(Object.entries(files).map(([k, v]) => [k, v.name])),
  };
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest, null, 1));
  console.log('captured ' + count + ' answers, ' + traceIds.length + ' traces, ' + services.length + ' services');
}

// --- assemble ------------------------------------------------------------

function assemble(opts) {
  const out = resolve(root, opts._[0] || 'build/demo-site');
  const data = resolve(root, opts.data || 'demo/data');
  readFileSync(join(data, 'manifest.json'));
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  cpSync(PUBLIC, out, { recursive: true });
  cpSync(data, join(out, 'data'), { recursive: true });
  const index = readFileSync(join(PUBLIC, 'index.html'), 'utf8')
    .replace('<html lang="en"', '<html lang="en" data-snapshot="data/"')
    .replace('<title>Spider Sense</title>', '<title>Spider Sense demo</title>');
  if (!index.includes('data-snapshot')) throw new Error('index.html has no <html lang="en" to mark');
  writeFileSync(join(out, 'index.html'), index);
  console.log('assembled ' + out + ' (open it through any static file server, e.g. python3 -m http.server -d ' + out + ')');
}

const opts = options(process.argv.slice(3));
const command = process.argv[2];
if (command === 'capture') await capture(opts);
else if (command === 'assemble') assemble(opts);
else {
  console.error('usage: demo-site.mjs capture [--url=] [--out=] [--minutes=] | assemble [<out dir>] [--data=]');
  process.exit(2);
}

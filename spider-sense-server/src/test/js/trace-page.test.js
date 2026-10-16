// pages/trace.js against traces the test controls: the waterfall, the profile and the span
// drawer read the trace's own numbers (pages.adoc#waterfall, #profile, #span-drawer).
import './browser-env.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const JS = '../../main/resources/public/assets/js/';
const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));
let api, router, ui, page;

/** The traces the server holds, by id. */
const traces = new Map();

globalThis.fetch = async (input) => {
  const url = new URL(input, 'http://localhost:4000');
  const id = decodeURIComponent(url.pathname.slice('/api/traces/'.length));
  const body = url.pathname.startsWith('/api/traces/') && traces.has(id) ? traces.get(id) : {};
  return new Response(JSON.stringify(body), { status: 200 });
};

before(async () => {
  api = await import(JS + 'api.js');
  router = await import(JS + 'router.js');
  ui = await import(JS + 'ui.js');
  page = await import(JS + 'pages/trace.js');
  router.register('/traces/:id');
  api.state.status = { thresholds: { slowRequestMs: 500, slowQueryMs: 100 }, counts: { traces: 1 } };
});

// Near the epoch, so a start in nanoseconds is exact in a double and the offsets come out whole.
const T0 = 1_000_000;

/** A span `at` ms into the trace, lasting `ms`. */
function span(spanId, parentSpanId, at, ms, extra = {}) {
  return {
    spanId, parentSpanId, service: 'web', name: spanId, kind: parentSpanId ? 'INTERNAL' : 'SERVER',
    start: T0 + Math.floor(at), startNs: (T0 + at) * 1e6, durationMs: ms, status: 'UNSET', statusMessage: null,
    attributes: {}, events: [], scope: 'io.opentelemetry.test', category: 'internal', summary: spanId,
    slow: false, error: false, ...extra,
  };
}

/** Puts a trace on the server and opens its page. */
async function open(id, durationMs, spans, query = {}, logs = []) {
  traces.set(id, { traceId: id, start: T0, end: T0 + durationMs, durationMs, services: ['web'], spans, logs });
  router.replace('/traces/' + id, query);
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params: { id }, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} });
  await settle();
  return { root, instance };
}

const textOf = (root, cls) => root.querySelectorAll('span').filter((s) => s.classList.contains(cls)).map((s) => s.textContent);

test('a trace under a millisecond is drawn and measured against its own duration', async () => {
  // Durations a double holds exactly, so the shares come out whole.
  const { root, instance } = await open('short', 0.5, [span('root', null, 0, 0.5), span('child', 'root', 0.125, 0.25)]);
  assert.deepEqual(root.querySelector('.wf-head').children.map((c) => c.textContent), ['Span', 'Timeline', '0.5 ms total']);
  const bars = root.querySelectorAll('span').filter((s) => s.classList.contains('wf-bar'));
  assert.deepEqual(bars.map((b) => b.style.width), ['100%', '50%']);
  assert.deepEqual(bars.map((b) => b.style.left), ['0%', '25%']);

  root.querySelectorAll('button').find((b) => b.textContent === 'Profile').click();
  const shares = root.querySelectorAll('tr.profile-row').map((tr) => tr.children[5].textContent);
  assert.deepEqual(shares, ['50.0', '50.0'], 'each self time is half of 0.5 ms');

  root.querySelector('tr.profile-row').click();
  const drawer = ui.drawerBody().querySelector('dl').children.map((c) => c.textContent);
  assert.equal(drawer[drawer.indexOf('duration') + 1], '0.5 ms · 100.0% of trace');
  assert.equal(drawer[drawer.indexOf('self') + 1], '0.3 ms · 50.0% of trace');
  instance.destroy();
});

test('the header counts one span, one error and one log in the singular', async () => {
  const log = { id: 'l1', at: T0 + 1, severity: 'ERROR', logger: 'web.Items', body: 'boom', attributes: {} };
  const { root, instance } = await open('single', 4, [span('root', null, 0, 4, { error: true, status: 'ERROR' })], {}, [log]);
  const counts = root.querySelector('.trace-head').querySelectorAll('span, a').map((e) => e.textContent).filter((t) => /^\d+ \w+$/.test(t));
  assert.deepEqual(counts, ['1 span', '1 error', '1 log']);
  instance.destroy();
});

test('a hot step keeps its slow or error tint, and a slow step has a turtle besides', async () => {
  // POST /items took 1,100 ms, its INSERT 700 ms, and a failed notify 300 ms: all three are hot.
  const { root, instance } = await open('hot', 1100, [
    span('post', null, 0, 1100, { summary: 'POST /items → 201', category: 'http', slow: true }),
    span('insert', 'post', 10, 700, { summary: 'INSERT items', category: 'db', slow: true }),
    span('notify', 'post', 720, 300, { summary: 'notify', error: true, status: 'ERROR' }),
  ], { view: 'profile' });
  const rows = root.querySelectorAll('tr.profile-row');
  const marks = (tr) => tr.className.split(' ').filter((c) => ['hot', 'warn', 'err'].includes(c)).sort().join(' ');
  assert.deepEqual(rows.map(marks), ['hot warn', 'hot warn', 'err hot']);
  const markers = (tr) => tr.querySelectorAll('span').filter((s) => s.classList.contains('marker')).map((s) => s.getAttribute('title'));
  assert.deepEqual(rows.map(markers), [['Slow'], ['Slow'], ['Error']]);
  instance.destroy();

  // With equal specificity the last rule wins, so the hot tint comes before the status tints.
  const css = readFileSync(new URL('../../main/resources/public/assets/app.css', import.meta.url), 'utf8');
  const at = (selector) => css.indexOf('\n' + selector + ' {');
  assert.ok(at('.profile-row.hot td') >= 0);
  assert.ok(at('.profile-row.hot td') < at('.profile-row.warn td'), 'a slow step that is hot too stays --warn');
  assert.ok(at('.profile-row.hot td') < at('.profile-row.err td'), 'a failed step that is hot too stays --err');
});

test('a profile step is slow as the server marks it, so a run of outbound calls the waterfall folds unmarked is unmarked', async () => {
  // Four outbound calls, one over slow.request.ms: a call is no request, so none is slow, and they fold.
  const call = (id, at, ms) => span(id, 'get', at, ms, { kind: 'CLIENT', category: 'http', summary: 'GET http://inventory/stock/{id} → 200' });
  const { root, instance } = await open('calls', 1000, [
    span('get', null, 0, 1000, { summary: 'GET /orders → 200', category: 'http', slow: true }),
    call('c1', 10, 100), call('c2', 120, 100), call('c3', 230, 700), call('c4', 940, 50),
  ], { view: 'profile' });
  const rows = root.querySelectorAll('tr.profile-row');
  assert.equal(rows.length, 2, 'the four calls are one run');
  const markers = (tr) => tr.querySelectorAll('span').filter((s) => s.classList.contains('marker')).map((s) => s.getAttribute('title'));
  assert.deepEqual(rows.map(markers), [['Slow'], []]);
  assert.ok(!rows[1].className.split(' ').includes('warn'), rows[1].className);
  instance.destroy();
});

test('a trace of no length draws its spans at the start and reads them as 0% of it', async () => {
  const { root, instance } = await open('instant', 0, [span('root', null, 0, 0)]);
  assert.equal(root.querySelector('.wf-head').children[2].textContent, '0.0 ms total');
  assert.equal(root.querySelector('span.wf-bar').style.left, '0%');
  root.querySelectorAll('button').find((b) => b.textContent === 'Profile').click();
  assert.equal(root.querySelector('tr.profile-row').children[5].textContent, '0.0');
  instance.destroy();
});

test('the span drawer gives the scope name last, after the attributes and the events', async () => {
  const failed = span('root', null, 0, 4, {
    error: true, status: 'ERROR', attributes: { 'http.route': '/items' },
    events: [{ name: 'exception', time: T0 + 2, attributes: { 'exception.type': 'java.lang.IllegalStateException' } }],
  });
  const { root, instance } = await open('scoped', 4, [failed]);
  root.querySelector('.wf-row').click();
  const blocks = ui.drawerBody().children;
  assert.ok(!blocks[0].textContent.includes('scope'), 'the first block holds the timing and the status, not the scope');
  assert.equal(blocks[blocks.length - 2].querySelector('.sub-head').textContent, 'Events');
  assert.equal(blocks[blocks.length - 1].textContent, 'scopeio.opentelemetry.test');
  instance.destroy();
});

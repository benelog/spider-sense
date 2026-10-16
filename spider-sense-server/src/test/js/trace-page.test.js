// pages/trace.js against traces the test controls: the waterfall, the profile and the span
// drawer read the trace's own numbers (pages.adoc#waterfall, #profile, #span-drawer).
import './browser-env.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';

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
async function open(id, durationMs, spans, query = {}) {
  traces.set(id, { traceId: id, start: T0, end: T0 + durationMs, durationMs, services: ['web'], spans, logs: [] });
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

test('a trace of no length draws its spans at the start and reads them as 0% of it', async () => {
  const { root, instance } = await open('instant', 0, [span('root', null, 0, 0)]);
  assert.equal(root.querySelector('.wf-head').children[2].textContent, '0.0 ms total');
  assert.equal(root.querySelector('span.wf-bar').style.left, '0%');
  root.querySelectorAll('button').find((b) => b.textContent === 'Profile').click();
  assert.equal(root.querySelector('tr.profile-row').children[5].textContent, '0.0');
  instance.destroy();
});

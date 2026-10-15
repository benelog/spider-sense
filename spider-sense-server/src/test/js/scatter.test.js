// pages/scatter.js: the linear axis' maximum, the Live merge, the selection's label, and the list under the chart (pages.adoc#scatter).
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { document } from './fake-dom.js';
import { scatterYMax, mergePoints, selectionLabel } from '../../main/resources/public/assets/js/pages/scatter.js';
import { scatterChart } from '../../main/resources/public/assets/js/charts.js';
import * as fmt from '../../main/resources/public/assets/js/format.js';

test('the axis runs to half again the p99 of the durations', () => {
  const ds = Array.from({ length: 100 }, (_, i) => i + 1);      // p99 is the 100th value
  assert.equal(scatterYMax(ds), 150);
  const outlier = Array.from({ length: 200 }, () => 20).concat([10000]);
  assert.equal(scatterYMax(outlier), 30, 'one outlier in 201 does not stretch the axis');
});

test('the durations may come in any order, and are left as they came', () => {
  const ds = [40, 10, 20];
  assert.equal(scatterYMax(ds), 60);
  assert.deepEqual(ds, [40, 10, 20]);
});

test('the axis is at least 10 ms, and 100 ms with no point', () => {
  assert.equal(scatterYMax([1, 2]), 10);
  assert.equal(scatterYMax([]), 100);
});

const point = (start, trace) => [start, 12.5, 'svc', 'GET /x', trace, 0];

test('a Live merge adds the new traces once and drops the ones before the window', () => {
  const on = [point(100, 'a'), point(200, 'b')];
  const { points, cut } = mergePoints(on, [point(200, 'b'), point(300, 'c')], 150);
  assert.deepEqual(points.map((p) => p[4]), ['b', 'c']);
  assert.equal(cut, false);
});

test('a Live merge keeps each request of a trace that crosses services, and each once', () => {
  // The callee ends first, so it is merged first; the caller comes with the same trace id a merge later.
  const callee = [1010, 20, 'silk-bookstore', 'GET /api/books/{id}', 't1', 0];
  const caller = [1000, 140, 'spring-orders', 'GET /orders/{id}', 't1', 2];
  const first = mergePoints([], [callee], 0);
  const second = mergePoints(first.points, [caller, callee], 0);
  assert.deepEqual(second.points.map((p) => p[2]).sort(), ['silk-bookstore', 'spring-orders']);
  // The trace's slow-query flag can come on between two fetches; the point is still the same one.
  const third = mergePoints(second.points, [caller, [1010, 20, 'silk-bookstore', 'GET /api/books/{id}', 't1', 4]], 0);
  assert.equal(third.points.length, 2);
});

test('a Live merge keeps the newest points up to the limit and says it cut', () => {
  const on = [point(300, 'c'), point(100, 'a'), point(200, 'b')];
  const { points, cut } = mergePoints(on, [point(400, 'd')], 0, 3);
  assert.deepEqual(points.map((p) => p[4]).sort(), ['b', 'c', 'd'], 'the oldest goes first');
  assert.equal(cut, true);
});

test('the scatter draws more points than a call takes arguments', () => {
  const n = 200000;
  const points = Array.from({ length: n }, (_, i) => point(1000 + i, 't' + i));
  const container = document.createElement('div');
  assert.doesNotThrow(() => scatterChart(container, { points, window: { from: 0, to: 1000 + n }, logScale: true }).destroy());
});

test('a dot\'s tooltip names the day of a request before today', () => {
  const at = Date.now() - 2 * 86400000;
  const p = [at, 40, 'svc', 'GET /x', 'a', 0];
  const container = document.createElement('div');
  const chart = scatterChart(container, { points: [p], window: { from: at - 60000, to: at + 60000 } });
  // the fake plot places a value at itself, so the pointer goes where the point's values are
  chart.plot.over.dispatch('mousemove', { clientX: at / 1000, clientY: 40 });
  const tip = container.querySelector('.chart-tip').innerHTML;
  assert.ok(tip.includes(fmt.day(at) + ' ' + fmt.clock(at)), tip);
  chart.destroy();
});

test('an empty list with no rectangle drawn says the window holds no trace, not the selection', async () => {
  const JS = '../../main/resources/public/assets/js/';
  globalThis.fetch = async (input) => {
    const path = new URL(input, 'http://localhost:4000').pathname;
    const body = path === '/api/scatter' ? { points: [] } : path === '/api/traces' ? { traces: [], total: 0 } : {};
    return new Response(JSON.stringify(body), { status: 200 });
  };
  const api = await import(JS + 'api.js');
  const router = await import(JS + 'router.js');
  const page = await import(JS + 'pages/scatter.js');
  api.state.status = { counts: { spans: 10, traces: 5 } };
  router.register('/scatter');
  router.replace('/scatter', {});
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params: {}, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} });
  await new Promise((r) => setTimeout(r, 30));
  assert.deepEqual(root.querySelectorAll('tr.empty-row').map((tr) => tr.textContent), ['No trace in this window.']);
  instance.destroy();
});

test('the selection label reads the clock within today and names the day of an end outside it', () => {
  const now = new Date(2026, 9, 15, 15, 0, 0).getTime();
  const sel = (from, to) => ({ from, to, minMs: 10, maxMs: 250 });
  const span = ', ' + fmt.dur(10) + ' – ' + fmt.dur(250);
  const today = sel(new Date(2026, 9, 15, 14, 2, 10).getTime(), new Date(2026, 9, 15, 14, 5, 40).getTime());
  assert.equal(selectionLabel(today, now), '14:02:10 – 14:05:40' + span);
  const yesterday = sel(new Date(2026, 9, 14, 14, 2, 10).getTime(), new Date(2026, 9, 14, 14, 5, 40).getTime());
  assert.equal(selectionLabel(yesterday, now), 'Oct 14 14:02:10 – Oct 14 14:05:40' + span);
  const across = sel(new Date(2026, 9, 14, 23, 58, 0).getTime(), new Date(2026, 9, 15, 0, 2, 0).getTime());
  assert.equal(selectionLabel(across, now), 'Oct 14 23:58:00 – 00:02:00' + span, 'only the end outside today names its day');
});

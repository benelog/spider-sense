// pages/metrics.js: a series' name in the legend, and the metric the URL names (pages.adoc#metrics).
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { labelOf } from '../../main/resources/public/assets/js/pages/metrics.js';

test('a series is named by its attributes, less the jvm. prefix', () => {
  const s = { service: 'orders', attributes: { 'jvm.memory.pool.name': 'G1 Eden Space', type: 'heap' } };
  assert.equal(labelOf(s, 'orders'), 'memory.pool.name=G1 Eden Space type=heap');
});

test('a series without attributes is named by its service, else value', () => {
  assert.equal(labelOf({ service: 'orders' }, 'orders'), 'orders');
  assert.equal(labelOf({}, 'orders'), 'value');
});

test('with every service shown, the service leads the name', () => {
  assert.equal(labelOf({ service: 'orders', attributes: { type: 'heap' } }, ''), 'orders · type=heap');
  assert.equal(labelOf({ attributes: { type: 'heap' } }, ''), 'type=heap');
});

test('a metric the URL names and the catalog lacks gives way in the URL too', async () => {
  const JS = '../../main/resources/public/assets/js/';
  globalThis.fetch = async (input) => {
    const path = new URL(input, 'http://localhost:4000').pathname;
    const body = path === '/api/metrics'
      ? { metrics: [{ name: 'http.server.request.duration', type: 'histogram', unit: 's', series: 1 }] }
      : { series: [] };
    return new Response(JSON.stringify(body), { status: 200 });
  };
  const router = await import(JS + 'router.js');
  const page = await import(JS + 'pages/metrics.js');
  router.register('/metrics');
  router.replace('/metrics', { metric: 'no.such.metric' });
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params: {}, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} });
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(router.currentRoute().query.metric, 'http.server.request.duration');
  instance.destroy();
});

test('a theme flip leaves the error box or the empty state that took the chart\'s place', async () => {
  const JS = '../../main/resources/public/assets/js/';
  let answer = 'points';
  globalThis.fetch = async (input) => {
    const path = new URL(input, 'http://localhost:4000').pathname;
    if (path === '/api/metrics') return new Response(JSON.stringify({ metrics: [{ name: 'queue.depth', type: 'gauge', unit: '', series: 1 }] }), { status: 200 });
    if (answer === 'failure') return new Response(JSON.stringify({ error: 'the store is closed' }), { status: 500 });
    const t = [Date.now() - 60000, Date.now()];
    return new Response(JSON.stringify({ name: 'queue.depth', type: 'gauge', unit: '', series: answer === 'points' ? [{ service: 'orders', t, v: [3, 4] }] : [] }), { status: 200 });
  };
  const router = await import(JS + 'router.js');
  const charts = await import(JS + 'charts.js');
  const page = await import(JS + 'pages/metrics.js');
  router.register('/metrics');
  router.replace('/metrics', { metric: 'queue.depth' });
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params: {}, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} });
  const body = () => root.querySelector('div.chart');
  const shows = async (what, done) => {
    for (let waited = 0; !done(); waited += 10) {
      if (waited > 2000) assert.fail('timed out waiting for ' + what);
      await new Promise((r) => setTimeout(r, 10));
    }
  };
  const plotted = () => !!(body().children[0] || {}).plot;
  await shows('the chart', plotted);
  answer = 'failure';
  instance.refresh();
  await shows('the error box', () => body().querySelector('.error-box'));
  charts.retheme();
  assert.ok(body().querySelector('.error-box') && !plotted(), 'the error box stays where the chart was');
  answer = 'points';
  instance.refresh();
  await shows('the chart again', plotted);
  answer = 'none';
  instance.refresh();
  await shows('the empty state', () => body().querySelector('.empty-state'));
  charts.retheme();
  assert.ok(body().querySelector('.empty-state') && !plotted(), 'the empty state stays where the chart was');
  instance.destroy();
});

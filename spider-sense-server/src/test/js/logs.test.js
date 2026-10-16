// page.js: the Live-tail merge after Load more, as the Logs page keys it, and the Logs page's
// own Live tail (pages.adoc#logs).
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeNewestPage } from '../../main/resources/public/assets/js/page.js';

const JS = '../../main/resources/public/assets/js/';
const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));
const log = (id, at) => ({ id, at });

test('the newest page lands on top of the loaded rows, newest first, without a duplicate', () => {
  const rows = [log(3, 300), log(2, 200), log(1, 100)];
  const incoming = [log(5, 500), log(4, 400), log(3, 300)];
  assert.deepEqual(mergeNewestPage(rows, incoming, 0, 3).map((l) => l.id), [5, 4, 3, 2, 1]);
});

test('rows the window has left are dropped', () => {
  const rows = [log(2, 200), log(1, 100)];
  assert.deepEqual(mergeNewestPage(rows, [log(3, 300), log(2, 200)], 150, 10).map((l) => l.id), [3, 2]);
});

test('rows at the same instant are ordered by id', () => {
  const rows = [log(1, 100)];
  assert.deepEqual(mergeNewestPage(rows, [log(2, 100), log(1, 100)], 0, 10).map((l) => l.id), [2, 1]);
});

test('a row the newest page holds again replaces the loaded one, as a trace whose spans arrived since does (pages.adoc#traces-list)', () => {
  const trace = (traceId, start, spanCount) => ({ traceId, start, spanCount });
  const rows = [trace('e', 300, 1), trace('d', 200, 1), trace('c', 100, 4)];
  const incoming = [trace('f', 400, 2), trace('e', 250, 3)];
  const merged = mergeNewestPage(rows, incoming, 0, 50, (r) => r.start, (r) => r.traceId);
  assert.deepEqual(merged.map((r) => [r.traceId, r.start, r.spanCount]), [['f', 400, 2], ['e', 250, 3], ['d', 200, 1], ['c', 100, 4]],
    'the root that came later moved its start, and the row keeps its place by it');
});

test('a full page that holds none of the loaded rows would open a gap, so it is not merged', () => {
  const rows = [log(1, 100)];
  assert.equal(mergeNewestPage(rows, [log(3, 300), log(2, 200)], 0, 2), null);
  assert.deepEqual(mergeNewestPage(rows, [log(2, 200)], 0, 2).map((l) => l.id), [2, 1]);
});

test('a change of service or range reloads the list while a row is open and the page is scrolled down (pages.adoc#logs)', async () => {
  const asked = [];
  globalThis.fetch = async (input) => {
    const url = new URL(input, 'http://localhost:4000');
    asked.push(url.searchParams);
    const service = url.searchParams.get('service');
    const logs = ['web', 'web', 'books', 'books']
      .filter((s) => !service || s === service)
      .map((s, i) => ({ id: i + 1, at: Date.now() - 1000 * (i + 1), service: s, severity: 'INFO', body: s + ' ' + i }));
    return new Response(JSON.stringify({ logs, total: logs.length }), { status: 200 });
  };
  const api = await import(JS + 'api.js');
  const router = await import(JS + 'router.js');
  const page = await import(JS + 'pages/logs.js');
  router.register('/logs');
  router.replace('/logs', {});
  api.state.status = { counts: { logs: 4 } };
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params: {}, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} });
  await settle();
  const services = () => root.querySelectorAll('tbody tr').filter((tr) => !tr.classList.contains('log-detail'))
    .map((tr) => tr.querySelector('.chip').textContent);
  assert.deepEqual(services(), ['web', 'web', 'books', 'books']);
  root.querySelector('tbody tr').click();
  globalThis.scrollY = 600;
  try {
    asked.length = 0;
    instance.refresh();                      // a Live tick holds the tail under the open row
    await settle();
    assert.equal(asked.length, 0);
    api.state.service = 'books';
    instance.refresh();                      // what a change of the top bar's service does
    await settle();
    assert.deepEqual(asked.map((q) => q.get('service')), ['books']);
    assert.deepEqual(services(), ['books', 'books']);
    api.state.range = '1h';
    instance.refresh();
    await settle();
    assert.equal(asked.length, 2, 'a change of range reloads too');
  } finally {
    globalThis.scrollY = 0;
    api.state.service = '';
    api.state.range = api.DEFAULT_RANGE;
    instance.destroy();
  }
});

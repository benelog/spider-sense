// pages/compare.js: the tiles' verdict, by the API's rule (marks-and-compare.adoc#verdicts), and
// the page against answers the test controls.
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verdictOf } from '../../main/resources/public/assets/js/pages/compare.js';

const JS = '../../main/resources/public/assets/js/';
const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));

test('a p95 is worse when it grew by more than a fifth and by at least 10 ms', () => {
  assert.equal(verdictOf('p95', 40, 50), 'worse');
  assert.equal(verdictOf('p95', 40, 49.9), 'same');
  assert.equal(verdictOf('p95', 100, 120), 'same');
  assert.equal(verdictOf('p95', 100, 120.1), 'worse');
  assert.equal(verdictOf('p95', 1, 9), 'same');
});

test('a p95 is better when it shrank by the same bounds', () => {
  assert.equal(verdictOf('p95', 50, 40), 'better');
  assert.equal(verdictOf('p95', 49.9, 40), 'same');
});

test('errors are worse when they grew and better when they fell', () => {
  assert.equal(verdictOf('errors', 1, 2), 'worse');
  assert.equal(verdictOf('errors', 2, 1), 'better');
  assert.equal(verdictOf('errors', 2, 2), 'same');
});

test('an Apdex is worse when it fell at the two decimals the tile shows', () => {
  assert.equal(verdictOf('apdex', 0.95, 0.9), 'worse');
  assert.equal(verdictOf('apdex', 0.9, 0.95), 'better');
  assert.equal(verdictOf('apdex', 0.95, 0.948), 'same');
  assert.equal(verdictOf('apdex', 0.925, 0.9205), 'worse', '0.93 → 0.92 is a fall');
  assert.equal(verdictOf('apdex', 0.935, 0.9305), 'worse', '0.94 → 0.93 is a fall');
  assert.equal(verdictOf('apdex', 0.9205, 0.925), 'better', '0.92 → 0.93 is a rise');
  assert.equal(verdictOf('apdex', 0.926, 0.934), 'same', '0.93 → 0.93 is no change, though it moved 0.008');
});

test('a side without data has no verdict', () => {
  assert.equal(verdictOf('p95', null, 50), 'same');
  assert.equal(verdictOf('errors', 3, undefined), 'same');
});

// --- the page --------------------------------------------------------------------------

const server = { marks: [], totals: null };

function answer(path) {
  if (path === '/api/marks') return { marks: server.marks };
  if (path === '/api/compare') return { totals: server.totals, endpoints: [], queries: [], errors: [] };
  return {};
}

async function openCompare() {
  globalThis.fetch = async (input) => new Response(JSON.stringify(answer(new URL(input, 'http://localhost:4000').pathname)), { status: 200 });
  const api = await import(JS + 'api.js');
  const router = await import(JS + 'router.js');
  const page = await import(JS + 'pages/compare.js');
  router.register('/compare');
  router.replace('/compare', {});
  api.state.marks = server.marks;
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const ctx = { params: {}, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} };
  const instance = page.render(root, ctx);
  await settle();
  return { root, instance };
}

const twoMarks = [{ id: 2, at: 2000, name: 'after' }, { id: 1, at: 1000, name: 'before' }];
const someTotals = { requests: 10, errors: 0, p95Ms: 50, apdex: 1 };

test('a Live tick leaves the mark selects alone when the marks did not change, so an open list stays open', async () => {
  server.marks = twoMarks;
  server.totals = { before: someTotals, after: someTotals };
  const { root, instance } = await openCompare();
  const selects = root.querySelectorAll('select');
  const first = selects.map((s) => s.children.slice());
  instance.refresh();
  await settle();
  selects.forEach((s, i) => assert.ok(s.children.every((o, j) => o === first[i][j]), s.getAttribute('aria-label') + ' kept its options'));
  assert.deepEqual(selects.map((s) => s.value), ['before', 'after', 'now']);
  instance.destroy();
});

// pages/findings.js: the impact column, the evidence numbers and the cut list (pages.adoc#findings).
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { impactText, numberText } from '../../main/resources/public/assets/js/pages/findings.js';
import { bothTimes } from '../../main/resources/public/assets/js/format.js';

test('each kind is ranked by its own number, with its unit in words in the title', () => {
  assert.deepEqual(impactText({ kind: 'error', numbers: { count: 1234 } }), { text: '1,234 times', cls: 'bad', title: '1,234 occurrences' });
  assert.deepEqual(impactText({ kind: 'log-error', numbers: { count: 3 } }), { text: '3 times', cls: 'bad', title: '3 error logs' });
  assert.deepEqual(impactText({ kind: 'error', numbers: { count: 1 } }), { text: '1 time', cls: 'bad', title: '1 occurrence' });
  assert.deepEqual(impactText({ kind: 'n-plus-one', numbers: { medianRepeats: 12, affected: 3 } }),
    { text: '12 × 3', title: '12 repeats (the median) × 3 affected requests' });
  assert.deepEqual(impactText({ kind: 'pool-exhausted', numbers: { pendingMax: 1 } }),
    { text: '1 pending', title: '1 request waiting for a connection at the peak' });
  assert.deepEqual(impactText({ kind: 'gc-pause', numbers: { worstMs: 312.4 } }), { text: '312 ms', title: '312 ms, the longest pause' });
  assert.deepEqual(impactText({ kind: 'heap-pressure', numbers: { ratioMax: 0.62 } }), { text: '62.0%', title: '62.0% of the heap limit at the peak' });
  assert.deepEqual(impactText({ kind: 'thread-growth', numbers: { first: 20, last: 32 } }), { text: '+12 threads', title: '+12 threads over the window' });
  assert.deepEqual(impactText({ kind: 'slow-query', numbers: { totalMs: 1500 } }), { text: '1,500 ms', title: '1,500 ms in total' });
});

test('a regression is ranked by the number of the kind it was', () => {
  assert.deepEqual(impactText({ kind: 'regression', numbers: { originalKind: 'error', count: 2 } }), { text: '2 times', cls: 'bad', title: '2 occurrences' });
});

test('usedMax is bytes on a heap-pressure and connections on a pool-exhausted', () => {
  assert.deepEqual(numberText('usedMax', 512 * 1024 * 1024, 'heap-pressure'), { text: '512 MiB' });
  assert.deepEqual(numberText('usedMax', 10, 'pool-exhausted'), { text: '10' });
});

test('a number takes the unit its key says', () => {
  assert.deepEqual(numberText('p95Ms', 312.4, 'slow-request'), { text: '312 ms' });
  assert.deepEqual(numberText('dbMsPerRequest', 1500, 'slow-request'), { text: '1,500 ms' });
  assert.deepEqual(numberText('dbCallsPerRequest', 2.5, 'slow-request'), { text: '2.50' });
  assert.deepEqual(numberText('dbShare', 0.62, 'slow-request'), { text: '62.0%' });
  assert.deepEqual(numberText('apdex', 0.9, 'slow-request'), { text: '0.90' });
  assert.deepEqual(numberText('max', 1234, 'thread-growth'), { text: '1,234' });
});

test('an absent value is a muted dash, a string is monospaced, a moment carries both times', () => {
  assert.deepEqual(numberText('p95Ms', null, 'slow-request'), { text: '-', cls: 'muted' });
  assert.deepEqual(numberText('gc', 'G1 Young Generation', 'gc-pause'), { text: 'G1 Young Generation', cls: 'mono' });
  assert.equal(numberText('lastSeen', 1_700_000_000_000, 'error').title, bothTimes(1_700_000_000_000));
});

test('a list the limit cut says how many findings there are', async () => {
  const JS = '../../main/resources/public/assets/js/';
  const answer = { findings: [], total: 0, requests: 10 };
  globalThis.fetch = async () => new Response(JSON.stringify(answer), { status: 200 });
  const api = await import(JS + 'api.js');
  const page = await import(JS + 'pages/findings.js');
  api.state.service = '';
  answer.findings = Array.from({ length: 100 }, (_, i) => ({
    id: 'f' + i, kind: 'slow-query', severity: 'warn', state: 'new', service: 'orders', title: 'Slow query ' + i, numbers: { totalMs: 100 },
  }));
  answer.total = 154;
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params: {}, initialQuery: {}, query: () => ({}), setTitle() {}, navigate() {} });
  await new Promise((r) => setTimeout(r, 30));
  const note = root.querySelector('.panel-note');
  assert.equal(note.hidden, false);
  assert.equal(note.textContent, '100 of 154 findings; a service or a shorter range shows the rest.');
  answer.total = 100;
  instance.refresh();
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(note.hidden, true, 'a whole list says nothing');
  instance.destroy();
});

// widgets.js: where a finding points (pages.adoc#findings).
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findingTarget, queryTarget, traceTable } from '../../main/resources/public/assets/js/widgets.js';

const shared = { service: 'orders', range: '1h' };

test('a finding points at its subject page, the id encoded', () => {
  assert.deepEqual(findingTarget({ subject: { endpointId: 'GET /a/{id}' } }, shared), { path: '/endpoints/GET%20%2Fa%2F%7Bid%7D', query: shared, label: 'Endpoint' });
  assert.deepEqual(findingTarget({ subject: { queryId: 'q1' } }, shared), { path: '/queries/q1', query: shared, label: 'Query' });
  assert.deepEqual(findingTarget({ subject: { errorId: 'e1' } }, shared), { path: '/errors/e1', query: shared, label: 'Error' });
});

test('an N+1 points at its endpoint, and its statement is a second place to go', () => {
  const finding = { kind: 'n-plus-one', subject: { endpointId: 'e', queryId: 'q1' } };
  assert.equal(findingTarget(finding, shared).label, 'Endpoint');
  assert.deepEqual(queryTarget(finding, shared), { path: '/queries/q1', query: shared, label: 'Query' });
  assert.equal(queryTarget({ subject: { endpointId: 'e' } }, shared), null);
});

test('a pool or JVM finding points at the JVM page of its service', () => {
  assert.deepEqual(findingTarget({ service: 'books', subject: { pool: 'HikariPool-1' } }, shared),
    { path: '/jvm', query: { service: 'books', range: '1h' }, label: 'JVM' });
  assert.deepEqual(findingTarget({ subject: { jvm: true } }, shared), { path: '/jvm', query: shared, label: 'JVM' });
});

test('a log-error finding points at the error logs of its logger', () => {
  assert.deepEqual(findingTarget({ service: 'books', subject: { logger: 'com.example.Books' } }, {}),
    { path: '/logs', query: { service: 'books', severity: 'ERROR', q: 'com.example.Books' }, label: 'Logs' });
});

test('a finding with no page of its own points at its first trace, or nowhere', () => {
  assert.deepEqual(findingTarget({ subject: { job: 'nightly' }, traces: ['t1', 't2'] }, {}), { path: '/traces/t1', query: {}, label: 'Trace' });
  assert.equal(findingTarget({ subject: {} }, {}), null);
});

test('the slowest of a group show the span they are ordered by, with the bar on it (pages.adoc#endpoints)', () => {
  const row = (traceId, durationMs, matchMs) => ({ traceId, start: 1_700_000_000_000, rootName: 'GET /report', services: ['orders'], durationMs, matchMs, spanCount: 2 });
  const node = traceTable([row('a', 900, 850), row('b', 3000, 80)], { match: { label: 'Query', title: 'The query' } });
  assert.deepEqual(node.querySelectorAll('th').map((th) => th.textContent).slice(0, 4), ['Time', 'Root', 'Query', 'Duration']);
  const cells = (i) => node.querySelectorAll('tbody tr')[i].querySelectorAll('td').map((td) => td.textContent);
  assert.deepEqual(cells(0).slice(2, 4), ['850 ms', '900 ms']);
  assert.deepEqual(cells(1).slice(2, 4), ['80.0 ms', '3,000 ms']);
  const bars = node.querySelectorAll('.dbar-fill').map((bar) => bar.style.width);
  assert.equal(bars.length, 2, 'one bar a row, on the matching span');
  assert.equal(bars[0], '100%');
  assert.ok(parseFloat(bars[1]) < 10, bars[1]);
  const plain = traceTable([row('a', 900, 850)]);
  assert.ok(!plain.querySelectorAll('th').some((th) => th.textContent === 'Query'));
});

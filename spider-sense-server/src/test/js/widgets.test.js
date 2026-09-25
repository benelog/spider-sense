// widgets.js: where a finding points (pages.adoc#findings).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findingTarget } from '../../main/resources/public/assets/js/widgets.js';

const shared = { service: 'orders', range: '1h' };

test('a finding points at its subject page, the id encoded', () => {
  assert.deepEqual(findingTarget({ subject: { endpointId: 'GET /a/{id}' } }, shared), { path: '/endpoints/GET%20%2Fa%2F%7Bid%7D', query: shared });
  assert.deepEqual(findingTarget({ subject: { queryId: 'q1' } }, shared), { path: '/queries/q1', query: shared });
  assert.deepEqual(findingTarget({ subject: { errorId: 'e1' } }, shared), { path: '/errors/e1', query: shared });
});

test('a pool or JVM finding points at the JVM page of its service', () => {
  assert.deepEqual(findingTarget({ service: 'books', subject: { pool: 'HikariPool-1' } }, shared),
    { path: '/jvm', query: { service: 'books', range: '1h' } });
  assert.deepEqual(findingTarget({ subject: { jvm: true } }, shared), { path: '/jvm', query: shared });
});

test('a log-error finding points at the error logs of its logger', () => {
  assert.deepEqual(findingTarget({ service: 'books', subject: { logger: 'com.example.Books' } }, {}),
    { path: '/logs', query: { service: 'books', severity: 'ERROR', q: 'com.example.Books' } });
});

test('a finding with no page of its own points at its first trace, or nowhere', () => {
  assert.deepEqual(findingTarget({ subject: { job: 'nightly' }, traces: ['t1', 't2'] }, {}), { path: '/traces/t1', query: {} });
  assert.equal(findingTarget({ subject: {} }, {}), null);
});

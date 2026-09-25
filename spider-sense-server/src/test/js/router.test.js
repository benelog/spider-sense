// router.js: the hash as a path and a query (ui.adoc#urls).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parse, href, detailPath, detailHref } from '../../main/resources/public/assets/js/router.js';
import { state } from '../../main/resources/public/assets/js/api.js';

test('parse splits the hash into a path and a query', () => {
  assert.deepEqual(parse('#/traces/abc?service=orders&range=1h'), { path: '/traces/abc', query: { service: 'orders', range: '1h' } });
  assert.deepEqual(parse('#/'), { path: '/', query: {} });
  assert.deepEqual(parse(''), { path: '/', query: {} });
  assert.deepEqual(parse('services'), { path: '/services', query: {} });
});

test('parse drops a trailing slash but not the root', () => {
  assert.equal(parse('#/services/').path, '/services');
  assert.equal(parse('#/').path, '/');
});

test('parse keeps the path encoded and decodes the query once', () => {
  const parsed = parse('#/endpoints/GET%20%2Fbooks%2F%7Bid%7D?q=50%25%20off');
  assert.equal(parsed.path, '/endpoints/GET%20%2Fbooks%2F%7Bid%7D');
  assert.equal(parsed.query.q, '50% off');
});

test('href drops empty values and false', () => {
  assert.equal(href('/traces', { service: 'orders', q: '', live: false, x: null, y: undefined }), '#/traces?service=orders');
  assert.equal(href('/'), '#/');
  assert.equal(href('/logs', { q: 'a b&c' }), '#/logs?q=a+b%26c');
});

test('what href writes, parse reads back', () => {
  const query = { q: '50% off / sale', service: 'spring-orders', minMs: '10' };
  assert.deepEqual(parse(href('/traces', query)), { path: '/traces', query });
});

test('detailPath encodes the id, slashes and percent signs included', () => {
  assert.equal(detailPath('endpoints', 'GET /books/{id}'), '/endpoints/GET%20%2Fbooks%2F%7Bid%7D');
  assert.equal(detailPath('services', '50%-off'), '/services/50%25-off');
  assert.equal(detailPath('traces', 'abc123'), '/traces/abc123');
});

test('detailHref carries the top bar query', () => {
  state.service = 'orders';
  state.range = '1h';
  try {
    assert.equal(detailHref('traces', 'abc'), '#/traces/abc?service=orders&range=1h');
  } finally {
    state.service = '';
    state.range = '15m';
  }
});

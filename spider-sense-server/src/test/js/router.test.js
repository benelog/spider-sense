// router.js: the hash as a path and a query (ui.adoc#urls).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parse, href, detailPath, detailHref, queryParam, compile, match } from '../../main/resources/public/assets/js/router.js';
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

test('queryParam takes an allowed value and falls back on anything else', () => {
  assert.equal(queryParam({ sort: 'p95' }, 'sort', ['total', 'p95'], 'total'), 'p95');
  assert.equal(queryParam({ sort: 'nope' }, 'sort', ['total', 'p95'], 'total'), 'total');
  assert.equal(queryParam({}, 'sort', ['total'], 'total'), 'total');
  assert.equal(queryParam(null, 'severity', ['WARN'], ''), '');
});

const routesOf = (...patterns) => patterns.map((pattern) => ({ ...compile(pattern), pattern }));

test('a pattern compiles to its parameter names and an anchored expression', () => {
  const { re, names } = compile('/traces/:id');
  assert.deepEqual(names, ['id']);
  assert.ok(re.test('/traces/abc'));
  assert.ok(!re.test('/traces/abc/spans'));
  assert.ok(!re.test('/x/traces/abc'));
  assert.ok(compile('/a.b').re.test('/a.b') && !compile('/a.b').re.test('/axb'), 'a dot is literal');
});

test('the first route that matches wins, and its parameters are named', () => {
  const routes = routesOf('/', '/services/:name', '/services/:name/:tab', '/traces/:id');
  assert.equal(match(routes, '/').route.pattern, '/');
  assert.deepEqual(match(routes, '/services/orders'), { route: routes[1], params: { name: 'orders' } });
  assert.deepEqual(match(routes, '/services/orders/jvm').params, { name: 'orders', tab: 'jvm' });
  assert.equal(match(routes, '/nowhere'), null);
  assert.equal(match([], '/'), null);
});

test('a parameter is decoded once, and an encoded slash stays inside its segment', () => {
  const routes = routesOf('/endpoints/:id', '/queries/:id');
  assert.deepEqual(match(routes, '/endpoints/GET%20%2Fbooks%2F%7Bid%7D').params, { id: 'GET /books/{id}' });
  assert.deepEqual(match(routes, '/queries/50%2525').params, { id: '50%25' });
  assert.deepEqual(match(routes, '/queries/100%').params, { id: '100%' }, 'a malformed escape is kept as it came');
});

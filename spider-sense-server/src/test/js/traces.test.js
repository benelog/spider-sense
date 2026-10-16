// pages/traces.js against answers the test controls: Load more under a Live tick, and the
// endpoint filter while the window has no call of its endpoint (pages.adoc#traces-list).
import './browser-env.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';

const JS = '../../main/resources/public/assets/js/';
const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));
let api, router, traces;

/** What the server answers: `newest` is the newest trace's start, and 400 traces go back a millisecond apart. */
const server = { newest: Date.now(), total: 400, endpoints: [], asked: [] };

function traceAt(start) {
  return { traceId: start.toString(16).padStart(32, '0'), start, rootName: 'GET /books', services: ['bookstore'], durationMs: 5, spanCount: 1 };
}

globalThis.fetch = async (input) => {
  const url = new URL(input, 'http://localhost:4000');
  server.asked.push(url.pathname + url.search);
  let body = {};
  if (url.pathname === '/api/traces') {
    // As Params.optionalLong does: a bound that is no whole number is a 400.
    for (const name of ['minMs', 'maxMs']) {
      const bound = url.searchParams.get(name);
      if (bound !== null && !/^-?\d+$/.test(bound)) {
        return new Response(JSON.stringify({ error: 'Invalid value for parameter ' + name + ': ' + bound }), { status: 400 });
      }
    }
    const before = Number(url.searchParams.get('before')) || server.newest + 1;
    const limit = Number(url.searchParams.get('limit'));
    const list = [];
    for (let at = Math.min(before - 1, server.newest); list.length < limit && at > server.newest - server.total; at--) list.push(traceAt(at));
    body = { traces: list, total: server.total };
  } else if (url.pathname === '/api/endpoints') {
    body = { endpoints: server.endpoints };
  }
  return new Response(JSON.stringify(body), { status: 200 });
};

before(async () => {
  api = await import(JS + 'api.js');
  router = await import(JS + 'router.js');
  traces = await import(JS + 'pages/traces.js');
  router.register('/traces');
  api.state.status = { counts: { traces: 400 } };
});

function open(query = {}) {
  router.replace('/traces', query);
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const ctx = { params: {}, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} };
  return { root, page: traces.render(root, ctx) };
}

const startsOf = (root) => root.querySelectorAll('tbody tr').map((tr) => tr.textContent);
const countLabel = (root) => root.querySelector('.querybar').children.at(-1).textContent;

test('a Live tick after Load more puts the newest traces on top and keeps the rows Load more fetched', async () => {
  api.state.service = '';
  const { root, page } = open();
  await settle();
  assert.equal(countLabel(root), '50 of 400');
  root.querySelector('.panel-note button').click();
  await settle();
  assert.equal(countLabel(root), '100 of 400');
  server.newest += 2;               // two requests arrived since
  server.total += 2;
  page.refresh();
  await settle();
  assert.equal(countLabel(root), '102 of 402');
  assert.equal(startsOf(root).length, 102);
  page.destroy();
});

test('a fraction in Min or Max asks for whole milliseconds, widened outwards, and the list stays', async () => {
  api.state.service = '';
  const lastAsked = () => new URL(server.asked.filter((p) => p.startsWith('/api/traces?')).at(-1), 'http://localhost').searchParams;
  const { root, page } = open({ minMs: '2.5', maxMs: '7.2' });
  await settle();
  assert.equal(lastAsked().get('minMs'), '2', 'the open lower bound goes down');
  assert.equal(lastAsked().get('maxMs'), '8', 'the closed upper bound goes up');
  assert.equal(countLabel(root), '50 of ' + server.total);
  const [search, min, max] = root.querySelectorAll('input');
  min.value = '1e3';
  max.value = '';
  search.dispatch('keydown', { key: 'Enter' });
  await settle();
  assert.equal(lastAsked().get('minMs'), '1000');
  assert.equal(lastAsked().get('maxMs'), null);
  assert.equal(root.querySelector('.error-box'), null);
  assert.equal(router.currentRoute().query.minMs, '1e3', 'the hash keeps what was typed');
  page.destroy();
});

test('a bar of the Response summary opens the list by request, and the select says so', async () => {
  api.state.service = '';
  server.asked.length = 0;
  const { root, page } = open({ status: 'ok', maxMs: '125', by: 'entry' });
  await settle();
  const select = root.querySelectorAll('select').find((s) => s.getAttribute('aria-label') === 'Filter by the trace or by one of its requests');
  assert.equal(select.value, 'entry');
  const asked = server.asked.find((u) => u.startsWith('/api/traces?'));
  assert.ok(asked && new URL(asked, 'http://localhost').searchParams.get('by') === 'entry', asked);
  select.value = 'trace';
  select.dispatch('change');
  await settle();
  assert.equal(router.currentRoute().query.by, undefined, 'by trace is the default, so the hash leaves it out');
  const last = server.asked.filter((u) => u.startsWith('/api/traces?')).at(-1);
  assert.equal(new URL(last, 'http://localhost').searchParams.get('by'), null, last);
  page.destroy();
});

test('the endpoint the URL names stays chosen when the window has no call of it', async () => {
  api.state.service = 'bookstore';
  server.endpoints = [{ endpointId: 'aaaa1111', name: 'GET /books' }, { endpointId: 'bbbb2222', name: 'GET /authors' }];
  const { root, page } = open({ service: 'bookstore', endpointId: 'aaaa1111' });
  await settle();
  const select = root.querySelectorAll('select').find((s) => s.getAttribute('aria-label') === 'Endpoint filter');
  assert.equal(select.value, 'aaaa1111');
  server.endpoints = [{ endpointId: 'bbbb2222', name: 'GET /authors' }];
  page.refresh();
  await settle();
  assert.equal(select.value, 'aaaa1111', 'the select says what the hash and the request filter by');
  const option = select.children.find((o) => o.getAttribute('value') === 'aaaa1111');
  assert.equal(option && option.textContent, 'GET /books (not seen)');
  assert.equal(router.currentRoute().query.endpointId, 'aaaa1111');
  page.destroy();
});

test('a Live tick leaves the endpoint options alone when the endpoints did not change, so an open list stays open', async () => {
  api.state.service = 'bookstore';
  server.endpoints = [{ endpointId: 'aaaa1111', name: 'GET /books' }, { endpointId: 'bbbb2222', name: 'GET /authors' }];
  const { root, page } = open({ service: 'bookstore' });
  await settle();
  const select = root.querySelectorAll('select').find((s) => s.getAttribute('aria-label') === 'Endpoint filter');
  const first = select.children.slice();
  page.refresh();
  await settle();
  assert.ok(select.children.every((o, i) => o === first[i]), 'the same option nodes');
  server.endpoints = [{ endpointId: 'bbbb2222', name: 'GET /authors' }];
  page.refresh();
  await settle();
  assert.deepEqual(select.children.map((o) => o.textContent), ['Any endpoint', 'GET /authors']);
  page.destroy();
});

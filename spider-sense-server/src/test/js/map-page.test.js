// pages/map.js against a map the test controls: what the nodes, the edges and the drawers say
// (pages.adoc#map).
import './browser-env.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';

const JS = '../../main/resources/public/assets/js/';
const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));
let api, ui, page;

/** One request from outside reaches orders, which calls example.com once, and that call fails. */
const MAP = {
  window: { from: 0, to: 60_000 },
  nodes: [
    { id: 'user', kind: 'user', name: 'Clients' },
    {
      id: 'svc:orders', kind: 'service', name: 'orders', requests: 1, errors: 0, errorRate: 0, rps: 0.02,
      p50Ms: 40, p95Ms: 40, p99Ms: 40, maxMs: 40, apdex: 1, histogram: [1, 0, 0, 0, 0], hasJvm: false,
    },
    { id: 'http:example.com:443', kind: 'http', name: 'example.com:443', calls: 1, errors: 1, avgMs: 20, p95Ms: 20 },
  ],
  edges: [
    { from: 'user', to: 'svc:orders', calls: 1, errors: 0, avgMs: 40, p95Ms: 40 },
    { from: 'svc:orders', to: 'http:example.com:443', calls: 1, errors: 1, avgMs: 20, p95Ms: 20 },
  ],
};

let answer = MAP;

globalThis.fetch = async (input) => {
  const url = new URL(input, 'http://localhost:4000');
  return new Response(JSON.stringify(url.pathname === '/api/map' ? answer : {}), { status: 200 });
};

before(async () => {
  api = await import(JS + 'api.js');
  ui = await import(JS + 'ui.js');
  page = await import(JS + 'pages/map.js');
  api.state.status = { counts: { spans: 3, traces: 1 } };
});

async function open(map = MAP) {
  answer = map;
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params: {}, initialQuery: {}, query: () => ({}), setTitle() {}, navigate() {} });
  await settle();
  return { root, instance };
}

const nodeOf = (root, id) => root.querySelectorAll('g.map-node').find((g) => g.getAttribute('data-id') === id);
/** A node's second line, whole, as its <title> keeps it. */
const lineOf = (root, id) => nodeOf(root, id).querySelector('text.map-node-line').querySelector('title').textContent;
const edgeTitleOf = (root, from, to) => root.querySelectorAll('g.map-edge')
  .find((g) => g.getAttribute('data-from') === from && g.getAttribute('data-to') === to)
  .querySelector('path').querySelector('title').textContent;

test('a count of one reads in the singular on a node, an edge and a drawer', async () => {
  const { root, instance } = await open();
  assert.equal(lineOf(root, 'http:example.com:443'), '1 call · 20.0 ms');
  assert.equal(lineOf(root, 'user'), '1 request');
  assert.equal(edgeTitleOf(root, 'svc:orders', 'http:example.com:443'), '1 call · 1 error · avg 20.0 ms · p95 20.0 ms');
  nodeOf(root, 'http:example.com:443').dispatch('click');
  const called = ui.drawerBody().querySelectorAll('dd').map((dd) => dd.textContent);
  assert.ok(called.includes('1 call, 1 error'), called.join(' | '));
  instance.destroy();
});

/** A drawer's `dl` as [term, description] pairs. */
const pairsOf = (dl) => {
  const items = dl.children.map((c) => c.textContent);
  return items.filter((_, i) => i % 2 === 0).map((dt, i) => [dt, items[2 * i + 1]]);
};

test('the Clients node\'s drawer gives the requests its node line counts and the services they reach', async () => {
  const books = { ...MAP.nodes[1], id: 'svc:books', name: 'books', requests: 159 };
  const map = {
    ...MAP,
    nodes: [...MAP.nodes, books],
    edges: [...MAP.edges, { from: 'user', to: 'svc:books', calls: 159, errors: 2, avgMs: 12, p95Ms: 30 }],
  };
  const { root, instance } = await open(map);
  assert.equal(lineOf(root, 'user'), '160 requests');
  nodeOf(root, 'user').dispatch('click');
  const body = ui.drawerBody();
  const [totals, reached] = body.querySelectorAll('dl');
  assert.deepEqual(pairsOf(totals), [['requests', '160'], ['errors', '2']]);
  assert.equal(body.querySelector('.sub-head').textContent, 'Calls');
  assert.deepEqual(pairsOf(reached), [['orders', '1 request · p95 40.0 ms'], ['books', '159 requests, 2 errors · p95 30.0 ms']]);
  instance.destroy();
});

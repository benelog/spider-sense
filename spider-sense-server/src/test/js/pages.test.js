// Every page renders against the mock (ui.adoc#mock): no error box, a refresh, a destroy, and the
// interactions the refactors touched. The browser is browser-env.js, so this is a smoke test of
// the page code, not of the layout.
import './browser-env.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';

const JS = '../../main/resources/public/assets/js/';
const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));

/** Waits until `done()` holds, as the mock answers on its own time; fails after two seconds. */
async function until(done, what) {
  for (let waited = 0; !done(); waited += 10) {
    if (waited > 2000) assert.fail('timed out waiting for ' + what);
    await settle(10);
  }
}
let api, router, ui, ids;

before(async () => {
  const log = console.log;
  console.log = () => {};          // the mock announces itself
  await import(JS + 'dev/mock.js');
  console.log = log;
  api = await import(JS + 'api.js');
  router = await import(JS + 'router.js');
  ui = await import(JS + 'ui.js');
  api.state.status = await api.status();
  ids = {
    service: (await api.services()).services[0].name,
    endpoint: (await api.endpoints({})).endpoints[0].endpointId,
    trace: (await api.traces({ limit: 1 })).traces[0].traceId,
    query: (await api.queries({ limit: 1 })).queries[0].queryId,
    error: (await api.errors({ limit: 1 })).errors[0].errorId,
  };
  for (const p of ['/', '/services/:name', '/endpoints/:id', '/traces/:id', '/queries/:id', '/errors/:id']) router.register(p);
});

/** What app.js hands a page: query() reads the hash as it is now, initialQuery as it was. */
function pageContext(params) {
  return { params, initialQuery: {}, query: () => router.currentRoute().query || {}, setTitle() {}, navigate() {} };
}

/** Renders a page, waits for its answer, and fails on a thrown error or an error box. */
async function visit(name, params = {}, service = '') {
  api.state.service = service;
  const page = await import(JS + 'pages/' + name + '.js');
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, pageContext(params)) || {};
  await until(() => !root.querySelector('.loading'), name + ' to load');
  await settle();
  assert.deepEqual(root.querySelectorAll('.error-box').map((b) => b.textContent), [], name + ' shows an error box');
  return { root, instance };
}

const PAGES = [
  ['overview'], ['findings'], ['compare'], ['map'], ['services'], ['service', () => ({ name: ids.service })],
  ['endpoint', () => ({ id: ids.endpoint })], ['scatter'], ['traces'], ['trace', () => ({ id: ids.trace })],
  ['queries'], ['query', () => ({ id: ids.query })], ['errors'], ['error', () => ({ id: ids.error })],
  ['logs'], ['jvm'], ['metrics'],
];

for (const [name, params] of PAGES) {
  test('the ' + name + ' page renders, refreshes and goes', async () => {
    for (const service of ['', ids.service]) {
      const { root, instance } = await visit(name, params ? params() : {}, service);
      assert.ok(root.children.length > 0);
      if (instance.refresh) instance.refresh();
      await settle(60);
      assert.equal(root.querySelectorAll('.error-box').length, 0, name + ' after a refresh');
      if (instance.destroy) instance.destroy();
    }
  });
}

test('Enter opens a log row and Space a finding (ui.adoc#accessibility)', async () => {
  const logs = await visit('logs');
  const row = logs.root.querySelector('tbody tr');
  row.dispatch('keydown', { key: 'Enter' });
  assert.equal(logs.root.querySelectorAll('tr.log-detail').length, 1);
  assert.equal(row.getAttribute('aria-expanded'), 'true');
  logs.instance.destroy();

  const findings = await visit('findings');
  findings.root.querySelector('tbody tr').dispatch('keydown', { key: ' ' });
  assert.equal(findings.root.querySelectorAll('tr.f-detail').length, 1);
  findings.instance.refresh();
  await settle(60);
  assert.equal(findings.root.querySelectorAll('tr.f-detail').length, 1, 'an open finding stays open across a refresh');
  findings.instance.destroy();
});

test('the chart switches press their buttons and the legends follow the series', async () => {
  const overview = await visit('overview');
  const load = overview.root.querySelectorAll('button').find((b) => b.textContent === 'Load');
  load.click();
  await until(() => /^≤125 ms/.test(overview.root.querySelector('.chart-legend').textContent), 'the Load legend');
  assert.equal(load.getAttribute('aria-pressed'), 'true');
  assert.match(overview.root.querySelector('.chart-legend').textContent, /^≤125 ms.*Errorsp95 response time, right axis$/);
  overview.instance.destroy();

  const error = await visit('error', { id: ids.error });
  const all = error.root.querySelectorAll('button').find((b) => b.textContent === 'All');
  all.click();
  assert.equal(all.getAttribute('aria-pressed'), 'true');
  error.instance.destroy();
});

test('the query page\'s callers and the error page\'s endpoints link to the endpoint page (api.adoc#callers)', async () => {
  for (const [name, id] of [['query', ids.query], ['error', ids.error]]) {
    const { root, instance } = await visit(name, { id });
    const links = root.querySelectorAll('a').map((a) => a.getAttribute('href') || '').filter((href) => href.startsWith('#/endpoints/'));
    assert.ok(links.length > 0, name + ' links an endpoint');
    instance.destroy();
  }
});

test('the query page lists where the statement is issued as code frames (pages.adoc#query)', async () => {
  let id = null;
  for (const q of (await api.queries({ limit: 100 })).queries) {
    if (((await api.query(q.queryId, {})).code || []).length) { id = q.queryId; break; }
  }
  assert.ok(id, 'the mock names the code of a query');
  const { root, instance } = await visit('query', { id });
  const code = root.querySelectorAll('div').find((d) => d.classList.contains('f-code'));
  assert.ok(code, 'the Code list');
  assert.ok(code.querySelectorAll('div').some((d) => d.classList.contains('src-frame')), 'its frames are code frames');
  instance.destroy();
});

test('the span drawer puts the statement first with a Copy button and folds a captured stack (pages.adoc#span-drawer)', async () => {
  const id = (await api.traces({ q: 'JdbcPreparedStatement', limit: 1 })).traces[0].traceId;
  const span = (await api.trace(id)).spans.find((s) => s.attributes['code.stacktrace']);
  assert.ok(span, 'the mock captures a stack on a slow statement');
  const { root, instance } = await visit('trace', { id });
  root.querySelectorAll('div').find((d) => d.dataset && d.dataset.key === span.spanId).click();
  const body = ui.drawerBody();
  const divs = body.querySelectorAll('div');
  const sections = divs.filter((d) => d.classList.contains('sub-head')).map((d) => d.textContent);
  assert.deepEqual(sections, ['db.statement', 'Attributes', 'code.stacktrace'], 'the statement first, the stack after the attributes');
  assert.ok(divs.some((d) => d.classList.contains('code-block')), 'the statement has a Copy button');
  assert.ok(!body.querySelectorAll('dt').some((dt) => dt.textContent === 'db.statement' || dt.textContent === 'code.stacktrace'),
    'neither is repeated among the attributes');
  const frames = divs.filter((d) => d.classList.contains('src-frame')).map((d) => d.textContent);
  assert.deepEqual(frames, ['net.benelog.bookstore.BookRepository.search(BookRepository.java:58)']);
  assert.ok(body.querySelectorAll('span').some((s) => s.classList.contains('st-fold')), 'the framework frames are folded');
  instance.destroy();
});

test('the waterfall labels a span with its summary and keeps its name in the title (pages.adoc#waterfall)', async () => {
  const trace = await api.trace(ids.trace);
  const { root, instance } = await visit('trace', { id: ids.trace });
  const labels = root.querySelectorAll('span').filter((s) => s.classList.contains('wf-label') && s.getAttribute('title'));
  assert.ok(labels.length > 0);
  for (const label of labels) {
    const named = trace.spans.filter((s) => s.name === label.getAttribute('title'));
    assert.ok(named.length, 'the title is a span name');
    assert.ok(named.some((s) => label.textContent === (s.summary || s.name)), label.textContent);
  }
  instance.destroy();
});

test('a profile row opens the span drawer, and the page closes it when it goes', async () => {
  const trace = await visit('trace', { id: ids.trace });
  trace.root.querySelectorAll('button').find((b) => b.textContent === 'Profile').click();
  assert.ok(trace.root.querySelectorAll('tr.profile-row').length > 0);
  trace.root.querySelector('tr.profile-row').click();
  assert.equal(ui.drawerOpen(), true);
  assert.ok(ui.drawerBody().textContent.includes('span id'), 'the drawer body holds the span');
  trace.instance.destroy();
  assert.equal(ui.drawerOpen(), false);
  assert.equal(ui.drawerBody(), null);
});

test('the Mark dialog marks through the API and closes', async () => {
  let marked = null;
  const dlg = ui.markDialog({ onDone: (mark) => { marked = mark; } });
  dlg.querySelector('button.btn-primary').click();
  await until(() => !dlg.open, 'the dialog to close');
  assert.equal(dlg.open, false);
  assert.ok(marked && marked.name);
});

test('hovering a map edge highlights it and its two nodes (pages.adoc#map-edges)', async () => {
  const { root, instance } = await visit('map');
  const edges = root.querySelectorAll('g.map-edge');
  assert.ok(edges.length > 1, 'the mock draws more than one edge');
  const edge = edges[0];
  const from = edge.getAttribute('data-from'), to = edge.getAttribute('data-to');
  assert.ok(from && to, 'the edge names its ends');
  edge.dispatch('mouseenter');
  for (const n of root.querySelectorAll('g.map-node')) {
    const id = n.getAttribute('data-id');
    assert.equal(n.classList.contains('dim'), id !== from && id !== to, id);
  }
  for (const e of root.querySelectorAll('g.map-edge')) assert.equal(e.classList.contains('dim'), e !== edge);
  edge.dispatch('mouseleave');
  assert.equal(root.querySelectorAll('g.dim').length, 0);
  instance.destroy();
});

test('Space opens a map node as Enter does (ui.adoc#accessibility)', async () => {
  const { root, instance } = await visit('map');
  const ev = root.querySelector('g.map-node').dispatch('keydown', { key: ' ' });
  assert.equal(ui.drawerOpen(), true);
  assert.equal(ev.defaultPrevented, true, 'the panel does not scroll');
  instance.destroy();
});

test('the Metrics catalog is a listbox the arrow keys walk and Space picks from', async () => {
  const { root, instance } = await visit('metrics');
  const box = root.querySelector('.catalog');
  assert.equal(box.getAttribute('role'), 'listbox');
  assert.ok(box.getAttribute('aria-label'));
  const items = () => root.querySelectorAll('.catalog-item');
  assert.ok(items().length > 2);
  assert.deepEqual(items().map((i) => i.getAttribute('tabindex')), items().map((i) => (i.getAttribute('aria-selected') === 'true' ? '0' : '-1')),
    'one tab stop, on the selected option');
  items()[0].dispatch('keydown', { key: 'ArrowDown' });
  assert.equal(document.activeElement, items()[1]);
  items()[1].dispatch('keydown', { key: 'ArrowUp' });
  assert.equal(document.activeElement, items()[0]);
  const ev = items()[1].dispatch('keydown', { key: ' ' });
  assert.equal(ev.defaultPrevented, true);
  assert.equal(items()[1].getAttribute('aria-selected'), 'true');
  instance.destroy();
});

/** The URLs fetched while `fn` runs. */
async function asked(fn) {
  const real = globalThis.fetch;
  const urls = [];
  globalThis.fetch = (url, init) => { urls.push(String(url)); return real(url, init); };
  try { await fn(); } finally { globalThis.fetch = real; }
  return urls;
}

/** The scatter's uPlot, through the overlay the fake one leaves in the chart. */
const scatterPlot = (root) => root.querySelector('div.chart').children[0].plot;

test('the scatter widens a rectangle to whole milliseconds rather than rounding it (pages.adoc#scatter)', async () => {
  const { root, instance } = await visit('scatter');
  const plot = scatterPlot(root);
  // A pixel is a millisecond from 1000 s on the x-axis, and the y-axis grows upwards from 200 px.
  plot.posToVal = (v, axis) => (axis === 'y' ? 200 - v : 1000 + v / 1000);
  const urls = await asked(async () => {
    plot.setSelect({ left: 0.6, width: 999.8, top: 47.6, height: 52.8 });   // 1000000.6–1001000.4 ms, 99.6–152.4 ms
    await settle(60);
  });
  const traces = urls.filter((u) => u.startsWith('/api/traces'));
  assert.equal(traces.length, 1);
  const q = new URL(traces[0], 'http://localhost').searchParams;
  assert.equal(q.get('minMs'), '99');
  assert.equal(q.get('maxMs'), '153');
  assert.equal(q.get('from'), '1000000');
  assert.equal(q.get('to'), '1001001');
  instance.destroy();
});

test('a scatter toggle changes the view in place, without reloading the window (pages.adoc#scatter)', async () => {
  const { root, instance } = await visit('scatter');
  const button = (label) => root.querySelectorAll('button').find((b) => b.textContent === label);
  for (const label of ['Failed', 'Log scale', 'Heatmap']) {
    const urls = await asked(async () => {
      button(label).click();
      instance.refresh();          // what app.js does when the page's own query changes the hash
      await settle(80);
    });
    assert.deepEqual(urls.filter((u) => u.startsWith('/api/scatter')), [], label + ' reloads the points');
    assert.ok(urls.filter((u) => u.startsWith('/api/traces')).length <= 1, label + ' asks the traces twice');
  }
  instance.destroy();
});

test('a change of service reloads the scatter', async () => {
  const { instance } = await visit('scatter');
  const urls = await asked(async () => {
    api.state.service = ids.service;
    instance.refresh();
    await settle(80);
  });
  api.state.service = '';
  assert.equal(urls.filter((u) => u.startsWith('/api/scatter')).length, 1);
  instance.destroy();
});

test('the scatter redraws the selection on every rebuild of its chart, until it is cleared', async () => {
  const { root, instance } = await visit('scatter');
  const proto = Object.getPrototypeOf(scatterPlot(root));
  const valToPos = proto.valToPos;
  // The inverse of the posToVal below: a pixel is a millisecond from 1000 s, and y grows upwards from 200 px.
  proto.valToPos = (v, axis) => (axis === 'y' ? 200 - v : (v - 1000) * 1000);
  try {
    const plot = scatterPlot(root);
    plot.posToVal = (v, axis) => (axis === 'y' ? 200 - v : 1000 + v / 1000);
    plot.setSelect({ left: 0.6, width: 399.8, top: 47.6, height: 52.8 });
    await settle(60);
    for (const label of ['Failed', 'Log scale', 'Heatmap']) {
      root.querySelectorAll('button').find((b) => b.textContent === label).click();
      const rebuilt = scatterPlot(root);
      assert.notEqual(rebuilt, plot, label + ' rebuilt the chart');
      const near = Object.fromEntries(Object.entries(rebuilt.select).map(([k, v]) => [k, Math.round(v * 10) / 10]));
      assert.deepEqual(near, { left: 0, top: 47.6, width: 401, height: 52.8 }, 'the rectangle after ' + label);
    }
    instance.onEscape();
    root.querySelectorAll('button').find((b) => b.textContent === 'Dots').click();
    assert.equal(scatterPlot(root).select.width, 0, 'a cleared selection is not redrawn');
  } finally {
    proto.valToPos = valToPos;
    instance.destroy();
  }
});

/** Runs `fn` while the mock's answer to `path` goes through `change` first. */
async function withAnswer(path, change, fn) {
  const real = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    const res = await real(url, init);
    if (String(url).split('?')[0] !== path) return res;
    return new Response(JSON.stringify(change(await res.json())), { status: res.status, headers: { 'content-type': 'application/json' } });
  };
  try { return await fn(); } finally { globalThis.fetch = real; }
}

test('the Overview shows a worker\'s findings, services and tingles though it has no request (pages.adoc#overview)', async () => {
  await withAnswer('/api/overview', (body) => ({
    ...body,
    totals: { requests: 0, errors: 0, errorRate: 0, rps: 0, p50Ms: null, p95Ms: null, p99Ms: null, maxMs: null, apdex: null, histogram: [0, 0, 0, 0, 0] },
    series: { t: [], requests: [], errors: [], p95Ms: [], histogram: [[], [], [], []] },
    tingles: [{ kind: 'slow-query', service: body.services[0].name, title: 'select', detail: '', at: Date.now(), traceId: 'abc' }],
  }), async () => {
    const { root, instance } = await visit('overview');
    assert.equal(root.querySelectorAll('.empty-state').length, 0, 'no empty state while there is something to show');
    assert.ok(root.querySelectorAll('.service-card').length > 0, 'the service cards');
    assert.equal(root.querySelectorAll('.tingle').length, 1, 'the tingle');
    assert.equal(root.querySelector('.hist-empty').textContent, '-', 'the Response summary says -');
    const tiles = Object.fromEntries(root.querySelectorAll('.stat').map((t) => [t.querySelector('.stat-caption').textContent, t.querySelector('.stat-number').textContent]));
    assert.equal(tiles['error rate'], '-', 'no error rate without a request');
    assert.equal(tiles['requests per second'], '-', 'no rate without a request');
    instance.destroy();
  });
});

test('a tingle without a trace is not a link', async () => {
  await withAnswer('/api/overview', (body) => ({
    ...body,
    tingles: [
      { kind: 'slow-query', service: body.services[0].name, title: 'with', detail: '', at: Date.now(), traceId: 'abc' },
      { kind: 'slow-query', service: body.services[0].name, title: 'without', detail: '', at: Date.now() - 1, traceId: null },
    ],
  }), async () => {
    const { root, instance } = await visit('overview');
    const [withTrace, without] = root.querySelectorAll('.tingle');
    assert.equal(withTrace.getAttribute('role'), 'link');
    assert.equal(withTrace.getAttribute('tabindex'), '0');
    assert.equal(without.getAttribute('role'), null);
    assert.equal(without.getAttribute('tabindex'), null);
    instance.destroy();
  });
});

test('the Overview shows the empty state only before anything has arrived', async () => {
  await withAnswer('/api/findings', () => ({ findings: [] }), () => withAnswer('/api/overview', (body) => ({
    ...body, totals: { requests: 0, histogram: [0, 0, 0, 0, 0] }, services: [], tingles: [],
  }), async () => {
    const { root, instance } = await visit('overview');
    assert.equal(root.querySelectorAll('.empty-state').length, 1);
    assert.match(root.querySelector('.empty-sentence').textContent, /^Nothing has arrived yet\./);
    instance.destroy();
  }));
});

test('the Overview asks for the service the top bar names (api.adoc#overview)', async () => {
  const real = globalThis.fetch;
  const asked = [];
  globalThis.fetch = (url, init) => { asked.push(String(url)); return real(url, init); };
  try {
    const { instance } = await visit('overview', {}, ids.service);
    const overview = asked.find((u) => u.startsWith('/api/overview?'));
    assert.equal(new URLSearchParams(overview.split('?')[1]).get('service'), ids.service);
    instance.destroy();
  } finally {
    globalThis.fetch = real;
    api.state.service = '';
  }
});

test('the Overview paints before its findings answer, and they fill their panel when they come (pages.adoc#overview)', async () => {
  const real = globalThis.fetch;
  let release;
  const held = new Promise((r) => { release = r; });
  globalThis.fetch = async (url, init) => {
    if (String(url).startsWith('/api/findings?')) await held;
    return real(url, init);
  };
  try {
    api.state.service = '';
    const page = await import(JS + 'pages/overview.js');
    const root = document.createElement('main');
    document.body.replaceChildren(root);
    const instance = page.render(root, pageContext({}));
    await until(() => root.querySelectorAll('.stat').length === 7, 'the stat tiles');
    assert.equal(root.querySelectorAll('.findings .loading').length, 1, 'the findings panel waits with its own spinner');
    release();
    await until(() => !root.querySelector('.loading'), 'the findings');
    instance.destroy();
  } finally {
    globalThis.fetch = real;
  }
});

test('the Overview\'s tiles and Response summary bars link to the pages behind them (pages.adoc#overview)', async () => {
  const { root, instance } = await visit('overview');
  const tiles = root.querySelectorAll('.stat-row a.stat-link').map((a) => a.getAttribute('href').split('?')[0]);
  assert.deepEqual(tiles, ['#/traces', '#/errors', '#/scatter', '#/scatter', '#/scatter', '#/traces']);
  const bars = root.querySelectorAll('a.hist-link').map((a) => a.getAttribute('href'));
  assert.equal(bars.length, 5);
  assert.match(bars[1], /^#\/traces\?.*minMs=\d+.*maxMs=\d+/);
  assert.match(bars[4], /status=error/);
  instance.destroy();
});

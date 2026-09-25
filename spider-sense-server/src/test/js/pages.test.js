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

/** Renders a page, waits for its answer, and fails on a thrown error or an error box. */
async function visit(name, params = {}, service = '') {
  api.state.service = service;
  const page = await import(JS + 'pages/' + name + '.js');
  const root = document.createElement('main');
  document.body.replaceChildren(root);
  const instance = page.render(root, { params, query: {}, setTitle() {}, navigate() {} }) || {};
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

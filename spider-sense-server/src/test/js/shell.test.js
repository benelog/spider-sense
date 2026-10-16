// app.js: the shell around the pages, booted against the mock on index.html's own body
// (ui.adoc#urls, ui.adoc#keyboard, ui.adoc#recording).
import './browser-env.js';
import { document, Element } from './fake-dom.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { EventSourceStub } from '../../main/resources/public/assets/js/dev/shim.js';

const JS = '../../main/resources/public/assets/js/';
const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));

/** index.html's body as elements: tags, attributes and text, enough for app.js to find its controls. */
function parseBody(html) {
  const body = html.slice(html.indexOf('<body>') + 6, html.indexOf('</body>'))
    .replace(/<!--[\s\S]*?-->/g, '').replace(/<script[\s\S]*?<\/script>/g, '');
  const VOID = new Set(['input', 'br', 'img', 'meta', 'link', 'hr']);
  const stack = [document.body];
  const re = /<\/([\w-]+)\s*>|<([\w-]+)((?:\s+[\w:-]+(?:="[^"]*")?)*)\s*(\/?)>|([^<]+)/g;
  let m;
  while ((m = re.exec(body))) {
    const top = stack[stack.length - 1];
    if (m[1]) { stack.pop(); continue; }
    if (m[5] !== undefined) { if (m[5].trim()) top.appendChild(document.createTextNode(m[5])); continue; }
    const el = document.createElement(m[2]);
    for (const a of m[3].matchAll(/([\w:-]+)(?:="([^"]*)")?/g)) el.setAttribute(a[1], a[2] === undefined ? '' : a[2]);
    top.appendChild(el);
    if (!m[4] && !VOID.has(m[2].toLowerCase())) stack.push(el);
  }
}

/** A select's options, which the service filter compares before it refills them. */
Object.defineProperty(Element.prototype, 'options', { get() { return this.all('option'); } });

/** The window's listeners, which browser-env drops: app.js's keydown, the router's hashchange. */
const listeners = {};
globalThis.addEventListener = (type, fn) => { (listeners[type] ||= []).push(fn); };

let api, router;

before(async () => {
  parseBody(readFileSync(new URL('../../main/resources/public/index.html', import.meta.url), 'utf8'));
  const log = console.log;
  console.log = () => {};          // the mock announces itself
  await import(JS + 'dev/mock.js');
  console.log = log;
  api = await import(JS + 'api.js');
  router = await import(JS + 'router.js');
  await import(JS + 'app.js');
  for (let waited = 0; !document.getElementById('main').children.length; waited += 10) {
    if (waited > 2000) assert.fail('timed out waiting for the shell to boot');
    await settle(10);
  }
});

function navHref(path) {
  return document.querySelectorAll('a[data-nav]').find((a) => a.getAttribute('data-nav') === path).getAttribute('href');
}

test('the sidebar\'s links carry the service, the range, Live and the chart the top bar shows (ui.adoc#urls)', async () => {
  router.setQuery({ service: 'spring-orders', range: '1h', live: '1', chart: 'load' });
  assert.equal(navHref('/traces'), '#/traces?service=spring-orders&range=1h&live=1&chart=load');
  assert.equal(document.querySelector('a.brand').getAttribute('href'), '#/?service=spring-orders&range=1h&live=1&chart=load', 'the logo too');
  assert.equal(document.getElementById('tingle-link').getAttribute('href'), '#/?service=spring-orders&range=1h&live=1&chart=load', 'and the tingles entry');
  router.setQuery({ service: '', range: '', live: '', chart: '' });
  assert.equal(navHref('/traces'), '#/traces', 'a link names only what differs from the defaults');
});

/** Waits until `done()` holds, as the mock answers on its own time; fails after two seconds. */
async function until(done, what) {
  for (let waited = 0; !done(); waited += 10) {
    if (waited > 2000) assert.fail('timed out waiting for ' + what);
    await settle(10);
  }
}

/** A hash the page did not set: a link followed, Back or Forward, an edited URL. */
function follow(hash) {
  location.hash = hash;
  for (const fn of listeners.hashchange) fn({});
}

const main = () => document.getElementById('main');
const selectOf = (label) => main().querySelectorAll('select').find((s) => s.getAttribute('aria-label') === label);
const pickedMetric = () => {
  const item = main().querySelectorAll('.catalog-item').find((i) => i.getAttribute('aria-selected') === 'true');
  return item && item.querySelector('.catalog-name').textContent;
};

test('a hash the page did not set opens the view it names, on the same route too (ui.adoc#urls)', async () => {
  follow('#/traces?status=error');
  await until(() => selectOf('Status filter') && main().querySelector('tbody'), 'the traces filtered to errors');
  assert.equal(selectOf('Status filter').value, 'error');
  follow('#/traces');                // the sidebar's Traces entry, or Back
  await until(() => selectOf('Status filter').value === 'all', 'the traces unfiltered');
  const page = main().children[0];
  router.setQuery({ range: '1h' });  // a change the top bar makes only refreshes the page
  assert.equal(main().children[0], page);
  router.setQuery({ range: '' });

  follow('#/metrics?metric=jvm.memory.used');
  await until(() => pickedMetric() === 'jvm.memory.used', 'the metric the URL names');
  follow('#/metrics?metric=jvm.memory.committed');
  await until(() => pickedMetric() === 'jvm.memory.committed', 'the metric the new URL names');
  follow('#/');
});

const linkNamed = (text) => main().querySelectorAll('a').find((a) => a.textContent === text);

test('a link a page built once carries the service, the range and Live the top bar shows now (ui.adoc#urls)', async () => {
  follow('#/');
  await until(() => linkNamed('All findings'), 'the Overview');
  router.setQuery({ service: 'spring-orders', range: '1h', live: '1' });
  assert.equal(linkNamed('All findings').getAttribute('href'), '#/findings?service=spring-orders&range=1h&live=1');
  assert.equal(linkNamed('All services').getAttribute('href'), '#/services?service=spring-orders&range=1h&live=1');
  router.setQuery({ service: '', range: '', live: '' });

  follow('#/services/spring-orders');
  await until(() => linkNamed('All queries'), 'the Service page');
  router.setQuery({ range: '6h' });
  assert.equal(linkNamed('All queries').getAttribute('href'), '#/queries?range=6h&service=spring-orders', 'its own service stays');
  assert.equal(linkNamed('All errors').getAttribute('href'), '#/errors?range=6h&service=spring-orders');
  router.setQuery({ range: '' });

  follow('#/findings');
  await until(() => main().querySelector('tbody tr'), 'the findings');
  main().querySelector('tbody tr').click();
  const evidenceLinks = () => main().querySelectorAll('tr.f-detail a').map((a) => a.getAttribute('href')).filter((href) => href.startsWith('#/'));
  assert.ok(evidenceLinks().length > 0, 'the open finding links its subject');
  router.setQuery({ range: '1h' });
  for (const href of evidenceLinks()) assert.match(href, /[?&]range=1h(&|$)/);
  router.setQuery({ range: '' });

  let id = null;
  for (const t of (await api.traces({ limit: 200 })).traces) if (((await api.trace(t.traceId)).logs || []).length) { id = t.traceId; break; }
  follow('#/traces/' + id);
  await until(() => linkNamed('Logs page') && linkNamed('Logs page').dataset.carry, 'the trace page');
  router.setQuery({ live: '1' });
  const logs = new URLSearchParams(linkNamed('Logs page').getAttribute('href').split('?')[1]);
  assert.deepEqual([logs.get('live'), logs.get('traceId'), logs.has('service')], ['1', id, false]);
  router.setQuery({ live: '' });
  follow('#/');
});

test('Try again after the server came back brings the foot and the service filter back with it (ui.adoc#not-answering)', async () => {
  const served = globalThis.fetch;
  const foot = () => document.getElementById('foot-mode').textContent;
  const offered = () => document.getElementById('service-select').options.map((o) => o.textContent);
  await settle(80);                  // the mock's answers still on their way, which a new read would share
  globalThis.fetch = () => Promise.reject(new TypeError('Failed to fetch'));
  try {
    follow('#/errors');
    await until(() => main().querySelector('.error-box'), 'the error box');
    assert.equal(foot(), 'not answering');
  } finally {
    // The server is back, and knows a service it did not before.
    globalThis.fetch = async (url, init) => {
      const res = await served(url, init);
      if (String(url).split('?')[0] !== '/api/services') return res;
      const body = await res.json();
      body.services.push({ name: 'late-service' });
      return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
    };
  }
  try {
    main().querySelector('.error-box button').click();
    await until(() => offered().includes('late-service'), 'the service filter');
    await until(() => foot() !== 'not answering', 'the foot');
  } finally {
    globalThis.fetch = served;
    follow('#/');
  }
});

/** The live stream app.js subscribes to, caught as it subscribes, so a test can deliver an event as the server would. */
let stream = null;
const subscribe = EventSourceStub.prototype.addEventListener;
EventSourceStub.prototype.addEventListener = function (type, fn) {
  if (type === 'tingle') stream = this;
  return subscribe.call(this, type, fn);
};

test('the tingles entry carries its name as a title and an aria-label, and the label the count of new tingles (ui.adoc#layout)', () => {
  const link = document.getElementById('tingle-link');
  assert.equal(link.getAttribute('title'), 'Tingles');
  assert.equal(link.getAttribute('aria-label'), 'Tingles', 'a collapsed icon still says where it goes');
  stream.dispatch('tingle', { kind: 'error', at: Date.now(), service: 'spring-orders', title: 'GET /orders', detail: 'IllegalStateException', traceId: 'a1b2' });
  assert.equal(document.getElementById('tingle-badge').textContent, '1');
  assert.equal(link.getAttribute('aria-label'), 'Tingles, 1 new', 'and the count its badge shows');
  link.click();
  assert.equal(link.getAttribute('aria-label'), 'Tingles', 'which a click clears with the badge');
});

/** A key pressed on the page, outside any field. */
function press(key) {
  const event = { key, target: document.body, preventDefault() {} };
  for (const fn of listeners.keydown) fn(event);
}

test('a recording has no Live: L does nothing, live=1 leaves the hash, and the keys dialog leaves L out (ui.adoc#recording)', async () => {
  api.state.recording = true;
  try {
    router.setQuery({ live: '1' });
    assert.equal(api.state.live, false);
    assert.doesNotMatch(location.hash, /live=/, 'the hash drops live=1');
    assert.equal(document.body.classList.contains('is-live'), false);
    press('L');
    assert.equal(api.state.live, false);
    assert.doesNotMatch(location.hash, /live=/);
    press('?');
    const dlg = document.querySelector('dialog');
    const keys = dlg.querySelectorAll('kbd').map((k) => k.textContent);
    assert.ok(keys.includes('M') && !keys.includes('L'), 'the dialog lists ' + keys.join(' '));
    dlg.close();
  } finally {
    api.state.recording = false;
  }
  press('L');
  assert.equal(api.state.live, true, 'outside a recording L turns Live on');
  press('L');
  assert.equal(api.state.live, false);
});

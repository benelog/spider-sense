// app.js: the shell around the pages, booted against the mock on index.html's own body
// (ui.adoc#urls, ui.adoc#keyboard, ui.adoc#recording).
import './browser-env.js';
import { document, Element } from './fake-dom.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

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

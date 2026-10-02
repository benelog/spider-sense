// ui.js: where the focus goes when a drawer opens and closes, and the toast's live region (ui.adoc#accessibility).
import { document } from './fake-dom.js';
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { h, drawer, closeDrawer, closeDrawerSilently, toast } from '../../main/resources/public/assets/js/ui.js';

afterEach(() => {
  closeDrawerSilently();
  document.body.replaceChildren();
  document.activeElement = undefined;
});

function row() {
  const node = h('div.wf-row', { tabindex: '0' }, 'GET /orders');
  document.body.appendChild(node);
  node.focus();
  return node;
}

test('an open drawer takes the focus, and gives it back to the row it came from on close', () => {
  const opener = row();
  const node = drawer({ title: 'SELECT', body: h('p', 'the span') });
  assert.equal(document.activeElement, node);
  assert.equal(node.getAttribute('tabindex'), '-1');
  closeDrawer();
  assert.equal(document.activeElement, opener);
});

test('a drawer opened from another keeps the first opener to return to', () => {
  const opener = row();
  const first = drawer({ title: 'one', body: h('p', 'first') });
  const link = h('a', { href: '#' }, 'next');
  first.appendChild(link);
  link.focus();
  drawer({ title: 'two', body: h('p', 'second') });
  closeDrawer();
  assert.equal(document.activeElement, opener);
});

test('closing leaves alone a focus the person has moved elsewhere', () => {
  row();
  drawer({ title: 'SELECT', body: h('p', 'the span') });
  const search = h('input');
  document.body.appendChild(search);
  search.focus();
  closeDrawer();
  assert.equal(document.activeElement, search);
});

test('the toast region is in the page before its first message', () => {
  const html = readFileSync(new URL('../../main/resources/public/index.html', import.meta.url), 'utf8');
  assert.match(html, /<div id="toast" role="status" aria-live="polite"><\/div>/);
});

test('without the region a toast puts it in the page empty and the message after', async () => {
  toast('Marked before');
  const region = document.body.children.find((c) => c.id === 'toast');
  assert.equal(region.getAttribute('aria-live'), 'polite');
  assert.equal(region.textContent, '', 'empty when it enters the page');
  await new Promise((resolve) => setTimeout(resolve, 80));
  assert.equal(region.textContent, 'Marked before');
});

test('a toast only changes the text of the region already there', () => {
  const region = h('div#toast', { role: 'status', 'aria-live': 'polite' });
  document.body.appendChild(region);
  const lookup = document.getElementById;
  document.getElementById = (id) => (id === 'toast' ? region : null);
  try {
    toast('Copied');
    assert.equal(region.textContent, 'Copied');
    assert.equal(document.body.children.length, 1, 'no second region');
  } finally {
    document.getElementById = lookup;
  }
});

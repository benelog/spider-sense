// ui.js: where the focus goes when a drawer opens and closes (ui.adoc#accessibility).
import { document } from './fake-dom.js';
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { h, drawer, closeDrawer, closeDrawerSilently } from '../../main/resources/public/assets/js/ui.js';

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

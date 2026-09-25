// page.js: the newest load wins, a destroyed page paints nothing, and a failure shows the error box.
import './fake-dom.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pageLoader, skeleton } from '../../main/resources/public/assets/js/page.js';

/** A fetch whose answers the test hands out in any order. */
function deferred() {
  const calls = [];
  const fetch = (...args) => new Promise((resolve, reject) => calls.push({ args, resolve, reject }));
  return { fetch, calls };
}
const settle = () => new Promise((r) => setImmediate(r));

test('only the newest load paints', async () => {
  const { fetch, calls } = deferred();
  const painted = [];
  const loader = pageLoader({ fetch, paint: (res, arg) => painted.push([res, arg]), body: document.createElement('div') });
  loader.load('first');
  loader.load('second');
  calls[1].resolve('B');
  calls[0].resolve('A');
  await settle();
  assert.deepEqual(painted, [['B', 'second']]);
});

test('a destroyed page paints nothing, not even an error', async () => {
  const { fetch, calls } = deferred();
  const body = document.createElement('div');
  let painted = 0;
  const loader = pageLoader({ fetch, paint: () => painted++, body });
  loader.load();
  loader.load();
  loader.destroy();
  calls[0].resolve('A');
  calls[1].reject(new Error('gone'));
  await settle();
  assert.equal(painted, 0);
  assert.equal(body.childNodes.length, 0);
  assert.equal(loader.isDestroyed(), true);
});

test('a failure puts the error box in the body, and Try again loads again', async () => {
  const { fetch, calls } = deferred();
  const body = document.createElement('div');
  const errors = [];
  const loader = pageLoader({ fetch, paint: () => {}, body, onError: (e, arg) => errors.push([e.message, arg]) });
  loader.load('x');
  calls[0].reject(new Error('boom'));
  await settle();
  assert.deepEqual(errors, [['boom', 'x']]);
  const box = body.children[0];
  assert.equal(box.className, 'error-box');
  assert.ok(box.textContent.includes('boom'));
  box.all('button')[0].click();
  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1].args, []);
});

test('onError returning false keeps what is on screen', async () => {
  const { fetch, calls } = deferred();
  const body = document.createElement('div');
  const loader = pageLoader({ fetch, paint: () => {}, body, onError: () => false });
  loader.load();
  calls[0].reject(new Error('boom'));
  await settle();
  assert.equal(body.childNodes.length, 0);
});

test('a skeleton builds once, and again after something replaced it', async () => {
  const root = document.createElement('main');
  const a = document.createElement('section');
  const layout = skeleton(root, () => [a]);
  const page = root.children[0];
  assert.equal(page.children[0].className, 'loading');
  layout.build();
  layout.build();
  assert.deepEqual(page.children, [a]);
  assert.equal(layout.built, true);
  const { fetch, calls } = deferred();
  const loader = pageLoader({ fetch, paint: () => layout.build(), body: layout });
  loader.load();
  calls[0].reject(new Error('boom'));
  await settle();
  assert.equal(layout.built, false);
  assert.equal(page.children[0].className, 'error-box');
  loader.load();
  calls[1].resolve({});
  await settle();
  assert.deepEqual(page.children, [a]);
});

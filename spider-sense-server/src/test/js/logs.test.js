// pages/logs.js: the Live-tail merge after Load more (pages.adoc#logs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeNewestPage } from '../../main/resources/public/assets/js/pages/logs.js';

const log = (id, at) => ({ id, at });

test('the newest page lands on top of the loaded rows, newest first, without a duplicate', () => {
  const rows = [log(3, 300), log(2, 200), log(1, 100)];
  const incoming = [log(5, 500), log(4, 400), log(3, 300)];
  assert.deepEqual(mergeNewestPage(rows, incoming, 0, 3).map((l) => l.id), [5, 4, 3, 2, 1]);
});

test('rows the window has left are dropped', () => {
  const rows = [log(2, 200), log(1, 100)];
  assert.deepEqual(mergeNewestPage(rows, [log(3, 300), log(2, 200)], 150, 10).map((l) => l.id), [3, 2]);
});

test('rows at the same instant are ordered by id', () => {
  const rows = [log(1, 100)];
  assert.deepEqual(mergeNewestPage(rows, [log(2, 100), log(1, 100)], 0, 10).map((l) => l.id), [2, 1]);
});

test('a full page that holds none of the loaded rows would open a gap, so it is not merged', () => {
  const rows = [log(1, 100)];
  assert.equal(mergeNewestPage(rows, [log(3, 300), log(2, 200)], 0, 2), null);
  assert.deepEqual(mergeNewestPage(rows, [log(2, 200)], 0, 2).map((l) => l.id), [2, 1]);
});

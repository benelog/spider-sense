// pages/metrics.js: a series' name in the legend (pages.adoc#metrics).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { labelOf } from '../../main/resources/public/assets/js/pages/metrics.js';

test('a series is named by its attributes, less the jvm. prefix', () => {
  const s = { service: 'orders', attributes: { 'jvm.memory.pool.name': 'G1 Eden Space', type: 'heap' } };
  assert.equal(labelOf(s, 'orders'), 'memory.pool.name=G1 Eden Space type=heap');
});

test('a series without attributes is named by its service, else value', () => {
  assert.equal(labelOf({ service: 'orders' }, 'orders'), 'orders');
  assert.equal(labelOf({}, 'orders'), 'value');
});

test('with every service shown, the service leads the name', () => {
  assert.equal(labelOf({ service: 'orders', attributes: { type: 'heap' } }, ''), 'orders · type=heap');
  assert.equal(labelOf({ attributes: { type: 'heap' } }, ''), 'type=heap');
});

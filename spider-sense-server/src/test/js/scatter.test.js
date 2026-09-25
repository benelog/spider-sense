// pages/scatter.js: the linear axis' maximum (pages.adoc#scatter).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { scatterYMax } from '../../main/resources/public/assets/js/pages/scatter.js';

test('the axis runs to half again the p99 of the durations', () => {
  const ds = Array.from({ length: 100 }, (_, i) => i + 1);      // p99 is the 100th value
  assert.equal(scatterYMax(ds), 150);
  const outlier = Array.from({ length: 200 }, () => 20).concat([10000]);
  assert.equal(scatterYMax(outlier), 30, 'one outlier in 201 does not stretch the axis');
});

test('the durations may come in any order, and are left as they came', () => {
  const ds = [40, 10, 20];
  assert.equal(scatterYMax(ds), 60);
  assert.deepEqual(ds, [40, 10, 20]);
});

test('the axis is at least 10 ms, and 100 ms with no point', () => {
  assert.equal(scatterYMax([1, 2]), 10);
  assert.equal(scatterYMax([]), 100);
});

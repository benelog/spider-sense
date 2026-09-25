// charts.js: the legend a chart's spec implies, and the series alignment of the JVM and Metrics pages.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { legendItems, alignedTimes, alignTo } from '../../main/resources/public/assets/js/charts.js';
import { throughputSpec } from '../../main/resources/public/assets/js/loadchart.js';

test('the legend lists the series under their legend label, leaving out the hidden ones', () => {
  const spec = {
    series: [
      { label: 'Calls', legendLabel: 'Calls per bucket', color: 'silk' },
      { label: 'p95', color: 'accent' },
      { label: 'a p95', color: 'series1', legend: false },
    ],
    legendExtra: [{ label: 'dashed: p95', color: 'silk' }],
  };
  assert.deepEqual(legendItems(spec), [
    { label: 'Calls per bucket', color: 'silk' },
    { label: 'p95', color: 'accent' },
    { label: 'dashed: p95', color: 'silk' },
  ]);
});

test('the throughput legend follows its mode', () => {
  const series = { t: [1, 2], requests: [3, 4], errors: [0, 1], histogram: [[1, 1], [1, 1], [0, 1], [0, 0]], p95Ms: [5, 6] };
  assert.deepEqual(legendItems(throughputSpec(series, 'requests', { p95: true })).map((i) => i.label),
    ['Requests per bucket', 'Errors', 'p95 response time, right axis']);
  assert.deepEqual(legendItems(throughputSpec(series, 'load')).map((i) => i.label),
    ['≤125 ms', '≤500 ms', '≤2 s', '>2 s', 'Errors']);
});

test('series sampled at different instants share the union of their times', () => {
  const a = { t: [1, 3], used: [10, 30] };
  const b = { t: [2, 3], used: [20, null] };
  const t = alignedTimes([a, b]);
  assert.deepEqual(t, [1, 2, 3]);
  assert.deepEqual(alignTo(t, a, 'used'), [10, null, 30]);
  assert.deepEqual(alignTo(t, b, 'used'), [null, 20, null]);
});

// pages/scatter.js: the linear axis' maximum and the Live merge (pages.adoc#scatter).
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { document } from './fake-dom.js';
import { scatterYMax, mergePoints } from '../../main/resources/public/assets/js/pages/scatter.js';
import { scatterChart } from '../../main/resources/public/assets/js/charts.js';
import * as fmt from '../../main/resources/public/assets/js/format.js';

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

const point = (start, trace) => [start, 12.5, 'svc', 'GET /x', trace, 0];

test('a Live merge adds the new traces once and drops the ones before the window', () => {
  const on = [point(100, 'a'), point(200, 'b')];
  const { points, cut } = mergePoints(on, [point(200, 'b'), point(300, 'c')], 150);
  assert.deepEqual(points.map((p) => p[4]), ['b', 'c']);
  assert.equal(cut, false);
});

test('a Live merge keeps the newest points up to the limit and says it cut', () => {
  const on = [point(300, 'c'), point(100, 'a'), point(200, 'b')];
  const { points, cut } = mergePoints(on, [point(400, 'd')], 0, 3);
  assert.deepEqual(points.map((p) => p[4]).sort(), ['b', 'c', 'd'], 'the oldest goes first');
  assert.equal(cut, true);
});

test('the scatter draws more points than a call takes arguments', () => {
  const n = 200000;
  const points = Array.from({ length: n }, (_, i) => point(1000 + i, 't' + i));
  const container = document.createElement('div');
  assert.doesNotThrow(() => scatterChart(container, { points, window: { from: 0, to: 1000 + n }, logScale: true }).destroy());
});

test('a dot\'s tooltip names the day of a request before today', () => {
  const at = Date.now() - 2 * 86400000;
  const p = [at, 40, 'svc', 'GET /x', 'a', 0];
  const container = document.createElement('div');
  const chart = scatterChart(container, { points: [p], window: { from: at - 60000, to: at + 60000 } });
  // the fake plot places a value at itself, so the pointer goes where the point's values are
  chart.plot.over.dispatch('mousemove', { clientX: at / 1000, clientY: 40 });
  const tip = container.querySelector('.chart-tip').innerHTML;
  assert.ok(tip.includes(fmt.day(at) + ' ' + fmt.clock(at)), tip);
  chart.destroy();
});

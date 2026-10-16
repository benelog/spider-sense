// charts.js: the legend a chart's spec implies, and the series alignment of the JVM and Metrics pages.
process.env.TZ = 'UTC';

import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { document } from './fake-dom.js';
import { legendItems, alignedTimes, alignTo, stackColumns, tipLeft, valueText, tickText, durationRange, countScales, COUNT_INCRS, timeTicks, timeSeries, chartBox, retheme } from '../../main/resources/public/assets/js/charts.js';
import * as fmt from '../../main/resources/public/assets/js/format.js';
import { throughputSpec } from '../../main/resources/public/assets/js/throughput.js';

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

test('both throughput modes draw a bar as tall as its requests with the errors on top, and the tooltip gives the counts (pages.adoc#overview)', () => {
  const series = { t: [Date.now() - 60000, Date.now()], requests: [10, 4], errors: [1, 0], histogram: [[6, 2], [2, 1], [1, 1], [0, 0]] };
  for (const [mode, under] of [['requests', 'Requests'], ['load', '>2 s']]) {
    const spec = throughputSpec(series, mode);
    const { columns, column } = stackColumns(spec.series);
    const top = (label) => columns[column[spec.series.findIndex((s) => s.label === label)] - 1];
    assert.deepEqual(top('Errors'), [10, 4], mode + ': the errors end where the requests do');
    assert.deepEqual(top(under), [9, 4], mode + ': the segment under them ends where they begin');
  }
  const container = document.createElement('div');
  const chart = timeSeries(container, throughputSpec(series, 'requests'));
  chart.plot.cursor = { idx: 0, left: 10, top: 10 };
  for (const hook of chart.plot.opts.hooks.setCursor) hook(chart.plot);
  const tip = container.querySelector('.chart-tip').innerHTML;
  assert.match(tip, /Requests<\/span><span class="v">10</, 'the requests, errors included: ' + tip);
  assert.match(tip, /Errors<\/span><span class="v">1</);
  chart.destroy();
});

test('what replaced a chart box\'s chart stays when the theme flips, until the next show', () => {
  const box = chartBox({ title: 'Metric' });
  const spec = { t: [Date.now() - 60000, Date.now()], series: [{ label: 'v', values: [1, 2] }] };
  box.show(spec);
  const sentence = document.createElement('p');
  box.empty(sentence);
  retheme();
  assert.deepEqual(box.body.children, [sentence], 'the empty state, not the chart it replaced');
  assert.equal(box.node.querySelector('.chart-legend'), null, 'and no legend');
  box.show(spec);
  assert.ok(box.body.children[0].plot, 'the next show draws the chart again');
  retheme();
  assert.ok(box.body.children[0].plot, 'which a flip redraws');
  box.destroy();
});

test('series sampled at different instants share the union of their times', () => {
  const a = { t: [1, 3], used: [10, 30] };
  const b = { t: [2, 3], used: [20, null] };
  const t = alignedTimes([a, b]);
  assert.deepEqual(t, [1, 2, 3]);
  assert.deepEqual(alignTo(t, a, 'used'), [10, null, 30]);
  assert.deepEqual(alignTo(t, b, 'used'), [null, 20, null]);
});

test('a stacked series holds the cumulative value and paints before the ones below it', () => {
  const { columns, order, column } = stackColumns([
    { label: 'fast', type: 'bar', stack: 'load', values: [1, 2] },
    { label: 'slow', type: 'bar', stack: 'load', values: [3, null] },
    { label: 'p95', type: 'line', values: [5, null] },
  ]);
  assert.deepEqual(order, [1, 0, 2]);
  assert.deepEqual(columns, [[4, 2], [1, 2], [5, null]]);
  assert.deepEqual(column, [2, 1, 3], 'spec series i is uPlot series column[i]');
});

test('bars take one slot per stack and one per unstacked bar series', () => {
  const { slotOf, bars } = stackColumns([
    { type: 'bar', stack: 'a', values: [] },
    { type: 'line', values: [] },
    { type: 'bar', values: [] },
    { type: 'bar', stack: 'a', values: [] },
  ]);
  assert.deepEqual([...slotOf], [[0, 0], [2, 1], [3, 0]]);
  assert.equal(bars, 2);
});

test('a tooltip is centred on its anchor and kept 4 px inside the chart', () => {
  assert.equal(tipLeft(200, 100, 600), '150px');
  assert.equal(tipLeft(10, 100, 600), '4px');
  assert.equal(tipLeft(590, 100, 600), '496px');
});

test('a duration in a tooltip carries its own unit, in seconds from 10 s', () => {
  assert.equal(valueText('ms', 12.34), '12.3 ms');
  assert.equal(valueText('ms', 250), '250 ms');
  assert.equal(valueText('ms', 12000), '12.0 s');
  assert.equal(valueText('ms', null), '-');
  assert.equal(durationRange(5000, 12000), '5,000 ms – 12.0 s');
});

test('a value that is not a count keeps its fraction in the tooltip and on the axis', () => {
  assert.equal(valueText('y', 0.25), '0.25');
  assert.equal(valueText('y', 0.003), '0.003');
  assert.deepEqual([1.4, 0.7, 2.6].map((v) => valueText('load', v)), ['1.4', '0.7', '2.6']);
  assert.equal(valueText('y', 42), '42');
  assert.equal(valueText('y', 1234.5), '1,235');
  assert.deepEqual([0, 0.5, 1, 1.5, 2].map((v) => tickText('load', v)), ['0', '0.5', '1', '1.5', '2']);
});

test('only a scale of whole numbers that is no duration, percentage or load counts', () => {
  const counts = countScales([
    { scale: 'y', values: [1, 2, null] },
    { scale: 'ms', values: [3, 4] },
    { scale: 'pending', values: [0, 1] },
    { scale: 'load', values: [1, 2] },
  ]);
  assert.deepEqual([...counts].sort(), ['pending', 'y']);
  assert.deepEqual([...countScales([{ values: [0.25, 0.5] }, { values: [1] }])], [], 'a metric under 1 is not a count');
});

test('the ticks of an axis labelled ms stay in milliseconds', () => {
  assert.equal(tickText('ms', 50), '50.0');
  assert.equal(tickText('ms', 5000), '5,000');
  assert.equal(tickText('ms', 12000), '12,000');
});

test('a count axis in the billions still finds a whole-number step that keeps its ticks 30 px apart', () => {
  // uPlot's own search: the first step whose ticks are at least `space` px apart, or none, and with none it draws no tick and no grid
  const step = (range, plotPx, space = 30) => COUNT_INCRS.find((incr) => incr * plotPx / range >= space);
  const memory = 1.6e8;
  assert.ok(countScales([{ values: [memory, memory + 1] }]).has('y'), 'bytes are whole numbers, so a count axis');
  assert.equal(step(memory * 1.15, 160), 5e7);
  assert.ok(step(4e12 * 1.15, 1000) != null, 'a few terabytes still have a step');
  assert.equal(step(1.15, 160), 1, 'a series at 0 or 1 still steps by one');
  assert.ok(COUNT_INCRS.every(Number.isInteger));
});

test('a value axis widens to its widest tick, so a count in the billions keeps its digits and clears the axis label', () => {
  const container = document.createElement('div');
  const chart = timeSeries(container, {
    t: [Date.now() - 60000, Date.now()],
    series: [{ label: 'committed', values: [1.2e9, 1.5e9] }],
    axes: [{ scale: 'y', label: 'By' }, { scale: 'y', side: 1, label: 'By' }],
  });
  // uPlot's side of it: the axis font at the pixel ratio, the gap before the values, and a canvas that measures 6 px a character
  const u = {
    axes: [{}, { font: ['11px sans-serif', 11, 11], gap: 5, ticks: { show: false, size: 10 }, _size: 52 }],
    ctx: { measureText: (text) => ({ width: 6 * text.length }) },
  };
  u.axes[2] = u.axes[1];
  for (const i of [1, 2]) {
    const axis = chart.plot.opts.axes[i];
    const sizeOf = (ticks) => (typeof axis.size === 'function' ? axis.size(u, ticks, i, 1) : axis.size);
    const ticks = axis.values(u, [0, 5e8, 1e9, 1.5e9]);
    assert.deepEqual(ticks, ['0', '500,000,000', '1,000,000,000', '1,500,000,000']);
    assert.ok(sizeOf(ticks) >= 6 * 13 + 5, `the widest tick and its gap fit in ${sizeOf(ticks)} px`);
    assert.equal(sizeOf(['0', '50', '100']), 52, 'short ticks keep the 52 px');
  }
  chart.destroy();
});

test('a value axis reaches under 0 when a value does, so a gauge below zero stays in the plot', () => {
  const t = [Date.now() - 60000, Date.now()];
  /** The range the chart gives uPlot for `values`, with uPlot's own min and max of them. */
  const rangeOf = (values, scale = 'y') => {
    const chart = timeSeries(document.createElement('div'), { t, series: [{ label: 'v', values, scale }] });
    const range = chart.plot.opts.scales[scale].range(chart.plot, Math.min(...values), Math.max(...values));
    chart.destroy();
    return range;
  };
  for (const values of [[-21, -18], [-21, 4], [-0.75, -0.25], [-3.5, 12.25]]) {
    const [low, high] = rangeOf(values);
    assert.ok(low < Math.min(...values) && high >= Math.max(...values), `[${values}] is drawn on [${low}, ${high}]`);
  }
  assert.deepEqual(rangeOf([-21, -18]), [-21 * 1.15, 0], 'all below zero: from under the lowest up to 0');
  assert.deepEqual(rangeOf([3, 7]), [0, 7 * 1.15], 'none below zero: from 0, as before');
  assert.deepEqual(rangeOf([0, 0]), [0, 1], 'a count at 0 keeps whole-number ticks');
  assert.deepEqual(rangeOf([0.2, 0.4], 'pct'), [0, 1], 'a percentage keeps its least of 1');
});

test('a time axis names the day under its first tick before today and under the first tick of each day after', () => {
  const now = Date.UTC(2026, 9, 15, 12, 0, 0) / 1000;
  const hours = (...hs) => hs.map((h) => now + h * 3600);
  assert.deepEqual(timeTicks(hours(-2, -1, 0), false, now * 1000), ['10:00:00', '11:00:00', '12:00:00'], 'today: the clock alone');
  assert.deepEqual(timeTicks(hours(-24, -12, 0), true, now * 1000), ['12:00\nOct 14', '00:00\nOct 15', '12:00'],
    'yesterday named under its first tick, today under its first');
});

test('a time-series tooltip names the day of a point before today', () => {
  const at = Date.now() - 2 * 86400000;
  const container = document.createElement('div');
  const chart = timeSeries(container, { t: [at, at + 60000], series: [{ label: 'Requests', values: [3, 4] }] });
  chart.plot.cursor = { idx: 0, left: 10, top: 10 };
  for (const hook of chart.plot.opts.hooks.setCursor) hook(chart.plot);
  const tip = container.querySelector('.chart-tip').innerHTML;
  assert.ok(tip.includes(`<div class="t">${fmt.day(at)} ${fmt.clock(at)}</div>`), tip);
  chart.destroy();
});

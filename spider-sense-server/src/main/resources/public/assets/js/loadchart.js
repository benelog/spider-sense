// The Requests | Load toggle and the chart spec behind it, shared by the Overview,
// the Service page and the Endpoint page. docs/ui.md Overview item 2.

import { h } from './ui.js';
import { bucketColors, bucketLabels } from './buckets.js';

/** The mode in the hash query; `fallback` is the page's default when `chart` is absent. */
export function chartMode(query, fallback = 'requests') {
  const v = (query || {}).chart;
  if (v === 'load' || v === 'requests') return v;
  return fallback;
}

/** Two buttons in the panel head; onChange gets 'requests' or 'load'. */
export function loadToggle(mode, onChange) {
  const node = h('div.row', { style: { gap: '2px' }, role: 'group', 'aria-label': 'Chart mode' });
  const make = (id, label) => h('button.btn', {
    type: 'button', 'aria-pressed': String(mode === id),
    onclick: () => { if (mode !== id) onChange(id); },
  }, label);
  node.appendChild(make('requests', 'Requests'));
  node.appendChild(make('load', 'Load'));
  return node;
}

/**
 * The throughput chart.
 * mode 'requests': requests in silk with errors stacked on top.
 * mode 'load': the four response-time buckets stacked bottom-up, errors on top.
 * `p95` adds the p95 line on the right axis.
 */
export function throughputSpec(series, mode, opts = {}) {
  const t = series.t || [];
  const colors = bucketColors();
  const labels = bucketLabels();
  const hist = series.histogram || [];
  const bars = mode === 'load'
    ? [0, 1, 2, 3].map((i) => ({
      label: labels[i], values: hist[i] || t.map(() => 0), color: colors[i], type: 'bar', scale: 'y', stack: 'load',
    })).concat([{ label: 'Errors', values: series.errors || [], color: 'err', type: 'bar', scale: 'y', stack: 'load' }])
    : [
      { label: 'Requests', values: series.requests || [], color: 'silk', type: 'bar', scale: 'y' },
      { label: 'Errors', values: series.errors || [], color: 'err', type: 'bar', scale: 'y' },
    ];
  const spec = {
    height: opts.height || 220,
    t,
    series: bars,
    axes: [{ scale: 'y', side: 3, label: opts.yLabel || 'Requests' }],
  };
  if (opts.p95) {
    spec.series = spec.series.concat([{ label: 'p95', values: series.p95Ms || [], color: 'accent', type: 'line', scale: 'ms', width: 2 }]);
    spec.axes.push({ scale: 'ms', side: 1, label: 'p95 (ms)', color: 'accent' });
  }
  return spec;
}

/** The legend that goes with it. */
export function throughputLegend(mode, opts = {}) {
  const colors = bucketColors();
  const labels = bucketLabels();
  const items = mode === 'load'
    ? [0, 1, 2, 3].map((i) => ({ label: labels[i], color: colors[i] })).concat([{ label: 'Errors', color: 'err' }])
    : [{ label: 'Requests per bucket', color: 'silk' }, { label: 'Errors', color: 'err' }];
  if (opts.p95) items.push({ label: 'p95 response time, right axis', color: 'accent' });
  return items;
}

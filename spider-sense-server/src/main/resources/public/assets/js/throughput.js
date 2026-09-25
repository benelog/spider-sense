// The Requests | Load switch and the chart spec behind it, shared by the Overview,
// the Service page and the Endpoint page. pages.adoc#overview item 2.

import * as api from './api.js';
import * as router from './router.js';
import { segmented } from './ui.js';
import { bucketColors, bucketLabels } from './buckets.js';

/** The mode the top bar's `chart` names, which the shell keeps in the shared state; `fallback` is the page's default. */
export function chartMode(fallback = 'requests') {
  return api.state.chart || fallback;
}

/**
 * The switch in the panel head; onChange gets 'requests' or 'load'. The choice is written to the
 * hash in full, Requests too: the Service page defaults to Load, and the choice crosses pages.
 * `sync()` re-reads it after a same-page hash change, which only calls a page's refresh().
 */
export function chartModeSwitch(fallback, onChange) {
  const node = segmented({
    label: 'Chart mode',
    options: [['requests', 'Requests'], ['load', 'Load']],
    value: chartMode(fallback),
    onChange: (mode) => {
      router.setQuery({ chart: mode });
      onChange(mode);
    },
  });
  node.sync = () => node.set(chartMode(fallback));
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
      { label: 'Requests', legendLabel: 'Requests per bucket', values: series.requests || [], color: 'silk', type: 'bar', scale: 'y' },
      { label: 'Errors', values: series.errors || [], color: 'err', type: 'bar', scale: 'y' },
    ];
  const spec = {
    height: opts.height || 220,
    t,
    series: bars,
    axes: [{ scale: 'y', side: 3, label: opts.yLabel || 'Requests' }],
  };
  if (opts.p95) {
    spec.series = spec.series.concat([{
      label: 'p95', legendLabel: 'p95 response time, right axis', values: series.p95Ms || [], color: 'accent', type: 'line', scale: 'ms', width: 2,
    }]);
    spec.axes.push({ scale: 'ms', side: 1, label: 'p95 (ms)', color: 'accent' });
  }
  return spec;
}

// Response-time buckets and Apdex: the bounds from /api/status, the five colours,
// the labels built from the bounds, and the response-summary bars.
// ui.adoc#response-buckets, api.adoc#buckets.

import * as api from './api.js';
import { h } from './ui.js';
import * as fmt from './format.js';

const DEFAULT_BOUNDS = [125, 500, 2000];

/** [T/4, T, 4T] from /api/status, or the default bounds before status has arrived. */
export function bucketBounds(status = api.state.status) {
  const t = (status || {}).thresholds || {};
  const b = t.responseBucketsMs;
  if (Array.isArray(b) && b.length === 3 && b.every((v) => typeof v === 'number' && v > 0)) return b;
  return DEFAULT_BOUNDS;
}

/** A bound as a label: "125 ms" under a second, "2 s" above. */
function boundLabel(ms) {
  if (ms < 1000) return fmt.dur(ms);
  const s = Math.round((ms / 1000) * 10) / 10;
  return s + ' s';
}

/** ['≤125 ms', '≤500 ms', '≤2 s', '>2 s', 'error'] */
export function bucketLabels(status = api.state.status) {
  const [a, b, c] = bucketBounds(status);
  return ['≤' + boundLabel(a), '≤' + boundLabel(b), '≤' + boundLabel(c), '>' + boundLabel(c), 'error'];
}

/** The colour token of each bucket, resolvable by charts.js and by CSS. */
export function bucketColors() {
  return ['bucket1', 'bucket2', 'bucket3', 'bucket4', 'err'];
}

const CSS_VARS = ['var(--bucket-1)', 'var(--bucket-2)', 'var(--bucket-3)', 'var(--bucket-4)', 'var(--err)'];

/** The same five colours as CSS variables, so a theme change needs no redraw. */
export function bucketVars() { return CSS_VARS.slice(); }

/** An error rate above this (1%) is shown as bad. */
export const ERROR_RATE_BAD = 0.01;

/** The slow-request threshold before /api/status has said it (configuration.adoc, `slow.request.ms`). */
export const DEFAULT_SLOW_REQUEST_MS = 500;

/** The slow-request threshold from /api/status, or the default before it has arrived. */
export function slowRequestMs(status = api.state.status) {
  return ((status || {}).thresholds || {}).slowRequestMs || DEFAULT_SLOW_REQUEST_MS;
}

/** '' up to 0.85, 'is-warn' under it, 'is-bad' under 0.7. */
export function apdexClass(value) {
  if (value == null) return '';
  if (value < 0.7) return 'is-bad';
  if (value < 0.85) return 'is-warn';
  return '';
}

/** An Apdex in a cell or a card: two decimals, coloured as `apdexClass` grades it. */
export function apdexCell(value, tag = 'span') {
  const grade = apdexClass(value);
  return h(tag, { class: grade === 'is-bad' ? 'bad' : grade === 'is-warn' ? 'warned' : null }, fmt.apdex(value));
}

/** The five counts as a title: "≤125 ms: 900 (75.0%)…". */
export function histogramTitle(histogram) {
  const labels = bucketLabels();
  const total = (histogram || []).reduce((a, b) => a + (b || 0), 0);
  return labels
    .map((l, i) => l + ': ' + fmt.count((histogram || [])[i] || 0) + (total ? ' (' + fmt.pct(((histogram || [])[i] || 0) / total) + ')' : ''))
    .join('\n');
}

/**
 * The response summary: five vertical bars in the bucket colours, the count above
 * each and the label beneath. `compact` is the 120x28 form for a header, with the
 * counts and the labels in the title instead.
 */
export function histogramBars(histogram, opts = {}) {
  const values = (histogram || []).slice(0, 5);
  while (values.length < 5) values.push(0);
  const total = values.reduce((a, b) => a + (b || 0), 0);
  if (!total) return h('span.muted', { class: 'muted hist-empty' }, '-');
  const labels = bucketLabels();
  const colors = bucketVars();
  const max = Math.max(...values.map((v) => v || 0), 1);
  const title = histogramTitle(values);
  return h('div.hist', {
    class: 'hist' + (opts.compact ? ' compact' : ''),
    title: opts.compact ? title : null,
    role: 'img',
    'aria-label': 'Response summary. ' + title.replace(/\n/g, ', '),
  }, values.map((v, i) => h('div.hist-bar', { title: opts.compact ? null : labels[i] + ': ' + fmt.count(v || 0) + (total ? ' (' + fmt.pct((v || 0) / total) + ')' : '') },
    h('span.hist-count', fmt.count(v || 0)),
    h('span.hist-track', h('span.hist-fill', { style: { height: Math.max(v ? 2 : 0, ((v || 0) / max) * 100) + '%', background: colors[i] } })),
    h('span.hist-label', labels[i]))));
}

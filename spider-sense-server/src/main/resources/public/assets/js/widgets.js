// The widgets more than one page shows, so that a page module is only a page: the trace table,
// the Overview's stat tiles, the RED charts, a finding's severity dot, kind chip and target, and
// a query group's schema block.

import * as api from './api.js';
import * as router from './router.js';
import { h, table, icon, chip, stat, serviceChip, statusChip, durationBar } from './ui.js';
import { chartBox } from './charts.js';
import { chartModeSwitch, throughputSpec } from './throughput.js';
import { apdexClass, fmtApdex } from './buckets.js';
import { dur, count, rate, pct, time, bothTimes, shortId } from './format.js';

// --- traces ---------------------------------------------------------------

/** The trace table of the Traces, Endpoint, Query, Error and Scatter pages. */
export function traceTable(rows, opts = {}) {
  const ref = { max: 0 };
  const recalc = (rs) => { ref.max = rs.reduce((m, r) => Math.max(m, r.durationMs || 0), 0); };
  recalc(rows);
  const columns = [
    { key: 'start', label: 'Time', sortable: false, width: '88px', render: (r) => h('span.mono', { title: bothTimes(r.start) }, time(r.start)) },
    {
      key: 'rootName', label: 'Root', sortable: false, cls: 'wide',
      render: (r) => h('div',
        h('div.cell-ellipsis', { title: r.rootName }, h('b', r.rootName)),
        h('div.row', { style: { gap: '4px', marginTop: '2px' } },
          (r.services || []).slice(0, 3).map((s) => serviceChip(s)),
          (r.services || []).length > 3 ? h('span.muted', '+' + ((r.services || []).length - 3)) : null)),
    },
    {
      key: 'durationMs', label: 'Duration', align: 'right', sortable: false, width: '110px',
      render: (r) => h('div.duration-cell',
        h('span', { class: r.error ? 'bad' : r.slow ? 'warned' : '' }, dur(r.durationMs)),
        durationBar(r.durationMs, ref.max, r.error ? 'err' : r.slow ? 'slow' : null)),
    },
    { key: 'spanCount', label: 'Spans', align: 'right', sortable: false, width: '58px', render: (r) => count(r.spanCount) },
    { key: 'dbCount', label: 'DB', align: 'right', sortable: false, width: '48px', render: (r) => (r.dbCount ? count(r.dbCount) : h('span.muted', '-')) },
    { key: 'httpStatus', label: 'Status', align: 'right', sortable: false, width: '58px', render: (r) => statusChip(r.httpStatus) },
    {
      key: 'marks', label: '', sortable: false, width: '58px',
      render: (r) => h('span.row', { style: { gap: '4px' } },
        r.error ? h('span.marker.err', { title: 'Error' }, icon('bolt')) : null,
        r.slow ? h('span.marker.slow', { title: 'Slow request' }, icon('turtle')) : null),
    },
  ];
  if (opts.showId) {
    columns.splice(1, 0, { key: 'traceId', label: 'Trace', sortable: false, width: '92px', render: (r) => h('span.mono.muted', { title: r.traceId }, shortId(r.traceId)) });
  }
  const rowOpts = {
    rowKey: (r) => r.traceId,
    rowClass: (r) => (r.error ? 'is-error' : r.slow ? 'is-slow' : ''),
    onRowClick: opts.onRowClick || ((r) => router.openDetail('traces', r.traceId)),
    empty: opts.empty || 'No trace matches this window and filter.',
  };
  const node = table(columns, { rows, ...rowOpts });
  /** Replace the rows in place: scroll position and the sort header survive. */
  const setRows = node.setRows;
  node.setRows = (rs) => { recalc(rs); return setRows(rs); };
  return node;
}

// --- a service's numbers and charts --------------------------------------

/** The seven tiles of pages.adoc#overview item 1; the Service page shows the same row. */
export function statTiles(totals, thresholds) {
  const t = totals || {};
  const slow = (thresholds && thresholds.slowRequestMs) || 500;
  return [
    stat(count(t.requests), 'total', 'requests'),
    stat(fmtApdex(t.apdex), '', 'apdex', { class: apdexClass(t.apdex), title: 'Apdex, T = ' + dur(slow) }),
    stat(pct(t.errorRate || 0), '', 'error rate', { class: t.errorRate > 0.01 ? 'is-bad' : '' }),
    stat(dur(t.p50Ms), '', 'p50'),
    stat(dur(t.p95Ms), '', 'p95', { class: t.p95Ms > slow ? 'is-warn' : '' }),
    stat(dur(t.p99Ms), '', 'p99'),
    stat(rate(t.rps || 0), '/s', 'requests per second'),
  ];
}

/**
 * The three RED charts of the Service and Endpoint pages. The first carries the
 * Requests | Load switch; `chart` in the hash query wins, Load is the default here.
 */
export function redCharts() {
  let lastSeries = {};
  const modeSwitch = chartModeSwitch('load', () => node.apply(lastSeries));
  const charts = [
    chartBox({ title: 'Requests and errors', actions: modeSwitch }),
    chartBox({ title: 'Response time' }),
    chartBox({ title: 'Error rate' }),
  ];
  const node = h('div.grid-3', charts.map((c) => c.node));

  /** A same-page hash change only calls refresh(), so `chart` is re-read here. */
  node.syncMode = () => modeSwitch.sync();

  node.apply = (series) => {
    lastSeries = series;
    const t = series.t || [];
    const specs = [
      throughputSpec(series, modeSwitch.value(), { height: 160 }),
      {
        height: 160, t,
        series: [
          { label: 'p50', values: series.p50Ms || [], color: 'series5', type: 'line', scale: 'ms', width: 1.6 },
          { label: 'p95', values: series.p95Ms || [], color: 'accent', type: 'line', scale: 'ms', width: 2 },
          { label: 'p99', values: series.p99Ms || [], color: 'warn', type: 'line', scale: 'ms', width: 1.6, dash: [4, 3] },
        ],
        axes: [{ scale: 'ms', label: 'ms' }],
      },
      {
        height: 160, t,
        series: [{
          label: 'Error rate', legendLabel: 'Errors as a share of requests',
          values: errorRate(series), color: 'err', type: 'area', scale: 'pct', width: 2,
        }],
        axes: [{ scale: 'pct', label: '%' }],
      },
    ];
    specs.forEach((spec, i) => charts[i].show(spec));
  };
  node.destroy = () => charts.forEach((c) => c.destroy());
  return node;
}

function errorRate(series) {
  const req = series.requests || [];
  const err = series.errors || [];
  return req.map((r, i) => (r ? ((err[i] || 0) / r) * 100 : null));
}

// --- findings -------------------------------------------------------------

const KIND_LABEL = {
  regression: 'regression',
  error: 'error',
  'log-error': 'log error',
  'n-plus-one': 'n+1',
  'n-plus-one-http': 'n+1 http',
  'slow-query': 'slow query',
  'slow-endpoint': 'slow endpoint',
  'slow-job': 'slow job',
  'slow-external': 'slow external',
  'pool-exhausted': 'pool',
  'gc-pause': 'gc pause',
  'heap-pressure': 'heap',
  'thread-growth': 'threads',
};

export function severityDot(severity) {
  const s = severity || 'low';
  return h('span.sev-dot', { class: 'sev-dot sev-' + s, title: s + ' severity' });
}

export function kindChip(kind) {
  return chip(KIND_LABEL[kind] || kind, { class: 'chip-kind', title: kind });
}

/**
 * Where a finding points: its subject's page, and the first evidence trace when the
 * subject has no page of its own (a job) or names nothing (pages.adoc#findings).
 */
export function findingTarget(finding, shared = api.sharedQuery()) {
  const subject = finding.subject || {};
  if (subject.endpointId) return { path: router.detailPath('endpoints', subject.endpointId), query: shared };
  if (subject.queryId) return { path: router.detailPath('queries', subject.queryId), query: shared };
  if (subject.errorId) return { path: router.detailPath('errors', subject.errorId), query: shared };
  if (subject.pool || subject.jvm) return { path: '/jvm', query: { ...shared, service: finding.service || shared.service } };
  if (subject.logger) {
    return {
      path: '/logs',
      query: { ...shared, service: finding.service || shared.service, severity: 'ERROR', q: subject.logger },
    };
  }
  const trace = (finding.traces || [])[0];
  if (trace) return { path: router.detailPath('traces', trace), query: shared };
  return null;
}

export function goToFinding(finding) {
  const target = findingTarget(finding);
  if (target) router.go(target.path, target.query);
}

/**
 * The schema block under the statement (findings.adoc#schema), as the
 * text rendering has it: one line per table, then one line for the columns.
 *
 * <p>Nothing is computed here. The tables, the predicates and the unindexed columns
 * are the ones the API carries, so the page and the CLI say the same thing; a
 * finding whose block is null shows nothing.
 */
export function schemaLines(schema) {
  if (!schema) return null;
  const predicates = schema.predicates || [];
  const unindexed = schema.unindexed || [];
  return h('div.f-schema.mono',
    (schema.tables || []).map((t) => h('div.f-schema-line',
      h('span.muted', 'indexes ' + t.table + ': '),
      (t.indexes || []).length
        ? (t.indexes || []).map((index, i) => h('span',
          i ? ', ' : null,
          (index.name || '') + ' (' + (index.columns || []).join(', ') + ')',
          index.unique ? h('span.muted', ' unique') : null))
        : h('span.muted', 'none'))),
    h('div.f-schema-line',
      h('span.muted', 'predicates: '),
      predicates.length
        ? [predicates.join(', '),
          h('span.muted', '; unindexed: '),
          unindexed.length ? h('span.accent', unindexed.join(', ')) : h('span.muted', 'none')]
        : h('span.muted', 'none')));
}

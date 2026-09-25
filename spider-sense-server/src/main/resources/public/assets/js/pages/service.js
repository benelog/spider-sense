// One service: resource header, RED charts, endpoints, top queries and errors,
// dependencies and the resource attributes.

import * as api from '../api.js';
import * as router from '../router.js';
import {
  h, fill, icon, panel, table, chip, methodChip, statusBar, comparator,
  spinner, serviceColor,
} from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { chartBox } from '../charts.js';
import { histogramBars, apdexCell } from '../buckets.js';
import { chartModeSwitch, throughputSpec } from '../loadchart.js';
import { statTiles } from './overview.js';
import { statementColumn, errorTypeColumn, messageColumn, seenColumn, durationColumn, countColumn } from '../columns.js';
import { count, rate, rel, bothTimes } from '../format.js';

/**
 * The three RED charts, shared with the Endpoint page. The first carries the
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

/** The endpoints table, shared with the Service page only for now. */
export function endpointTable(rows, sortState, onSort) {
  const columns = [
    { key: 'method', label: 'Method', width: '68px', render: (e) => methodChip(e.method) || h('span.muted', '-') },
    { key: 'route', label: 'Endpoint', cls: 'wide', render: (e) => h('span.cell-ellipsis', { title: e.name }, e.route || e.name) },
    countColumn('calls', 'Calls'),
    { key: 'rps', label: 'rps', align: 'right', width: '62px', render: (e) => rate(e.rps || 0) },
    { key: 'apdex', label: 'Apdex', align: 'right', width: '66px', render: (e) => apdexCell(e.apdex) },
    durationColumn('avgMs', 'avg'),
    durationColumn('p50Ms', 'p50'),
    durationColumn('p95Ms', 'p95'),
    durationColumn('p99Ms', 'p99'),
    durationColumn('maxMs', 'max'),
    { key: 'errors', label: 'Errors', align: 'right', width: '66px', render: (e) => (e.errors ? h('span.bad', count(e.errors)) : h('span.muted', '0')) },
    { key: 'statusCodes', label: 'Status', sortable: false, width: '80px', render: (e) => statusBar(e.statusCodes) },
    durationColumn('totalMs', 'Total time', '92px'),
  ];
  const opts = {
    rowKey: (e) => e.endpointId,
    onRowClick: (e) => router.openDetail('endpoints', e.endpointId),
    empty: 'No endpoint in this window.',
  };
  const node = table(columns, { ...opts, rows, sort: sortState, onSort });
  return node;
}

export function render(root, ctx) {
  const name = ctx.params.name;
  let sort = { key: ctx.query.sort || 'totalMs', dir: ctx.query.dir === 'asc' ? 'asc' : 'desc' };
  let endpoints = [];
  let endpointNode = null;

  ctx.setTitle(name);

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const statsRow = h('div.stat-row');
  const red = redCharts();
  const endpointBody = h('div', spinner());
  const endpointPanel = panel({ title: 'Endpoints' }, endpointBody);
  const queriesBody = h('div');
  const errorsBody = h('div');
  const half = h('div.grid-2',
    panel({ title: 'Top queries', actions: h('a.link-btn', { href: router.href('/queries', { ...api.sharedQuery(), service: name }) }, 'All queries') }, queriesBody),
    panel({ title: 'Top errors', actions: h('a.link-btn', { href: router.href('/errors', { ...api.sharedQuery(), service: name }) }, 'All errors') }, errorsBody));
  const depsBody = h('div');
  const depsPanel = panel({ title: 'Dependencies' }, depsBody);
  const resourceBody = h('div');
  const resourcePanel = h('section.panel',
    h('details.collapsible', h('summary', 'Resource attributes'), resourceBody));

  const layout = skeleton(root, () => [headPanel, statsRow, red, endpointPanel, half, depsPanel, resourcePanel]);

  function paintHead(summary, resource) {
    const r = resource || {};
    fill(head,
      h('div.row', { style: { gap: '10px' } },
        h('span.dot', { style: { background: serviceColor(name), width: '10px', height: '10px', borderRadius: '50%' } }),
        h('b', { style: { fontSize: '15px' } }, name),
        summary.embedded ? chip('embedded', { class: 'chip-accent' }) : null,
        summary.language ? chip(summary.language) : null,
        r['process.runtime.name'] ? chip(r['process.runtime.name'] + ' ' + (r['process.runtime.version'] || '')) : null,
        r['host.name'] ? chip(r['host.name'], { title: 'host.name' }) : null,
        r['process.pid'] ? chip('pid ' + r['process.pid']) : null),
      h('div.row', { style: { marginLeft: 'auto', gap: '12px' } },
        h('span.muted', { style: { fontSize: '11px' }, title: bothTimes(summary.lastSeen) }, 'last seen ' + rel(summary.lastSeen)),
        summary.hasJvm ? h('a.btn', { href: router.href('/jvm', { ...api.sharedQuery(), service: name }) }, icon('jvm'), 'JVM') : null,
        h('a.btn', { href: router.href('/traces', { ...api.sharedQuery(), service: name }) }, icon('trace'), 'Traces'),
        histogramBars(summary.histogram, { compact: true })));
  }

  function sortedEndpoints() {
    return endpoints.slice().sort(comparator(sort, { route: (e) => e.route || e.name }));
  }

  function onSort(key) {
    sort = { key, dir: sort.key === key && sort.dir === 'desc' ? 'asc' : 'desc' };
    router.setQuery({ sort: key, dir: sort.dir });
    endpointNode = null;
    paintEndpoints();
  }

  function paintEndpoints() {
    if (!endpointNode) {
      endpointNode = endpointTable(sortedEndpoints(), sort, onSort);
      fill(endpointBody, endpointNode);
    } else {
      endpointNode.setRows(sortedEndpoints());
    }
  }

  // The three tables under the endpoints are built once, and a Live refresh gives them new
  // rows, so a focused row and a scrolled table survive it (ui.adoc#live-refresh).
  const queriesTable = table([
    statementColumn(140),
    countColumn('calls', 'Calls', '62px'),
    durationColumn('avgMs', 'avg', '72px'),
    durationColumn('p95Ms', 'p95', '72px'),
    durationColumn('totalMs', 'Total', '80px'),
  ], {
    rowKey: (q) => q.queryId,
    onRowClick: (q) => router.openDetail('queries', q.queryId),
    empty: 'No database call in this window.',
  });
  fill(queriesBody, queriesTable);

  const errorsTable = table([
    errorTypeColumn({ short: true }),
    messageColumn(90),
    { key: 'count', label: 'Count', align: 'right', width: '62px', render: (e) => h('span.bad', count(e.count)) },
    seenColumn('lastSeen', 'Last seen', '86px'),
  ], {
    rowKey: (e) => e.errorId,
    onRowClick: (e) => router.openDetail('errors', e.errorId),
    empty: 'No error in this window.',
  });
  fill(errorsBody, errorsTable);

  const depsTable = table([
    { key: 'kind', label: 'Kind', sortable: false, width: '80px', render: (d) => h('span.row', { style: { gap: '6px' } }, icon(d.kind === 'db' ? 'database' : d.kind === 'http' ? 'trace' : 'service'), d.kind) },
    { key: 'target', label: 'Target', sortable: false, cls: 'wide', render: (d) => h('span.cell-ellipsis.mono', { title: d.target }, d.target) },
    countColumn('calls', 'Calls'),
    { key: 'errors', label: 'Errors', align: 'right', width: '66px', render: (d) => (d.errors ? h('span.bad', count(d.errors)) : h('span.muted', '0')) },
    durationColumn('avgMs', 'avg'),
    durationColumn('p95Ms', 'p95'),
  ], {
    rowKey: (d) => d.kind + '|' + d.target,
    empty: 'This service called nothing else in this window.',
  });
  fill(depsBody, depsTable);

  function paintResource(resource) {
    const entries = Object.entries(resource || {});
    fill(resourceBody, h('div', { style: { padding: '0 14px 14px' } },
      entries.length
        ? h('dl.kv', entries.map(([k, v]) => [h('dt', k), h('dd', String(v))]))
        : h('span.muted', 'No resource attribute.')));
  }

  const loader = pageLoader({
    fetch: () => api.service(name),
    paint: (data) => {
      layout.build();
      paintHead(data.service || {}, data.resource);
      fill(statsRow, statTiles(data.service || {}, (api.state.status || {}).thresholds));
      red.syncMode();
      red.apply(data.series || {});
      endpoints = data.endpoints || [];
      paintEndpoints();
      queriesTable.setRows(data.queries || []);
      errorsTable.setRows(data.errors || []);
      depsTable.setRows(data.dependencies || []);
      paintResource(data.resource);
    },
    body: layout,
  });

  loader.load();
  return { refresh: loader.load, destroy: () => { loader.destroy(); red.destroy(); } };
}

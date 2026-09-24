// One service: resource header, RED charts, endpoints, top queries and errors,
// dependencies and the resource attributes.

import * as api from '../api.js';
import * as router from '../router.js';
import {
  h, fill, icon, panel, table, fillRows, chip, methodChip, statusBar, comparator,
  spinner, errorBox, serviceColor,
} from '../ui.js';
import { timeSeries, legend } from '../charts.js';
import { histogramBars, apdexClass, fmtApdex } from '../buckets.js';
import { chartMode, loadToggle, throughputSpec, throughputLegend } from '../loadchart.js';
import { statTiles } from './overview.js';
import { oneLineSql } from '../sql.js';
import { dur, count, rate, pct, rel, bothTimes, truncate } from '../format.js';

/**
 * The three RED charts, shared with the Endpoint page. The first carries the
 * Requests | Load toggle; `chart` in the hash query wins, Load is the default here.
 */
export function redCharts(opts = {}) {
  const bodies = [h('div.chart'), h('div.chart'), h('div.chart')];
  const legends = [h('div'), h('div'), h('div')];
  const charts = [null, null, null];
  let mode = chartMode(opts.query, 'load');
  let lastSeries = {};
  const modeBox = h('div.row', { style: { gap: '2px' } });
  const node = h('div.grid-3',
    panel({ title: 'Requests and errors', actions: modeBox }, legends[0], bodies[0]),
    panel({ title: 'Response time' }, legends[1], bodies[1]),
    panel({ title: 'Error rate' }, legends[2], bodies[2]));

  function paintModeToggle() {
    fill(modeBox, loadToggle(mode, (next) => {
      mode = next;
      // written out in full, so the Overview and this page share the choice
      router.setQuery({ chart: next });
      paintModeToggle();
      node.apply(lastSeries);
    }));
  }
  paintModeToggle();

  /** A same-page hash change only calls refresh(), so `chart` is re-read here. */
  node.syncMode = () => {
    const next = chartMode(router.currentRoute().query, 'load');
    if (next === mode) return;
    mode = next;
    paintModeToggle();
  };

  node.apply = (series) => {
    lastSeries = series;
    const t = series.t || [];
    const specs = [
      throughputSpec(series, mode, { height: 160 }),
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
        series: [{ label: 'Error rate', values: errorRate(series), color: 'err', type: 'area', scale: 'pct', width: 2 }],
        axes: [{ scale: 'pct', label: '%' }],
      },
    ];
    const legendSets = [
      throughputLegend(mode),
      [{ label: 'p50', color: 'series5' }, { label: 'p95', color: 'accent' }, { label: 'p99', color: 'warn' }],
      [{ label: 'Errors as a share of requests', color: 'err' }],
    ];
    specs.forEach((spec, i) => {
      fill(legends[i], legend(legendSets[i]));
      if (charts[i]) charts[i].update(spec);
      else charts[i] = timeSeries(bodies[i], spec);
    });
  };
  node.destroy = () => charts.forEach((c) => c && c.destroy());
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
    { key: 'calls', label: 'Calls', align: 'right', width: '72px', render: (e) => count(e.calls) },
    { key: 'rps', label: 'rps', align: 'right', width: '62px', render: (e) => rate(e.rps || 0) },
    {
      key: 'apdex', label: 'Apdex', align: 'right', width: '66px',
      render: (e) => h('span', { class: apdexClass(e.apdex) === 'is-bad' ? 'bad' : apdexClass(e.apdex) === 'is-warn' ? 'warned' : '' }, fmtApdex(e.apdex)),
    },
    { key: 'avgMs', label: 'avg', align: 'right', width: '74px', render: (e) => dur(e.avgMs) },
    { key: 'p50Ms', label: 'p50', align: 'right', width: '74px', render: (e) => dur(e.p50Ms) },
    { key: 'p95Ms', label: 'p95', align: 'right', width: '74px', render: (e) => dur(e.p95Ms) },
    { key: 'p99Ms', label: 'p99', align: 'right', width: '74px', render: (e) => dur(e.p99Ms) },
    { key: 'maxMs', label: 'max', align: 'right', width: '74px', render: (e) => dur(e.maxMs) },
    { key: 'errors', label: 'Errors', align: 'right', width: '66px', render: (e) => (e.errors ? h('span.bad', count(e.errors)) : h('span.muted', '0')) },
    { key: 'statusCodes', label: 'Status', sortable: false, width: '80px', render: (e) => statusBar(e.statusCodes) },
    { key: 'totalMs', label: 'Total time', align: 'right', width: '92px', render: (e) => dur(e.totalMs) },
  ];
  const opts = {
    rowKey: (e) => e.endpointId,
    onRowClick: (e) => router.go('/endpoints/' + encodeURIComponent(e.endpointId), api.sharedQuery()),
    empty: 'No endpoint in this window.',
  };
  const node = table(columns, { ...opts, rows, sort: sortState, onSort });
  node.setRows = (rs) => fillRows(node, rs, opts);
  return node;
}

export function render(root, ctx) {
  const name = ctx.params.name;
  let destroyed = false;
  const latest = api.requestSequence();
  let sort = { key: ctx.query.sort || 'totalMs', dir: ctx.query.dir === 'asc' ? 'asc' : 'desc' };
  let endpoints = [];
  let endpointNode = null;

  ctx.setTitle(name);

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const statsRow = h('div.stat-row');
  const red = redCharts({ query: ctx.query });
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

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, headPanel, statsRow, red, endpointPanel, half, depsPanel, resourcePanel);
  }

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

  function paintQueries(list) {
    fill(queriesBody, table([
      { key: 'statement', label: 'Statement', sortable: false, cls: 'wide', render: (q) => h('span.cell-ellipsis.mono', { title: q.statement }, oneLineSql(q.statement, 140)) },
      { key: 'calls', label: 'Calls', align: 'right', sortable: false, width: '62px', render: (q) => count(q.calls) },
      { key: 'avgMs', label: 'avg', align: 'right', sortable: false, width: '72px', render: (q) => dur(q.avgMs) },
      { key: 'p95Ms', label: 'p95', align: 'right', sortable: false, width: '72px', render: (q) => dur(q.p95Ms) },
      { key: 'totalMs', label: 'Total', align: 'right', sortable: false, width: '80px', render: (q) => dur(q.totalMs) },
    ], {
      rows: list,
      rowKey: (q) => q.queryId,
      onRowClick: (q) => router.go('/queries/' + encodeURIComponent(q.queryId), api.sharedQuery()),
      empty: 'No database call in this window.',
    }));
  }

  function paintErrors(list) {
    fill(errorsBody, table([
      { key: 'type', label: 'Type', sortable: false, cls: 'wide', render: (e) => h('span.cell-ellipsis.mono', { title: e.type }, shortType(e.type)) },
      { key: 'message', label: 'Message', sortable: false, cls: 'wide', render: (e) => h('span.cell-ellipsis', { title: e.message }, truncate(e.message, 90)) },
      { key: 'count', label: 'Count', align: 'right', sortable: false, width: '62px', render: (e) => h('span.bad', count(e.count)) },
      { key: 'lastSeen', label: 'Last seen', align: 'right', sortable: false, width: '86px', render: (e) => h('span', { title: bothTimes(e.lastSeen) }, rel(e.lastSeen)) },
    ], {
      rows: list,
      rowKey: (e) => e.errorId,
      onRowClick: (e) => router.go('/errors/' + encodeURIComponent(e.errorId), api.sharedQuery()),
      empty: 'No error in this window.',
    }));
  }

  function paintDeps(list) {
    fill(depsBody, table([
      { key: 'kind', label: 'Kind', sortable: false, width: '80px', render: (d) => h('span.row', { style: { gap: '6px' } }, icon(d.kind === 'db' ? 'database' : d.kind === 'http' ? 'trace' : 'service'), d.kind) },
      { key: 'target', label: 'Target', sortable: false, cls: 'wide', render: (d) => h('span.cell-ellipsis.mono', { title: d.target }, d.target) },
      { key: 'calls', label: 'Calls', align: 'right', sortable: false, width: '72px', render: (d) => count(d.calls) },
      { key: 'errors', label: 'Errors', align: 'right', sortable: false, width: '66px', render: (d) => (d.errors ? h('span.bad', count(d.errors)) : h('span.muted', '0')) },
      { key: 'avgMs', label: 'avg', align: 'right', sortable: false, width: '74px', render: (d) => dur(d.avgMs) },
      { key: 'p95Ms', label: 'p95', align: 'right', sortable: false, width: '74px', render: (d) => dur(d.p95Ms) },
    ], { rows: list, rowKey: (d) => d.kind + '|' + d.target, empty: 'This service called nothing else in this window.' }));
  }

  function paintResource(resource) {
    const entries = Object.entries(resource || {});
    fill(resourceBody, h('div', { style: { padding: '0 14px 14px' } },
      entries.length
        ? h('dl.kv', entries.map(([k, v]) => [h('dt', k), h('dd', String(v))]))
        : h('span.muted', 'No resource attribute.')));
  }

  async function load() {
    const current = latest();
    try {
      const data = await api.service(name);
      if (destroyed || !current()) return;
      build();
      paintHead(data.service || {}, data.resource);
      fill(statsRow, statTiles(data.service || {}, (api.state.status || {}).thresholds));
      red.syncMode();
      red.apply(data.series || {});
      endpoints = data.endpoints || [];
      paintEndpoints();
      paintQueries(data.queries || []);
      paintErrors(data.errors || []);
      paintDeps(data.dependencies || []);
      paintResource(data.resource);
    } catch (e) {
      if (destroyed || !current()) return;
      built = false;
      fill(page, errorBox(e, load));
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; red.destroy(); } };
}

function shortType(type) {
  if (!type) return '-';
  const i = type.lastIndexOf('.');
  return i < 0 ? type : type.slice(i + 1);
}

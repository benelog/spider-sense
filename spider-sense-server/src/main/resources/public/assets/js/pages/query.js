// One query group: the statement, stats, a calls/p95 chart, callers and slowest traces.

import * as api from '../api.js';
import { h, fill, panel, stat, table, chip, serviceChip, copyBlock } from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { chartBox } from '../charts.js';
import { formatSql } from '../sql.js';
import { traceTable } from './traces.js';
import { schemaLines } from './findings.js';
import { copyButtons, cliLine } from '../copyas.js';
import { serviceColumn, countColumn } from '../columns.js';
import { dur, count, rel, bothTimes } from '../format.js';

export function render(root, ctx) {
  const id = ctx.params.id;
  let loaded = null;

  const head = h('div', { style: { padding: '14px', display: 'grid', gap: '10px' } });
  const headPanel = panel({ title: 'Statement' }, head);
  const statsRow = h('div.stat-row');
  const chart = chartBox({ title: 'Calls and p95' });
  // The two tables are built once, and a Live refresh gives them new rows, so a focused row
  // and a scrolled table survive it (ui.adoc#live-refresh).
  const callersTable = table([
    { key: 'endpoint', label: 'Endpoint', cls: 'wide', render: (c) => h('span.cell-ellipsis', { title: c.endpoint }, c.endpoint) },
    serviceColumn(),
    countColumn('calls', 'Calls'),
  ], {
    rowKey: (c) => c.service + '|' + c.endpoint,
    empty: 'No caller recorded.',
  });
  const tracesTable = traceTable([], { empty: 'No trace contains this query in this window.' });
  const callersBody = h('div', callersTable);
  const tracesBody = h('div', tracesTable);
  const half = h('div.grid-2',
    panel({ title: 'Callers' }, callersBody),
    panel({ title: 'Slowest traces' }, tracesBody));

  const layout = skeleton(root, () => [headPanel, statsRow, chart.node, half]);

  function paint({ win, data }) {
    const q = data.query || {};
    loaded = { window: win, service: q.service };
    layout.build();
    ctx.setTitle((q.operation || 'Query') + (q.table ? ' ' + q.table : ''));
    fill(head,
      h('div.row', { style: { gap: '8px' } },
        q.system ? chip(q.system) : null,
        q.namespace ? chip(q.namespace, { title: 'db.namespace' }) : null,
        q.operation ? chip(q.operation) : null,
        q.table ? chip(q.table, { title: 'db.sql.table' }) : null,
        serviceChip(q.service),
        h('span.muted', { style: { marginLeft: 'auto', fontSize: '11px' }, title: bothTimes(q.lastSeen) }, 'last seen ' + rel(q.lastSeen))),
      copyBlock(formatSql(q.statement || '')),
      schemaLines(q.schema),
      h('div.row', copyButtons({
        markdown: () => ({ path: '/api/queries/' + encodeURIComponent(id), query: api.params({}, { window: loaded.window, service: null }) }),
        cli: () => cliLine('queries', loaded.window, loaded.service),
      })));

    fill(statsRow,
      stat(count(q.calls), '', 'calls'),
      stat(dur(q.avgMs), '', 'average'),
      stat(dur(q.p50Ms), '', 'p50'),
      stat(dur(q.p95Ms), '', 'p95'),
      stat(dur(q.maxMs), '', 'max'),
      stat(dur(q.totalMs), '', 'total time'),
      stat(count(q.slowCalls), '', 'slow calls', { class: q.slowCalls ? 'is-warn' : '' }));

    const series = data.series || {};
    const spec = {
      height: 180,
      t: series.t || [],
      series: [
        { label: 'Calls', legendLabel: 'Calls per bucket', values: series.calls || [], color: 'silk', type: 'bar' },
        { label: 'p95', legendLabel: 'p95, right axis', values: series.p95Ms || [], color: 'accent', type: 'line', scale: 'ms', width: 2 },
      ],
      axes: [{ scale: 'y', label: 'Calls' }, { scale: 'ms', side: 1, label: 'p95 (ms)', color: 'accent' }],
    };
    chart.show(spec);

    callersTable.setRows(q.callers || []);
    tracesTable.setRows(data.traces || []);
  }

  const loader = pageLoader({
    fetch: async () => {
      const win = api.windowFor();
      return { win, data: await api.query(id, { window: win }) };
    },
    paint,
    body: layout,
  });

  loader.load();
  return { refresh: loader.load, destroy: () => { loader.destroy(); chart.destroy(); } };
}

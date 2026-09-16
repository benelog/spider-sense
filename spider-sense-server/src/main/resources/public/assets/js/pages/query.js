// One query group: the statement, stats, a calls/p95 chart, callers and slowest traces.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, stat, table, chip, serviceChip, copyBlock, spinner, errorBox } from '../ui.js';
import { timeSeries, legend } from '../charts.js';
import { formatSql } from '../sql.js';
import { traceTable } from './traces.js';
import { dur, count, rel, bothTimes } from '../format.js';

export function render(root, ctx) {
  const id = ctx.params.id;
  let destroyed = false;
  let chart = null;

  const head = h('div', { style: { padding: '14px', display: 'grid', gap: '10px' } });
  const headPanel = panel({ title: 'Statement' }, head);
  const statsRow = h('div.stat-row');
  const chartBody = h('div.chart');
  const chartLegend = h('div');
  const chartPanel = panel({ title: 'Calls and p95' }, chartLegend, chartBody);
  const callersBody = h('div');
  const tracesBody = h('div');
  const half = h('div.grid-2',
    panel({ title: 'Callers' }, callersBody),
    panel({ title: 'Slowest traces' }, tracesBody));

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, headPanel, statsRow, chartPanel, half);
  }

  async function load() {
    try {
      const data = await api.query(id);
      if (destroyed) return;
      const q = data.query || {};
      build();
      ctx.setTitle((q.operation || 'Query') + (q.table ? ' ' + q.table : ''));
      fill(head,
        h('div.row', { style: { gap: '8px' } },
          q.system ? chip(q.system) : null,
          q.namespace ? chip(q.namespace, { title: 'db.namespace' }) : null,
          q.operation ? chip(q.operation) : null,
          q.table ? chip(q.table, { title: 'db.sql.table' }) : null,
          serviceChip(q.service),
          h('span.muted', { style: { marginLeft: 'auto', fontSize: '11px' }, title: bothTimes(q.lastSeen) }, 'last seen ' + rel(q.lastSeen))),
        copyBlock(formatSql(q.statement || '')));

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
          { label: 'Calls', values: series.calls || [], color: 'silk', type: 'bar' },
          { label: 'p95', values: series.p95Ms || [], color: 'accent', type: 'line', scale: 'ms', width: 2 },
        ],
        axes: [{ scale: 'y', label: 'Calls' }, { scale: 'ms', side: 1, label: 'p95 (ms)', color: 'accent' }],
      };
      fill(chartLegend, legend([{ label: 'Calls per bucket', color: 'silk' }, { label: 'p95, right axis', color: 'accent' }]));
      if (chart) chart.update(spec); else chart = timeSeries(chartBody, spec);

      fill(callersBody, table([
        { key: 'endpoint', label: 'Endpoint', sortable: false, cls: 'wide', render: (c) => h('span.cell-ellipsis', { title: c.endpoint }, c.endpoint) },
        { key: 'service', label: 'Service', sortable: false, width: '150px', render: (c) => serviceChip(c.service) },
        { key: 'calls', label: 'Calls', align: 'right', sortable: false, width: '72px', render: (c) => count(c.calls) },
      ], { rows: q.callers || [], rowKey: (c) => c.service + '|' + c.endpoint, empty: 'No caller recorded.' }));

      fill(tracesBody, traceTable(data.traces || [], { empty: 'No trace contains this query in this window.' }));
    } catch (e) {
      if (destroyed) return;
      built = false;
      fill(page, errorBox(e, load));
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; if (chart) chart.destroy(); } };
}

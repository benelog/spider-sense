// One error group: count over time, the sample stack trace, endpoints, recent traces.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, stat, table, serviceChip, idButton, spinner, errorBox } from '../ui.js';
import { timeSeries, legend } from '../charts.js';
import { stackTrace } from '../sql.js';
import { codeFrame } from '../frames.js';
import { traceTable } from './traces.js';
import { count, rel, bothTimes, full, splitType } from '../format.js';

export function render(root, ctx) {
  const id = ctx.params.id;
  let destroyed = false;
  let chart = null;

  const head = h('div', { style: { padding: '14px', display: 'grid', gap: '8px' } });
  const headPanel = panel({}, head);
  const statsRow = h('div.stat-row');
  const chartBody = h('div.chart');
  const chartLegend = h('div');
  const chartPanel = panel({ title: 'Occurrences' }, chartLegend, chartBody);
  const stackBody = h('div', { style: { padding: '14px' } });
  const stackPanel = panel({ title: 'Sample stack trace' }, stackBody);
  const endpointsBody = h('div');
  const tracesBody = h('div');
  const half = h('div.grid-2',
    panel({ title: 'Endpoints' }, endpointsBody),
    panel({ title: 'Recent traces' }, tracesBody));

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, headPanel, statsRow, chartPanel, stackPanel, half);
  }

  async function load() {
    try {
      const data = await api.errorGroup(id);
      if (destroyed) return;
      const e = data.error || {};
      build();
      const { pkg, name } = splitType(e.type);
      ctx.setTitle(name || 'Error');
      fill(head,
        h('div.row', { style: { gap: '8px' } },
          h('span.mono', { style: { fontSize: '14px' } }, h('span.muted', pkg), h('b', name)),
          serviceChip(e.service)),
        h('div', { style: { color: 'var(--err)' } }, e.message || ''),
        e.sample ? h('div.row', { style: { gap: '12px' } },
          h('span.muted', { style: { fontSize: '11px' } }, 'sample'),
          idButton(e.sample.traceId, 'Copy trace id'),
          h('a.link-btn', { href: router.href('/traces/' + e.sample.traceId, api.sharedQuery()) }, 'Open trace'),
          h('span.muted', { style: { fontSize: '11px' }, title: bothTimes(e.sample.at) }, full(e.sample.at))) : null);

      fill(statsRow,
        stat(count(e.count), '', 'occurrences', { class: 'is-bad' }),
        stat(rel(e.firstSeen), '', 'first seen', { title: bothTimes(e.firstSeen) }),
        stat(rel(e.lastSeen), '', 'last seen', { title: bothTimes(e.lastSeen) }),
        stat(count((e.endpoints || []).length), '', 'endpoints'));

      const series = data.series || {};
      const spec = {
        height: 160,
        t: series.t || [],
        series: [{ label: 'Errors', values: series.count || [], color: 'err', type: 'bar' }],
        axes: [{ scale: 'y' }],
      };
      fill(chartLegend, legend([{ label: 'Occurrences per bucket', color: 'err' }]));
      if (chart) chart.update(spec); else chart = timeSeries(chartBody, spec);

      const code = data.code || [];
      fill(stackBody,
        code.length
          ? h('div.f-code', { style: { marginBottom: '12px' } }, h('div.sub-head', 'Code'),
            code.map((frame) => codeFrame(frame)))
          : null,
        e.sample && e.sample.stacktrace
          ? stackTrace(e.sample.stacktrace)
          : h('span.muted', 'This error carried no stack trace.'));

      fill(endpointsBody, table([
        { key: 'name', label: 'Endpoint', sortable: false, cls: 'wide', render: (x) => h('span.cell-ellipsis', { title: x.name }, x.name) },
        { key: 'count', label: 'Count', align: 'right', sortable: false, width: '72px', render: (x) => count(x.count) },
      ], { rows: e.endpoints || [], rowKey: (x) => x.name, empty: 'No endpoint recorded.' }));

      fill(tracesBody, traceTable(data.traces || [], { empty: 'No trace in this window.' }));
    } catch (err) {
      if (destroyed) return;
      built = false;
      fill(page, errorBox(err, load));
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; if (chart) chart.destroy(); } };
}

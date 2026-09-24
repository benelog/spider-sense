// One endpoint: header, RED charts, then slowest/recent traces, queries, errors.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, methodChip, statusBar, tabs, spinner, errorBox, serviceChip, breakdownBar, breakdownLead } from '../ui.js';
import { redCharts } from './service.js';
import { histogramBars, apdexClass, fmtApdex } from '../buckets.js';
import { traceTable } from './traces.js';
import { oneLineSql } from '../sql.js';
import { dur, count, rate, rel, bothTimes, truncate } from '../format.js';

export function render(root, ctx) {
  const id = ctx.params.id;
  let destroyed = false;
  let data = null;
  let activeTab = ctx.query.tab || 'slowest';

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const red = redCharts({ query: ctx.query });
  const tabsBody = h('div', spinner());
  const tabsPanel = panel({}, tabsBody);

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, headPanel, red, tabsPanel);
  }

  function paintHead(e) {
    const breakdown = (data && data.breakdown) || {};
    const bar = breakdownBar(breakdown);
    const lead = breakdownLead(breakdown);
    fill(head,
      h('div.row', { style: { gap: '10px' } },
        methodChip(e.method),
        h('b', { style: { fontSize: '15px' } }, e.route || e.name),
        serviceChip(e.service),
        chip(e.kind || 'SERVER')),
      h('div.row', { style: { marginLeft: 'auto', gap: '18px' } },
        item('calls', count(e.calls)),
        item('rps', rate(e.rps || 0)),
        item('apdex', h('span', { class: apdexClass(e.apdex) === 'is-bad' ? 'bad' : apdexClass(e.apdex) === 'is-warn' ? 'warned' : '' }, fmtApdex(e.apdex))),
        item('p95', dur(e.p95Ms)),
        item('max', dur(e.maxMs)),
        item('errors', e.errors ? h('span.bad', count(e.errors)) : '0'),
        h('div.th-item', h('span.k', 'status'), statusBar(e.statusCodes)),
        // Where the time went, over the 20 slowest traces (findings.adoc#time).
        bar ? h('div.th-item', h('span.k', 'time in ' + lead[0]), bar) : null,
        histogramBars(e.histogram, { compact: true })));
  }

  function item(k, v) {
    return h('div.th-item', h('span.k', k), h('span.v', v));
  }

  function paintTabs() {
    const node = tabs([
      {
        id: 'slowest', label: 'Slowest traces', count: (data.traces || []).length,
        render: () => traceTable(data.traces || [], { empty: 'No trace in this window.' }),
      },
      {
        id: 'recent', label: 'Recent traces', count: (data.recent || []).length,
        render: () => traceTable(data.recent || [], { empty: 'No trace in this window.' }),
      },
      {
        id: 'queries', label: 'Queries', count: (data.queries || []).length,
        render: () => table([
          { key: 'statement', label: 'Statement', sortable: false, cls: 'wide', render: (q) => h('span.cell-ellipsis.mono', { title: q.statement }, oneLineSql(q.statement, 160)) },
          { key: 'system', label: 'System', sortable: false, width: '70px', render: (q) => (q.system ? chip(q.system) : h('span.muted', '-')) },
          { key: 'calls', label: 'Calls', align: 'right', sortable: false, width: '66px', render: (q) => count(q.calls) },
          { key: 'avgMs', label: 'avg', align: 'right', sortable: false, width: '74px', render: (q) => dur(q.avgMs) },
          { key: 'p95Ms', label: 'p95', align: 'right', sortable: false, width: '74px', render: (q) => dur(q.p95Ms) },
          { key: 'totalMs', label: 'Total', align: 'right', sortable: false, width: '82px', render: (q) => dur(q.totalMs) },
        ], {
          rows: data.queries || [],
          rowKey: (q) => q.queryId,
          onRowClick: (q) => router.go('/queries/' + encodeURIComponent(q.queryId), api.sharedQuery()),
          empty: 'This endpoint made no database call in this window.',
        }),
      },
      {
        id: 'errors', label: 'Errors', count: (data.errors || []).length,
        render: () => table([
          { key: 'type', label: 'Type', sortable: false, cls: 'wide', render: (e) => h('span.cell-ellipsis.mono', { title: e.type }, e.type) },
          { key: 'message', label: 'Message', sortable: false, cls: 'wide', render: (e) => h('span.cell-ellipsis', { title: e.message }, truncate(e.message, 100)) },
          { key: 'count', label: 'Count', align: 'right', sortable: false, width: '66px', render: (e) => h('span.bad', count(e.count)) },
          { key: 'lastSeen', label: 'Last seen', align: 'right', sortable: false, width: '90px', render: (e) => h('span', { title: bothTimes(e.lastSeen) }, rel(e.lastSeen)) },
        ], {
          rows: data.errors || [],
          rowKey: (e) => e.errorId,
          onRowClick: (e) => router.go('/errors/' + encodeURIComponent(e.errorId), api.sharedQuery()),
          empty: 'No error in this window.',
        }),
      },
    ], {
      active: activeTab,
      onSelect: (tab) => { activeTab = tab; router.setQuery({ tab: tab === 'slowest' ? '' : tab }); },
    });
    fill(tabsBody, node);
  }

  async function load() {
    try {
      data = await api.endpoint(id);
      if (destroyed) return;
      build();
      const e = data.endpoint || {};
      ctx.setTitle(e.name || 'Endpoint');
      paintHead(e);
      red.syncMode();
      red.apply(data.series || {});
      paintTabs();
    } catch (err) {
      if (destroyed) return;
      built = false;
      fill(page, errorBox(err, load));
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; red.destroy(); } };
}

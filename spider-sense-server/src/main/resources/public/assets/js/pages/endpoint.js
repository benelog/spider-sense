// One endpoint: header, RED charts, then slowest/recent traces, queries, errors.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, methodChip, statusBar, tabs, spinner, serviceChip, breakdownBar, breakdownLead } from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { histogramBars, apdexCell } from '../buckets.js';
import { traceTable, redCharts } from '../widgets.js';
import { statementColumn, errorTypeColumn, messageColumn, seenColumn, durationColumn, countColumn } from '../columns.js';
import { dur, count, rate } from '../format.js';

export function render(root, ctx) {
  const id = ctx.params.id;
  let data = null;
  let activeTab = router.queryParam(ctx.query(), 'tab', ['slowest', 'recent', 'queries', 'errors'], 'slowest');

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const red = redCharts();
  const tabsBody = h('div', spinner());
  const tabsPanel = panel({}, tabsBody);

  const layout = skeleton(root, () => [headPanel, red, tabsPanel]);

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
        item('apdex', apdexCell(e.apdex)),
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

  // The tab strip and each tab's table are built once; a Live refresh gives them new rows,
  // so the open table keeps its scroll position and focus (ui.adoc#live-refresh).
  let tabNode = null;
  const tables = {};
  const TAB_ROWS = {
    slowest: () => data.traces || [],
    recent: () => data.recent || [],
    queries: () => data.queries || [],
    errors: () => data.errors || [],
  };

  function tableOf(tab) {
    if (tables[tab]) return tables[tab];
    const rows = TAB_ROWS[tab]();
    if (tab === 'slowest' || tab === 'recent') {
      tables[tab] = traceTable(rows, { empty: 'No trace in this window.' });
    } else if (tab === 'queries') {
      tables[tab] = table([
        statementColumn(160),
        { key: 'system', label: 'System', width: '70px', render: (q) => (q.system ? chip(q.system) : h('span.muted', '-')) },
        countColumn('calls', 'Calls', '66px'),
        durationColumn('avgMs', 'avg'),
        durationColumn('p95Ms', 'p95'),
        durationColumn('totalMs', 'Total', '82px'),
      ], {
        rowKey: (q) => q.queryId,
        onRowClick: (q) => router.openDetail('queries', q.queryId),
        empty: 'This endpoint made no database call in this window.',
        rows,
      });
    } else {
      tables[tab] = table([
        errorTypeColumn(),
        messageColumn(100),
        { key: 'count', label: 'Count', align: 'right', width: '66px', render: (e) => h('span.bad', count(e.count)) },
        seenColumn('lastSeen', 'Last seen', '90px'),
      ], {
        rowKey: (e) => e.errorId,
        onRowClick: (e) => router.openDetail('errors', e.errorId),
        empty: 'No error in this window.',
        rows,
      });
    }
    return tables[tab];
  }

  function paintTabs() {
    if (tabNode) {
      for (const [tab, rows] of Object.entries(TAB_ROWS)) {
        const counter = tabNode.querySelector('[data-tab="' + tab + '"] .tab-count');
        if (counter) counter.textContent = String(rows().length);
        const node = tables[tab];
        if (!node) continue;
        node.setRows(rows());
      }
      return;
    }
    tabNode = tabs([
      { id: 'slowest', label: 'Slowest traces', count: TAB_ROWS.slowest().length, render: () => tableOf('slowest') },
      { id: 'recent', label: 'Recent traces', count: TAB_ROWS.recent().length, render: () => tableOf('recent') },
      { id: 'queries', label: 'Queries', count: TAB_ROWS.queries().length, render: () => tableOf('queries') },
      { id: 'errors', label: 'Errors', count: TAB_ROWS.errors().length, render: () => tableOf('errors') },
    ], {
      active: activeTab,
      onSelect: (tab) => { activeTab = tab; router.setQuery({ tab }, { defaults: { tab: 'slowest' } }); },
    });
    fill(tabsBody, tabNode);
  }

  const loader = pageLoader({
    fetch: () => api.endpoint(id),
    paint: (res) => {
      data = res;
      layout.build();
      const e = data.endpoint || {};
      ctx.setTitle(e.name || 'Endpoint');
      paintHead(e);
      red.syncMode();
      red.apply(data.series || {});
      paintTabs();
    },
    body: layout,
  });

  loader.load();
  return { refresh: loader.load, destroy: () => { loader.destroy(); red.destroy(); } };
}

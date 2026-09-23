// Error groups, each with a sparkline of its occurrences over the window.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, fillRows, chip, serviceChip, spinner, errorBox } from '../ui.js';
import { sparkline, themeColors } from '../charts.js';
import { count, rel, bothTimes, truncate, splitType } from '../format.js';

export function render(root, ctx) {
  let destroyed = false;
  let rows = [];
  let node = null;
  // Read at each paint, so a theme flip gives the next refresh its colour.
  let errColor = themeColors().err;

  const body = h('div', spinner());
  root.appendChild(panel({ title: 'Errors' }, body));

  const columns = [
    {
      key: 'type', label: 'Type', sortable: false, width: '260px',
      render: (e) => {
        const { pkg, name } = splitType(e.type);
        return h('span.mono.cell-ellipsis', { title: e.type }, h('span.muted', pkg), name);
      },
    },
    { key: 'message', label: 'Message', sortable: false, cls: 'wide', render: (e) => h('span.cell-ellipsis', { title: e.message }, truncate(e.message, 160)) },
    { key: 'service', label: 'Service', sortable: false, width: '150px', render: (e) => serviceChip(e.service) },
    { key: 'count', label: 'Count', align: 'right', sortable: false, width: '68px', render: (e) => h('span.bad', count(e.count)) },
    {
      key: 'endpoints', label: 'Endpoints', sortable: false, width: '220px',
      render: (e) => {
        const list = e.endpoints || [];
        return h('span.row', { style: { gap: '4px' } },
          list.slice(0, 2).map((x) => chip(x.name, { title: x.name + ': ' + count(x.count) })),
          list.length > 2 ? h('span.muted', '+' + (list.length - 2)) : null);
      },
    },
    {
      key: 'series', label: 'Occurrences', sortable: false, width: '130px',
      render: (e) => sparkline(e.series || [], { color: errColor, label: (e.type || 'error') + ' occurrences over the window' }),
    },
    { key: 'firstSeen', label: 'First seen', align: 'right', sortable: false, width: '92px', render: (e) => h('span', { title: bothTimes(e.firstSeen) }, rel(e.firstSeen)) },
    { key: 'lastSeen', label: 'Last seen', align: 'right', sortable: false, width: '92px', render: (e) => h('span', { title: bothTimes(e.lastSeen) }, rel(e.lastSeen)) },
  ];

  const opts = {
    rowKey: (e) => e.errorId,
    onRowClick: (e) => router.go('/errors/' + encodeURIComponent(e.errorId), api.sharedQuery()),
    empty: 'No error in this window.',
  };

  function paint() {
    errColor = themeColors().err;
    if (!node) {
      node = table(columns, { ...opts, rows });
      fill(body, node);
    } else {
      fillRows(node, rows, opts);
    }
  }

  async function load() {
    try {
      const res = await api.errors({ limit: 100 });
      if (destroyed) return;
      rows = res.errors || [];
      paint();
    } catch (e) {
      if (!destroyed) { node = null; fill(body, errorBox(e, load)); }
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; } };
}

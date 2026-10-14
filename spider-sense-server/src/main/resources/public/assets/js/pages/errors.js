// Error groups, each with a sparkline of its occurrences over the window.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, spinner } from '../ui.js';
import { pageLoader } from '../page.js';
import { errorTypeColumn, messageColumn, serviceColumn, seenColumn, fitTable } from '../columns.js';
import { sparkline } from '../charts.js';
import { count } from '../format.js';

export function render(root, ctx) {
  let node = null;

  const body = h('div', spinner());
  root.appendChild(panel({ title: 'Errors' }, body));

  const columns = [
    errorTypeColumn({ width: '190px' }),
    messageColumn(160),
    serviceColumn('120px'),
    { key: 'count', label: 'Count', align: 'right', sortable: false, width: '56px', render: (e) => h('span.bad', count(e.count)) },
    {
      key: 'endpoints', label: 'Endpoints', sortable: false, width: '150px',
      render: (e) => {
        const list = e.endpoints || [];
        return h('span.row', { style: { gap: '4px' } },
          list.slice(0, 2).map((x) => chip(x.name, { title: x.name + ': ' + count(x.count) })),
          list.length > 2 ? h('span.muted', '+' + (list.length - 2)) : null);
      },
    },
    {
      key: 'series', label: 'Occurrences', sortable: false, width: '134px',
      render: (e) => sparkline(e.series || [], { color: 'var(--err)', label: (e.type || 'error') + ' occurrences over the window' }),
    },
    seenColumn('firstSeen', 'First seen', '84px'),
    seenColumn('lastSeen', 'Last seen', '84px'),
  ];

  const opts = {
    rowKey: (e) => e.errorId,
    onRowClick: (e) => router.openDetail('errors', e.errorId),
    empty: 'No error in this window.',
  };

  function paint(res) {
    const rows = res.errors || [];
    if (!node) {
      node = table(columns, { ...opts, rows });
      fitTable(node, columns);
      fill(body, node);
    } else {
      node.setRows(rows);
    }
  }

  const loader = pageLoader({
    fetch: () => api.errors({ limit: 100 }),
    paint,
    body,
    onError: () => { node = null; },
  });

  loader.load();
  return { refresh: loader.load, destroy: loader.destroy };
}

// Services list: one sortable table.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, serviceColor, comparator, spinner, emptyState, snippetBlocks, seedServices } from '../ui.js';
import { pageLoader } from '../page.js';
import { sparkline } from '../charts.js';
import { apdexCell } from '../buckets.js';
import { seenColumn, durationColumn, countColumn } from '../columns.js';
import { rate, pct } from '../format.js';

export function render(root, ctx) {
  let rows = [];
  let sort = { key: ctx.query.sort || 'requests', dir: ctx.query.dir === 'asc' ? 'asc' : 'desc' };
  let node = null;

  const body = h('div', spinner());
  root.appendChild(panel({ title: 'Services' }, body));

  const columns = [
    {
      key: 'name', label: 'Service', cls: 'wide',
      render: (s) => h('div.row', { style: { gap: '8px' } },
        h('span.dot', { style: { background: serviceColor(s.name), width: '8px', height: '8px', borderRadius: '50%', flex: 'none' } }),
        h('b', s.name),
        s.embedded ? chip('embedded', { class: 'chip-accent' }) : null),
    },
    { key: 'language', label: 'Language', width: '92px', render: (s) => (s.language ? chip(s.language) : h('span.muted', 'unknown')) },
    countColumn('requests', 'Requests', '84px'),
    { key: 'rps', label: 'rps', align: 'right', width: '64px', render: (s) => rate(s.rps || 0) },
    { key: 'errorRate', label: 'Errors', align: 'right', width: '72px', render: (s) => h('span', { class: s.errorRate > 0.01 ? 'bad' : '' }, pct(s.errorRate || 0)) },
    { key: 'apdex', label: 'Apdex', align: 'right', width: '70px', render: (s) => apdexCell(s.apdex) },
    durationColumn('p50Ms', 'p50', '78px'),
    durationColumn('p95Ms', 'p95', '78px'),
    durationColumn('p99Ms', 'p99', '78px'),
    { key: 'spark', label: 'Requests over time', sortable: false, width: '130px', render: (s) => sparkline(s.sparkline || [], { color: serviceColor(s.name), label: s.name + ' requests per bucket' }) },
    seenColumn(),
  ];

  const opts = {
    rowKey: (s) => s.name,
    onRowClick: (s) => router.openDetail('services', s.name),
    empty: 'No service has reported in this window.',
  };

  function sorted() {
    return rows.slice().sort(comparator(sort));
  }

  function onSort(key) {
    sort = { key, dir: sort.key === key && sort.dir === 'desc' ? 'asc' : 'desc' };
    router.setQuery({ sort: key, dir: sort.dir });
    node = null;
    paint();
  }

  function paint() {
    if (!node) {
      node = table(columns, { ...opts, rows: sorted(), sort, onSort });
      fill(body, node);
    } else {
      node.setRows(sorted());
    }
  }

  const loader = pageLoader({
    fetch: () => api.services(),
    paint: (res) => {
      rows = res.services || [];
      seedServices(rows.map((s) => s.name));
      if (!rows.length) {
        node = null;
        fill(body, emptyState('No service has sent anything yet.', snippetBlocks((api.state.status || {}).endpoint || location.origin)));
        return;
      }
      paint();
    },
    body,
    onError: () => { node = null; },
  });

  loader.load();
  return { refresh: loader.load, destroy: loader.destroy };
}

// Queries list: sort selector, a filter on the statement, one table.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, table, chip, debounce, spinner } from '../ui.js';
import { pageLoader } from '../page.js';
import { statementColumn, serviceColumn, seenColumn, durationColumn, countColumn } from '../columns.js';
import { count } from '../format.js';

const SORTS = [
  { id: 'total', label: 'Total time' },
  { id: 'avg', label: 'Average' },
  { id: 'p95', label: 'p95' },
  { id: 'max', label: 'Max' },
  { id: 'calls', label: 'Calls' },
];

/**
 * The `unindexed` cell (pages.adoc#queries): the columns the statement filters on that no
 * index leads with, as the text rendering's column has them — `none` when every
 * predicate is served, `—` when the query group carries no schema block.
 */
function unindexedCell(schema) {
  if (!schema) return h('span.muted', '—');
  const columns = schema.unindexed || [];
  if (!columns.length) return h('span.muted', 'none');
  const text = columns.join(', ');
  return h('span.cell-ellipsis.mono.accent', { title: text }, text);
}

export function render(root, ctx) {
  let rows = [];
  let node = null;
  let sort = router.queryParam(ctx.query(), 'sort', SORTS.map((s) => s.id), 'total');
  let filterText = ctx.query().q || '';

  const input = h('input', { type: 'search', placeholder: 'Filter statements', value: filterText, 'aria-label': 'Filter statements' });
  const sortSelect = h('select', { 'aria-label': 'Sort by' }, SORTS.map((s) => h('option', { value: s.id }, s.label)));
  sortSelect.value = sort;

  const applyFilter = debounce(() => {
    filterText = input.value.trim();
    router.setQuery({ q: filterText });
    paint();
  }, 300);
  input.addEventListener('input', applyFilter);
  sortSelect.addEventListener('change', () => {
    sort = sortSelect.value;
    router.setQuery({ sort }, { defaults: { sort: 'total' } });
    loader.load();
  });

  const body = h('div', spinner());
  root.appendChild(panel({ title: 'Queries' },
    h('div.querybar', h('div.search', icon('search'), input), h('label', 'Sort', sortSelect)),
    body));

  const columns = [
    statementColumn(220),
    { key: 'system', label: 'System', sortable: false, width: '68px', render: (q) => (q.system ? chip(q.system) : h('span.muted', '-')) },
    { key: 'operation', label: 'Op', sortable: false, width: '68px', render: (q) => h('span.mono', q.operation || '-') },
    { key: 'table', label: 'Table', sortable: false, width: '110px', render: (q) => h('span.cell-ellipsis.mono.muted', { title: q.table || '' }, q.table || '-') },
    { key: 'unindexed', label: 'Unindexed', sortable: false, width: '140px', render: (q) => unindexedCell(q.schema) },
    serviceColumn(),
    countColumn('calls', 'Calls', '70px'),
    durationColumn('avgMs', 'avg'),
    durationColumn('p95Ms', 'p95'),
    durationColumn('maxMs', 'max'),
    durationColumn('totalMs', 'Total', '84px'),
    { key: 'slowCalls', label: 'Slow', align: 'right', sortable: false, width: '62px', render: (q) => (q.slowCalls ? h('span.accent', count(q.slowCalls)) : h('span.muted', '0')) },
    seenColumn('lastSeen', 'Last seen', '88px'),
  ];

  const opts = {
    rowKey: (q) => q.queryId,
    onRowClick: (q) => router.openDetail('queries', q.queryId),
    empty: 'No database call in this window.',
  };

  function filtered() {
    if (!filterText) return rows;
    const needle = filterText.toLowerCase();
    return rows.filter((q) => (q.statement || '').toLowerCase().includes(needle) || (q.table || '').toLowerCase().includes(needle));
  }

  function paint() {
    if (!node) {
      node = table(columns, { ...opts, rows: filtered() });
      fill(body, node);
    } else {
      node.setRows(filtered());
    }
  }

  const loader = pageLoader({
    fetch: () => api.queries({ sort, limit: 100 }),
    paint: (res) => { rows = res.queries || []; paint(); },
    body,
    onError: () => { node = null; },
  });

  loader.load();
  return { refresh: loader.load, destroy: () => { loader.destroy(); applyFilter.cancel(); } };
}

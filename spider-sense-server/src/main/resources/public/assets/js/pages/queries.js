// Queries list: sort selector, a filter on the statement, one table.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, table, debounce, spinner } from '../ui.js';
import { pageLoader } from '../page.js';
import { statementColumn, serviceColumn, seenColumn, durationColumn, countColumn, fitTable } from '../columns.js';
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

/** `SELECT orders h2`: the operation, the table dimmed, the system dimmer, all three in the title. */
function opTableCell(q) {
  const title = [q.operation, q.table, q.system ? '(' + q.system + ')' : null].filter(Boolean).join(' ');
  return h('span.op-table.mono', { title },
    q.operation || '-', ' ', h('span.muted', q.table || '-'),
    q.system ? h('span.muted', { style: { opacity: 0.7 } }, ' ' + q.system) : null);
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

  // Fixed widths that leave the statement the rest of the panel, so the table fits 1280 px
  // (ui.adoc#narrow-screens).
  const columns = [
    statementColumn(220),
    { key: 'operation', label: 'Op · Table', sortable: false, width: '130px', render: opTableCell },
    { key: 'unindexed', label: 'Unindexed', sortable: false, width: '96px', render: (q) => unindexedCell(q.schema) },
    serviceColumn('116px'),
    countColumn('calls', 'Calls', '54px'),
    durationColumn('avgMs', 'avg', '70px'),
    durationColumn('p95Ms', 'p95', '70px'),
    durationColumn('maxMs', 'max', '70px'),
    durationColumn('totalMs', 'Total', '72px'),
    { key: 'slowCalls', label: 'Slow', align: 'right', sortable: false, width: '44px', render: (q) => (q.slowCalls ? h('span.accent', count(q.slowCalls)) : h('span.muted', '0')) },
    seenColumn('lastSeen', 'Last seen', '84px'),
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
      fitTable(node, columns);
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

// Queries list: sort selector, a filter on the statement, one table.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, table, fillRows, chip, serviceChip, debounce, spinner, errorBox } from '../ui.js';
import { oneLineSql } from '../sql.js';
import { dur, count, rel, bothTimes } from '../format.js';

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
  let destroyed = false;
  let rows = [];
  let node = null;
  let sort = SORTS.some((s) => s.id === ctx.query.sort) ? ctx.query.sort : 'total';
  let text = ctx.query.q || '';

  const input = h('input', { type: 'search', placeholder: 'Filter statements', value: text, 'aria-label': 'Filter statements' });
  const sortSelect = h('select', { 'aria-label': 'Sort by' }, SORTS.map((s) => h('option', { value: s.id }, s.label)));
  sortSelect.value = sort;

  const apply = debounce(() => {
    text = input.value.trim();
    router.setQuery({ q: text });
    paint();
  }, 300);
  input.addEventListener('input', apply);
  sortSelect.addEventListener('change', () => {
    sort = sortSelect.value;
    router.setQuery({ sort: sort === 'total' ? '' : sort });
    load();
  });

  const body = h('div', spinner());
  root.appendChild(panel({ title: 'Queries' },
    h('div.querybar', h('div.search', icon('search'), input), h('label', 'Sort', sortSelect)),
    body));

  const columns = [
    { key: 'statement', label: 'Statement', sortable: false, cls: 'wide', render: (q) => h('span.cell-ellipsis.mono', { title: q.statement }, oneLineSql(q.statement, 220)) },
    { key: 'system', label: 'System', sortable: false, width: '68px', render: (q) => (q.system ? chip(q.system) : h('span.muted', '-')) },
    { key: 'operation', label: 'Op', sortable: false, width: '68px', render: (q) => h('span.mono', q.operation || '-') },
    { key: 'table', label: 'Table', sortable: false, width: '110px', render: (q) => h('span.cell-ellipsis.mono.muted', { title: q.table || '' }, q.table || '-') },
    { key: 'unindexed', label: 'Unindexed', sortable: false, width: '140px', render: (q) => unindexedCell(q.schema) },
    { key: 'service', label: 'Service', sortable: false, width: '150px', render: (q) => serviceChip(q.service) },
    { key: 'calls', label: 'Calls', align: 'right', sortable: false, width: '70px', render: (q) => count(q.calls) },
    { key: 'avgMs', label: 'avg', align: 'right', sortable: false, width: '74px', render: (q) => dur(q.avgMs) },
    { key: 'p95Ms', label: 'p95', align: 'right', sortable: false, width: '74px', render: (q) => dur(q.p95Ms) },
    { key: 'maxMs', label: 'max', align: 'right', sortable: false, width: '74px', render: (q) => dur(q.maxMs) },
    { key: 'totalMs', label: 'Total', align: 'right', sortable: false, width: '84px', render: (q) => dur(q.totalMs) },
    { key: 'slowCalls', label: 'Slow', align: 'right', sortable: false, width: '62px', render: (q) => (q.slowCalls ? h('span.accent', count(q.slowCalls)) : h('span.muted', '0')) },
    { key: 'lastSeen', label: 'Last seen', align: 'right', sortable: false, width: '88px', render: (q) => h('span', { title: bothTimes(q.lastSeen) }, rel(q.lastSeen)) },
  ];

  const opts = {
    rowKey: (q) => q.queryId,
    onRowClick: (q) => router.go('/queries/' + encodeURIComponent(q.queryId), api.sharedQuery()),
    empty: 'No database call in this window.',
  };

  function filtered() {
    if (!text) return rows;
    const needle = text.toLowerCase();
    return rows.filter((q) => (q.statement || '').toLowerCase().includes(needle) || (q.table || '').toLowerCase().includes(needle));
  }

  function paint() {
    if (!node) {
      node = table(columns, { ...opts, rows: filtered() });
      fill(body, node);
    } else {
      fillRows(node, filtered(), opts);
    }
  }

  async function load() {
    try {
      const res = await api.queries({ sort, limit: 100 });
      if (destroyed) return;
      rows = res.queries || [];
      paint();
    } catch (e) {
      if (!destroyed) { node = null; fill(body, errorBox(e, load)); }
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; apply.cancel(); } };
}

// Traces list: a query bar and a table that pages backwards with `before` and `beforeId`.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, table, serviceChip, statusChip, durationBar, debounce, spinner, emptyState, snippetBlocks } from '../ui.js';
import { pageLoader } from '../page.js';
import { dur, count, time, bothTimes, shortId } from '../format.js';

/** A trace table shared by the Traces, Endpoint, Query and Error pages. */
export function traceTable(rows, opts = {}) {
  const ref = { max: 0 };
  const recalc = (rs) => { ref.max = rs.reduce((m, r) => Math.max(m, r.durationMs || 0), 0); };
  recalc(rows);
  const columns = [
    { key: 'start', label: 'Time', sortable: false, width: '88px', render: (r) => h('span.mono', { title: bothTimes(r.start) }, time(r.start)) },
    {
      key: 'rootName', label: 'Root', sortable: false, cls: 'wide',
      render: (r) => h('div',
        h('div.cell-ellipsis', { title: r.rootName }, h('b', r.rootName)),
        h('div.row', { style: { gap: '4px', marginTop: '2px' } },
          (r.services || []).slice(0, 3).map((s) => serviceChip(s)),
          (r.services || []).length > 3 ? h('span.muted', '+' + ((r.services || []).length - 3)) : null)),
    },
    {
      key: 'durationMs', label: 'Duration', align: 'right', sortable: false, width: '110px',
      render: (r) => h('div.duration-cell',
        h('span', { class: r.error ? 'bad' : r.slow ? 'warned' : '' }, dur(r.durationMs)),
        durationBar(r.durationMs, ref.max, r.error ? 'err' : r.slow ? 'slow' : null)),
    },
    { key: 'spanCount', label: 'Spans', align: 'right', sortable: false, width: '58px', render: (r) => count(r.spanCount) },
    { key: 'dbCount', label: 'DB', align: 'right', sortable: false, width: '48px', render: (r) => (r.dbCount ? count(r.dbCount) : h('span.muted', '-')) },
    { key: 'httpStatus', label: 'Status', align: 'right', sortable: false, width: '58px', render: (r) => statusChip(r.httpStatus) },
    {
      key: 'marks', label: '', sortable: false, width: '58px',
      render: (r) => h('span.row', { style: { gap: '4px' } },
        r.error ? h('span.marker.err', { title: 'Error' }, icon('bolt')) : null,
        r.slow ? h('span.marker.slow', { title: 'Slow request' }, icon('turtle')) : null),
    },
  ];
  if (opts.showId) {
    columns.splice(1, 0, { key: 'traceId', label: 'Trace', sortable: false, width: '92px', render: (r) => h('span.mono.muted', { title: r.traceId }, shortId(r.traceId)) });
  }
  const rowOpts = {
    rowKey: (r) => r.traceId,
    rowClass: (r) => (r.error ? 'is-error' : r.slow ? 'is-slow' : ''),
    onRowClick: opts.onRowClick || ((r) => router.go('/traces/' + r.traceId, api.sharedQuery())),
    empty: opts.empty || 'No trace matches this window and filter.',
  };
  const node = table(columns, { rows, ...rowOpts });
  /** Replace the rows in place: scroll position and the sort header survive. */
  const setRows = node.setRows;
  node.setRows = (rs) => { recalc(rs); return setRows(rs); };
  return node;
}

export function render(root, ctx) {
  const q = { ...ctx.query };
  const filter = {
    q: q.q || '',
    minMs: q.minMs || '',
    maxMs: q.maxMs || '',
    status: q.status || 'all',
    endpointId: q.endpointId || '',
  };
  let rows = [];
  let total = 0;
  // The service the endpoint filter belongs to: an endpoint is one route of one service.
  let endpointService = api.state.service || '';

  const input = h('input', { type: 'search', placeholder: 'Search span names and attributes', value: filter.q, 'aria-label': 'Search traces' });
  const minInput = h('input', { type: 'number', min: '0', step: '10', placeholder: 'ms', value: filter.minMs, 'aria-label': 'Minimum duration in milliseconds' });
  const maxInput = h('input', { type: 'number', min: '0', step: '10', placeholder: 'ms', value: filter.maxMs, 'aria-label': 'Maximum duration in milliseconds' });
  const statusSelect = h('select', { 'aria-label': 'Status filter' },
    h('option', { value: 'all' }, 'Any status'),
    h('option', { value: 'error' }, 'Errors only'),
    h('option', { value: 'ok' }, 'No error'));
  statusSelect.value = filter.status;
  const endpointSelect = h('select', { 'aria-label': 'Endpoint filter' }, h('option', { value: '' }, 'Any endpoint'));
  endpointSelect.hidden = true;

  const apply = debounce(() => {
    filter.q = input.value.trim();
    filter.minMs = minInput.value;
    filter.maxMs = maxInput.value;
    filter.status = statusSelect.value;
    filter.endpointId = endpointSelect.value;
    router.setQuery({ q: filter.q, minMs: filter.minMs, maxMs: filter.maxMs, status: filter.status === 'all' ? '' : filter.status, endpointId: filter.endpointId });
    rows = [];
    list.load();
  }, 400);

  input.addEventListener('input', apply);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') apply.flush(); });
  minInput.addEventListener('input', apply);
  maxInput.addEventListener('input', apply);
  statusSelect.addEventListener('change', () => apply.flush());
  endpointSelect.addEventListener('change', () => apply.flush());

  const countLabel = h('span.muted', { style: { marginLeft: 'auto', fontSize: '11px' } });
  const bar = h('div.querybar',
    h('div.search', icon('search'), input),
    h('label.range-field', 'Min', minInput, h('span.range-dash', '–'), 'Max', maxInput),
    statusSelect,
    endpointSelect,
    countLabel);

  const body = h('div', spinner());
  const moreBtn = h('button.btn', { type: 'button', onclick: () => loadMore() }, 'Load more');
  const foot = h('div.panel-note', { style: { display: 'flex', justifyContent: 'center' } }, moreBtn);
  foot.hidden = true;
  const view = panel({ title: 'Traces' }, bar, body, foot);
  root.appendChild(view);

  let tableNode = null;

  function paint() {
    countLabel.textContent = rows.length ? count(rows.length) + ' of ' + count(total) : '';
    if (!rows.length) {
      const hasFilter = filter.q || filter.minMs || filter.maxMs || filter.status !== 'all' || filter.endpointId;
      tableNode = null;
      fill(body, hasFilter || (api.state.status && api.state.status.counts && api.state.status.counts.traces)
        ? traceTable([], {})
        : emptyState('No trace has arrived yet. Point an application at this collector and reload.', snippetBlocks((api.state.status || {}).endpoint || location.origin)));
      foot.hidden = true;
      return;
    }
    if (!tableNode) {
      tableNode = traceTable(rows, {});
      fill(body, tableNode);
    } else {
      tableNode.setRows(rows);
    }
    foot.hidden = rows.length >= total || rows.length === 0;
  }

  /** The endpoint filter offers the top bar's service's endpoints, and is hidden without one. */
  const endpoints = pageLoader({
    fetch: async () => (api.state.service ? api.endpoints({}) : null),
    paint: (res) => {
      if (!res) { endpointSelect.hidden = true; return; }
      const options = res.endpoints || [];
      const value = endpointSelect.value || filter.endpointId;
      fill(endpointSelect, h('option', { value: '' }, 'Any endpoint'),
        options.map((e) => h('option', { value: e.endpointId }, e.name)));
      endpointSelect.value = options.some((e) => e.endpointId === value) ? value : '';
      endpointSelect.hidden = false;
    },
    onError: () => { endpointSelect.hidden = true; return false; },
  });

  /** `cursor` is the last row's `{ before: start, beforeId: traceId }` when loading more. */
  const list = pageLoader({
    fetch: (cursor) => api.traces({
      q: filter.q,
      minMs: filter.minMs,
      maxMs: filter.maxMs,
      status: filter.status === 'all' ? '' : filter.status,
      endpointId: filter.endpointId,
      limit: 50,
      before: cursor && cursor.before,
      beforeId: cursor && cursor.beforeId,
    }),
    paint: (res, cursor) => {
      const incoming = res.traces || [];
      total = res.total || incoming.length;
      if (cursor) {
        const seen = new Set(rows.map((r) => r.traceId));
        rows = rows.concat(incoming.filter((r) => !seen.has(r.traceId)));
      } else {
        rows = incoming;
      }
      paint();
    },
    body,
    onError: () => { tableNode = null; },
  });

  function loadMore() {
    const last = rows[rows.length - 1];
    if (last) list.load({ before: last.start, beforeId: last.traceId });
  }

  endpoints.load();
  list.load();

  return {
    refresh: () => {
      const service = api.state.service || '';
      if (service !== endpointService) {
        endpointService = service;
        if (filter.endpointId) {
          // The endpoint was one of the previous service's: the filter goes, from the hash
          // too, and the query change this makes refreshes the page again without it.
          filter.endpointId = '';
          endpointSelect.value = '';
          rows = [];
          router.setQuery({ endpointId: '' });
          return;
        }
      }
      if (!rows.length || !document.querySelector('.drawer')) { endpoints.load(); list.load(); }
    },
    destroy: () => { list.destroy(); endpoints.destroy(); apply.cancel(); },
  };
}

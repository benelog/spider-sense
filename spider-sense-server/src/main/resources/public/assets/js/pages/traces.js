// Traces list: a query bar and a table that pages backwards with `before` and `beforeId`.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, fillOptions, icon, panel, debounce, spinner, noDataYet, drawerOpen } from '../ui.js';
import { pageLoader, mergeNewestPage } from '../page.js';
import { traceTable } from '../widgets.js';
import { count } from '../format.js';

/**
 * A duration bound as the API takes it, whole milliseconds (api.adoc#traces): a fraction typed in a
 * field widens outwards, `round` taking the open lower bound down and the closed upper one up, so no
 * trace in the typed range is left out. An empty or unreadable field is no bound.
 */
function wholeMs(typed, round) {
  const text = String(typed).trim();
  const ms = text === '' ? NaN : Number(text);
  return Number.isFinite(ms) ? Math.max(-Number.MAX_SAFE_INTEGER, Math.min(Number.MAX_SAFE_INTEGER, round(ms))) : '';
}

export function render(root, ctx) {
  const initialQuery = { ...ctx.query() };
  const filter = {
    q: initialQuery.q || '',
    minMs: initialQuery.minMs || '',
    maxMs: initialQuery.maxMs || '',
    status: router.queryParam(initialQuery, 'status', ['all', 'error', 'ok'], 'all'),
    endpointId: initialQuery.endpointId || '',
    // What the duration, the status, the service and the endpoint are read off: the trace, or
    // one request of it, as the Scatter and the Response summary count (api.adoc#traces).
    by: router.queryParam(initialQuery, 'by', ['trace', 'entry'], 'trace'),
  };
  const LIMIT = 50;
  let rows = [];
  let total = 0;
  // Load more has paged back from the newest page, for the view `loadedFor` names.
  let pagedBack = false;
  let loadedFor = '';
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
  const bySelect = h('select', { 'aria-label': 'Filter by the trace or by one of its requests' },
    h('option', { value: 'trace' }, 'By trace'),
    h('option', { value: 'entry' }, 'By request'));
  bySelect.value = filter.by;
  const endpointSelect = h('select', { 'aria-label': 'Endpoint filter' }, h('option', { value: '' }, 'Any endpoint'));
  endpointSelect.hidden = true;

  const applyFilter = debounce(() => {
    filter.q = input.value.trim();
    filter.minMs = minInput.value;
    filter.maxMs = maxInput.value;
    filter.status = statusSelect.value;
    filter.endpointId = endpointSelect.value;
    filter.by = bySelect.value;
    router.setQuery({ ...filter }, { defaults: { status: 'all', by: 'trace' } });
    rows = [];
    list.load();
  }, 400);

  input.addEventListener('input', applyFilter);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') applyFilter.flush(); });
  minInput.addEventListener('input', applyFilter);
  maxInput.addEventListener('input', applyFilter);
  statusSelect.addEventListener('change', () => applyFilter.flush());
  endpointSelect.addEventListener('change', () => applyFilter.flush());
  bySelect.addEventListener('change', () => applyFilter.flush());

  const countLabel = h('span.muted', { style: { marginLeft: 'auto', fontSize: '11px' } });
  const bar = h('div.querybar',
    h('div.search', icon('search'), input),
    h('label.range-field', 'Min', minInput, h('span.range-dash', '–'), 'Max', maxInput),
    statusSelect,
    bySelect,
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
        : noDataYet('No trace has arrived yet. Point an application at this collector and reload.'));
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

  /** The names of the endpoints the select has offered, for one the window no longer holds. */
  const endpointNames = new Map();

  /**
   * The endpoint filter offers the top bar's service's endpoints, and is hidden without one. The
   * endpoint the filter names stays an option when the window has no call of it, as `<name> (not
   * seen)`, so the select never reads "Any endpoint" while the hash and the list filter by it.
   */
  const endpoints = pageLoader({
    fetch: async () => (api.state.service ? api.endpoints({}) : null),
    paint: (res) => {
      if (!res) { endpointSelect.hidden = true; return; }
      const options = res.endpoints || [];
      for (const e of options) endpointNames.set(e.endpointId, e.name);
      const value = filter.endpointId;
      const unseen = value && !options.some((e) => e.endpointId === value) ? value : '';
      fillOptions(endpointSelect, [['', 'Any endpoint'],
        ...options.map((e) => [e.endpointId, e.name]),
        ...(unseen ? [[unseen, (endpointNames.get(unseen) || unseen) + ' (not seen)']] : [])]);
      endpointSelect.value = value;
      endpointSelect.hidden = false;
    },
    onError: () => { endpointSelect.hidden = true; return false; },
  });

  /** The top-bar state and the filter the rows answer: a change of either starts over. */
  function viewKey() {
    return JSON.stringify([api.state.service, api.state.range, filter.q, filter.minMs, filter.maxMs, filter.status, filter.endpointId, filter.by]);
  }

  /**
   * `cursor` is the last row's `{ before: start, beforeId: traceId }` when loading more. Without one,
   * the newest page replaces the rows, except under a Live tick after Load more, which merges it
   * (pages.adoc#traces-list), as the Logs page does.
   */
  const list = pageLoader({
    fetch: async (cursor) => {
      const key = viewKey();
      const tail = !cursor && pagedBack && key === loadedFor && rows.length > 0;
      const w = api.windowFor();
      const res = await api.traces({
        q: filter.q,
        minMs: wholeMs(filter.minMs, Math.floor),
        maxMs: wholeMs(filter.maxMs, Math.ceil),
        status: filter.status === 'all' ? '' : filter.status,
        endpointId: filter.endpointId,
        by: filter.by === 'trace' ? '' : filter.by,
        limit: LIMIT,
        before: cursor && cursor.before,
        beforeId: cursor && cursor.beforeId,
      }, { window: w });
      return { res, key, tail, w };
    },
    paint: ({ res, key, tail, w }, cursor) => {
      const incoming = res.traces || [];
      total = res.total || incoming.length;
      const kept = tail ? mergeNewestPage(rows, incoming, w.from, LIMIT, (r) => r.start, (r) => r.traceId) : null;
      if (cursor) {
        const seen = new Set(rows.map((r) => r.traceId));
        rows = rows.concat(incoming.filter((r) => !seen.has(r.traceId)));
        pagedBack = true;
      } else if (kept) {
        rows = kept;
      } else {
        rows = incoming;
        pagedBack = false;
      }
      loadedFor = key;
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
      if (!rows.length || !drawerOpen()) { endpoints.load(); list.load(); }
    },
    destroy: () => { list.destroy(); endpoints.destroy(); applyFilter.cancel(); },
  };
}

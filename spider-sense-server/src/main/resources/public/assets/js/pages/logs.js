// Logs: a query bar and a dense table that tails while Live is on.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, table, serviceChip, severityChip, debounce, spinner, emptyState, snippetBlocks } from '../ui.js';
import { pageLoader } from '../page.js';
import { stackTrace } from '../frames.js';
import { timeMs, bothTimes, count, shortId } from '../format.js';

const SEVERITIES = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'];
const LIMIT = 200;

export function render(root, ctx) {
  let rows = [];
  let total = 0;
  let node = null;
  const expanded = new Set();
  // Set once Load more has fetched a page further back: a Live tick then merges the newest
  // page into the rows instead of replacing them, for the view the rows were loaded for.
  let pagedBack = false;
  let loadedFor = '';

  const filter = {
    q: ctx.query.q || '',
    severity: router.queryParam(ctx.query, 'severity', SEVERITIES, ''),
    traceId: ctx.query.traceId || '',
  };

  const input = h('input', { type: 'search', placeholder: 'Search log bodies', value: filter.q, 'aria-label': 'Search logs' });
  const sevSelect = h('select', { 'aria-label': 'Minimum severity' },
    h('option', { value: '' }, 'Any severity'),
    SEVERITIES.map((s) => h('option', { value: s }, s + ' and above')));
  sevSelect.value = filter.severity;
  const traceInput = h('input', { type: 'text', placeholder: 'trace id', value: filter.traceId, 'aria-label': 'Trace id', style: { width: '190px', fontFamily: 'var(--font-mono)' } });

  const apply = debounce(() => {
    filter.q = input.value.trim();
    filter.severity = sevSelect.value;
    filter.traceId = traceInput.value.trim();
    router.setQuery({ q: filter.q, severity: filter.severity, traceId: filter.traceId });
    rows = [];
    loader.load();
  }, 400);
  input.addEventListener('input', apply);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') apply.flush(); });
  traceInput.addEventListener('input', apply);
  sevSelect.addEventListener('change', () => apply.flush());

  const countLabel = h('span.muted', { style: { marginLeft: 'auto', fontSize: '11px' } });
  const body = h('div', spinner());
  const moreBtn = h('button.btn', { type: 'button', onclick: () => loadMore() }, 'Load more');
  const foot = h('div.panel-note', { style: { display: 'flex', justifyContent: 'center' } }, moreBtn);
  foot.hidden = true;

  root.appendChild(panel({ title: 'Logs' },
    h('div.querybar',
      h('div.search', icon('search'), input),
      sevSelect,
      h('label', 'Trace', traceInput),
      countLabel),
    body, foot));

  const columns = [
    { key: 'at', label: 'Time', sortable: false, width: '112px', render: (l) => h('span.mono', { title: bothTimes(l.at) }, timeMs(l.at)) },
    { key: 'severity', label: 'Level', sortable: false, width: '64px', render: (l) => severityChip(l.severity) },
    { key: 'service', label: 'Service', sortable: false, width: '148px', render: (l) => serviceChip(l.service) },
    { key: 'logger', label: 'Logger', sortable: false, width: '180px', render: (l) => h('span.cell-ellipsis.mono.muted', { title: l.logger }, l.logger || '-') },
    { key: 'body', label: 'Message', sortable: false, cls: 'wide', render: (l) => h('span.log-body', l.body) },
    {
      key: 'traceId', label: 'Trace', sortable: false, width: '88px',
      render: (l) => (l.traceId
        ? h('a.mono', { href: router.detailHref('traces', l.traceId), title: l.traceId, onclick: (e) => e.stopPropagation() }, shortId(l.traceId))
        : h('span.muted', '-')),
    },
  ];

  function paint() {
    countLabel.textContent = rows.length ? count(rows.length) + ' of ' + count(total) : '';
    if (!node) {
      node = table(columns, {
        rowKey: (l) => String(l.id),
        detail: detailOf,
        detailClass: 'log-detail',
        expanded,
        empty: 'No log in this window.',
      });
      fill(body, node);
    }
    node.setRows(rows);
    foot.hidden = rows.length >= total || !rows.length;
  }

  /** An open row: its attributes, and the stack trace an exception carries. */
  function detailOf(l) {
    const attrs = { ...(l.attributes || {}) };
    const stack = attrs['exception.stacktrace'];
    delete attrs['exception.stacktrace'];
    return h('div', { style: { display: 'grid', gap: '10px', padding: '4px 0' } },
      Object.keys(attrs).length ? h('dl.kv', Object.entries(attrs).map(([k, v]) => [h('dt', k), h('dd', String(v))])) : h('span.muted', 'No attribute.'),
      stack ? stackTrace(stack) : null);
  }

  /** The top-bar state and the filter the rows answer: a change of either starts over. */
  function viewKey() {
    return JSON.stringify([api.state.service, api.state.range, filter.q, filter.severity, filter.traceId]);
  }

  /**
   * The newest page merged into the rows already loaded, newest first by `at` then `id`, less
   * the rows the window has left; null when the page does not reach them, and a gap would open.
   */
  function merged(incoming, from) {
    const known = new Set(rows.map((l) => l.id));
    const fresh = incoming.filter((l) => !known.has(l.id));
    if (fresh.length === incoming.length && incoming.length >= LIMIT) return null;
    return fresh.concat(rows)
      .filter((l) => l.at >= from)
      .sort((a, b) => (b.at - a.at) || (b.id - a.id));
  }

  /**
   * `cursor` is the last row's `{ before: at, beforeId: id }` when loading more. Without one,
   * the newest page replaces the rows, except under a Live tick after Load more, which merges it.
   */
  async function fetchLogs(cursor) {
    const key = viewKey();
    const tail = !cursor && pagedBack && key === loadedFor && rows.length > 0;
    const w = api.windowFor();
    const res = await api.logs({
      q: filter.q, severity: filter.severity, traceId: filter.traceId, limit: LIMIT,
      before: cursor && cursor.before, beforeId: cursor && cursor.beforeId,
    }, { window: w });
    return { res, key, tail, w };
  }

  function paintLogs({ res, key, tail, w }, cursor) {
    const incoming = res.logs || [];
    total = res.total || incoming.length;
    const kept = tail ? merged(incoming, w.from) : null;
    if (cursor) {
      const seen = new Set(rows.map((l) => l.id));
      rows = rows.concat(incoming.filter((l) => !seen.has(l.id)));
      pagedBack = true;
    } else if (kept) {
      rows = kept;
    } else {
      rows = incoming;
      pagedBack = false;
    }
    loadedFor = key;
    if (!rows.length && !filter.q && !filter.severity && !filter.traceId
        && !((api.state.status || {}).counts || {}).logs) {
      node = null;
      fill(body, emptyState('No log record has arrived yet. The OpenTelemetry agent exports logs when the logs exporter is on.',
        snippetBlocks((api.state.status || {}).endpoint || location.origin)));
      foot.hidden = true;
      return;
    }
    paint();
  }

  const loader = pageLoader({ fetch: fetchLogs, paint: paintLogs, body, onError: () => { node = null; } });

  function loadMore() {
    const last = rows[rows.length - 1];
    if (last) loader.load({ before: last.at, beforeId: last.id });
  }

  loader.load();

  return {
    // Live tail: new lines arrive at the top; the scroll position is left alone.
    refresh: () => { if (!expanded.size || window.scrollY < 40) loader.load(); },
    destroy: () => { loader.destroy(); apply.cancel(); },
  };
}

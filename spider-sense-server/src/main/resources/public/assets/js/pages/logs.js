// Logs: a query bar and a dense table that tails while Live is on.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, table, serviceChip, severityChip, renderList, debounce, spinner, errorBox, emptyState, snippetBlocks } from '../ui.js';
import { stackTrace } from '../sql.js';
import { timeMs, bothTimes, count, shortId } from '../format.js';

const SEVERITIES = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'];

export function render(root, ctx) {
  let destroyed = false;
  const latest = api.requestSequence();
  let rows = [];
  let total = 0;
  let node = null;
  const expanded = new Set();

  const filter = {
    q: ctx.query.q || '',
    severity: SEVERITIES.includes(ctx.query.severity) ? ctx.query.severity : '',
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
    load();
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
        ? h('a.mono', { href: router.href('/traces/' + l.traceId, api.sharedQuery()), title: l.traceId, onclick: (e) => e.stopPropagation() }, shortId(l.traceId))
        : h('span.muted', '-')),
    },
  ];

  const opts = {
    rowKey: (l) => String(l.id),
    onRowClick: (l) => toggle(l),
    empty: 'No log in this window.',
  };

  function toggle(l) {
    const key = String(l.id);
    if (expanded.has(key)) expanded.delete(key); else expanded.add(key);
    paint();
  }

  /** Rows plus a detail row after each expanded one. */
  function withDetails() {
    const out = [];
    for (const l of rows) {
      out.push(l);
      if (expanded.has(String(l.id))) out.push({ ...l, id: 'detail:' + l.id, detail: l });
    }
    return out;
  }

  function paint() {
    countLabel.textContent = rows.length ? count(rows.length) + ' of ' + count(total) : '';
    const list = withDetails();
    if (!node) {
      node = table(columns, { ...opts, rows: [] });
      fill(body, node);
    }
    renderList(node.tbody, list, {
      key: (l) => String(l.id),
      create: (l) => (l.detail ? detailRow(l.detail) : buildRow(l)),
      update: (n, l) => { if (!l.detail) { const fresh = buildRow(l); n.replaceChildren(...fresh.childNodes); } },
    });
    if (!list.length) fill(node.tbody, h('tr.empty-row', h('td', { colspan: columns.length }, h('span.muted', opts.empty))));
    foot.hidden = rows.length >= total || !rows.length;
  }

  function buildRow(l) {
    const tr = h('tr.clickable', { tabindex: 0, onclick: (e) => { if (!e.target.closest('a')) toggle(l); } });
    for (const col of columns) {
      const td = h('td', { class: [col.align === 'right' ? 'right' : null, col.cls].filter(Boolean).join(' ') || null });
      const v = col.render(l);
      if (v) td.appendChild(v.nodeType ? v : document.createTextNode(String(v)));
      tr.appendChild(td);
    }
    return tr;
  }

  function detailRow(l) {
    const attrs = { ...(l.attributes || {}) };
    const stack = attrs['exception.stacktrace'];
    delete attrs['exception.stacktrace'];
    return h('tr.log-detail', h('td', { colspan: columns.length },
      h('div', { style: { display: 'grid', gap: '10px', padding: '4px 0' } },
        Object.keys(attrs).length ? h('dl.kv', Object.entries(attrs).map(([k, v]) => [h('dt', k), h('dd', String(v))])) : h('span.muted', 'No attribute.'),
        stack ? stackTrace(stack) : null)));
  }

  /** `cursor` is the last row's `{ before: at, beforeId: id }` when loading more. */
  async function load(cursor) {
    const current = latest();
    try {
      const res = await api.logs({
        q: filter.q, severity: filter.severity, traceId: filter.traceId, limit: 200,
        before: cursor && cursor.before, beforeId: cursor && cursor.beforeId,
      });
      if (destroyed || !current()) return;
      const incoming = res.logs || [];
      total = res.total || incoming.length;
      if (cursor) {
        const seen = new Set(rows.map((l) => l.id));
        rows = rows.concat(incoming.filter((l) => !seen.has(l.id)));
      } else {
        rows = incoming;
      }
      if (!rows.length && !filter.q && !filter.severity && !filter.traceId
          && !((api.state.status || {}).counts || {}).logs) {
        node = null;
        fill(body, emptyState('No log record has arrived yet. The OpenTelemetry agent exports logs when the logs exporter is on.',
          snippetBlocks((api.state.status || {}).endpoint || location.origin)));
        foot.hidden = true;
        return;
      }
      paint();
    } catch (e) {
      if (!destroyed && current()) { node = null; fill(body, errorBox(e, () => load())); }
    }
  }

  function loadMore() {
    const last = rows[rows.length - 1];
    if (last) load({ before: last.at, beforeId: last.id });
  }

  load();

  return {
    // Live tail: new lines arrive at the top; the scroll position is left alone.
    refresh: () => { if (!expanded.size || window.scrollY < 40) load(); },
    destroy: () => { destroyed = true; apply.cancel(); },
  };
}

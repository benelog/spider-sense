// One trace: the waterfall, the Scouter-style profile, the span drawer and the logs.

import * as api from '../api.js';
import * as router from '../router.js';
import {
  h, fill, icon, panel, table, chip, serviceChip, serviceColor, severityChip, idButton, segmented, categoryIcon,
  drawer, closeDrawer, spinner,
} from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { formatSql } from '../sql.js';
import { stackTrace } from '../frames.js';
import { slowRequestMs } from '../buckets.js';
import { dur, count, timeMs, bothTimes, offset, full } from '../format.js';
import {
  startMsOf, traceStartMs, spanTree, flattenTree, selfTimes, profileRows, hotSpanIds,
} from '../trace-model.js';


export function render(root, ctx) {
  const traceId = ctx.params.id;
  let data = null;
  let view = router.queryParam(ctx.query, 'view', ['waterfall', 'profile'], 'waterfall');
  let profileSort = router.queryParam(ctx.query, 'sort', ['start', 'elapsed', 'self'], 'start');
  let selectedSpan = ctx.query.span || null;
  const collapsed = new Set();
  let shape = '';

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const bodyBox = h('div', spinner());
  const bodyPanel = panel({ title: 'Spans' }, bodyBox);
  const logsBox = h('div');
  const logsPanel = h('section.panel#trace-logs',
    h('div.panel-head', h('h2.panel-title', 'Logs')), logsBox);

  const layout = skeleton(root, () => [headPanel, bodyPanel, logsPanel]);

  // --- header -----------------------------------------------------------

  function paintHead() {
    const rootSpan = (data.spans || [])[0] || {};
    const errors = (data.spans || []).filter((s) => s.error).length;
    fill(head,
      h('div', { style: { display: 'grid', gap: '4px', minWidth: '0' } },
        h('div.row', { style: { gap: '8px' } },
          h('b', { style: { fontSize: '15px' } }, rootSpan.name || 'Trace'),
          (data.services || []).map((s) => serviceChip(s))),
        h('div.row', { style: { gap: '14px' } },
          idButton(data.traceId || traceId, 'Copy trace id'),
          h('span.muted', { style: { fontSize: '11px' }, title: bothTimes(data.start) }, full(data.start)),
          h('span.muted', { style: { fontSize: '11px' } }, dur(data.durationMs)),
          h('span.muted', { style: { fontSize: '11px' } }, count((data.spans || []).length) + ' spans'),
          errors ? h('span.bad', { style: { fontSize: '11px' } }, count(errors) + ' errors') : null,
          (data.logs || []).length
            ? h('a.link-btn', { href: '#trace-logs', onclick: (e) => { e.preventDefault(); logsPanel.scrollIntoView({ behavior: 'smooth', block: 'start' }); } }, count(data.logs.length) + ' logs')
            : null)),
      h('div.row', { style: { marginLeft: 'auto', gap: '8px' } },
        segmented({
          label: 'View',
          options: [['waterfall', 'Waterfall'], ['profile', 'Profile']],
          value: view,
          onChange: (id) => { view = id; router.setQuery({ view: id }, { defaults: { view: 'waterfall' } }); paintBody(); },
        }),
        h('a.btn', { href: api.exportUrl({ traceId: data.traceId || traceId }), download: 'trace-' + (data.traceId || traceId) + '.json' }, icon('download'), 'Export')));
  }

  // --- waterfall --------------------------------------------------------

  function paintWaterfall() {
    const t0 = traceStartMs(data);
    const total = Math.max(1, data.durationMs || 1);
    const rows = flattenTree(spanTree(data.spans || []), collapsed);
    const box = h('div.waterfall',
      h('div.wf-head', h('span', 'Span'), h('span', 'Timeline'), h('span.right', dur(total) + ' total')));
    for (const { span, depth, hasChildren } of rows) {
      box.appendChild(waterfallRow(span, depth, hasChildren, t0, total));
    }
    fill(bodyBox, box);
  }

  function waterfallRow(span, depth, hasChildren, t0, total) {
    const startPct = Math.max(0, ((startMsOf(span) - t0) / total) * 100);
    const widthPct = Math.max(0.4, Math.min(100 - startPct, (span.durationMs / total) * 100));
    const bar = h('span.wf-bar', {
      class: 'wf-bar' + (span.error ? ' err' : ''),
      style: { left: startPct + '%', width: widthPct + '%', background: span.error ? undefined : serviceColor(span.service) },
    });
    const label = h('span.wf-dur', { class: 'wf-dur' + (span.error ? ' bad' : '') }, dur(span.durationMs));
    const toggle = hasChildren
      ? h('button.wf-toggle', {
        type: 'button',
        'aria-expanded': String(!collapsed.has(span.spanId)),
        'aria-label': (collapsed.has(span.spanId) ? 'Expand ' : 'Collapse ') + span.name,
        onclick: (e) => {
          e.stopPropagation();
          if (collapsed.has(span.spanId)) collapsed.delete(span.spanId); else collapsed.add(span.spanId);
          paintWaterfall();
          // The rows are new: keep a keyboard user on the toggle they pressed.
          const again = bodyBox.querySelector('[data-key="' + CSS.escape(span.spanId) + '"] .wf-toggle');
          if (again) again.focus();
        },
      }, icon('chevron'))
      : h('span.wf-spacer');
    const row = h('div.wf-row', {
      class: 'wf-row' + (span.slow ? ' slow' : '') + (selectedSpan === span.spanId ? ' selected' : ''),
      dataset: { key: span.spanId },
      tabindex: 0,
      onclick: () => openSpan(span),
      // Only the row's own Enter: one on the toggle inside it is the toggle's.
      onkeydown: (e) => { if (e.key === 'Enter' && e.target === e.currentTarget) openSpan(span); },
    },
      h('div.wf-name', { style: { paddingLeft: depth * 14 + 'px' } },
        toggle,
        h('span.wf-svc', { style: { background: serviceColor(span.service) }, title: span.service }),
        icon(categoryIcon(span.category, 'chart')),
        h('span.wf-label', { title: span.summary || span.name }, span.name),
        span.error ? h('span.marker.err', { title: 'Error' }, icon('bolt')) : null),
      h('div.wf-track', bar),
      label);
    return row;
  }

  // --- profile ----------------------------------------------------------

  function paintProfile() {
    const total = Math.max(1, data.durationMs || 1);
    const slowMs = slowRequestMs();
    const rows = profileRows(data, profileSort);
    // The three steps that actually spent the time, and only when they spent enough of it to be worth reading.
    const hot = hotSpanIds(rows, total);
    const chronological = profileSort === 'start';
    const sortState = { key: profileSort, dir: chronological ? 'asc' : 'desc' };
    const onSort = (key) => {
      profileSort = key;
      router.setQuery({ sort: key }, { defaults: { sort: 'start' } });
      paintProfile();
    };
    fill(bodyBox, table([
      { key: 'index', label: '#', align: 'right', sortable: false, width: '44px', render: (r) => h('span.muted.mono', String(r.index)) },
      { key: 'start', label: 'Start', align: 'right', width: '84px', render: (r) => h('span.mono', offset(r.startOffset)) },
      { key: 'gap', label: 'Gap', align: 'right', sortable: false, width: '76px', render: (r) => (chronological && r.gap > 0.5 ? h('span.mono.muted', dur(r.gap)) : h('span.muted', '-')) },
      { key: 'elapsed', label: 'Elapsed', align: 'right', width: '84px', render: (r) => h('span.mono', dur(r.span.durationMs)) },
      { key: 'self', label: 'Self', align: 'right', width: '84px', render: (r) => h('span.mono.self-value', dur(r.self)) },
      { key: 'pct', label: '%', align: 'right', sortable: false, width: '58px', render: (r) => h('span.mono.muted', ((r.self / total) * 100).toFixed(1)) },
      {
        key: 'step', label: 'Step', sortable: false, cls: 'wide',
        render: (r) => h('span.cell-ellipsis', { style: { paddingLeft: r.depth * 14 + 'px' }, title: r.span.summary || r.span.name },
          h('span.row', { style: { gap: '6px' } },
            icon(categoryIcon(r.span.category, 'chart')),
            h('span', r.span.summary || r.span.name),
            r.span.error ? icon('bolt') : null)),
      },
      { key: 'service', label: 'Service', sortable: false, width: '150px', render: (r) => serviceChip(r.span.service) },
    ], {
      rows,
      sort: sortState,
      onSort,
      rowKey: (r) => r.span.spanId,
      rowClass: (r) => 'profile-row' + (r.span.error ? ' err' : r.span.slow || r.span.durationMs > slowMs ? ' warn' : '') + (hot.has(r.span.spanId) ? ' hot' : ''),
      onRowClick: (r) => openSpan(r.span),
      empty: 'This trace has no span.',
    }));
  }

  function paintBody() {
    if (view === 'profile') paintProfile(); else paintWaterfall();
  }

  // --- span drawer ------------------------------------------------------

  function openSpan(span) {
    selectedSpan = span.spanId;
    router.setQuery({ span: span.spanId });
    for (const row of bodyBox.querySelectorAll('.wf-row.selected, tr.selected')) row.classList.remove('selected');
    const rows = bodyBox.querySelectorAll('.wf-row, tbody tr');
    for (const row of rows) if (row.dataset.key === span.spanId) row.classList.add('selected');
    drawer({
      title: span.name,
      subtitle: span.service + ' · ' + span.kind,
      body: spanBody(span),
      onClose: () => { selectedSpan = null; router.setQuery({ span: '' }); },
    });
  }

  function spanBody(span) {
    const t0 = traceStartMs(data);
    const total = Math.max(1, data.durationMs || 1);
    const selfMs = selfTimes(data.spans || []).get(span.spanId) || 0;
    const attrs = Object.entries(span.attributes || {}).sort((a, b) => a[0].localeCompare(b[0]));
    return [
      h('dl.kv',
        h('dt', 'span id'), h('dd', span.spanId),
        h('dt', 'parent'), h('dd', span.parentSpanId || '—'),
        h('dt', 'kind'), h('dd', span.kind || 'INTERNAL'),
        h('dt', 'start'), h('dd', offset(startMsOf(span) - t0) + ' (' + timeMs(span.start) + ')'),
        h('dt', 'duration'), h('dd', dur(span.durationMs) + ' · ' + ((span.durationMs / total) * 100).toFixed(1) + '% of trace'),
        h('dt', 'self'), h('dd', dur(selfMs) + ' · ' + ((selfMs / total) * 100).toFixed(1) + '% of trace'),
        h('dt', 'status'), h('dd', { class: span.error ? 'bad' : '' }, (span.status || 'UNSET') + (span.statusMessage ? ' — ' + span.statusMessage : '')),
        h('dt', 'scope'), h('dd', span.scope || '—')),
      attrs.length ? h('div',
        h('div.sub-head', { style: { marginBottom: '6px' } }, 'Attributes'),
        h('dl.kv', attrs.map(([k, v]) => [h('dt', k), h('dd', attrValue(k, v))]))) : null,
      (span.events || []).length ? h('div',
        h('div.sub-head', { style: { marginBottom: '6px' } }, 'Events'),
        h('div', { style: { display: 'grid', gap: '10px' } }, (span.events || []).map((ev) => eventBlock(ev, t0)))) : null,
    ];
  }

  function attrValue(key, value) {
    if (Array.isArray(value)) return h('pre', value.join('\n'));
    const text = String(value);
    if (key === 'db.statement' || key === 'db.query.text') return h('pre', formatSql(text));
    if (text.length > 120 || text.includes('\n')) return h('pre', text);
    return document.createTextNode(text);
  }

  function eventBlock(ev, t0) {
    const attrs = { ...(ev.attributes || {}) };
    const stack = attrs['exception.stacktrace'];
    delete attrs['exception.stacktrace'];
    return h('div', { style: { display: 'grid', gap: '6px' } },
      h('div.row', { style: { gap: '8px' } },
        chip(ev.name, { class: ev.name === 'exception' ? 'chip-accent' : '' }),
        h('span.muted', { style: { fontSize: '11px' }, title: bothTimes(ev.time) }, offset(ev.time - t0))),
      Object.keys(attrs).length ? h('dl.kv', Object.entries(attrs).map(([k, v]) => [h('dt', k), h('dd', String(v))])) : null,
      stack ? stackTrace(stack) : null);
  }

  // --- logs -------------------------------------------------------------

  function paintLogs() {
    const logs = data.logs || [];
    const t0 = traceStartMs(data);
    fill(logsBox, table([
      { key: 'offset', label: 'Offset', align: 'right', sortable: false, width: '80px', render: (l) => h('span.mono.muted', offset(l.at - t0)) },
      { key: 'severity', label: 'Level', sortable: false, width: '68px', render: (l) => severityChip(l.severity) },
      { key: 'logger', label: 'Logger', sortable: false, width: '180px', render: (l) => h('span.cell-ellipsis.mono.muted', { title: l.logger }, l.logger || '-') },
      { key: 'body', label: 'Message', sortable: false, cls: 'wide', render: (l) => h('span.log-body', l.body) },
    ], { rows: logs, rowKey: (l) => l.id, empty: 'No log carries this trace id.' }));
  }

  /** What a late export changes: the spans, the extent, the services and the logs. */
  function shapeOf(d) {
    return [(d.spans || []).length, d.start, d.durationMs, (d.services || []).join(','), (d.logs || []).length].join('|');
  }

  /**
   * `live` is a Live refresh: a trace's spans arrive in several exports and from several
   * services, so one opened early is incomplete. The refetch repaints only when the trace
   * changed, keeping the collapsed spans, the selected span, its open drawer and the focus.
   */
  function paint(next, live = false) {
    const nextShape = shapeOf(next);
    if (live && layout.built && nextShape === shape) return;
    shape = nextShape;
    data = next;
    const focused = live && document.activeElement && bodyBox.contains(document.activeElement)
      ? document.activeElement.closest('[data-key]') : null;
    layout.build();
    ctx.setTitle(((data.spans || [])[0] || {}).name || 'Trace');
    paintHead();
    paintBody();
    paintLogs();
    if (focused) {
      const again = bodyBox.querySelector('[data-key="' + CSS.escape(focused.dataset.key) + '"]');
      if (again) again.focus();
    }
    if (selectedSpan) {
      const span = (data.spans || []).find((s) => s.spanId === selectedSpan);
      if (span && live) {
        for (const row of bodyBox.querySelectorAll('.wf-row, tbody tr')) row.classList.toggle('selected', row.dataset.key === span.spanId);
        const open = document.querySelector('.drawer .drawer-body');
        if (open) fill(open, spanBody(span));
      } else if (span) {
        openSpan(span);
      }
    }
  }

  const loader = pageLoader({
    fetch: () => api.trace(traceId),
    paint,
    body: layout,
    // A Live refresh that fails keeps the trace on screen; the next tick asks again.
    onError: (e, live) => !(live && layout.built),
  });

  loader.load();
  return {
    refresh: () => { if (api.state.live) loader.load(true); },
    onEscape: () => closeDrawer(),
    destroy: () => { loader.destroy(); closeDrawer(true); },
  };
}

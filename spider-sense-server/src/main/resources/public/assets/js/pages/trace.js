// One trace: the waterfall, the Scouter-style profile, the span drawer and the logs.

import * as api from '../api.js';
import * as router from '../router.js';
import {
  h, fill, icon, panel, table, chip, serviceChip, serviceColor, severityChip, idButton,
  drawer, closeDrawer, spinner, errorBox,
} from '../ui.js';
import { formatSql, stackTrace } from '../sql.js';
import { dur, count, timeMs, bothTimes, offset, full } from '../format.js';

const CATEGORY_ICON = { http: 'trace', db: 'database', messaging: 'log', rpc: 'service', internal: 'chart' };

export function render(root, ctx) {
  const traceId = ctx.params.id;
  let destroyed = false;
  let data = null;
  let view = ctx.query.view === 'profile' ? 'profile' : 'waterfall';
  let selectedSpan = ctx.query.span || null;
  const collapsed = new Set();

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const bodyBox = h('div', spinner());
  const bodyPanel = panel({ title: 'Spans' }, bodyBox);
  const logsBox = h('div');
  const logsPanel = h('section.panel#trace-logs',
    h('div.panel-head', h('h2.panel-title', 'Logs')), logsBox);

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, headPanel, bodyPanel, logsPanel);
  }

  // --- model ------------------------------------------------------------

  function tree(spans) {
    const byId = new Map(spans.map((s) => [s.spanId, s]));
    const children = new Map();
    const roots = [];
    for (const s of spans) {
      const parent = s.parentSpanId && byId.has(s.parentSpanId) ? s.parentSpanId : null;
      if (parent) {
        if (!children.has(parent)) children.set(parent, []);
        children.get(parent).push(s);
      } else {
        roots.push(s);
      }
    }
    return { roots, children };
  }

  function flatten(spans) {
    const { roots, children } = tree(spans);
    const out = [];
    const walk = (span, depth) => {
      const kids = children.get(span.spanId) || [];
      out.push({ span, depth, hasChildren: kids.length > 0 });
      if (collapsed.has(span.spanId)) return;
      for (const kid of kids) walk(kid, depth + 1);
    };
    for (const r of roots) walk(r, 0);
    return out;
  }

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
        h('div.row', { style: { gap: '2px' }, role: 'group', 'aria-label': 'View' },
          viewBtn('waterfall', 'Waterfall'),
          viewBtn('profile', 'Profile')),
        h('a.btn', { href: api.exportUrl({ traceId: data.traceId || traceId }), download: 'trace-' + (data.traceId || traceId) + '.json' }, icon('download'), 'Export')));
  }

  function viewBtn(id, label) {
    return h('button.btn', {
      type: 'button', 'aria-pressed': String(view === id),
      onclick: () => { view = id; router.setQuery({ view: id === 'waterfall' ? '' : id }); paintHead(); paintBody(); },
    }, label);
  }

  // --- waterfall --------------------------------------------------------

  function paintWaterfall() {
    const spans = data.spans || [];
    const t0 = data.start;
    const total = Math.max(1, data.durationMs || 1);
    const rows = flatten(spans);
    const box = h('div.waterfall',
      h('div.wf-head', h('span', 'Span'), h('span', 'Timeline'), h('span.right', dur(total) + ' total')));
    for (const { span, depth, hasChildren } of rows) {
      box.appendChild(waterfallRow(span, depth, hasChildren, t0, total));
    }
    fill(bodyBox, box);
  }

  function waterfallRow(span, depth, hasChildren, t0, total) {
    const startPct = Math.max(0, ((span.start - t0) / total) * 100);
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
        },
      }, icon('chevron'))
      : h('span.wf-spacer');
    const row = h('div.wf-row', {
      class: 'wf-row' + (span.slow ? ' slow' : '') + (selectedSpan === span.spanId ? ' selected' : ''),
      dataset: { key: span.spanId },
      tabindex: 0,
      onclick: () => openSpan(span),
      onkeydown: (e) => { if (e.key === 'Enter') openSpan(span); },
    },
      h('div.wf-name', { style: { paddingLeft: depth * 14 + 'px' } },
        toggle,
        h('span.wf-svc', { style: { background: serviceColor(span.service) }, title: span.service }),
        icon(CATEGORY_ICON[span.category] || 'chart'),
        h('span.wf-label', { title: span.summary || span.name }, span.name),
        span.error ? h('span.marker.err', { title: 'Error' }, icon('bolt')) : null),
      h('div.wf-track', bar),
      label);
    return row;
  }

  // --- profile ----------------------------------------------------------

  function paintProfile() {
    const spans = (data.spans || []).slice().sort((a, b) => a.start - b.start || b.durationMs - a.durationMs);
    const depths = depthMap(data.spans || []);
    const t0 = data.start;
    const slowMs = ((api.state.status || {}).thresholds || {}).slowRequestMs || 500;
    let prevEnd = t0;
    const rows = spans.map((span, i) => {
      const gap = span.start - prevEnd;
      prevEnd = Math.max(prevEnd, span.start);
      return { span, index: i + 1, startOffset: span.start - t0, gap, depth: depths.get(span.spanId) || 0 };
    });
    fill(bodyBox, table([
      { key: 'index', label: '#', align: 'right', sortable: false, width: '44px', render: (r) => h('span.muted.mono', String(r.index)) },
      { key: 'start', label: 'Start', align: 'right', sortable: false, width: '84px', render: (r) => h('span.mono', offset(r.startOffset)) },
      { key: 'gap', label: 'Gap', align: 'right', sortable: false, width: '76px', render: (r) => (r.gap > 0.5 ? h('span.mono.muted', dur(r.gap)) : h('span.muted', '-')) },
      { key: 'elapsed', label: 'Elapsed', align: 'right', sortable: false, width: '84px', render: (r) => h('span.mono', dur(r.span.durationMs)) },
      {
        key: 'step', label: 'Step', sortable: false, cls: 'wide',
        render: (r) => h('span.cell-ellipsis', { style: { paddingLeft: r.depth * 14 + 'px' }, title: r.span.summary || r.span.name },
          h('span.row', { style: { gap: '6px' } },
            icon(CATEGORY_ICON[r.span.category] || 'chart'),
            h('span', r.span.summary || r.span.name),
            r.span.error ? icon('bolt') : null)),
      },
      { key: 'service', label: 'Service', sortable: false, width: '150px', render: (r) => serviceChip(r.span.service) },
    ], {
      rows,
      rowKey: (r) => r.span.spanId,
      rowClass: (r) => 'profile-row' + (r.span.error ? ' err' : r.span.slow || r.span.durationMs > slowMs ? ' warn' : ''),
      onRowClick: (r) => openSpan(r.span),
      empty: 'This trace has no span.',
    }));
  }

  function depthMap(spans) {
    const byId = new Map(spans.map((s) => [s.spanId, s]));
    const depths = new Map();
    const depthOf = (s, seen = new Set()) => {
      if (depths.has(s.spanId)) return depths.get(s.spanId);
      if (seen.has(s.spanId)) return 0;
      seen.add(s.spanId);
      const parent = s.parentSpanId && byId.has(s.parentSpanId) ? byId.get(s.parentSpanId) : null;
      const d = parent ? depthOf(parent, seen) + 1 : 0;
      depths.set(s.spanId, d);
      return d;
    };
    for (const s of spans) depthOf(s);
    return depths;
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
    const t0 = data.start;
    const total = Math.max(1, data.durationMs || 1);
    const attrs = Object.entries(span.attributes || {}).sort((a, b) => a[0].localeCompare(b[0]));
    return [
      h('dl.kv',
        h('dt', 'span id'), h('dd', span.spanId),
        h('dt', 'parent'), h('dd', span.parentSpanId || '—'),
        h('dt', 'kind'), h('dd', span.kind || 'INTERNAL'),
        h('dt', 'start'), h('dd', offset(span.start - t0) + ' (' + timeMs(span.start) + ')'),
        h('dt', 'duration'), h('dd', dur(span.durationMs) + ' · ' + ((span.durationMs / total) * 100).toFixed(1) + '% of trace'),
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
    const t0 = data.start;
    fill(logsBox, table([
      { key: 'offset', label: 'Offset', align: 'right', sortable: false, width: '80px', render: (l) => h('span.mono.muted', offset(l.at - t0)) },
      { key: 'severity', label: 'Level', sortable: false, width: '68px', render: (l) => severityChip(l.severity) },
      { key: 'logger', label: 'Logger', sortable: false, width: '180px', render: (l) => h('span.cell-ellipsis.mono.muted', { title: l.logger }, l.logger || '-') },
      { key: 'body', label: 'Message', sortable: false, cls: 'wide', render: (l) => h('span.log-body', l.body) },
    ], { rows: logs, rowKey: (l) => l.id, empty: 'No log carries this trace id.' }));
  }

  async function load() {
    try {
      data = await api.trace(traceId);
      if (destroyed) return;
      build();
      ctx.setTitle(((data.spans || [])[0] || {}).name || 'Trace');
      paintHead();
      paintBody();
      paintLogs();
      if (selectedSpan) {
        const span = (data.spans || []).find((s) => s.spanId === selectedSpan);
        if (span) openSpan(span);
      }
    } catch (e) {
      if (destroyed) return;
      built = false;
      fill(page, errorBox(e, load));
    }
  }

  load();
  return {
    refresh: () => { /* a trace is immutable once it is complete */ },
    onEscape: () => closeDrawer(),
    destroy: () => { destroyed = true; closeDrawer(true); },
  };
}

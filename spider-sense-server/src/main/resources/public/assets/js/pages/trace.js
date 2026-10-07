// One trace: the waterfall, the Scouter-style profile, the span drawer and the logs.

import * as api from '../api.js';
import * as router from '../router.js';
import {
  h, fill, icon, panel, table, chip, serviceChip, serviceColor, severityChip, idButton, segmented, categoryIcon,
  drawer, drawerBody, closeDrawer, closeDrawerSilently, spinner, copyBlock,
} from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { formatSql } from '../sql.js';
import { stackTrace, foldedStack, appFrames, codeFrame } from '../frames.js';
import { slowRequestMs } from '../buckets.js';
import { detailOf } from './logs.js';
import { dur, count, timeMs, bothTimes, offset, full } from '../format.js';
import {
  startMsOf, traceStartMs, spanTree, flattenTree, selfTimes, profileRows, hotSpanIds,
} from '../trace-model.js';

/** The attributes that hold a span's statement, the old semantic convention's and the new one's. */
const STATEMENT_KEYS = ['db.statement', 'db.query.text'];
/** The stack the extension captures on a slow or repeated call (findings.adoc#code). */
const STACK_KEY = 'code.stacktrace';

/** The narrowest top-bar range that still holds an instant, so the Logs page finds a trace's records. */
export function rangeHolding(at, now = Date.now()) {
  const range = api.RANGES.find((r) => r.ms != null && now - r.ms <= at);
  return range ? range.id : 'all';
}

/** The line a span reads as: its summary, or its name when it has none. */
function summaryOf(span) {
  return span.summary || span.name;
}

/** `SELECT product (h2) × 21`: a run of repeated siblings as the CLI's tree says it. */
function runLabel(run) {
  return summaryOf(run.spans[0]) + ' × ' + run.count;
}

/** `21 × SELECT product (h2), 0.4 ms avg, 8.2 ms total`. */
function runTitle(run) {
  return run.count + ' × ' + summaryOf(run.spans[0]) + ', ' + dur(run.totalMs / run.count) + ' avg, ' + dur(run.totalMs) + ' total';
}

export function render(root, ctx) {
  const traceId = ctx.params.id;
  let data = null;
  let view = router.queryParam(ctx.query(), 'view', ['waterfall', 'profile'], 'waterfall');
  let profileSort = router.queryParam(ctx.query(), 'sort', ['start', 'elapsed', 'self'], 'start');
  let selectedSpan = ctx.query().span || null;
  const collapsed = new Set();
  // The runs of repeated siblings opened in place; both views share them (trace-model.js#siblingRuns).
  const expandedRuns = new Set();
  let lastShape = '';

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const bodyBox = h('div', spinner());
  const bodyPanel = panel({ title: 'Spans' }, bodyBox);
  const logsBox = h('div');
  // The open log rows, kept across a Live repaint.
  const openLogs = new Set();
  const logsLink = h('a.link-btn', { title: 'These records on the Logs page' }, 'Logs page');
  const logsPanel = h('section.panel#trace-logs',
    h('div.panel-head', h('h2.panel-title', 'Logs'), h('div.panel-actions', logsLink)), logsBox);

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
    const rows = flattenTree(spanTree(data.spans || []), collapsed, expandedRuns);
    const box = h('div.waterfall',
      h('div.wf-head', h('span', 'Span'), h('span', 'Timeline'), h('span.right', dur(total) + ' total')));
    for (const { span, depth, hasChildren, run } of rows) {
      box.appendChild(run ? runRow(run, depth, t0, total) : waterfallRow(span, depth, hasChildren, t0, total));
    }
    fill(bodyBox, box);
  }

  /** Flips a span's collapse or a run's expansion, repaints, and keeps the focus on the toggle pressed. */
  function flip(set, key) {
    if (set.has(key)) set.delete(key); else set.add(key);
    paintBody();
    // The rows are new: keep a keyboard user on the control they pressed.
    const again = bodyBox.querySelector('[data-key="' + CSS.escape(key) + '"]');
    const target = again && (again.querySelector('.wf-toggle') || again);
    if (target) target.focus();
  }

  function barOf(fromMs, durationMs, t0, total, error, service) {
    const startPct = Math.max(0, ((fromMs - t0) / total) * 100);
    const widthPct = Math.max(0.4, Math.min(100 - startPct, (durationMs / total) * 100));
    return h('span.wf-bar', {
      class: error ? 'err' : null,
      style: { left: startPct + '%', width: widthPct + '%', background: error ? undefined : serviceColor(service) },
    });
  }

  /**
   * A run of repeated siblings as one row, as the CLI's tree has it (`SELECT product × 21`): its
   * bar spans the run, its duration is the run's total, and it expands in place.
   */
  function runRow(run, depth, t0, total) {
    const first = run.spans[0];
    const open = expandedRuns.has(run.key);
    const error = run.spans.some((s) => s.error);
    const name = runLabel(run);
    const toggle = () => flip(expandedRuns, run.key);
    return h('div.wf-row.wf-run', {
      class: [run.spans.some((s) => s.slow) && 'slow'],
      dataset: { key: run.key },
      tabindex: 0,
      title: runTitle(run),
      onclick: toggle,
      onkeydown: (e) => { if (e.key === 'Enter' && e.target === e.currentTarget) toggle(); },
    },
      h('div.wf-name', { style: { paddingLeft: depth * 14 + 'px' } },
        h('button.wf-toggle', {
          type: 'button',
          'aria-expanded': String(open),
          'aria-label': (open ? 'Fold ' : 'Show the ') + run.count + ' ' + summaryOf(first),
          onclick: (e) => { e.stopPropagation(); toggle(); },
        }, icon('chevron')),
        h('span.wf-svc', { style: { background: serviceColor(first.service) }, title: first.service }),
        icon(categoryIcon(first.category, 'chart')),
        h('span.wf-label', name),
        error ? h('span.marker.err', { title: 'Error' }, icon('bolt')) : null),
      h('div.wf-track', barOf(run.start, run.end - run.start, t0, total, error, first.service)),
      h('span.wf-dur', { class: error ? 'bad' : null }, dur(run.totalMs)));
  }

  function waterfallRow(span, depth, hasChildren, t0, total) {
    const bar = barOf(startMsOf(span), span.durationMs, t0, total, span.error, span.service);
    const label = h('span.wf-dur', { class: span.error ? 'bad' : null }, dur(span.durationMs));
    const toggle = hasChildren
      ? h('button.wf-toggle', {
        type: 'button',
        'aria-expanded': String(!collapsed.has(span.spanId)),
        'aria-label': (collapsed.has(span.spanId) ? 'Expand ' : 'Collapse ') + span.name,
        onclick: (e) => { e.stopPropagation(); flip(collapsed, span.spanId); },
      }, icon('chevron'))
      : h('span.wf-spacer');
    const row = h('div.wf-row', {
      class: [span.slow && 'slow', selectedSpan === span.spanId && 'selected'],
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
        h('span.wf-label', { title: span.name }, summaryOf(span)),
        span.error ? h('span.marker.err', { title: 'Error' }, icon('bolt')) : null),
      h('div.wf-track', bar),
      label);
    return row;
  }

  // --- profile ----------------------------------------------------------

  function paintProfile() {
    const total = Math.max(1, data.durationMs || 1);
    const slowMs = slowRequestMs();
    const rows = profileRows(data, profileSort, expandedRuns);
    // The three steps that actually spent the time, and only when they spent enough of it to be worth reading.
    const hot = hotSpanIds(rows, total);
    const chronological = profileSort === 'start';
    const sortState = { key: profileSort, dir: chronological ? 'asc' : 'desc' };
    const onSort = (key) => {
      profileSort = key;
      router.setQuery({ sort: key }, { defaults: { sort: 'start' } });
      paintProfile();
    };
    const members = (r) => (r.run ? r.run.spans : [r.span]);
    fill(bodyBox, table([
      { key: 'index', label: '#', align: 'right', sortable: false, width: '58px', render: (r) => h('span.muted.mono', r.run ? r.index + '–' + r.lastIndex : String(r.index)) },
      { key: 'start', label: 'Start', align: 'right', width: '84px', render: (r) => h('span.mono', offset(r.startOffset)) },
      { key: 'gap', label: 'Gap', align: 'right', sortable: false, width: '76px', render: (r) => (chronological && r.gap > 0.5 ? h('span.mono.muted', dur(r.gap)) : h('span.muted', '-')) },
      { key: 'elapsed', label: 'Elapsed', align: 'right', width: '84px', render: (r) => h('span.mono', dur(r.elapsed)) },
      { key: 'self', label: 'Self', align: 'right', width: '84px', render: (r) => h('span.mono.self-value', dur(r.self)) },
      { key: 'pct', label: '%', align: 'right', sortable: false, width: '58px', render: (r) => h('span.mono.muted', ((r.self / total) * 100).toFixed(1)) },
      {
        key: 'step', label: 'Step', sortable: false, cls: 'wide',
        render: (r) => h('span.cell-ellipsis', { style: { paddingLeft: r.depth * 14 + 'px' }, title: r.run ? runTitle(r.run) : summaryOf(r.span) },
          h('span.row', { style: { gap: '6px' } },
            r.run
              ? h('button.wf-toggle', {
                type: 'button',
                'aria-expanded': String(expandedRuns.has(r.run.key)),
                'aria-label': (expandedRuns.has(r.run.key) ? 'Fold ' : 'Show the ') + r.run.count + ' ' + summaryOf(r.span),
                onclick: (e) => { e.stopPropagation(); flip(expandedRuns, r.run.key); },
              }, icon('chevron'))
              : null,
            icon(categoryIcon(r.span.category, 'chart')),
            h('span', r.run ? runLabel(r.run) : summaryOf(r.span)),
            members(r).some((s) => s.error) ? icon('bolt') : null)),
      },
      { key: 'service', label: 'Service', sortable: false, width: '150px', render: (r) => serviceChip(r.span.service) },
    ], {
      rows,
      sort: sortState,
      onSort,
      rowKey: (r) => r.key,
      rowClass: (r) => 'profile-row'
        + (members(r).some((s) => s.error) ? ' err' : members(r).some((s) => s.slow || s.durationMs > slowMs) ? ' warn' : '')
        + (hot.has(r.key) ? ' hot' : ''),
      // A run's row opens and folds it in place; a span's opens the span.
      onRowClick: (r) => (r.run ? flip(expandedRuns, r.run.key) : openSpan(r.span)),
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
    const all = span.attributes || {};
    // The statement and the captured stack are read, not scanned: each gets a section of its own,
    // the statement before the other attributes and the stack after them (pages.adoc#span-drawer).
    const statements = STATEMENT_KEYS.filter((k) => all[k] != null);
    const stack = all[STACK_KEY] != null ? String(all[STACK_KEY]) : null;
    const attrs = Object.entries(all)
      .filter(([k]) => !statements.includes(k) && k !== STACK_KEY)
      .sort((a, b) => a[0].localeCompare(b[0]));
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
      statements.map((k) => h('div',
        h('div.sub-head.mono', { style: { marginBottom: '6px' } }, k),
        copyBlock(formatSql(String(all[k]))))),
      attrs.length ? h('div',
        h('div.sub-head', { style: { marginBottom: '6px' } }, 'Attributes'),
        h('dl.kv', attrs.map(([k, v]) => [h('dt', k), h('dd', attrValue(v))]))) : null,
      stack !== null ? stackSection(stack) : null,
      (span.events || []).length ? h('div',
        h('div.sub-head', { style: { marginBottom: '6px' } }, 'Events'),
        h('div', { style: { display: 'grid', gap: '10px' } }, (span.events || []).map((ev) => eventBlock(ev, t0)))) : null,
    ];
  }

  function attrValue(value) {
    if (Array.isArray(value)) return h('pre', value.join('\n'));
    const text = String(value);
    if (text.length > 120 || text.includes('\n')) return h('pre', text);
    return document.createTextNode(text);
  }

  /**
   * `code.stacktrace`, the stack the extension captured where the span ended: its application
   * frames as code frames, then the stack with its framework runs folded.
   */
  function stackSection(text) {
    const code = appFrames(text);
    return h('div', { style: { display: 'grid', gap: '6px' } },
      h('div.sub-head.mono', STACK_KEY),
      code.length ? h('div.f-code', code.map((frame) => codeFrame(frame))) : null,
      foldedStack(text, 'app'));
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
    // Every service's records, over a range that still holds the trace.
    logsLink.href = router.href('/logs', {
      ...api.sharedQuery(), service: '', range: rangeHolding(t0), traceId: data.traceId || traceId,
    });
    fill(logsBox, table([
      { key: 'offset', label: 'Offset', align: 'right', sortable: false, width: '80px', render: (l) => h('span.mono.muted', offset(l.at - t0)) },
      { key: 'severity', label: 'Level', sortable: false, width: '68px', render: (l) => severityChip(l.severity) },
      { key: 'logger', label: 'Logger', sortable: false, width: '180px', render: (l) => h('span.cell-ellipsis.mono.muted', { title: l.logger }, l.logger || '-') },
      { key: 'body', label: 'Message', sortable: false, cls: 'wide', render: (l) => h('span.log-body', l.body) },
    ], {
      rows: logs,
      rowKey: (l) => l.id,
      // A row opens to its attributes and the exception it carries, as on the Logs page.
      detail: detailOf,
      detailClass: 'log-detail',
      expanded: openLogs,
      empty: 'No log carries this trace id.',
    }));
  }

  /** What a late export changes: the spans, the extent, the services and the logs. */
  function shapeOf(d) {
    return [(d.spans || []).length, d.start, d.durationMs, (d.services || []).join(','), (d.logs || []).length].join('|');
  }

  /**
   * `{ live: true }` is a Live refresh: a trace's spans arrive in several exports and from several
   * services, so one opened early is incomplete. The refetch repaints only when the trace
   * changed, keeping the collapsed spans, the selected span, its open drawer and the focus.
   */
  function paint(next, { live = false } = {}) {
    const nextShape = shapeOf(next);
    if (live && layout.built && nextShape === lastShape) return;
    lastShape = nextShape;
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
        const open = drawerBody();
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
    onError: (e, { live = false } = {}) => !(live && layout.built),
  });

  loader.load();
  return {
    refresh: () => { if (api.state.live) loader.load({ live: true }); },
    onEscape: () => closeDrawer(),
    destroy: () => { loader.destroy(); closeDrawerSilently(); },
  };
}

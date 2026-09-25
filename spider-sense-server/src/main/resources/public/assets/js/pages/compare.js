// Compare: two windows side by side, [before, after) and [after, until), named by
// marks (marks-and-compare.adoc#compare). The agent's compare, for people.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, serviceChip, markDialog, copyBlock, spinner, emptyState } from '../ui.js';
import { pageLoader } from '../page.js';
import { oneLineSql } from '../sql.js';
import { fmtApdex } from '../buckets.js';
import { errorTypeColumn, messageColumn, serviceColumn } from '../columns.js';
import { count, dur, rate, time } from '../format.js';

const VERDICTS = ['worse', 'new', 'same', 'better', 'gone'];

/** The verdict chip: the word first, so the colour is never alone. */
export function verdictChip(verdict) {
  const v = VERDICTS.includes(verdict) ? verdict : 'same';
  return chip(v, { class: 'verdict verdict-' + v });
}

/** `before → after`, with `—` for the side that has no data. */
function beforeAfterCell(before, after, format) {
  const fmt = (v) => (v === null || v === undefined ? '—' : format(v));
  return h('span.ba',
    h('span.ba-before', fmt(before)),
    h('span.ba-arrow', '→'),
    h('span.ba-after', fmt(after)));
}

/** One measure of a row's two sides, `before → after`. */
function sidesColumn(key, label, format) {
  const sideValue = (side) => (side ? side[key] : null);
  return { key, label, align: 'right', render: (r) => beforeAfterCell(sideValue(r.before), sideValue(r.after), format) };
}

/** More than a fifth bigger and bigger by at least 10 ms: Compare.java's bounds for a p95. */
const RELATIVE = 0.2;
const ABSOLUTE_MS = 10;
/** An Apdex that moved less than this reads the same at the two decimals a tile shows. */
const APDEX_STEP = 0.005;

/** `to` grew past both bounds over `from`, as Compare.grew decides it. */
function grew(from, to) {
  return to > from * (1 + RELATIVE) && to - from >= ABSOLUTE_MS;
}

/** The API's own rule (marks-and-compare.adoc#verdicts), as pages.adoc#compare repeats it for the tiles. */
export function verdictOf(kind, before, after) {
  if (before == null || after == null) return 'same';
  if (kind === 'errors') return after > before ? 'worse' : after < before ? 'better' : 'same';
  if (kind === 'p95') {
    if (grew(before, after)) return 'worse';
    if (grew(after, before)) return 'better';
    return 'same';
  }
  if (kind === 'apdex') {
    if (after < before - APDEX_STEP) return 'worse';
    if (after > before + APDEX_STEP) return 'better';
    return 'same';
  }
  return 'same';
}

function tile(caption, before, after, format, verdict) {
  const fmt = (v) => (v === null || v === undefined ? '—' : format(v));
  return h('div.stat.cmp-tile', { class: 'stat cmp-tile is-' + (verdict || 'same') },
    h('div.cmp-tile-value',
      h('span.cmp-before', fmt(before)),
      h('span.cmp-arrow', '→'),
      h('span.cmp-after', fmt(after))),
    h('div.stat-caption', caption));
}

/** `name · HH:mm:ss · service`, and the epoch when two marks share a name. */
function markOptions(marks) {
  const seen = new Map();
  for (const m of marks) seen.set(m.name, (seen.get(m.name) || 0) + 1);
  return marks.map((m) => ({
    mark: m,
    value: seen.get(m.name) > 1 ? String(m.at) : m.name,
    label: [m.name, time(m.at), m.service].filter(Boolean).join(' · '),
  }));
}

export function render(root, ctx) {
  let data = null;
  let built = false;
  let pending = false;

  const beforeSelect = h('select', { 'aria-label': 'Before' });
  const afterSelect = h('select', { 'aria-label': 'After' });
  const untilSelect = h('select', { 'aria-label': 'Until' });
  const compareBtn = h('button.btn.btn-primary', { type: 'button', onclick: () => apply() }, 'Compare');

  const bar = h('div.querybar.cmp-bar',
    h('label', h('span', 'Before'), beforeSelect),
    h('label', h('span', 'After'), afterSelect),
    h('label', h('span', 'Until'), untilSelect),
    compareBtn);

  const tiles = h('div.stat-row.cmp-tiles');
  const body = h('div', spinner());
  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } },
    panel({ class: 'cmp-panel' }, bar), tiles, body);
  root.appendChild(page);

  function query() { return router.currentRoute().query || {}; }

  function apply() {
    router.setQuery({
      before: beforeSelect.value,
      after: afterSelect.value,
      until: untilSelect.value === 'now' ? '' : untilSelect.value,
    });
    load();
  }

  /** The three selects, and the two newest marks when the hash named none. */
  function paintSelects() {
    const options = markOptions(api.state.marks || []);
    const q = query();
    const known = new Set(options.map((o) => o.value));
    const put = (node, chosen, extra) => {
      fill(node, extra, options.map((o) => h('option', { value: o.value }, o.label)));
      // a selector from the hash that no mark carries is still shown, as it was typed
      if (chosen && !known.has(chosen) && chosen !== 'now') node.appendChild(h('option', { value: chosen }, chosen));
      node.value = chosen || '';
    };
    const before = q.before || (options[1] ? options[1].value : '');
    const after = q.after || (options[0] ? options[0].value : '');
    const until = q.until || 'now';
    put(beforeSelect, before, null);
    put(afterSelect, after, null);
    put(untilSelect, until, h('option', { value: 'now' }, 'now'));
    const enough = !!(before && after);
    compareBtn.disabled = !enough;
    // the two newest marks are a choice like any other: it belongs in the URL
    if (enough && (!q.before || !q.after)) {
      pending = true;
      router.setQuery({ before, after, until: until === 'now' ? '' : until });
      pending = false;
    }
    return { before, after, until: until === 'now' ? '' : until, enough };
  }

  function needMarks() {
    tiles.hidden = true;
    built = false;
    fill(body, panel({}, emptyState(
      'Compare needs two marks: one before the change and one after it. Mark a moment, exercise the application, change the code, mark again.',
      h('div', { style: { display: 'grid', gap: '10px', justifyItems: 'center', width: 'min(640px, 100%)' } },
        h('button.btn.btn-primary', { type: 'button', onclick: () => markDialog({ onDone: () => loadMarks() }) }, 'Mark this moment'),
        copyBlock('java -jar spider-sense.jar mark before')))));
  }

  // --- the tables ---------------------------------------------------------

  const endpointColumns = [
    { key: 'verdict', label: '', width: '78px', render: (r) => verdictChip(r.verdict) },
    {
      key: 'name', label: 'Endpoint', cls: 'wide',
      render: (r) => h('span.row', { style: { gap: '8px' } },
        h('span.cell-ellipsis', { title: r.name }, r.name), serviceChip(r.service)),
    },
    sidesColumn('calls', 'Calls', count),
    sidesColumn('errors', 'Errors', count),
    sidesColumn('p50Ms', 'p50', dur),
    sidesColumn('p95Ms', 'p95', dur),
    sidesColumn('maxMs', 'max', dur),
    sidesColumn('dbCallsPerRequest', 'db calls / req', rate),
    sidesColumn('dbMsPerRequest', 'db ms / req', dur),
  ];

  const queryColumns = [
    { key: 'verdict', label: '', width: '78px', render: (r) => verdictChip(r.verdict) },
    {
      key: 'statement', label: 'Statement', cls: 'wide',
      render: (r) => h('span.row', { style: { gap: '8px' } },
        h('span.cell-ellipsis.mono', { title: r.statement }, oneLineSql(r.statement || '')), serviceChip(r.service)),
    },
    sidesColumn('calls', 'Calls', count),
    sidesColumn('callsPerRequest', 'Calls / req', rate),
    sidesColumn('p95Ms', 'p95', dur),
    sidesColumn('totalMs', 'Total', dur),
  ];

  const errorColumns = [
    { key: 'verdict', label: '', width: '78px', render: (r) => verdictChip(r.verdict) },
    errorTypeColumn({ width: '260px' }),
    messageColumn(160),
    serviceColumn(),
    { key: 'count', label: 'Count', align: 'right', width: '120px', render: (r) => beforeAfterCell(r.before, r.after, count) },
  ];

  const nodes = {};

  function paintTable(key, title, columns, rows, opts) {
    if (!nodes[key]) {
      const node = table(columns, { ...opts, rows });
      nodes[key] = { table: node, panel: panel({ title }, node) };
    } else {
      nodes[key].table.setRows(rows);
    }
    return nodes[key].panel;
  }

  function paint() {
    const totals = (data && data.totals) || {};
    const before = totals.before || {};
    const after = totals.after || {};
    tiles.hidden = false;
    fill(tiles,
      tile('requests', before.requests, after.requests, count, 'same'),
      tile('errors', before.errors, after.errors, count, verdictOf('errors', before.errors, after.errors)),
      tile('p95', before.p95Ms, after.p95Ms, dur, verdictOf('p95', before.p95Ms, after.p95Ms)),
      tile('apdex', before.apdex, after.apdex, fmtApdex, verdictOf('apdex', before.apdex, after.apdex)));

    const endpoints = paintTable('endpoints', 'Endpoints', endpointColumns, (data && data.endpoints) || [], {
      rowKey: (r) => r.endpointId,
      onRowClick: (r) => router.openDetail('endpoints', r.endpointId),
      empty: 'No endpoint in either window.',
    });
    const queries = paintTable('queries', 'Queries', queryColumns, (data && data.queries) || [], {
      rowKey: (r) => r.queryId,
      onRowClick: (r) => router.openDetail('queries', r.queryId),
      empty: 'No query in either window.',
    });
    const errors = paintTable('errors', 'Errors', errorColumns, (data && data.errors) || [], {
      rowKey: (r) => r.errorId,
      onRowClick: (r) => router.openDetail('errors', r.errorId),
      empty: 'No error in either window.',
    });
    if (!built) {
      built = true;
      fill(body, h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, endpoints, queries, errors));
    }
  }

  // Newest wins: a slow answer to the selectors before the last change never paints over it.
  const loader = pageLoader({
    fetch: (chosen) => api.compare({ before: chosen.before, after: chosen.after, until: chosen.until || undefined }),
    paint: (res) => { data = res; paint(); },
    body,
    retry: () => load(),
    onError: () => {
      delete nodes.endpoints; delete nodes.queries; delete nodes.errors;
      built = false;
      tiles.hidden = true;
    },
  });

  function load() {
    if (pending) return;
    const chosen = paintSelects();
    if (!chosen.enough) { needMarks(); return; }
    loader.load(chosen);
  }

  for (const select of [beforeSelect, afterSelect, untilSelect]) {
    select.addEventListener('change', () => apply());
  }

  // The page reads the marks from the shared state, which the shell fetches on a route
  // change and a Live tick only: the page fetches them itself when it opens (they may
  // still be in flight) and after its own Mark button made one.
  function loadMarks() {
    return api.marks(50).then((res) => {
      if (loader.isDestroyed()) return;
      api.state.marks = res.marks || api.state.marks;
      load();
    }).catch(() => { if (!loader.isDestroyed()) load(); });
  }

  loadMarks();

  return {
    refresh: () => { if (!loader.isDestroyed()) load(); },
    destroy: loader.destroy,
  };
}

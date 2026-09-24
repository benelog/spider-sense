// Compare: two windows side by side, [before, after) and [after, until), named by
// marks (marks-and-compare.adoc#compare). The agent's compare, for people.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, fillRows, chip, serviceChip, markDialog, copyBlock, spinner, errorBox, emptyState } from '../ui.js';
import { oneLineSql } from '../sql.js';
import { fmtApdex } from '../buckets.js';
import { count, dur, rate, time, truncate, splitType } from '../format.js';

const VERDICTS = ['worse', 'new', 'same', 'better', 'gone'];

/** The verdict chip: the word first, so the colour is never alone. */
export function verdictChip(verdict) {
  const v = VERDICTS.includes(verdict) ? verdict : 'same';
  return chip(v, { class: 'verdict verdict-' + v });
}

/** `before → after`, with `—` for the side that has no data. */
function cell(before, after, format) {
  const fmt = (v) => (v === null || v === undefined ? '—' : format(v));
  return h('span.ba',
    h('span.ba-before', fmt(before)),
    h('span.ba-arrow', '→'),
    h('span.ba-after', fmt(after)));
}

function side(row, key) {
  const s = row && row[key];
  return s === null || s === undefined ? null : s;
}

function value(s, key) {
  return s ? s[key] : null;
}

/** The API's own rule, as pages.adoc#compare repeats it for the tiles. */
function verdictOf(kind, before, after) {
  if (before == null || after == null) return 'same';
  if (kind === 'errors') return after > before ? 'worse' : after < before ? 'better' : 'same';
  if (kind === 'p95') {
    if (after > before * 1.2 && after - before > 10) return 'worse';
    if (before > after * 1.2 && before - after > 10) return 'better';
    return 'same';
  }
  if (kind === 'apdex') {
    if (after < before - 0.005) return 'worse';
    if (after > before + 0.005) return 'better';
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
  let destroyed = false;
  let data = null;
  let lastKey = '';
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
        h('button.btn.btn-primary', { type: 'button', onclick: () => markDialog({ onDone: () => load() }) }, 'Mark this moment'),
        copyBlock('java -jar spider-sense.jar mark before')))));
  }

  // --- the tables ---------------------------------------------------------

  const endpointColumns = [
    { key: 'verdict', label: '', sortable: false, width: '78px', render: (r) => verdictChip(r.verdict) },
    {
      key: 'name', label: 'Endpoint', sortable: false, cls: 'wide',
      render: (r) => h('span.row', { style: { gap: '8px' } },
        h('span.cell-ellipsis', { title: r.name }, r.name), serviceChip(r.service)),
    },
    { key: 'calls', label: 'Calls', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'calls'), value(side(r, 'after'), 'calls'), count) },
    { key: 'errors', label: 'Errors', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'errors'), value(side(r, 'after'), 'errors'), count) },
    { key: 'p50Ms', label: 'p50', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'p50Ms'), value(side(r, 'after'), 'p50Ms'), dur) },
    { key: 'p95Ms', label: 'p95', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'p95Ms'), value(side(r, 'after'), 'p95Ms'), dur) },
    { key: 'maxMs', label: 'max', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'maxMs'), value(side(r, 'after'), 'maxMs'), dur) },
    { key: 'dbCallsPerRequest', label: 'db calls / req', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'dbCallsPerRequest'), value(side(r, 'after'), 'dbCallsPerRequest'), rate) },
    { key: 'dbMsPerRequest', label: 'db ms / req', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'dbMsPerRequest'), value(side(r, 'after'), 'dbMsPerRequest'), dur) },
  ];

  const queryColumns = [
    { key: 'verdict', label: '', sortable: false, width: '78px', render: (r) => verdictChip(r.verdict) },
    {
      key: 'statement', label: 'Statement', sortable: false, cls: 'wide',
      render: (r) => h('span.row', { style: { gap: '8px' } },
        h('span.cell-ellipsis.mono', { title: r.statement }, oneLineSql(r.statement || '')), serviceChip(r.service)),
    },
    { key: 'calls', label: 'Calls', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'calls'), value(side(r, 'after'), 'calls'), count) },
    { key: 'callsPerRequest', label: 'Calls / req', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'callsPerRequest'), value(side(r, 'after'), 'callsPerRequest'), rate) },
    { key: 'p95Ms', label: 'p95', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'p95Ms'), value(side(r, 'after'), 'p95Ms'), dur) },
    { key: 'totalMs', label: 'Total', align: 'right', sortable: false, render: (r) => cell(value(side(r, 'before'), 'totalMs'), value(side(r, 'after'), 'totalMs'), dur) },
  ];

  const errorColumns = [
    { key: 'verdict', label: '', sortable: false, width: '78px', render: (r) => verdictChip(r.verdict) },
    {
      key: 'type', label: 'Type', sortable: false, width: '260px',
      render: (r) => {
        const { pkg, name } = splitType(r.type);
        return h('span.mono.cell-ellipsis', { title: r.type }, h('span.muted', pkg), name);
      },
    },
    { key: 'message', label: 'Message', sortable: false, cls: 'wide', render: (r) => h('span.cell-ellipsis', { title: r.message }, truncate(r.message || '', 160)) },
    { key: 'service', label: 'Service', sortable: false, width: '150px', render: (r) => serviceChip(r.service) },
    { key: 'count', label: 'Count', align: 'right', sortable: false, width: '120px', render: (r) => cell(r.before, r.after, count) },
  ];

  const nodes = {};

  function paintTable(key, title, columns, rows, opts) {
    if (!nodes[key]) {
      const node = table(columns, { ...opts, rows });
      nodes[key] = { table: node, panel: panel({ title }, node) };
    } else {
      fillRows(nodes[key].table, rows, opts);
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
      onRowClick: (r) => router.go('/endpoints/' + encodeURIComponent(r.endpointId), api.sharedQuery()),
      empty: 'No endpoint in either window.',
    });
    const queries = paintTable('queries', 'Queries', queryColumns, (data && data.queries) || [], {
      rowKey: (r) => r.queryId,
      onRowClick: (r) => router.go('/queries/' + encodeURIComponent(r.queryId), api.sharedQuery()),
      empty: 'No query in either window.',
    });
    const errors = paintTable('errors', 'Errors', errorColumns, (data && data.errors) || [], {
      rowKey: (r) => r.errorId,
      onRowClick: (r) => router.go('/errors/' + encodeURIComponent(r.errorId), api.sharedQuery()),
      empty: 'No error in either window.',
    });
    if (!built) {
      built = true;
      fill(body, h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, endpoints, queries, errors));
    }
  }

  async function load() {
    if (pending) return;
    const chosen = paintSelects();
    if (!chosen.enough) { needMarks(); return; }
    const key = [chosen.before, chosen.after, chosen.until, api.state.service].join('|');
    lastKey = key;
    try {
      const res = await api.compare({ before: chosen.before, after: chosen.after, until: chosen.until || undefined });
      if (destroyed || lastKey !== key) return;
      data = res;
      paint();
    } catch (e) {
      if (destroyed || lastKey !== key) return;
      delete nodes.endpoints; delete nodes.queries; delete nodes.errors;
      built = false;
      tiles.hidden = true;
      fill(body, errorBox(e, load));
    }
  }

  for (const select of [beforeSelect, afterSelect, untilSelect]) {
    select.addEventListener('change', () => apply());
  }

  // The marks may still be in flight when the page opens; they arrive with the shell.
  api.marks(50).then((res) => {
    if (destroyed) return;
    api.state.marks = res.marks || api.state.marks;
    load();
  }).catch(() => { if (!destroyed) load(); });

  return {
    refresh: () => { if (!destroyed) load(); },
    destroy: () => { destroyed = true; },
  };
}

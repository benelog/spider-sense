// Findings: what is worth fixing in this window, the agent's first answer (docs/agent.md),
// as a table whose rows open into the evidence behind them.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, serviceChip, renderList, copyBlock, spinner, errorBox, emptyState, snippetBlocks, dialog, toast, breakdownBar, breakdownLead } from '../ui.js';
import { formatSql } from '../sql.js';
import { fmtApdex } from '../buckets.js';
import { count, dur, rate, pct, bytes, time, bothTimes, truncate, shortId } from '../format.js';
import { codeFrame } from '../frames.js';

const KIND_LABEL = {
  regression: 'regression',
  error: 'error',
  'log-error': 'log error',
  'n-plus-one': 'n+1',
  'n-plus-one-http': 'n+1 http',
  'slow-query': 'slow query',
  'slow-endpoint': 'slow endpoint',
  'slow-job': 'slow job',
  'slow-external': 'slow external',
  'pool-exhausted': 'pool',
  'gc-pause': 'gc pause',
  'heap-pressure': 'heap',
  'thread-growth': 'threads',
};

/** The dot and the word, so the severity is not carried by colour alone. */
export function severityMark(severity) {
  const s = severity || 'low';
  return h('span.sev-mark', severityDot(s), h('span.sev-word', s));
}

/**
 * Whether a finding is listed last and dimmed: acknowledged, or resolved and not
 * back since (docs/agent.md).
 */
export function setAside(finding) {
  return !!finding.ack || (!!finding.resolution && finding.kind !== 'regression');
}

/**
 * The severity cell of a row: `acked` in place of the word for an acknowledged
 * finding, `resolved` for a resolved one that has not come back, with the note
 * as its title (docs/ui.md).
 */
function severityCell(finding) {
  if (finding.ack) {
    return h('span.sev-mark', { title: finding.ack.note || finding.severity + ' severity, acknowledged' },
      severityDot(finding.severity), h('span.sev-word', 'acked'));
  }
  if (setAside(finding)) {
    return h('span.sev-mark', { title: finding.resolution.note || finding.severity + ' severity, resolved' },
      severityDot(finding.severity), h('span.sev-word', 'resolved'));
  }
  return severityMark(finding.severity);
}

const STATE_TITLE = {
  new: 'not in the run before the last restart of its service',
  ongoing: 'also in the run before the last restart of its service',
  regressed: 'resolved, and back since',
};

/** `new`, `ongoing` or `regressed`, as a chip (docs/ui.md). */
export function stateChip(state) {
  if (!state) return null;
  return chip(state, { class: 'chip-state state-' + state, title: STATE_TITLE[state] || state });
}

export function severityDot(severity) {
  const s = severity || 'low';
  return h('span.sev-dot', { class: 'sev-dot sev-' + s, title: s + ' severity' });
}

export function kindChip(kind) {
  return chip(KIND_LABEL[kind] || kind, { class: 'chip-kind', title: kind });
}

/**
 * Where a finding points: its subject's page, and the first evidence trace when the
 * subject has no page of its own (a job) or names nothing (docs/ui.md).
 */
export function findingTarget(finding) {
  const subject = finding.subject || {};
  const shared = api.sharedQuery();
  if (subject.endpointId) return { path: '/endpoints/' + encodeURIComponent(subject.endpointId), query: shared };
  if (subject.queryId) return { path: '/queries/' + encodeURIComponent(subject.queryId), query: shared };
  if (subject.errorId) return { path: '/errors/' + encodeURIComponent(subject.errorId), query: shared };
  if (subject.pool || subject.jvm) return { path: '/jvm', query: { ...shared, service: finding.service || shared.service } };
  if (subject.logger) {
    return {
      path: '/logs',
      query: { ...shared, service: finding.service || shared.service, severity: 'ERROR', q: subject.logger },
    };
  }
  const trace = (finding.traces || [])[0];
  if (trace) return { path: '/traces/' + encodeURIComponent(trace), query: shared };
  return null;
}

export function goToFinding(finding) {
  const target = findingTarget(finding);
  if (target) router.go(target.path, target.query);
}

/** The number the kind is ranked by, as docs/ui.md spells the column out. */
export function impactOf(finding) {
  const n = finding.numbers || {};
  // A regression is ranked by the number its original kind is ranked by.
  if (finding.kind === 'regression' && n.originalKind) return impactOf({ ...finding, kind: n.originalKind });
  switch (finding.kind) {
    case 'error': return h('span.bad', count(n.count));
    case 'log-error': return h('span.bad', count(n.count));
    case 'n-plus-one':
    case 'n-plus-one-http': return h('span', count(n.medianRepeats) + ' × ' + count(n.affected));
    case 'pool-exhausted': return h('span', count(n.pendingMax));
    case 'gc-pause': return h('span', dur(n.worstMs));
    case 'heap-pressure': return h('span', pct(n.ratioMax));
    case 'thread-growth': return h('span', '+' + count((n.last || 0) - (n.first || 0)));
    default: return h('span', dur(n.totalMs));
  }
}

const NUMBER_LABEL = {
  p50Ms: 'p50', p95Ms: 'p95', maxMs: 'max', totalMs: 'total', msPerRequest: 'ms per request',
  dbCallsPerRequest: 'db calls / request', dbMsPerRequest: 'db ms / request',
  dbCallsPerRun: 'db calls / run', dbMsPerRun: 'db ms / run', dbShare: 'db share',
  slowCalls: 'slow calls', medianRepeats: 'median repeats', maxRepeats: 'max repeats',
  hotSpans: 'hot spans', breakdown: 'breakdown',
  firstSeen: 'first seen', lastSeen: 'last seen', usedMax: 'used max', pendingMax: 'pending max',
  at: 'worst at', worstMs: 'longest', shareMax: 'worst share', ratioMax: 'worst ratio',
  gc: 'collector', hotSpan: 'hot span',
  resolvedAt: 'resolved at', originalKind: 'was',
};

/**
 * The numbers whose unit the key alone does not say: `usedMax` is connections on a
 * `pool-exhausted` and bytes on a `heap-pressure`, so the kind decides.
 */
const BYTE_NUMBERS = { 'heap-pressure': new Set(['usedMax', 'limit']) };

function numberLabel(key) {
  return NUMBER_LABEL[key] || key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
}

/** One value of `numbers`, formatted as the same value is formatted everywhere else. */
function numberValue(key, value, kind) {
  if (value === null || value === undefined) return h('span.muted', '-');
  if (key === 'hotSpan') return hotSpanLine(value);
  if (key === 'hotSpans') return hotSpanList(value);
  if (key === 'breakdown') return breakdownLine(value);
  if (Array.isArray(value)) {
    if (!value.length) return h('span.muted', 'none');
    return h('ul.f-list', value.slice(0, 5).map((item) => h('li',
      h('span.f-list-name', item.endpoint || item.name || String(item)),
      (item.calls != null || item.count != null)
        ? h('span.f-list-count', count(item.calls != null ? item.calls : item.count))
        : null)));
  }
  if (typeof value === 'string') return h('span.mono', truncate(value, 200));
  if (key === 'at' || key === 'firstSeen' || key === 'lastSeen' || key === 'resolvedAt') {
    return h('span', { title: bothTimes(value) }, time(value));
  }
  if (key === 'apdex') return h('span', fmtApdex(value));
  if (key === 'dbShare' || key === 'shareMax' || key === 'ratioMax') return h('span', pct(value));
  if ((BYTE_NUMBERS[kind] || new Set()).has(key)) return h('span', bytes(value));
  if (key.endsWith('Ms') || key === 'msPerRequest') return h('span', dur(value));
  if (key.endsWith('PerRequest') || key.endsWith('PerRun') || key === 'max') return h('span', rate(value));
  return h('span', count(value));
}

/** `SELECT order_line · 312.4 ms self · 62.0%`: where the time went (docs/agent.md). */
function hotSpanLine(hot) {
  if (!hot || typeof hot !== 'object') return h('span.muted', '-');
  return h('span',
    h('span.mono', truncate(String(hot.name || ''), 120)),
    h('span.muted', ' · ' + dur(hot.selfMs) + ' self · ' + pct(hot.share)));
}

/**
 * The schema block under the statement (docs/agent.md, "The schema block"), as the
 * text rendering has it: one line per table, then one line for the columns.
 *
 * <p>Nothing is computed here. The tables, the predicates and the unindexed columns
 * are the ones the API carries, so the page and the CLI say the same thing; a
 * finding whose block is null shows nothing.
 */
export function schemaLines(schema) {
  if (!schema) return null;
  const predicates = schema.predicates || [];
  const unindexed = schema.unindexed || [];
  return h('div.f-schema.mono',
    (schema.tables || []).map((t) => h('div.f-schema-line',
      h('span.muted', 'indexes ' + t.table + ': '),
      (t.indexes || []).length
        ? (t.indexes || []).map((index, i) => h('span',
          i ? ', ' : null,
          (index.name || '') + ' (' + (index.columns || []).join(', ') + ')',
          index.unique ? h('span.muted', ' unique') : null))
        : h('span.muted', 'none'))),
    h('div.f-schema-line',
      h('span.muted', 'predicates: '),
      predicates.length
        ? [predicates.join(', '),
          h('span.muted', '; unindexed: '),
          unindexed.length ? h('span.accent', unindexed.join(', ')) : h('span.muted', 'none')]
        : h('span.muted', 'none')));
}

/**
 * The three summaries the time went to, over the finding's sample (docs/agent.md,
 * "Where the time went"): the name, then its own time, share and count.
 */
function hotSpanList(spans) {
  if (!Array.isArray(spans) || !spans.length) return h('span.muted', 'none');
  return h('ul.f-hot', spans.map((hot) => h('li',
    h('span.f-hot-name', { title: String(hot.name || '') }, String(hot.name || '')),
    h('span.f-hot-num', dur(hot.selfMs) + ' · ' + pct(hot.share) + ' · ×' + count(hot.count)))));
}

/** The four shares as the bar the endpoint page draws, with the numbers beside it. */
function breakdownLine(breakdown) {
  const bar = breakdownBar(breakdown);
  if (!bar) return h('span.muted', 'none');
  return h('div.f-breakdown', bar,
    h('span.muted', ['db', 'http', 'internal', 'self']
      .map((bucket) => bucket + ' ' + pct(breakdown[bucket] || 0)).join(' · ')));
}

/**
 * The Acknowledge dialog (docs/ui.md): one optional note, then the POST.
 *
 * <p>Small on purpose — an acknowledgement is a sentence about why a finding is
 * accepted, and the finding itself is on the screen behind it.
 */
function ackDialog(finding, onDone) {
  const noteInput = h('input', {
    type: 'text', placeholder: 'optional', autocomplete: 'off',
    'aria-label': 'Note', style: { width: '100%' },
  });
  const problem = h('div.form-error', { role: 'alert' });
  problem.hidden = true;

  const ok = h('button.btn.btn-primary', { type: 'button' }, 'Acknowledge');
  const dlg = dialog({
    title: 'Acknowledge this finding',
    body: h('div.mark-form',
      h('p.muted', finding.title || finding.id),
      h('label', h('span', 'Note'), noteInput),
      h('p.muted', 'It stays in the list, ranked after everything else, until it is withdrawn.'),
      problem),
    actions: [h('button.btn', { type: 'button', onclick: () => dlg.close() }, 'Cancel'), ok],
  });

  async function submit() {
    ok.disabled = true;
    try {
      await api.ackFinding(finding.id, noteInput.value.trim());
      dlg.close();
      toast('Acknowledged ' + finding.id);
      if (onDone) onDone();
    } catch (e) {
      ok.disabled = false;
      problem.hidden = false;
      problem.textContent = String(e && e.message ? e.message : e);
    }
  }

  ok.addEventListener('click', submit);
  noteInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  requestAnimationFrame(() => noteInput.focus());
  return dlg;
}

/**
 * The Resolve dialog (docs/ui.md): one optional note, then the POST; the finding
 * is reported as a regression if it comes back.
 */
function resolveDialog(finding, onDone) {
  const noteInput = h('input', {
    type: 'text', placeholder: 'optional, such as what the fix was', autocomplete: 'off',
    'aria-label': 'Note', style: { width: '100%' },
  });
  const problem = h('div.form-error', { role: 'alert' });
  problem.hidden = true;

  const ok = h('button.btn.btn-primary', { type: 'button' }, 'Resolve');
  const dlg = dialog({
    title: 'Resolve this finding',
    body: h('div.mark-form',
      h('p.muted', finding.title || finding.id),
      h('label', h('span', 'Note'), noteInput),
      h('p.muted', 'If it occurs again it is reported as a regression, ranked above everything else.'),
      problem),
    actions: [h('button.btn', { type: 'button', onclick: () => dlg.close() }, 'Cancel'), ok],
  });

  async function submit() {
    ok.disabled = true;
    try {
      await api.resolveFinding(finding.id, noteInput.value.trim());
      dlg.close();
      toast('Resolved ' + finding.id);
      if (onDone) onDone();
    } catch (e) {
      ok.disabled = false;
      problem.hidden = false;
      problem.textContent = String(e && e.message ? e.message : e);
    }
  }

  ok.addEventListener('click', submit);
  noteInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  requestAnimationFrame(() => noteInput.focus());
  return dlg;
}

/** A button that withdraws something and reloads the page. */
function withdrawButton(label, done, call, onChange) {
  const button = h('button.btn.btn-ghost', {
    type: 'button',
    onclick: async () => {
      button.disabled = true;
      try {
        await call();
        toast(done);
        if (onChange) onChange();
      } catch (e) {
        button.disabled = false;
        toast(String(e && e.message ? e.message : e));
      }
    },
  }, label);
  return button;
}

/**
 * The last line of the evidence: acknowledge or resolve it, or the note of what
 * was decided and a way back.
 */
function ackLine(finding, onChange) {
  if (finding.resolution) {
    return h('div.f-ack',
      h('span.muted', (finding.kind === 'regression' ? 'Resolved ' : 'Resolved, not back since ')
        + time(finding.resolution.at)),
      finding.resolution.note ? h('span.f-ack-note', finding.resolution.note) : null,
      withdrawButton('Reopen', 'Reopened ' + finding.id, () => api.unresolveFinding(finding.id), onChange));
  }
  if (finding.ack) {
    return h('div.f-ack',
      h('span.muted', 'Acknowledged ' + time(finding.ack.at)),
      finding.ack.note ? h('span.f-ack-note', finding.ack.note) : null,
      withdrawButton('Unacknowledge', 'Unacknowledged ' + finding.id, () => api.unackFinding(finding.id), onChange));
  }
  return h('div.f-ack',
    h('button.btn.btn-ghost', {
      type: 'button', onclick: () => resolveDialog(finding, onChange),
    }, 'Resolve'),
    h('button.btn.btn-ghost', {
      type: 'button', onclick: () => ackDialog(finding, onChange),
    }, 'Acknowledge'));
}

/**
 * The expanded row: why, the numbers, the statement, the code and the evidence.
 *
 * @param onChange called after an acknowledgement or a resolution changed, so the page reloads;
 *        with none, the evidence carries no buttons
 */
export function evidence(finding, onChange) {
  // A hot span the finding has no trace for is left out, as the text rendering leaves it out.
  const numbers = Object.entries(finding.numbers || {})
    .filter(([key, value]) => {
      if (key === 'hotSpan') return !!value;
      // An empty sample says nothing, as a finding without a trace says nothing.
      if (key === 'hotSpans') return Array.isArray(value) && value.length > 0;
      if (key === 'breakdown') return !!breakdownLead(value);
      return true;
    });
  const traces = finding.traces || [];
  const target = findingTarget(finding);
  return h('div.f-evidence',
    finding.why ? h('p.f-why', finding.why) : null,
    numbers.length
      ? h('dl.f-numbers', numbers.map(([key, value]) => h('div', {
        // Two of them are lists rather than values, so they take the whole row.
        class: key === 'hotSpans' || key === 'breakdown' ? 'f-wide' : null,
      },
        h('dt', numberLabel(key)),
        h('dd', numberValue(key, value, finding.kind === 'regression' ? (finding.numbers || {}).originalKind : finding.kind)))))
      : null,
    finding.statement ? copyBlock(formatSql(finding.statement)) : null,
    schemaLines(finding.schema),
    (finding.code || []).length
      ? h('div.f-code', h('div.sub-head', 'Code'),
        (finding.code || []).map((frame) => codeFrame(frame)))
      : null,
    h('div.f-links',
      traces.length
        ? h('span.row', { style: { gap: '6px' } }, h('span.muted', 'Traces'),
          traces.map((id) => h('a.mono', {
            href: router.href('/traces/' + id, api.sharedQuery()),
            title: id,
          }, shortId(id, 12))))
        : null,
      target
        ? h('a.btn.btn-ghost', { href: router.href(target.path, target.query) }, 'Go to')
        : null),
    onChange ? ackLine(finding, onChange) : null);
}

/** "in the last 15 min", so an empty state says which window it is empty in. */
export function windowName() {
  const range = api.rangeOf(api.state.range);
  return range.ms == null ? 'in all the data' : 'in the ' + range.label.toLowerCase();
}

export function render(root, ctx) {
  let destroyed = false;
  let rows = [];
  let requests = 0;
  let node = null;
  const expanded = new Set();

  const body = h('div', spinner());
  root.appendChild(panel({ title: 'Findings' }, body));

  const columns = [
    { key: 'n', label: '#', sortable: false, width: '36px', render: (f, i) => h('span.muted', String(i + 1)) },
    { key: 'severity', label: 'Severity', sortable: false, width: '96px', render: (f) => severityCell(f) },
    { key: 'state', label: 'State', sortable: false, width: '96px', render: (f) => stateChip(f.state) },
    { key: 'kind', label: 'Kind', sortable: false, width: '112px', render: (f) => kindChip(f.kind) },
    { key: 'service', label: 'Service', sortable: false, width: '150px', render: (f) => serviceChip(f.service) },
    { key: 'title', label: 'Title', sortable: false, cls: 'wide', render: (f) => h('span.cell-ellipsis', { title: f.title }, f.title) },
    { key: 'impact', label: 'Impact', align: 'right', sortable: false, width: '110px', render: (f) => impactOf(f) },
  ];

  function toggle(finding) {
    if (expanded.has(finding.id)) expanded.delete(finding.id);
    else expanded.add(finding.id);
    paint();
  }

  /** Each finding, followed by its evidence row while it is open. */
  function withEvidence() {
    const out = [];
    rows.forEach((f, i) => {
      out.push({ finding: f, index: i, key: f.id });
      if (expanded.has(f.id)) out.push({ finding: f, key: 'evidence:' + f.id, evidence: true });
    });
    return out;
  }

  /** What has to change before an open evidence row is rebuilt. */
  function ackSignature(finding) {
    const decided = finding.ack || finding.resolution;
    return decided ? (finding.ack ? 'a' : 'r') + decided.at + '|' + (decided.note || '') : '';
  }

  function buildRow(finding, index) {
    const tr = h('tr.clickable', {
      tabindex: 0,
      class: setAside(finding) ? 'clickable is-acked' : 'clickable',
      'aria-expanded': String(expanded.has(finding.id)),
      onclick: (e) => { if (!e.target.closest('a, button')) toggle(finding); },
      onkeydown: (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(finding); } },
    });
    for (const col of columns) {
      const td = h('td', { class: [col.align === 'right' ? 'right' : null, col.cls].filter(Boolean).join(' ') || null });
      const v = col.render(finding, index);
      if (v) td.appendChild(v);
      tr.appendChild(td);
    }
    return tr;
  }

  function paint() {
    const list = withEvidence();
    if (!node) {
      node = table(columns, { rows: [] });
      fill(body, node);
    }
    for (const row of Array.from(node.tbody.querySelectorAll('.empty-row'))) row.remove();
    renderList(node.tbody, list, {
      key: (item) => item.key,
      create: (item) => (item.evidence
        ? h('tr.f-detail', { dataset: { ack: ackSignature(item.finding) } },
          h('td', { colspan: columns.length }, evidence(item.finding, load)))
        : buildRow(item.finding, item.index)),
      update: (n, item) => {
        // An open evidence row is left alone by a Live refresh, unless its
        // acknowledgement changed: that is what the button in it just did.
        if (item.evidence) {
          const signature = ackSignature(item.finding);
          if (n.dataset.ack !== signature) {
            n.dataset.ack = signature;
            n.replaceChildren(h('td', { colspan: columns.length }, evidence(item.finding, load)));
          }
          return;
        }
        const fresh = buildRow(item.finding, item.index);
        n.className = fresh.className;
        n.setAttribute('aria-expanded', fresh.getAttribute('aria-expanded'));
        n.replaceChildren(...fresh.childNodes);
      },
    });
    if (!list.length) {
      fill(node.tbody, h('tr.empty-row', h('td', { colspan: columns.length },
        h('span.muted', 'Nothing worth fixing ' + windowName() + '.'))));
    }
  }

  async function load() {
    try {
      const res = await api.findings({ limit: 100 });
      if (destroyed) return;
      rows = res.findings || [];
      requests = res.requests || 0;
      if (!rows.length && !requests) {
        node = null;
        const s = api.state.status || {};
        fill(body, emptyState(
          ((s.counts || {}).spans
            ? 'No request ' + windowName() + ', so there is nothing to judge. Send some traffic, or widen the range in the top bar.'
            : 'Nothing has arrived yet. Attach Spider Sense to an application, or point any OTLP/HTTP sender at this collector.'),
          snippetBlocks(s.endpoint || location.origin)));
        return;
      }
      paint();
    } catch (e) {
      if (!destroyed) { node = null; fill(body, errorBox(e, load)); }
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; } };
}

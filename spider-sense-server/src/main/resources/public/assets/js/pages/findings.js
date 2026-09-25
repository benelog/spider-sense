// Findings: what is worth fixing in this window, the agent's first answer (findings.adoc),
// as a table whose rows open into the evidence behind them.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, serviceChip, copyBlock, spinner, noDataYet, formDialog, errorText, toast, breakdownBar, breakdownLead, BREAKDOWN_BUCKETS } from '../ui.js';
import { formatSql } from '../sql.js';
import { fmtApdex } from '../buckets.js';
import { count, dur, rate, pct, bytes, time, bothTimes, truncate, shortId } from '../format.js';
import { codeFrame } from '../frames.js';
import { copyButtons, cliLine } from '../copyas.js';
import { pageLoader } from '../page.js';
import { severityDot, kindChip, findingTarget, schemaLines } from '../widgets.js';

/** The dot and the word, so the severity is not carried by colour alone. */
export function severityMark(severity) {
  const s = severity || 'low';
  return h('span.sev-mark', severityDot(s), h('span.sev-word', s));
}

/**
 * Whether a finding is listed last and dimmed: acknowledged, or resolved and not
 * back since (findings.adoc#resolutions).
 */
export function setAside(finding) {
  return !!finding.ack || (!!finding.resolution && finding.kind !== 'regression');
}

/**
 * The severity cell of a row: `acked` in place of the word for an acknowledged
 * finding, `resolved` for a resolved one that has not come back, with the note
 * as its title (pages.adoc#findings).
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

/** `new`, `ongoing` or `regressed`, as a chip (pages.adoc#findings). */
export function stateChip(state) {
  if (!state) return null;
  return chip(state, { class: 'chip-state state-' + state, title: STATE_TITLE[state] || state });
}

/** The number the kind is ranked by, as pages.adoc#findings spells the column out. */
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
  // A duration ends in Ms, or in Ms per request or run (msPerRequest, dbMsPerRequest, dbMsPerRun).
  if (/(^ms|Ms)(PerRequest|PerRun)?$/.test(key)) return h('span', dur(value));
  if (key.endsWith('PerRequest') || key.endsWith('PerRun')) return h('span', rate(value));
  // max (a pool's connections, a JVM's threads) is a count like the rest (pages.adoc#findings).
  return h('span', count(value));
}

/** `SELECT order_line · 312.4 ms self · 62.0%`: where the time went (findings.adoc#time). */
function hotSpanLine(hot) {
  if (!hot || typeof hot !== 'object') return h('span.muted', '-');
  return h('span',
    h('span.mono', truncate(String(hot.name || ''), 120)),
    h('span.muted', ' · ' + dur(hot.selfMs) + ' self · ' + pct(hot.share)));
}

/**
 * The three summaries the time went to, over the finding's sample
 * (findings.adoc#time): the name, then its own time, share and count.
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
    h('span.muted', BREAKDOWN_BUCKETS
      .map((bucket) => bucket + ' ' + pct(breakdown[bucket] || 0)).join(' · ')));
}

/**
 * The Acknowledge and Resolve dialogs (ui.adoc#dialogs): one optional note, then the POST.
 *
 * <p>Small on purpose — the note is a sentence about why a finding is accepted or what the fix
 * was, and the finding itself is on the screen behind it.
 */
const DECISIONS = {
  ack: {
    title: 'Acknowledge this finding',
    outro: 'It stays in the list, ranked after everything else, until it is withdrawn.',
    placeholder: 'optional',
    submitLabel: 'Acknowledge',
    call: api.ackFinding,
    done: 'Acknowledged ',
  },
  resolve: {
    title: 'Resolve this finding',
    outro: 'If it occurs again it is reported as a regression, ranked above everything else.',
    placeholder: 'optional, such as what the fix was',
    submitLabel: 'Resolve',
    call: api.resolveFinding,
    done: 'Resolved ',
  },
};

function decisionDialog(kind, finding, onDone) {
  const d = DECISIONS[kind];
  return formDialog({
    title: d.title,
    intro: finding.title || finding.id,
    fields: [{ name: 'note', label: 'Note', placeholder: d.placeholder }],
    outro: d.outro,
    submitLabel: d.submitLabel,
    submit: ({ note }) => d.call(finding.id, note),
    done: () => {
      toast(d.done + finding.id);
      if (onDone) onDone();
    },
  });
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
        toast(errorText(e));
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
      type: 'button', onclick: () => decisionDialog('resolve', finding, onChange),
    }, 'Resolve'),
    h('button.btn.btn-ghost', {
      type: 'button', onclick: () => decisionDialog('ack', finding, onChange),
    }, 'Acknowledge'));
}

/**
 * The expanded row: why, the numbers, the statement, the code and the evidence.
 *
 * @param onChange called after an acknowledgement or a resolution changed, so the page reloads;
 *        with none, the evidence carries no buttons
 * @param listWindow returns the window the list was last asked for ({ from, to }), which the
 *        Copy as Markdown and Copy CLI line buttons name; with none, they are left out
 */
export function evidence(finding, onChange, listWindow) {
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
            href: router.detailHref('traces', id),
            title: id,
          }, shortId(id, 12))))
        : null,
      target
        ? h('a.btn.btn-ghost', { href: router.href(target.path, target.query) }, 'Go to')
        : null,
      listWindow ? copyButtons({
        markdown: () => ({ path: '/api/findings/' + encodeURIComponent(finding.id), query: api.params({}, { window: listWindow() }) }),
        cli: () => cliLine('findings', listWindow(), api.state.service),
      }) : null),
    onChange ? ackLine(finding, onChange) : null);
}

/** "in the last 15 min", so an empty state says which window it is empty in. */
export function windowName() {
  const range = api.rangeOf(api.state.range);
  return range.ms == null ? 'in all the data' : 'in the ' + range.label.toLowerCase();
}

export function render(root, ctx) {
  let rows = [];
  let listWindow = null;
  let node = null;
  // The open rows, kept across a table rebuilt after an error or an empty window.
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

  /** What has to change before an open evidence row is rebuilt: a Live refresh leaves it alone otherwise. */
  function ackSignature(finding) {
    const decided = finding.ack || finding.resolution;
    return decided ? (finding.ack ? 'a' : 'r') + decided.at + '|' + (decided.note || '') : '';
  }

  function paint() {
    if (!node) {
      node = table(columns, {
        rowKey: (f) => f.id,
        rowClass: (f) => (setAside(f) ? 'is-acked' : null),
        detail: (f) => evidence(f, loader.load, () => listWindow),
        detailKey: ackSignature,
        detailClass: 'f-detail',
        expanded,
        empty: () => 'Nothing worth fixing ' + windowName() + '.',
      });
      fill(body, node);
    }
    node.setRows(rows);
  }

  const loader = pageLoader({
    fetch: () => api.findings({ limit: 100 }),
    paint: (res) => {
      rows = res.findings || [];
      listWindow = res.window || api.windowFor();
      if (!rows.length && !res.requests) {
        node = null;
        fill(body, noDataYet(((api.state.status || {}).counts || {}).spans
          ? 'No request ' + windowName() + ', so there is nothing to judge. Send some traffic, or widen the range in the top bar.'
          : 'Nothing has arrived yet. Attach Spider Sense to an application, or point any OTLP/HTTP sender at this collector.'));
        return;
      }
      paint();
    },
    body,
    onError: () => { node = null; },
  });

  loader.load();
  return { refresh: loader.load, destroy: loader.destroy };
}

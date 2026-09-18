// Findings: what is worth fixing in this window, the agent's first answer (docs/agent.md),
// as a table whose rows open into the evidence behind them.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, chip, serviceChip, renderList, copyBlock, spinner, errorBox, emptyState, snippetBlocks } from '../ui.js';
import { formatSql } from '../sql.js';
import { fmtApdex } from '../buckets.js';
import { count, dur, rate, pct, time, bothTimes, truncate, shortId } from '../format.js';

const KIND_LABEL = {
  error: 'error',
  'n-plus-one': 'n+1',
  'slow-query': 'slow query',
  'slow-endpoint': 'slow endpoint',
  'slow-job': 'slow job',
  'pool-exhausted': 'pool',
};

/** The dot and the word, so the severity is not carried by colour alone. */
export function severityMark(severity) {
  const s = severity || 'low';
  return h('span.sev-mark', severityDot(s), h('span.sev-word', s));
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
  if (subject.pool) return { path: '/jvm', query: { ...shared, service: finding.service || shared.service } };
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
  switch (finding.kind) {
    case 'error': return h('span.bad', count(n.count));
    case 'n-plus-one': return h('span', count(n.medianRepeats) + ' × ' + count(n.affected));
    case 'pool-exhausted': return h('span', count(n.pendingMax));
    default: return h('span', dur(n.totalMs));
  }
}

const NUMBER_LABEL = {
  p50Ms: 'p50', p95Ms: 'p95', maxMs: 'max', totalMs: 'total', msPerRequest: 'ms per request',
  dbCallsPerRequest: 'db calls / request', dbMsPerRequest: 'db ms / request',
  dbCallsPerRun: 'db calls / run', dbMsPerRun: 'db ms / run', dbShare: 'db share',
  slowCalls: 'slow calls', medianRepeats: 'median repeats', maxRepeats: 'max repeats',
  firstSeen: 'first seen', lastSeen: 'last seen', usedMax: 'used max', pendingMax: 'pending max',
  at: 'worst at',
};

function numberLabel(key) {
  return NUMBER_LABEL[key] || key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
}

/** One value of `numbers`, formatted as the same value is formatted everywhere else. */
function numberValue(key, value) {
  if (value === null || value === undefined) return h('span.muted', '-');
  if (Array.isArray(value)) {
    if (!value.length) return h('span.muted', 'none');
    return h('ul.f-list', value.slice(0, 5).map((item) => h('li',
      h('span.f-list-name', item.endpoint || item.name || String(item)),
      (item.calls != null || item.count != null)
        ? h('span.f-list-count', count(item.calls != null ? item.calls : item.count))
        : null)));
  }
  if (typeof value === 'string') return h('span.mono', truncate(value, 200));
  if (key === 'at' || key === 'firstSeen' || key === 'lastSeen') {
    return h('span', { title: bothTimes(value) }, time(value));
  }
  if (key === 'apdex') return h('span', fmtApdex(value));
  if (key === 'dbShare') return h('span', pct(value));
  if (key.endsWith('Ms') || key === 'msPerRequest') return h('span', dur(value));
  if (key.endsWith('PerRequest') || key.endsWith('PerRun') || key === 'max') return h('span', rate(value));
  return h('span', count(value));
}

/** The expanded row: why, the numbers, the statement, the code and the evidence. */
export function evidence(finding) {
  const numbers = Object.entries(finding.numbers || {});
  const traces = finding.traces || [];
  const target = findingTarget(finding);
  return h('div.f-evidence',
    finding.why ? h('p.f-why', finding.why) : null,
    numbers.length
      ? h('dl.f-numbers', numbers.map(([key, value]) => h('div',
        h('dt', numberLabel(key)),
        h('dd', numberValue(key, value)))))
      : null,
    finding.statement ? copyBlock(formatSql(finding.statement)) : null,
    (finding.code || []).length
      ? h('div.f-code', h('div.sub-head', 'Code'),
        (finding.code || []).map((frame) => h('div.mono.f-frame', frame)))
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
        : null));
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
    { key: 'severity', label: 'Severity', sortable: false, width: '96px', render: (f) => severityMark(f.severity) },
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

  function buildRow(finding, index) {
    const tr = h('tr.clickable', {
      tabindex: 0,
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
        ? h('tr.f-detail', h('td', { colspan: columns.length }, evidence(item.finding)))
        : buildRow(item.finding, item.index)),
      update: (n, item) => {
        if (item.evidence) return;
        const fresh = buildRow(item.finding, item.index);
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

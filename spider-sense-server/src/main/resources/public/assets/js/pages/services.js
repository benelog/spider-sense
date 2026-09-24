// Services list: one sortable table.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, table, fillRows, chip, serviceColor, comparator, spinner, errorBox, emptyState, snippetBlocks, seedServices } from '../ui.js';
import { sparkline } from '../charts.js';
import { apdexClass, fmtApdex } from '../buckets.js';
import { dur, count, rate, pct, rel, bothTimes } from '../format.js';

export function render(root, ctx) {
  let destroyed = false;
  const latest = api.requestSequence();
  let rows = [];
  let sort = { key: ctx.query.sort || 'requests', dir: ctx.query.dir === 'asc' ? 'asc' : 'desc' };
  let node = null;

  const body = h('div', spinner());
  root.appendChild(panel({ title: 'Services' }, body));

  const columns = [
    {
      key: 'name', label: 'Service', cls: 'wide',
      render: (s) => h('div.row', { style: { gap: '8px' } },
        h('span.dot', { style: { background: serviceColor(s.name), width: '8px', height: '8px', borderRadius: '50%', flex: 'none' } }),
        h('b', s.name),
        s.embedded ? chip('embedded', { class: 'chip-accent' }) : null),
    },
    { key: 'language', label: 'Language', width: '92px', render: (s) => (s.language ? chip(s.language) : h('span.muted', 'unknown')) },
    { key: 'requests', label: 'Requests', align: 'right', width: '84px', render: (s) => count(s.requests) },
    { key: 'rps', label: 'rps', align: 'right', width: '64px', render: (s) => rate(s.rps || 0) },
    { key: 'errorRate', label: 'Errors', align: 'right', width: '72px', render: (s) => h('span', { class: s.errorRate > 0.01 ? 'bad' : '' }, pct(s.errorRate || 0)) },
    { key: 'apdex', label: 'Apdex', align: 'right', width: '70px', render: (s) => h('span', { class: apdexClass(s.apdex) === 'is-bad' ? 'bad' : apdexClass(s.apdex) === 'is-warn' ? 'warned' : '' }, fmtApdex(s.apdex)) },
    { key: 'p50Ms', label: 'p50', align: 'right', width: '78px', render: (s) => dur(s.p50Ms) },
    { key: 'p95Ms', label: 'p95', align: 'right', width: '78px', render: (s) => dur(s.p95Ms) },
    { key: 'p99Ms', label: 'p99', align: 'right', width: '78px', render: (s) => dur(s.p99Ms) },
    { key: 'spark', label: 'Requests over time', sortable: false, width: '130px', render: (s) => sparkline(s.sparkline || [], { color: serviceColor(s.name), label: s.name + ' requests per bucket' }) },
    { key: 'lastSeen', label: 'Last seen', align: 'right', width: '92px', render: (s) => h('span', { title: bothTimes(s.lastSeen) }, rel(s.lastSeen)) },
  ];

  const opts = {
    rowKey: (s) => s.name,
    onRowClick: (s) => router.go('/services/' + encodeURIComponent(s.name), api.sharedQuery()),
    empty: 'No service has reported in this window.',
  };

  function sorted() {
    return rows.slice().sort(comparator(sort));
  }

  function onSort(key) {
    sort = { key, dir: sort.key === key && sort.dir === 'desc' ? 'asc' : 'desc' };
    router.setQuery({ sort: key, dir: sort.dir });
    node = null;
    paint();
  }

  function paint() {
    if (!node) {
      node = table(columns, { ...opts, rows: sorted(), sort, onSort });
      fill(body, node);
    } else {
      fillRows(node, sorted(), opts);
    }
  }

  async function load() {
    const current = latest();
    try {
      const res = await api.services();
      if (destroyed || !current()) return;
      rows = res.services || [];
      seedServices(rows.map((s) => s.name));
      if (!rows.length) {
        node = null;
        fill(body, emptyState('No service has sent anything yet.', snippetBlocks((api.state.status || {}).endpoint || location.origin)));
        return;
      }
      paint();
    } catch (e) {
      if (!destroyed && current()) { node = null; fill(body, errorBox(e, load)); }
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; } };
}

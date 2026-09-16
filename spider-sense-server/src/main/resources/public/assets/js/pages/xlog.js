// XLog: the Scouter scatter. Every request is a dot; drag a rectangle to list those traces.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, spinner, errorBox, serviceColor, seedServices, emptyState, snippetBlocks } from '../ui.js';
import { xlogScatter, legend } from '../charts.js';
import { traceTable } from './traces.js';
import { count, dur, clock } from '../format.js';

export function render(root, ctx) {
  let destroyed = false;
  let points = [];
  let chart = null;
  let selection = null;
  let liveTimer = null;
  let truncated = false;
  const hidden = new Set();

  const logToggle = h('button.btn', {
    type: 'button', 'aria-pressed': String(ctx.query.log === '1'),
    onclick: () => {
      const on = logToggle.getAttribute('aria-pressed') !== 'true';
      logToggle.setAttribute('aria-pressed', String(on));
      router.setQuery({ log: on ? '1' : '' });
      if (chart) chart.setLogScale(on);
    },
  }, 'Log scale');

  const legendBox = h('div');
  const counts = h('div.xlog-counts');
  const clipNote = h('span.clip-note');
  const selBox = h('span.xlog-sel');
  selBox.hidden = true;
  const bar = h('div.xlog-bar', legendBox, logToggle, clipNote, counts);
  const chartBody = h('div.chart', { style: { minHeight: '380px' } }, spinner());
  const chartPanel = panel({
    title: 'Response time over time',
    actions: selBox,
  }, bar, chartBody);

  const listBody = h('div');
  const listTitle = h('h2.panel-title', 'Traces in this window');
  const listPanel = h('section.panel',
    h('div.panel-head', listTitle, h('div.panel-actions', h('span.muted', { style: { fontSize: '11px' } }, 'Drag a rectangle on the chart to filter'))),
    listBody);

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, chartPanel, listPanel);
  root.appendChild(page);

  let listNode = null;

  function stats() {
    const visible = points.filter((p) => !hidden.has(p[2]));
    return {
      total: visible.length,
      errors: visible.filter((p) => p[5] & 1).length,
      slow: visible.filter((p) => p[5] & 2).length,
    };
  }

  function yMaxOf() {
    const ds = points.filter((p) => !hidden.has(p[2])).map((p) => p[1]).sort((a, b) => a - b);
    if (!ds.length) return 100;
    const p99 = ds[Math.min(ds.length - 1, Math.floor(ds.length * 0.99))];
    return Math.max(10, p99 * 1.5);
  }

  function paintBar() {
    const services = [...new Set(points.map((p) => p[2]))].sort();
    seedServices(services);
    fill(legendBox, legend(services.map((s) => ({
      label: s, color: serviceColor(s), off: hidden.has(s),
      value: count(points.filter((p) => p[2] === s).length),
    })), {
      onToggle: (item) => {
        if (hidden.has(item.label)) hidden.delete(item.label); else hidden.add(item.label);
        paintBar();
        if (chart) { chart.setHidden(hidden); chart.setYMax(yMaxOf()); }
      },
    }));
    const s = stats();
    fill(counts,
      h('span', h('b', count(s.total)), ' points'),
      h('span', h('b', { class: s.errors ? 'bad' : '' }, count(s.errors)), ' errors'),
      h('span', h('b', { class: s.slow ? 'warned' : '' }, count(s.slow)), ' slow'));
    const max = yMaxOf();
    const above = points.filter((p) => !hidden.has(p[2]) && p[1] > max).length;
    clipNote.textContent = above ? '▲ ' + count(above) + ' above ' + dur(max) : '';
    clipNote.title = above ? 'Points above the axis maximum; switch to log scale to see them.' : '';
    if (truncated) {
      clipNote.textContent = (clipNote.textContent ? clipNote.textContent + ' · ' : '') + 'list truncated, shorten the range';
    }
  }

  function paintSelection() {
    if (!selection) {
      selBox.hidden = true;
      listTitle.textContent = 'Traces in this window';
      return;
    }
    selBox.hidden = false;
    fill(selBox,
      h('span', clock(selection.from) + ' – ' + clock(selection.to) + ', ' +
        dur(selection.minMs) + ' – ' + dur(selection.maxMs)),
      h('button.btn.btn-ghost', { type: 'button', onclick: clearSelection }, 'Clear'));
    listTitle.textContent = 'Traces in the selection';
  }

  function clearSelection() {
    selection = null;
    if (chart) chart.clearSelect();
    paintSelection();
    loadTraces();
  }

  async function loadTraces() {
    try {
      const extra = { limit: 50 };
      let opts;
      if (selection) {
        extra.minMs = Math.round(selection.minMs);
        extra.maxMs = Math.round(selection.maxMs);
        opts = { window: { from: selection.from, to: selection.to } };
      }
      const res = await api.traces(extra, opts);
      if (destroyed) return;
      const rows = res.traces || [];
      if (!listNode) {
        listNode = traceTable(rows, { empty: 'No trace in this selection.' });
        fill(listBody, listNode);
      } else {
        listNode.setRows(rows);
      }
    } catch (e) {
      if (!destroyed) { listNode = null; fill(listBody, errorBox(e, loadTraces)); }
    }
  }

  function makeChart(window) {
    if (chart) { chart.destroy(); chart = null; }
    fill(chartBody);
    chart = xlogScatter(chartBody, {
      height: Math.max(320, Math.round(innerHeight * 0.42)),
      points,
      window,
      hidden,
      logScale: logToggle.getAttribute('aria-pressed') === 'true',
      yMax: yMaxOf(),
      onSelect: (rect) => {
        selection = rect;
        paintSelection();
        loadTraces();
      },
      onPick: (p) => router.go('/traces/' + p[4], api.sharedQuery()),
    });
  }

  async function load(incremental) {
    try {
      const now = Date.now();
      const opts = incremental ? { window: { from: now - 10000, to: now } } : undefined;
      const res = await api.xlog({ limit: 5000 }, opts);
      if (destroyed) return;
      truncated = !!res.truncated;
      const incoming = res.points || [];
      if (incremental) {
        const seen = new Set(points.map((p) => p[4]));
        const fresh = incoming.filter((p) => !seen.has(p[4]));
        const w = api.windowFor();
        points = points.concat(fresh).filter((p) => p[0] >= w.from);
        paintBar();
        if (chart) chart.setPoints(points, w);
      } else {
        points = incoming;
        const w = (res.window && res.window.from) ? res.window : api.windowFor();
        if (!points.length && !(api.state.status && api.state.status.counts && api.state.status.counts.spans)) {
          fill(chartBody, emptyState('No request has been recorded yet.', snippetBlocks((api.state.status || {}).endpoint || location.origin)));
          fill(counts);
          return;
        }
        paintBar();
        makeChart(w);
      }
    } catch (e) {
      if (!destroyed) fill(chartBody, errorBox(e, () => load()));
    }
  }

  function startLive() {
    clearInterval(liveTimer);
    liveTimer = null;
    if (!api.state.live) return;
    liveTimer = setInterval(() => load(true), 2000);
  }

  load().then(() => { if (!destroyed) loadTraces(); });
  startLive();

  return {
    refresh: () => {
      startLive();
      if (api.state.live) return;     // the 2 s timer owns the refresh while Live is on
      load();
      loadTraces();
    },
    onEscape: () => { if (selection) clearSelection(); },
    destroy: () => {
      destroyed = true;
      clearInterval(liveTimer);
      if (chart) chart.destroy();
    },
  };
}

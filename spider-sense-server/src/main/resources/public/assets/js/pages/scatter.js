// Scatter: every request is a point on time × response time, as dots or as a
// heatmap. Drag a rectangle to list those traces. pages.adoc#scatter.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, spinner, errorBox, serviceColor, seedServices, emptyState, snippetBlocks } from '../ui.js';
import { scatterChart, legend } from '../charts.js';
import { traceTable } from './traces.js';
import { count, dur, clock } from '../format.js';

export function render(root, ctx) {
  let destroyed = false;
  const latest = api.requestSequence();
  const latestTraces = api.requestSequence();
  let points = [];
  let chart = null;
  let selection = null;
  let liveTimer = null;
  let truncated = false;
  const hidden = new Set();
  /** The service and range the points were last loaded for in full; Live only merges within them. */
  let loadedFor = null;
  const scope = () => api.state.service + '|' + api.state.range;

  ctx.setTitle('Response time scatter');

  let mode = ctx.query.mode === 'heatmap' ? 'heatmap' : 'dots';
  let showOk = ctx.query.hide !== 'ok';
  let showErr = ctx.query.hide !== 'err';

  const logToggle = h('button.btn', {
    type: 'button', 'aria-pressed': String(ctx.query.log === '1'),
    onclick: () => {
      const on = logToggle.getAttribute('aria-pressed') !== 'true';
      logToggle.setAttribute('aria-pressed', String(on));
      router.setQuery({ log: on ? '1' : '' });
      if (chart) chart.setLogScale(on);
      paintBar();
    },
  }, 'Log scale');

  /** Success and Failed: both on by default, one off hides those points everywhere. */
  function statusButton(id, label) {
    const btn = h('button.btn', {
      type: 'button', 'aria-pressed': String(id === 'ok' ? showOk : showErr),
      onclick: () => {
        if (id === 'ok') showOk = !showOk; else showErr = !showErr;
        if (!showOk && !showErr) { if (id === 'ok') showOk = true; else showErr = true; }
        btn.setAttribute('aria-pressed', String(id === 'ok' ? showOk : showErr));
        okBtn.setAttribute('aria-pressed', String(showOk));
        errBtn.setAttribute('aria-pressed', String(showErr));
        router.setQuery({ hide: showOk && showErr ? '' : showOk ? 'err' : 'ok' });
        paintBar();
        if (chart) { chart.setPoints(shown()); chart.setYMax(yMaxOf()); }
        loadTraces();
      },
    }, label);
    return btn;
  }
  const okBtn = statusButton('ok', 'Success');
  const errBtn = statusButton('err', 'Failed');

  const modeBox = h('div.row', { style: { gap: '2px' }, role: 'group', 'aria-label': 'Chart mode' });
  function paintModeToggle() {
    const make = (id, label) => h('button.btn', {
      type: 'button', 'aria-pressed': String(mode === id),
      onclick: () => {
        if (mode === id) return;
        mode = id;
        router.setQuery({ mode: id === 'dots' ? '' : id });
        paintModeToggle();
        if (chart) chart.setMode(mode);
      },
    }, label);
    fill(modeBox, make('dots', 'Dots'), make('heatmap', 'Heatmap'));
  }
  paintModeToggle();

  const legendBox = h('div');
  const counts = h('div.scatter-counts');
  const clipNote = h('span.clip-note');
  const selBox = h('span.scatter-sel');
  selBox.hidden = true;
  const bar = h('div.scatter-bar', legendBox, okBtn, errBtn, logToggle, modeBox, clipNote, counts);
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

  /** The points the Success / Failed toggles leave; the chart hides services itself. */
  function shown() {
    return points.filter((p) => ((p[5] & 1) ? showErr : showOk));
  }

  function visible() {
    return shown().filter((p) => !hidden.has(p[2]));
  }

  function stats() {
    const list = visible();
    return {
      total: list.length,
      errors: list.filter((p) => p[5] & 1).length,
      slow: list.filter((p) => p[5] & 2).length,
    };
  }

  function yMaxOf() {
    const ds = visible().map((p) => p[1]).sort((a, b) => a - b);
    if (!ds.length) return 100;
    const p99 = ds[Math.min(ds.length - 1, Math.floor(ds.length * 0.99))];
    return Math.max(10, p99 * 1.5);
  }

  function paintBar() {
    const services = [...new Set(points.map((p) => p[2]))].sort();
    seedServices(services);
    fill(legendBox, legend(services.map((s) => ({
      label: s, color: serviceColor(s), off: hidden.has(s),
      value: count(shown().filter((p) => p[2] === s).length),
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
    // Only the linear axis clips; the log scale runs to the slowest point.
    const logOn = logToggle.getAttribute('aria-pressed') === 'true';
    const above = logOn ? 0 : visible().filter((p) => p[1] > max).length;
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
    const current = latestTraces();
    try {
      const extra = { limit: 50 };
      if (showOk !== showErr) extra.status = showErr ? 'error' : 'ok';
      let opts;
      if (selection) {
        extra.minMs = Math.round(selection.minMs);
        extra.maxMs = Math.round(selection.maxMs);
        opts = { window: { from: selection.from, to: selection.to } };
      }
      const res = await api.traces(extra, opts);
      if (destroyed || !current()) return;
      const rows = res.traces || [];
      if (!listNode) {
        listNode = traceTable(rows, { empty: 'No trace in this selection.' });
        fill(listBody, listNode);
      } else {
        listNode.setRows(rows);
      }
    } catch (e) {
      if (!destroyed && current()) { listNode = null; fill(listBody, errorBox(e, loadTraces)); }
    }
  }

  function makeChart(window) {
    if (chart) { chart.destroy(); chart = null; }
    fill(chartBody);
    chart = scatterChart(chartBody, {
      height: Math.max(320, Math.round(innerHeight * 0.42)),
      points: shown(),
      window,
      hidden,
      mode,
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
    const requested = scope();
    // A Live merge never supersedes a full load; it is dropped by scope instead (below).
    const current = incremental ? () => true : latest();
    try {
      const now = Date.now();
      const opts = incremental ? { window: { from: now - 10000, to: now } } : undefined;
      const res = await api.scatter({ limit: 5000 }, opts);
      if (destroyed || !current()) return;
      truncated = !!res.truncated;
      const incoming = res.points || [];
      if (incremental) {
        if (requested !== loadedFor) return;   // asked for a service or range no longer shown
        const seen = new Set(points.map((p) => p[4]));
        const fresh = incoming.filter((p) => !seen.has(p[4]));
        const w = api.windowFor();
        points = points.concat(fresh).filter((p) => p[0] >= w.from);
        paintBar();
        if (chart) chart.setPoints(shown(), w);
      } else {
        loadedFor = requested;
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
      if (!destroyed && current()) fill(chartBody, errorBox(e, () => load()));
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
      // The 2 s timer owns the refresh while Live is on, but it only merges the last 10 s of the
      // same service and range: a change of either reloads the whole window and its traces.
      if (api.state.live && loadedFor === scope()) return;
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

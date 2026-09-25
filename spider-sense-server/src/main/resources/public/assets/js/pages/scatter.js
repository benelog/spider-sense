// Scatter: every request is a point on time × response time, as dots or as a
// heatmap. Drag a rectangle to list those traces. pages.adoc#scatter.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, spinner, errorBox, serviceColor, seedServices, noDataYet, segmented } from '../ui.js';
import { pageLoader } from '../page.js';
import { scatterChart, legend } from '../charts.js';
import { traceTable } from '../widgets.js';
import { count, dur, clock } from '../format.js';

/** Under Live the scatter merges the last 10 s every 2 s (pages.adoc#scatter), faster than the page refresh. */
const LIVE_MERGE_PERIOD_MS = 2000;
const LIVE_MERGE_WINDOW_MS = 10000;

export function render(root, ctx) {
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

  let mode = router.queryParam(ctx.query, 'mode', ['dots', 'heatmap'], 'dots');
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
        if (chart) chart.setPoints(shown(), null, yMaxOf());
        loadTraces();
      },
    }, label);
    return btn;
  }
  const okBtn = statusButton('ok', 'Success');
  const errBtn = statusButton('err', 'Failed');

  const modeSwitch = segmented({
    label: 'Chart mode',
    options: [['dots', 'Dots'], ['heatmap', 'Heatmap']],
    value: mode,
    onChange: (id) => {
      mode = id;
      router.setQuery({ mode: id }, { defaults: { mode: 'dots' } });
      if (chart) chart.setMode(mode);
    },
  });

  const legendBox = h('div');
  const counts = h('div.scatter-counts');
  const clipNote = h('span.clip-note');
  const selBox = h('span.scatter-sel');
  selBox.hidden = true;
  const bar = h('div.scatter-bar', legendBox, okBtn, errBtn, logToggle, modeSwitch, clipNote, counts);
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

  /** The traces under the chart: the window's, or the selection's, filtered as the toggles are. */
  const traceList = pageLoader({
    fetch: () => {
      const extra = { limit: 50 };
      if (showOk !== showErr) extra.status = showErr ? 'error' : 'ok';
      let opts;
      if (selection) {
        extra.minMs = Math.round(selection.minMs);
        extra.maxMs = Math.round(selection.maxMs);
        opts = { window: { from: selection.from, to: selection.to } };
      }
      return api.traces(extra, opts);
    },
    paint: (res) => {
      const rows = res.traces || [];
      if (!listNode) {
        listNode = traceTable(rows, { empty: 'No trace in this selection.' });
        fill(listBody, listNode);
      } else {
        listNode.setRows(rows);
      }
    },
    body: listBody,
    onError: () => { listNode = null; },
  });
  const loadTraces = () => traceList.load();

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
      onPick: (p) => router.openDetail('traces', p[4]),
    });
  }

  /** The whole window's points, for the service and range the top bar shows. */
  const loader = pageLoader({
    fetch: async () => ({ requested: scope(), res: await api.scatter({ limit: 5000 }) }),
    paint: ({ requested, res }) => {
      loadedFor = requested;
      // Only a full load can be cut; the 10 s a Live merge asks for leaves the cut set as it was.
      truncated = !!res.truncated;
      points = res.points || [];
      const w = (res.window && res.window.from) ? res.window : api.windowFor();
      if (!points.length && !(api.state.status && api.state.status.counts && api.state.status.counts.spans)) {
        fill(chartBody, noDataYet('No request has been recorded yet.'));
        fill(counts);
        return;
      }
      paintBar();
      makeChart(w);
    },
    body: chartBody,
  });

  /**
   * The last 10 s merged into the points on screen, every 2 s under Live. A merge never
   * supersedes a full load; it is dropped when the service or the range changed meanwhile.
   */
  async function mergeRecent() {
    const requested = scope();
    try {
      const now = Date.now();
      const res = await api.scatter({ limit: 5000 }, { window: { from: now - LIVE_MERGE_WINDOW_MS, to: now } });
      if (loader.isDestroyed() || requested !== loadedFor) return;
      const seen = new Set(points.map((p) => p[4]));
      const fresh = (res.points || []).filter((p) => !seen.has(p[4]));
      const w = api.windowFor();
      points = points.concat(fresh).filter((p) => p[0] >= w.from);
      paintBar();
      // The axis follows the merged points, as the ▲ note paintBar just wrote does.
      if (chart) chart.setPoints(shown(), w, yMaxOf());
    } catch (e) {
      if (!loader.isDestroyed()) fill(chartBody, errorBox(e, () => loader.load()));
    }
  }

  function startLive() {
    clearInterval(liveTimer);
    liveTimer = null;
    if (!api.state.live) return;
    liveTimer = setInterval(mergeRecent, LIVE_MERGE_PERIOD_MS);
  }

  loader.load().then(() => { if (!loader.isDestroyed()) loadTraces(); });
  startLive();

  return {
    refresh: () => {
      startLive();
      // The 2 s timer owns the refresh while Live is on, but it only merges the last 10 s of the
      // same service and range: a change of either reloads the whole window and its traces.
      if (api.state.live && loadedFor === scope()) return;
      loader.load();
      loadTraces();
    },
    onEscape: () => { if (selection) clearSelection(); },
    destroy: () => {
      loader.destroy();
      traceList.destroy();
      clearInterval(liveTimer);
      if (chart) chart.destroy();
    },
  };
}

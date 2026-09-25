// uPlot helpers. Colours are read from CSS custom properties at render time and
// every chart rebuilds itself when the theme changes or its container resizes.

import * as fmt from './format.js';
import { state } from './api.js';
import { panel, readSeriesColors, serviceColor, svgElement } from './ui.js';

const uPlot = globalThis.uPlot;

const charts = new Set();

/** Colours for the current theme, read fresh from the stylesheet. */
export function themeColors() {
  const cs = getComputedStyle(document.documentElement);
  const v = (name, fallback) => (cs.getPropertyValue(name).trim() || fallback);
  return {
    text: v('--text', '#e6e9ef'),
    muted: v('--text-muted', '#8a93a6'),
    line: v('--line', '#343a47'),
    panel: v('--bg-panel', '#1f232c'),
    raised: v('--bg-raised', '#2b303b'),
    accent: v('--accent', '#e2603f'),
    ok: v('--ok', '#5ec27f'),
    warn: v('--warn', '#f0b64b'),
    err: v('--err', '#e5484d'),
    silk: v('--silk', '#8a93a6'),
    bucket1: v('--bucket-1', '#5ec27f'),
    bucket2: v('--bucket-2', '#35c0b6'),
    bucket3: v('--bucket-3', '#f0b64b'),
    bucket4: v('--bucket-4', '#e2603f'),
    series: readSeriesColors(),
  };
}

/** Called by app.js after the theme flips. */
export function retheme() {
  for (const c of charts) c.rebuild();
}

/** Called by app.js when the marks changed: the same data, one more line on it. */
export function redrawAll() {
  for (const c of charts) if (c.plot) c.plot.redraw();
}

/** Canvas fonts cannot use CSS variables, so the UI font is resolved once per build. */
function uiFont(px = 11) {
  const family = getComputedStyle(document.documentElement).getPropertyValue('--font-ui').trim();
  return `${px}px ${family || 'system-ui, sans-serif'}`;
}

function withAlpha(hex, alpha) {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/** Nice tick values for the time axis of a window. */
function timeAxis(colors, opts = {}) {
  return {
    scale: 'x',
    stroke: colors.muted,
    grid: { stroke: withAlpha(colors.line, 0.75), width: 1 },
    ticks: { stroke: withAlpha(colors.line, 0.75), width: 1, size: 4 },
    font: uiFont(),
    space: 70,
    values: (u, splits) => splits.map((s) => (opts.short ? fmt.clockShort(s * 1000) : fmt.clock(s * 1000))),
  };
}

/** Scales that count things; their axes and their range stay on whole numbers. */
const COUNT_INCRS = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000, 1000000];

function isCountScale(scale) {
  return scale !== 'ms' && scale !== 'pct' && scale !== 'load';
}

function valueAxis(colors, axisOpts = {}) {
  return {
    scale: axisOpts.scale || 'y',
    side: axisOpts.side === undefined ? 3 : axisOpts.side,
    stroke: axisOpts.stroke || colors.muted,
    grid: axisOpts.grid === false ? { show: false } : { stroke: withAlpha(colors.line, 0.75), width: 1 },
    ticks: { show: false },
    font: uiFont(),
    size: axisOpts.size || 52,
    label: axisOpts.label,
    labelSize: axisOpts.label ? 18 : 0,
    labelFont: uiFont(),
    labelGap: 2,
    incrs: axisOpts.count !== false && isCountScale(axisOpts.scale || 'y') ? COUNT_INCRS : undefined,
    values: axisOpts.values || ((u, splits) => splits.map((s) => fmt.count(s))),
  };
}

/**
 * A resizing, theme-aware chart.
 * build(width, height, colors) must return { opts, data }.
 */
class Chart {
  constructor(container, build, height) {
    this.container = container;
    this.build = build;
    this.height = height;
    this.plot = null;
    this.data = null;
    this.ro = new ResizeObserver(() => this.resize());
    this.ro.observe(container);
    charts.add(this);
    this.rebuild();
  }

  /** The container's content box: clientWidth still counts the padding. */
  width() {
    const cs = getComputedStyle(this.container);
    const pad = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
    return Math.max(120, (this.container.clientWidth || 600) - pad);
  }

  rebuild() {
    const colors = themeColors();
    const w = this.width();
    const built = this.build(w, this.height, colors, this);
    if (!built) return;
    if (this.plot) { this.plot.destroy(); this.plot = null; }
    this.container.replaceChildren();
    this.data = built.data;
    this.plot = new uPlot({ width: w, height: this.height, ...built.opts }, built.data, this.container);
    if (built.after) built.after(this.plot);
  }

  resize() {
    if (!this.plot) return;
    const w = this.width();
    if (Math.abs(w - this.plot.width) < 2) return;
    this.plot.setSize({ width: w, height: this.height });
  }

  destroy() {
    charts.delete(this);
    this.ro.disconnect();
    if (this.plot) this.plot.destroy();
    this.plot = null;
    this.container.replaceChildren();
  }
}

export { Chart };

function tooltipFor(container) {
  let tip = container.querySelector('.chart-tip');
  if (!tip) {
    tip = document.createElement('div');
    tip.className = 'chart-tip';
    container.appendChild(tip);
  }
  return tip;
}

/**
 * The marks of the shared state, drawn on every time series (ui.adoc#marks-on-charts): a dashed
 * vertical line the full height of the plot with the name beside it, muted for the
 * automatic `start` marks and accent for the ones a person or an agent made.
 * A mark that names a service belongs to that service only.
 */
function drawMarks(u, colors) {
  const list = (state.marks || []).filter((m) => m && m.at != null
    && (!m.service || !state.service || m.service === state.service));
  if (!list.length) return;
  const dpr = devicePixelRatio || 1;
  const ctx = u.ctx;
  const { left, top, width, height } = u.bbox;
  ctx.save();
  ctx.beginPath();
  ctx.rect(left, top, width, height);
  ctx.clip();
  ctx.lineWidth = Math.max(1, Math.round(dpr));
  ctx.font = uiFont(11 * dpr);
  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
  let labelEnd = -Infinity;
  for (const mark of list.slice().sort((a, b) => a.at - b.at)) {
    const x = Math.round(u.valToPos(mark.at / 1000, 'x', true)) + 0.5;
    if (x < left || x > left + width) continue;
    const color = mark.name === 'start' ? colors.muted : colors.accent;
    ctx.strokeStyle = color;
    ctx.setLineDash([4 * dpr, 3 * dpr]);
    ctx.beginPath();
    ctx.moveTo(x, top);
    ctx.lineTo(x, top + height);
    ctx.stroke();
    ctx.setLineDash([]);
    // two marks a few seconds apart still get two lines, but only one readable name
    if (x < labelEnd) continue;
    ctx.fillStyle = color;
    ctx.fillText(mark.name, x + 3 * dpr, top + 2 * dpr);
    labelEnd = x + 3 * dpr + ctx.measureText(mark.name).width + 4 * dpr;
  }
  ctx.restore();
}

/**
 * Series that carry their own `t` (api.adoc#metrics, a JVM memory pool) are sampled at
 * different instants, so a chart of several of them runs on the union of their timestamps.
 */
export function alignedTimes(series) {
  const all = new Set();
  for (const s of series) for (const x of s.t || []) all.add(x);
  return Array.from(all).sort((a, b) => a - b);
}

/** One array of a series (`v`, `p95`, `count`, `used`) on the shared axis, `null` where it has no point. */
export function alignTo(t, s, key) {
  const values = s[key] || [];
  const byTime = new Map();
  (s.t || []).forEach((x, i) => { byTime.set(x, values[i] == null ? null : values[i]); });
  return t.map((x) => (byTime.has(x) ? byTime.get(x) : null));
}

/**
 * A time series' data columns and paint order, from its spec series.
 * A series with `stack: <key>` sits on the previous series carrying the same key, so its
 * column holds the cumulative value.
 * uPlot paints series in order, so within a stack the tallest cumulative series goes first
 * and the ones below it paint over it: `order` lists the spec indexes in paint order,
 * `columns` their values in that order, and `column[i]` is spec series i's uPlot series index
 * (1-based, after the time column).
 * Bars take one slot per stack group and one per unstacked bar series: `slotOf` maps a bar
 * series' spec index to its slot, and `bars` counts the slots.
 */
export function stackColumns(specSeries) {
  const running = new Map();
  const values = specSeries.map((s) => {
    const raw = s.values || [];
    if (!s.stack) return raw.map((v) => (v == null ? null : v));
    const under = running.get(s.stack) || [];
    const out = raw.map((v, i) => (under[i] || 0) + (v == null ? 0 : v));
    running.set(s.stack, out);
    return out;
  });
  const order = specSeries.map((s, i) => i);
  const groups = new Map();
  specSeries.forEach((s, i) => {
    if (!s.stack) return;
    if (!groups.has(s.stack)) groups.set(s.stack, []);
    groups.get(s.stack).push(i);
  });
  for (const slots of groups.values()) {
    const reversed = slots.slice().reverse();
    slots.forEach((slot, k) => { order[slot] = reversed[k]; });
  }
  const column = [];
  order.forEach((specIndex, k) => { column[specIndex] = k + 1; });
  const slotKeys = [];
  const slotOf = new Map();
  specSeries.forEach((s, i) => {
    if (s.type !== 'bar') return;
    const key = s.stack ? 'stack:' + s.stack : 'bar:' + i;
    let k = slotKeys.indexOf(key);
    if (k < 0) { k = slotKeys.length; slotKeys.push(key); }
    slotOf.set(i, k);
  });
  return { columns: order.map((i) => values[i]), order, column, slotOf, bars: slotKeys.length };
}

/**
 * A time series.
 * spec = {
 *   height, t: [msEpoch], short,
 *   series: [{ label, values, color, type: 'bar'|'line'|'area', scale: 'y'|'ms'|'pct', width, fill, dash }],
 *   axes: [{ scale, side, label, values }],
 *   legend: true
 * }
 */
export function timeSeries(container, spec) {
  const chart = new Chart(container, (width, height, colors) => {
    const xs = (spec.t || []).map((ms) => ms / 1000);
    const { columns, order, column, slotOf, bars } = stackColumns(spec.series);
    const data = [xs, ...columns];
    const scales = { x: { time: true } };
    const series = [{ label: 'Time' }];
    for (const specIndex of order) {
      const s = spec.series[specIndex];
      const color = resolveColor(s.color, colors);
      const scale = s.scale || 'y';
      scales[scale] = scales[scale] || { range: rangeFor(scale) };
      if (s.type === 'bar') {
        const idx = slotOf.get(specIndex);
        series.push({
          // a stacked bar is opaque, so the segment under it does not tint it
          label: s.label, scale, stroke: color, fill: withAlpha(color, s.fillAlpha == null ? (s.stack ? 1 : 0.85) : s.fillAlpha),
          width: 0, points: { show: false },
          paths: uPlot.paths.bars({ size: [bars > 1 && idx > 0 ? 0.62 : 0.72, 24, 1], align: 0, radius: 0.15 }),
          value: (u, v) => fmt.count(v),
        });
      } else {
        series.push({
          label: s.label, scale, stroke: color, width: s.width || 2,
          dash: s.dash || null,
          fill: s.type === 'area' ? withAlpha(color, 0.12) : null,
          points: { show: false },
          spanGaps: true,
          value: (u, v) => (s.scale === 'ms' ? fmt.durBare(v) + ' ms' : s.scale === 'pct' ? (v == null ? '-' : v.toFixed(1) + '%') : fmt.count(v)),
        });
      }
    }
    const axes = [timeAxis(colors, { short: spec.short })];
    const declared = spec.axes || [{ scale: 'y' }];
    declared.forEach((a, i) => {
      axes.push(valueAxis(colors, {
        scale: a.scale || 'y',
        side: a.side !== undefined ? a.side : (i === 0 ? 3 : 1),
        label: a.label,
        stroke: a.color ? resolveColor(a.color, colors) : colors.muted,
        grid: i === 0,
        values: a.values || (a.scale === 'ms'
          ? (u, splits) => splits.map((s) => fmt.durBare(s))
          : a.scale === 'pct'
            ? (u, splits) => splits.map((s) => (s == null ? '' : s.toFixed(s < 10 ? 1 : 0) + '%'))
            : undefined),
        size: a.size,
      }));
    });
    return {
      data,
      opts: {
        scales,
        series,
        axes,
        legend: { show: false },
        cursor: {
          y: false,
          drag: { x: false, y: false },
          points: { size: 7, width: 2, fill: (u, i) => u.series[i].stroke },
        },
        padding: [10, 8, 0, 0],
        hooks: {
          draw: [(u) => drawMarks(u, colors)],
          setCursor: [(u) => {
            const tip = tooltipFor(container);
            const { idx, left, top } = u.cursor;
            if (idx == null || left < 0) { tip.classList.remove('show'); return; }
            const rows = spec.series.map((s, i) => {
              const v = (s.values || [])[idx];      // the raw value, not the stacked one
              if (v == null) return null;
              const color = resolveColor(s.color, colors);
              return `<span class="k"><i style="background:${color}"></i>${escapeHtml(s.label)}</span><span class="v">${escapeHtml(u.series[column[i]].value(u, v))}</span>`;
            }).filter(Boolean);
            if (!rows.length) { tip.classList.remove('show'); return; }
            tip.innerHTML = `<div class="t">${fmt.clock(u.data[0][idx] * 1000)}</div>${rows.join('')}`;
            tip.classList.add('show');
            const w2 = tip.offsetWidth;
            tip.style.left = Math.min(Math.max(left - w2 / 2, 4), u.width - w2 - 4) + 'px';
            tip.style.top = Math.max(4, top - tip.offsetHeight - 12) + 'px';
          }],
          setSize: [(u) => { const tip = container.querySelector('.chart-tip'); if (tip) tip.classList.remove('show'); }],
        },
      },
    };
  }, spec.height || 220);

  chart.update = (next) => {
    Object.assign(spec, next);
    chart.rebuild();
  };
  return chart;
}

function rangeFor(scale) {
  if (scale === 'pct') return (u, min, max) => [0, Math.max(1, max * 1.15)];
  // A count scale never shrinks below [0, 1], so a series that stays at 0 still
  // gets whole-number ticks rather than thirds.
  if (isCountScale(scale)) return (u, min, max) => [0, Math.max(1, max == null || max <= 0 ? 1 : max * 1.15)];
  return (u, min, max) => [0, max == null || max <= 0 ? 1 : max * 1.15];
}

function resolveColor(color, colors) {
  if (!color) return colors.silk;
  if (color[0] === '#' || color.startsWith('rgb')) return color;
  if (colors[color]) return colors[color];
  const m = /^series(\d)$/.exec(color);
  if (m) return colors.series[(+m[1] - 1) % colors.series.length];
  return color;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

/** A legend row that goes above a chart; items = [{label, color, muted}]. */
export function legend(items, opts = {}) {
  const node = document.createElement('div');
  node.className = 'chart-legend';
  const colors = themeColors();
  for (const item of items) {
    const btn = document.createElement(opts.onToggle ? 'button' : 'span');
    btn.className = 'legend-item' + (item.off ? ' off' : '');
    if (opts.onToggle) {
      btn.type = 'button';
      btn.setAttribute('aria-pressed', String(!item.off));
      btn.addEventListener('click', () => opts.onToggle(item));
    }
    const swatch = document.createElement('i');
    swatch.style.background = resolveColor(item.color, colors);
    btn.appendChild(swatch);
    btn.appendChild(document.createTextNode(item.label));
    if (item.value != null) {
      const v = document.createElement('span');
      v.className = 'legend-value';
      v.textContent = item.value;
      btn.appendChild(v);
    }
    node.appendChild(btn);
  }
  return node;
}

/**
 * The legend a spec's series imply: each series under its `legendLabel`, or its `label` when the
 * legend says no more than the tooltip; `legend: false` leaves one out, and `spec.legendExtra`
 * adds items after them. One declaration, so a colour or a name cannot differ between the two.
 */
export function legendItems(spec) {
  return (spec.series || [])
    .filter((s) => s.legend !== false)
    .map((s) => ({ label: s.legendLabel || s.label, color: s.color }))
    .concat(spec.legendExtra || []);
}

/**
 * A time series in a panel, its legend above it: `show(spec)` draws it the first time and
 * redraws it after, `empty(content)` puts something else where the chart was until the next
 * `show`, and `destroy()` lets it go. `legend: false` leaves the legend out.
 */
export function chartBox({ title, actions, legend: withLegend = true } = {}) {
  const legendNode = document.createElement('div');
  const body = document.createElement('div');
  body.className = 'chart';
  const node = panel({ title, actions }, withLegend ? legendNode : null, body);
  let chart = null;
  return {
    node,
    body,
    show(spec) {
      if (withLegend) legendNode.replaceChildren(legend(legendItems(spec)));
      if (chart) chart.update(spec);
      else chart = timeSeries(body, spec);
    },
    empty(content) {
      legendNode.replaceChildren();
      body.replaceChildren(content);
    },
    setTitle(text) {
      const heading = node.querySelector('.panel-title');
      if (heading) heading.textContent = text;
    },
    destroy() {
      if (chart) chart.destroy();
      chart = null;
    },
  };
}

/** An inline SVG sparkline: no library, no interaction, 120x28 by default. */
export function sparkline(values, opts = {}) {
  const width = opts.width || 120, height = opts.height || 28;
  const svg = svgElement('svg', {
    class: 'sparkline', viewBox: `0 0 ${width} ${height}`, width, height,
    role: 'img', 'aria-label': opts.label || 'Requests per bucket',
  });
  const vals = (values || []).map((v) => (v == null ? 0 : v));
  if (vals.length < 2) {
    svg.appendChild(svgElement('line', { x1: 0, x2: width, y1: height - 1, y2: height - 1, class: 'spark-flat' }));
    return svg;
  }
  const max = Math.max(1, ...vals);
  const step = width / (vals.length - 1);
  const y = (v) => height - 1 - (v / max) * (height - 3);
  const pts = vals.map((v, i) => `${(i * step).toFixed(2)},${y(v).toFixed(2)}`);
  const area = svgElement('path', { d: `M0,${height} L${pts.join(' L')} L${width},${height} Z`, class: 'spark-area' });
  const path = svgElement('path', { d: 'M' + pts.join(' L'), class: 'spark-line' });
  svg.appendChild(area);
  svg.appendChild(path);
  if (opts.color) { path.setAttribute('stroke', opts.color); area.setAttribute('fill', withAlpha(opts.color, 0.14)); }
  return svg;
}

/**
 * The fields of a scatter point, as api.adoc#scatter sends it:
 * [start, durationMs, service, endpointName, traceId, flags].
 */
export const POINT = { START: 0, MS: 1, SERVICE: 2, ENDPOINT: 3, TRACE: 4, FLAGS: 5 };

/** The flags of a point: 1 is an error, 2 a slow request (and 4 a slow query). */
export const isError = (p) => (p[POINT.FLAGS] & 1) !== 0;
export const isSlow = (p) => (p[POINT.FLAGS] & 2) !== 0;

/** A click picks the nearest point within this many CSS pixels. */
const PICK_RADIUS_PX = 12;

/**
 * The response-time scatter: one dot per request, or a density heatmap, drawn by
 * hand in a draw hook.
 * points: [start, durationMs, service, endpoint, traceId, flags]
 * opts: { height, mode, logScale, yMax, hidden:Set(service), onSelect(rect), onPick(point) }
 */
export function scatterChart(container, opts) {
  // The scatter's own view state; `state` would shadow the shared state drawMarks reads.
  const view = {
    points: opts.points || [],
    mode: opts.mode === 'heatmap' ? 'heatmap' : 'dots',
    logScale: !!opts.logScale,
    hidden: opts.hidden || new Set(),
    window: opts.window,
    yMax: opts.yMax,
  };

  const chart = new Chart(container, (width, height, colors) => {
    const visible = view.points.filter((p) => !view.hidden.has(p[POINT.SERVICE]));
    const xs = visible.map((p) => p[POINT.START] / 1000);
    const ys = visible.map((p) => p[POINT.MS]);
    // The log axis starts under the fastest visible point, at 0.5 ms at the most, so no point
    // falls below it; a point of 0 ms, which a log axis has no room for, sits on its bottom edge.
    const fastest = ys.reduce((m, y) => Math.min(m, y), Infinity);
    const floor = view.logScale ? Math.max(0.01, Math.min(0.5, fastest * 0.8)) : 0;
    // The linear axis stops at yMax so a few outliers do not flatten the rest; the log
    // scale has room for them, so it runs to the slowest point.
    const top = view.logScale ? Math.max(10, ...ys) * 1.05 : (view.yMax || Math.max(10, ...ys) * 1.05);
    const data = [xs.length ? xs : [view.window.from / 1000, view.window.to / 1000], xs.length ? ys : [null, null]];

    // The heatmap's cells, in CSS pixels, recomputed on every draw so the hover can
    // read the same bins the canvas shows.
    let bins = null;
    const CELL_W = 6, ROWS = 24;

    // Cell coordinates are CSS pixels inside the plotting area, the space valToPos
    // and posToVal speak; the canvas adds the bbox offset and the pixel ratio.
    const binPoints = (u) => {
      const dpr = devicePixelRatio || 1;
      const width = u.bbox.width / dpr, height = u.bbox.height / dpr;
      const cols = Math.max(1, Math.round(width / CELL_W));
      const cellW = width / cols, cellH = height / ROWS;
      const cells = new Map();
      let max = 0;
      for (const p of visible) {
        const x = u.valToPos(p[POINT.START] / 1000, 'x');
        const y = u.valToPos(Math.max(p[POINT.MS], floor || 0.0001), 'y');
        if (x < -0.5 || x > width + 0.5 || y < -0.5 || y > height + 0.5) continue;
        const c = Math.min(cols - 1, Math.max(0, Math.floor(x / cellW)));
        const r = Math.min(ROWS - 1, Math.max(0, Math.floor(y / cellH)));
        const key = c + ':' + r;
        let cell = cells.get(key);
        if (!cell) { cell = { c, r, count: 0, errors: 0 }; cells.set(key, cell); }
        cell.count++;
        if (isError(p)) cell.errors++;
        if (cell.count > max) max = cell.count;
      }
      return { cells, cols, rows: ROWS, cellW, cellH, width, height, max };
    };

    const drawHeatmap = (u) => {
      const dpr = devicePixelRatio || 1;
      const ctx = u.ctx;
      bins = binPoints(u);
      ctx.save();
      ctx.beginPath();
      ctx.rect(u.bbox.left, u.bbox.top, u.bbox.width, u.bbox.height);
      ctx.clip();
      for (const cell of bins.cells.values()) {
        const x = u.bbox.left + cell.c * bins.cellW * dpr;
        const y = u.bbox.top + cell.r * bins.cellH * dpr;
        const cw = bins.cellW * dpr, ch = bins.cellH * dpr;
        const alpha = Math.max(0.08, Math.sqrt(cell.count / Math.max(1, bins.max)));
        ctx.fillStyle = withAlpha(colors.accent, alpha);
        ctx.fillRect(x, y, cw, ch);
        if (cell.errors) {
          ctx.strokeStyle = colors.err;
          ctx.lineWidth = 1 * dpr;
          ctx.strokeRect(x + dpr / 2, y + dpr / 2, cw - dpr, ch - dpr);
        }
      }
      ctx.restore();
    };

    const drawPoints = (u) => {
      if (view.mode === 'heatmap') { drawHeatmap(u); return; }
      bins = null;
      const ctx = u.ctx;
      const { left, top: t, width, height } = u.bbox;
      ctx.save();
      ctx.beginPath();
      ctx.rect(left, t, width, height);
      ctx.clip();
      const colorFor = new Map();
      const pts = visible;
      // plain points first, then slow rings, then errors on top
      for (const p of pts) {
        if (isError(p)) continue;
        let c = colorFor.get(p[POINT.SERVICE]);
        if (!c) { c = serviceColor(p[POINT.SERVICE]); colorFor.set(p[POINT.SERVICE], c); }
        const x = u.valToPos(p[POINT.START] / 1000, 'x', true);
        const y = u.valToPos(Math.max(p[POINT.MS], floor || 0.0001), 'y', true);
        if (y < t - 4) continue;
        ctx.fillStyle = c;
        ctx.globalAlpha = 0.85;
        ctx.beginPath();
        ctx.arc(x, y, 2.1 * devicePixelRatio, 0, Math.PI * 2);
        ctx.fill();
        if (isSlow(p)) {
          ctx.globalAlpha = 1;
          ctx.strokeStyle = colors.warn;
          ctx.lineWidth = 1.5 * devicePixelRatio;
          ctx.beginPath();
          ctx.arc(x, y, 4.6 * devicePixelRatio, 0, Math.PI * 2);
          ctx.stroke();
        }
      }
      ctx.globalAlpha = 1;
      ctx.strokeStyle = colors.err;
      ctx.lineWidth = 1.6 * devicePixelRatio;
      for (const p of pts) {
        if (!isError(p)) continue;
        const x = u.valToPos(p[POINT.START] / 1000, 'x', true);
        const y = u.valToPos(Math.max(p[POINT.MS], floor || 0.0001), 'y', true);
        if (y < t - 4) continue;
        const r = 3.6 * devicePixelRatio;
        ctx.beginPath();
        ctx.moveTo(x - r, y - r); ctx.lineTo(x + r, y + r);
        ctx.moveTo(x + r, y - r); ctx.lineTo(x - r, y + r);
        ctx.stroke();
      }
      ctx.restore();
    };

    return {
      data,
      opts: {
        scales: {
          x: { time: true, range: () => [view.window.from / 1000, view.window.to / 1000] },
          y: {
            distr: view.logScale ? 3 : 1,
            range: () => [floor, top],
          },
        },
        series: [
          { label: 'Time' },
          { label: 'Response time', scale: 'y', paths: () => null, points: { show: false } },
        ],
        axes: [
          timeAxis(colors),
          valueAxis(colors, { scale: 'y', count: false, label: 'Response time (ms)', size: 60, values: (u, splits) => splits.map((s) => fmt.durBare(s)) }),
        ],
        legend: { show: false },
        cursor: {
          drag: { x: true, y: true, setScale: false },
          points: { show: false },
          dataIdx: () => null,
        },
        padding: [10, 12, 0, 0],
        hooks: {
          draw: [drawPoints],
          setSelect: [(u) => {
            const sel = u.select;
            if (!sel || sel.width < 3 || sel.height < 3) return;
            const x0 = u.posToVal(sel.left, 'x') * 1000;
            const x1 = u.posToVal(sel.left + sel.width, 'x') * 1000;
            const y1 = u.posToVal(sel.top, 'y');
            const y0 = u.posToVal(sel.top + sel.height, 'y');
            if (opts.onSelect) opts.onSelect({ from: Math.round(x0), to: Math.round(x1), minMs: Math.max(0, y0), maxMs: y1 });
          }],
        },
      },
      after: (plot) => {
        const over = plot.over;
        const tip = tooltipFor(container);
        const pick = (ev) => {
          const rect = over.getBoundingClientRect();
          const px = ev.clientX - rect.left, py = ev.clientY - rect.top;
          let best = null, bestD = PICK_RADIUS_PX * PICK_RADIUS_PX;
          for (const p of view.points) {
            if (view.hidden.has(p[POINT.SERVICE])) continue;
            const x = plot.valToPos(p[POINT.START] / 1000, 'x');
            const y = plot.valToPos(Math.max(p[POINT.MS], floor || 0.0001), 'y');
            const d = (x - px) * (x - px) + (y - py) * (y - py);
            if (d < bestD) { bestD = d; best = p; }
          }
          return best;
        };
        const showHeatmapTip = (ev) => {
          over.style.cursor = 'crosshair';
          const rect = over.getBoundingClientRect();
          const px = ev.clientX - rect.left, py = ev.clientY - rect.top;
          const cell = bins
            ? bins.cells.get(Math.floor(px / bins.cellW) + ':' + Math.floor(py / bins.cellH))
            : null;
          if (!cell) { tip.classList.remove('show'); return; }
          const x0 = plot.posToVal(cell.c * bins.cellW, 'x') * 1000;
          const x1 = plot.posToVal((cell.c + 1) * bins.cellW, 'x') * 1000;
          const yTop = plot.posToVal(cell.r * bins.cellH, 'y');
          const yBottom = plot.posToVal((cell.r + 1) * bins.cellH, 'y');
          tip.innerHTML = `<div class="t">${fmt.clock(x0)} – ${fmt.clock(x1)}</div>` +
            `<span class="k">Response time</span><span class="v">${fmt.durBare(Math.max(0, yBottom))} – ${fmt.durBare(Math.max(0, yTop))} ms</span>` +
            `<span class="k">Requests</span><span class="v">${fmt.count(cell.count)}</span>` +
            (cell.errors ? `<span class="k">Errors</span><span class="v">${fmt.count(cell.errors)}</span>` : '');
          tip.classList.add('show');
          const tw = tip.offsetWidth;
          tip.style.left = Math.min(Math.max((cell.c + 0.5) * bins.cellW - tw / 2, 4), rect.width - tw - 4) + 'px';
          tip.style.top = Math.max(4, cell.r * bins.cellH - tip.offsetHeight - 10) + 'px';
        };

        over.addEventListener('mousemove', (ev) => {
          if (view.mode === 'heatmap') { showHeatmapTip(ev); return; }
          const p = pick(ev);
          if (!p) { tip.classList.remove('show'); over.style.cursor = 'crosshair'; return; }
          over.style.cursor = 'pointer';
          tip.innerHTML = `<div class="t">${escapeHtml(p[POINT.ENDPOINT])}</div>` +
            `<span class="k"><i style="background:${serviceColor(p[POINT.SERVICE])}"></i>${escapeHtml(p[POINT.SERVICE])}</span><span class="v">${fmt.durBare(p[POINT.MS])} ms</span>` +
            `<span class="k">Time</span><span class="v">${fmt.clock(p[POINT.START])}</span>`;
          tip.classList.add('show');
          const rect = over.getBoundingClientRect();
          const left = plot.valToPos(p[POINT.START] / 1000, 'x');
          const topPos = plot.valToPos(Math.max(p[POINT.MS], floor || 0.0001), 'y');
          const tw = tip.offsetWidth;
          tip.style.left = Math.min(Math.max(left - tw / 2, 4), rect.width - tw - 4) + 'px';
          tip.style.top = Math.max(4, topPos - tip.offsetHeight - 14) + 'px';
        });
        over.addEventListener('mouseleave', () => tip.classList.remove('show'));
        over.addEventListener('click', (ev) => {
          if (view.mode === 'heatmap') return;      // a cell is not one trace
          const p = pick(ev);
          if (p && opts.onPick) opts.onPick(p);
        });
      },
    };
  }, opts.height || 380);

  chart.setPoints = (points, window, yMax) => {
    view.points = points;
    if (window) view.window = window;
    if (yMax !== undefined) view.yMax = yMax;
    chart.rebuild();
  };
  chart.setMode = (mode) => { view.mode = mode === 'heatmap' ? 'heatmap' : 'dots'; chart.rebuild(); };
  chart.setLogScale = (on) => { view.logScale = on; chart.rebuild(); };
  chart.setHidden = (hidden) => { view.hidden = hidden; chart.rebuild(); };
  chart.setYMax = (v) => { view.yMax = v; chart.rebuild(); };
  chart.clearSelect = () => { if (chart.plot) chart.plot.setSelect({ left: 0, top: 0, width: 0, height: 0 }, false); };
  chart.view = view;
  return chart;
}

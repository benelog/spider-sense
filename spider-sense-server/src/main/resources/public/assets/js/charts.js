// uPlot helpers. Colours are read from CSS custom properties at render time and
// every chart rebuilds itself when the theme changes or its container resizes.

import { clock, clockShort, durBare, count as fmtCount } from './format.js';
import { readSeriesColors, serviceColor as uiServiceColor, seedServices } from './ui.js';

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
    series: readSeriesColors(),
  };
}

/** Called by app.js after the theme flips. */
export function retheme() {
  for (const c of charts) c.rebuild();
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
    values: (u, splits) => splits.map((s) => (opts.short ? clockShort(s * 1000) : clock(s * 1000))),
  };
}

function valueAxis(colors, o = {}) {
  return {
    scale: o.scale || 'y',
    side: o.side === undefined ? 3 : o.side,
    stroke: o.stroke || colors.muted,
    grid: o.grid === false ? { show: false } : { stroke: withAlpha(colors.line, 0.75), width: 1 },
    ticks: { show: false },
    font: uiFont(),
    size: o.size || 52,
    label: o.label,
    labelSize: o.label ? 18 : 0,
    labelFont: uiFont(),
    labelGap: 2,
    values: o.values || ((u, splits) => splits.map((s) => fmtCount(s))),
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

  width() {
    const w = this.container.clientWidth;
    return Math.max(120, w || 600);
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

  setData(data, resetScales = true) {
    this.data = data;
    if (this.plot) this.plot.setData(data, resetScales);
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

function tooltipFor(plot, container) {
  let tip = container.querySelector('.chart-tip');
  if (!tip) {
    tip = document.createElement('div');
    tip.className = 'chart-tip';
    container.appendChild(tip);
  }
  return tip;
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
  const chart = new Chart(container, (w, h, colors) => {
    const xs = (spec.t || []).map((ms) => ms / 1000);
    const data = [xs, ...spec.series.map((s) => s.values.map((v) => (v == null ? null : v)))];
    const scales = { x: { time: true } };
    const series = [{ label: 'Time' }];
    const bars = spec.series.filter((s) => s.type === 'bar').length;
    let barIndex = 0;
    for (const s of spec.series) {
      const color = resolveColor(s.color, colors);
      const scale = s.scale || 'y';
      scales[scale] = scales[scale] || { range: rangeFor(scale, spec) };
      if (s.type === 'bar') {
        const idx = barIndex++;
        series.push({
          label: s.label, scale, stroke: color, fill: withAlpha(color, s.fillAlpha == null ? 0.85 : s.fillAlpha),
          width: 0, points: { show: false },
          paths: uPlot.paths.bars({ size: [bars > 1 && idx > 0 ? 0.62 : 0.72, 24, 1], align: 0, radius: 0.15 }),
          value: (u, v) => fmtCount(v),
        });
      } else {
        series.push({
          label: s.label, scale, stroke: color, width: s.width || 2,
          dash: s.dash || null,
          fill: s.type === 'area' ? withAlpha(color, 0.12) : null,
          points: { show: false },
          spanGaps: true,
          value: (u, v) => (s.scale === 'ms' ? durBare(v) + ' ms' : s.scale === 'pct' ? (v == null ? '-' : v.toFixed(1) + '%') : fmtCount(v)),
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
          ? (u, splits) => splits.map((s) => durBare(s))
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
          setCursor: [(u) => {
            const tip = tooltipFor(u, container);
            const { idx, left, top } = u.cursor;
            if (idx == null || left < 0) { tip.classList.remove('show'); return; }
            const rows = spec.series.map((s, i) => {
              const v = u.data[i + 1][idx];
              if (v == null) return null;
              const color = resolveColor(s.color, colors);
              return `<span class="k"><i style="background:${color}"></i>${escapeHtml(s.label)}</span><span class="v">${escapeHtml(u.series[i + 1].value(u, v))}</span>`;
            }).filter(Boolean);
            if (!rows.length) { tip.classList.remove('show'); return; }
            tip.innerHTML = `<div class="t">${clock(u.data[0][idx] * 1000)}</div>${rows.join('')}`;
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

function rangeFor(scale, spec) {
  if (scale === 'pct') return (u, min, max) => [0, Math.max(1, max * 1.15)];
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

/** An inline SVG sparkline: no library, no interaction, 120x28 by default. */
export function sparkline(values, opts = {}) {
  const w = opts.width || 120, h = opts.height || 28;
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('class', 'sparkline');
  svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
  svg.setAttribute('width', String(w));
  svg.setAttribute('height', String(h));
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', opts.label || 'Requests per bucket');
  const vals = (values || []).map((v) => (v == null ? 0 : v));
  if (vals.length < 2) {
    const line = document.createElementNS(ns, 'line');
    line.setAttribute('x1', '0'); line.setAttribute('x2', String(w));
    line.setAttribute('y1', String(h - 1)); line.setAttribute('y2', String(h - 1));
    line.setAttribute('class', 'spark-flat');
    svg.appendChild(line);
    return svg;
  }
  const max = Math.max(1, ...vals);
  const step = w / (vals.length - 1);
  const y = (v) => h - 1 - (v / max) * (h - 3);
  const pts = vals.map((v, i) => `${(i * step).toFixed(2)},${y(v).toFixed(2)}`);
  const area = document.createElementNS(ns, 'path');
  area.setAttribute('d', `M0,${h} L${pts.join(' L')} L${w},${h} Z`);
  area.setAttribute('class', 'spark-area');
  svg.appendChild(area);
  const path = document.createElementNS(ns, 'path');
  path.setAttribute('d', 'M' + pts.join(' L'));
  path.setAttribute('class', 'spark-line');
  svg.appendChild(path);
  if (opts.color) { path.setAttribute('stroke', opts.color); area.setAttribute('fill', withAlpha(opts.color, 0.14)); }
  return svg;
}

/**
 * The XLog scatter: one dot per request, drawn by hand in a draw hook.
 * points: [start, durationMs, service, endpoint, traceId, flags]
 * opts: { height, logScale, yMax, hidden:Set(service), onSelect(rect), onPick(point), onHover }
 */
export function xlogScatter(container, opts) {
  const state = {
    points: opts.points || [],
    logScale: !!opts.logScale,
    hidden: opts.hidden || new Set(),
    window: opts.window,
    yMax: opts.yMax,
  };

  const chart = new Chart(container, (w, h, colors) => {
    const visible = state.points.filter((p) => !state.hidden.has(p[2]));
    const xs = visible.map((p) => p[0] / 1000);
    const ys = visible.map((p) => p[1]);
    const floor = state.logScale ? 0.1 : 0;
    const top = state.yMax || Math.max(10, ...ys) * 1.05;
    const data = [xs.length ? xs : [state.window.from / 1000, state.window.to / 1000], xs.length ? ys : [null, null]];

    const drawPoints = (u) => {
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
        if (p[5] & 1) continue;
        let c = colorFor.get(p[2]);
        if (!c) { c = serviceColorOf(p[2]); colorFor.set(p[2], c); }
        const x = u.valToPos(p[0] / 1000, 'x', true);
        const y = u.valToPos(Math.max(p[1], floor || 0.0001), 'y', true);
        if (y < t - 4) continue;
        ctx.fillStyle = c;
        ctx.globalAlpha = 0.85;
        ctx.beginPath();
        ctx.arc(x, y, 2.1 * devicePixelRatio, 0, Math.PI * 2);
        ctx.fill();
        if (p[5] & 2) {
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
        if (!(p[5] & 1)) continue;
        const x = u.valToPos(p[0] / 1000, 'x', true);
        const y = u.valToPos(Math.max(p[1], floor || 0.0001), 'y', true);
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
          x: { time: true, range: () => [state.window.from / 1000, state.window.to / 1000] },
          y: {
            distr: state.logScale ? 3 : 1,
            range: () => [state.logScale ? Math.max(0.1, 0.5) : 0, top],
          },
        },
        series: [
          { label: 'Time' },
          { label: 'Response time', scale: 'y', paths: () => null, points: { show: false } },
        ],
        axes: [
          timeAxis(colors),
          valueAxis(colors, { scale: 'y', label: 'Response time (ms)', size: 60, values: (u, splits) => splits.map((s) => durBare(s)) }),
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
        const tip = tooltipFor(plot, container);
        const pick = (ev) => {
          const rect = over.getBoundingClientRect();
          const px = ev.clientX - rect.left, py = ev.clientY - rect.top;
          let best = null, bestD = 144;
          for (const p of state.points) {
            if (state.hidden.has(p[2])) continue;
            const x = plot.valToPos(p[0] / 1000, 'x');
            const y = plot.valToPos(Math.max(p[1], floor || 0.0001), 'y');
            const d = (x - px) * (x - px) + (y - py) * (y - py);
            if (d < bestD) { bestD = d; best = p; }
          }
          return best;
        };
        over.addEventListener('mousemove', (ev) => {
          const p = pick(ev);
          if (!p) { tip.classList.remove('show'); over.style.cursor = 'crosshair'; return; }
          over.style.cursor = 'pointer';
          tip.innerHTML = `<div class="t">${escapeHtml(p[3])}</div>` +
            `<span class="k"><i style="background:${serviceColorOf(p[2])}"></i>${escapeHtml(p[2])}</span><span class="v">${durBare(p[1])} ms</span>` +
            `<span class="k">Time</span><span class="v">${clock(p[0])}</span>`;
          tip.classList.add('show');
          const rect = over.getBoundingClientRect();
          const left = plot.valToPos(p[0] / 1000, 'x');
          const topPos = plot.valToPos(Math.max(p[1], floor || 0.0001), 'y');
          const tw = tip.offsetWidth;
          tip.style.left = Math.min(Math.max(left - tw / 2, 4), rect.width - tw - 4) + 'px';
          tip.style.top = Math.max(4, topPos - tip.offsetHeight - 14) + 'px';
        });
        over.addEventListener('mouseleave', () => tip.classList.remove('show'));
        over.addEventListener('click', (ev) => {
          const p = pick(ev);
          if (p && opts.onPick) opts.onPick(p);
        });
      },
    };
  }, opts.height || 380);

  chart.setPoints = (points, window) => {
    state.points = points;
    if (window) state.window = window;
    chart.rebuild();
  };
  chart.setLogScale = (on) => { state.logScale = on; chart.rebuild(); };
  chart.setHidden = (hidden) => { state.hidden = hidden; chart.rebuild(); };
  chart.setYMax = (v) => { state.yMax = v; chart.rebuild(); };
  chart.clearSelect = () => { if (chart.plot) chart.plot.setSelect({ left: 0, top: 0, width: 0, height: 0 }, false); };
  chart.state = state;
  return chart;
}

/** The same first-seen assignment the rest of the UI uses. */
function serviceColorOf(name) { return uiServiceColor(name); }

export { seedServices as seedServiceColors };

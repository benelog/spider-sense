// Service map: the topology of the window as one hand-drawn SVG, over /api/map.
// pages.adoc#map, api.adoc#map.

import * as api from '../api.js';
import * as router from '../router.js';
import {
  h, fill, panel, stat, spinner, noDataYet,
  drawer, closeDrawer, seedServices,
} from '../ui.js';
import { pageLoader } from '../page.js';
import { timeSeries } from '../charts.js';
import { throughputSpec } from '../throughput.js';
import { histogramBars, bucketVars, apdexClass, fmtApdex } from '../buckets.js';
import { dur, count, rate, pct } from '../format.js';

const NS = 'http://www.w3.org/2000/svg';
const NODE_W = 200, NODE_H = 64, COL_PITCH = 260, ROW_PITCH = 96, PAD_X = 22, PAD_Y = 20;
const HIST_W = 40, HIST_H = 14, HIST_X = NODE_W - HIST_W - 12, HIST_Y = NODE_H - HIST_H - 10;

const KIND_ICON = { user: 'user', service: 'service', db: 'database', http: 'trace', messaging: 'log', rpc: 'service' };

function el(tag, attrs = {}, ...children) {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    node.setAttribute(k, String(v));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.appendChild(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
}

function ellipsis(text, max) {
  const s = String(text == null ? '' : text);
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

/** Characters that fit in `px` of the UI font at `size`, near enough for a label. */
function fits(px, size) {
  return Math.max(3, Math.floor(px / (size * 0.55)));
}

/** A point on the cubic P0 C1 C2 P3 at t. */
export function cubicAt(p0, c1, c2, p3, t) {
  const u = 1 - t;
  const a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
  return { x: a * p0.x + b * c1.x + c * c2.x + d * p3.x, y: a * p0.y + b * c1.y + c * c2.y + d * p3.y };
}

/** Does the straight segment cross this node's rectangle? The segment is a function of x. */
function segmentHitsRect(x1, y1, x2, y2, rect, margin = 4) {
  const left = Math.max(Math.min(x1, x2), rect.x - margin);
  const right = Math.min(Math.max(x1, x2), rect.x + NODE_W + margin);
  if (left > right) return false;
  const at = (x) => (x2 === x1 ? y1 : y1 + ((y2 - y1) * (x - x1)) / (x2 - x1));
  const ya = at(left), yb = at(right);
  const top = Math.min(ya, yb), bottom = Math.max(ya, yb);
  return bottom >= rect.y - margin && top <= rect.y + NODE_H + margin;
}

function pointInRect(p, rect, margin = 2) {
  return p.x > rect.x - margin && p.x < rect.x + NODE_W + margin
    && p.y > rect.y - margin && p.y < rect.y + NODE_H + margin;
}

const BOW = 0.6 * ROW_PITCH;

/**
 * The cubic of one edge. An edge that skips a column bows around the nodes in
 * between: its control points move 0.6 row pitches away from the nearest node it
 * would otherwise cross, and a further 0.6 each time that is not yet enough to
 * keep the whole curve out of the nodes. pages.adoc#map.
 */
export function edgeCurve(a, b, obstacles = []) {
  const p0 = { x: a.x + NODE_W, y: a.y + NODE_H / 2 };
  const p3 = { x: b.x, y: b.y + NODE_H / 2 };
  const dx = Math.max(40, (p3.x - p0.x) / 2);
  const lo = Math.min(a.column, b.column), hi = Math.max(a.column, b.column);
  const between = obstacles.filter((o) => o.column > lo && o.column < hi);
  const bowed = (dir, steps) => ({
    p0,
    p3,
    c1: { x: p0.x + dx, y: p0.y + dir * steps * BOW },
    c2: { x: p3.x - dx, y: p3.y + dir * steps * BOW },
  });
  const plain = bowed(1, 0);
  const blockers = between.filter((o) => segmentHitsRect(p0.x, p0.y, p3.x, p3.y, o));
  if (!blockers.length && !crosses(plain, between)) return plain;
  const mid = (p0.y + p3.y) / 2;
  const nearest = (blockers.length ? blockers : between).reduce((best, o) =>
    (best === null || Math.abs(o.y + NODE_H / 2 - mid) < Math.abs(best.y + NODE_H / 2 - mid) ? o : best), null);
  const away = nearest && nearest.y + NODE_H / 2 <= mid ? 1 : -1;   // the blocker is above, so bow below
  for (let steps = 1; steps <= 4; steps++) {
    for (const dir of [away, -away]) {
      const curve = bowed(dir, steps);
      if (!crosses(curve, between)) return curve;
    }
  }
  return bowed(away, 4);
}

/** The box a curve occupies, so the drawing can grow the viewBox around it. */
export function curveBounds(curve, steps = 24) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (let i = 0; i <= steps; i++) {
    const p = cubicAt(curve.p0, curve.c1, curve.c2, curve.p3, i / steps);
    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
    minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
  }
  return { minX, minY, maxX, maxY };
}

/** Samples the curve and reports whether any sample lands inside one of the rects. */
export function crosses(curve, rects, steps = 60) {
  for (let i = 0; i <= steps; i++) {
    const p = cubicAt(curve.p0, curve.c1, curve.c2, curve.p3, i / steps);
    for (const rect of rects) if (pointInRect(p, rect)) return true;
  }
  return false;
}

export function curvePath(curve) {
  return `M${curve.p0.x},${curve.p0.y} C${curve.c1.x},${curve.c1.y} ${curve.c2.x},${curve.c2.y} ${curve.p3.x},${curve.p3.y}`;
}

/**
 * Columns left to right: the user, the services ordered by the longest path from a
 * source, then every external target. Inside a column, nodes are sorted by name.
 */
export function layout(nodes, edges) {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const isService = (id) => (byId.get(id) || {}).kind === 'service';
  const parents = new Map();
  for (const e of edges) {
    if (!isService(e.from) || !isService(e.to)) continue;
    if (!parents.has(e.to)) parents.set(e.to, []);
    parents.get(e.to).push(e.from);
  }
  const depths = new Map();
  const depthOf = (id, seen) => {
    if (depths.has(id)) return depths.get(id);
    if (seen.has(id)) return 0;                 // a cycle stops here
    seen.add(id);
    const ps = parents.get(id) || [];
    const d = ps.length ? Math.max(...ps.map((p) => depthOf(p, seen) + 1)) : 0;
    seen.delete(id);
    depths.set(id, d);
    return d;
  };
  for (const n of nodes) if (n.kind === 'service') depthOf(n.id, new Set());

  const maxDepth = nodes.reduce((m, n) => (n.kind === 'service' ? Math.max(m, depths.get(n.id) || 0) : m), 0);
  const columnOf = (n) => {
    if (n.kind === 'user') return 0;
    if (n.kind === 'service') return 1 + (depths.get(n.id) || 0);
    return maxDepth + 2;
  };
  const columns = new Map();
  for (const n of nodes) {
    const c = columnOf(n);
    if (!columns.has(c)) columns.set(c, []);
    columns.get(c).push(n);
  }
  const positions = new Map();
  let rows = 1;
  for (const [c, list] of columns) {
    list.sort((a, b) => String(a.name).localeCompare(String(b.name)));
    rows = Math.max(rows, list.length);
    list.forEach((n, i) => {
      positions.set(n.id, { x: PAD_X + c * COL_PITCH, y: PAD_Y + i * ROW_PITCH, column: c });
    });
  }
  const columnCount = Math.max(1, columns.size ? Math.max(...columns.keys()) + 1 : 1);
  return {
    positions,
    width: Math.max(columnCount * COL_PITCH, PAD_X * 2 + (columnCount - 1) * COL_PITCH + NODE_W),
    height: PAD_Y * 2 + (rows - 1) * ROW_PITCH + NODE_H,
    columns: columnCount,
  };
}

export function render(root, ctx) {
  let data = null;
  let layoutKey = null;
  let placed = null;
  let drawerChart = null;
  const nodeRefs = new Map();
  const edgeRefs = new Map();
  let highlighted = null;

  ctx.setTitle('Service map');

  const svgBox = h('div.map-scroll');
  const view = panel({ title: 'Service map' }, svgBox);
  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, view);
  root.appendChild(page);
  fill(svgBox, spinner());
  sizePanel();

  function sizePanel() {
    const top = 52, gaps = 90;
    svgBox.style.height = Math.max(420, innerHeight - top - gaps) + 'px';
  }
  addEventListener('resize', sizePanel);

  // --- neighbours -------------------------------------------------------

  function neighboursOf(id) {
    const set = new Set([id]);
    for (const e of (data.edges || [])) {
      if (e.from === id) set.add(e.to);
      if (e.to === id) set.add(e.from);
    }
    return set;
  }

  function applyDim() {
    const svg = svgBox.querySelector('svg');
    if (!svg) return;
    let keep = null;
    if (highlighted) keep = neighboursOf(highlighted);
    else if (api.state.service) {
      const id = 'svc:' + api.state.service;
      if (nodeRefs.has(id)) keep = neighboursOf(id);
    }
    for (const [id, ref] of nodeRefs) ref.g.classList.toggle('dim', !!keep && !keep.has(id));
    for (const [key, ref] of edgeRefs) {
      const [from, to] = key.split('\u0000');
      const dim = !!keep && !(keep.has(from) && keep.has(to));
      ref.g.classList.toggle('dim', dim);
      ref.labelG.classList.toggle('dim', dim);
    }
  }

  // --- drawing ----------------------------------------------------------

  function draw() {
    const nodes = data.nodes || [];
    const edges = data.edges || [];
    nodeRefs.clear();
    edgeRefs.clear();
    placed = layout(nodes, edges);
    seedServices(nodes.filter((n) => n.kind === 'service').map((n) => n.name));

    // The curves come first: a bowed edge may need room above or below the nodes,
    // and the viewBox grows around it rather than clipping it.
    const rects = obstacles();
    const curves = new Map();
    for (const e of edges) {
      const a = placed.positions.get(e.from), b = placed.positions.get(e.to);
      if (!a || !b) continue;
      curves.set(edgeKey(e), edgeCurve(a, b, rects.filter((o) => o.id !== e.from && o.id !== e.to)));
    }
    let minX = 0, minY = 0, maxX = placed.width, maxY = placed.height;
    for (const curve of curves.values()) {
      const box = curveBounds(curve);
      minX = Math.min(minX, box.minX);
      maxX = Math.max(maxX, box.maxX);
      minY = Math.min(minY, box.minY - 18);
      maxY = Math.max(maxY, box.maxY + 18);
    }
    const width = Math.round(maxX - minX), height = Math.round(maxY - minY);

    const svg = el('svg', {
      class: 'map-svg',
      width,
      height,
      viewBox: `${Math.round(minX)} ${Math.round(minY)} ${width} ${height}`,
      role: 'group',
      'aria-label': 'Service map',
    });
    svg.style.minWidth = Math.max(placed.columns * COL_PITCH, width) + 'px';
    const defs = el('defs', {},
      el('marker', { id: 'map-arrow', viewBox: '0 0 8 8', refX: '7', refY: '4', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' },
        el('path', { d: 'M0 1 L7 4 L0 7 z', class: 'map-arrow-head' })));
    svg.appendChild(defs);

    const edgeLayer = el('g', { class: 'map-edges' });
    const nodeLayer = el('g', { class: 'map-nodes' });
    // The labels ride above the nodes, so a halo that reaches over a node edge is
    // still readable instead of being painted over.
    const labelLayer = el('g', { class: 'map-labels' });
    svg.appendChild(edgeLayer);
    svg.appendChild(nodeLayer);
    svg.appendChild(labelLayer);

    for (const e of edges) {
      const drawn = drawEdge(e, curves.get(edgeKey(e)));
      if (!drawn) continue;
      edgeLayer.appendChild(drawn.edge);
      labelLayer.appendChild(drawn.label);
    }
    for (const n of nodes) {
      const g = drawNode(n);
      if (g) nodeLayer.appendChild(g);
    }
    fill(svgBox, svg);
    for (const ref of edgeRefs.values()) sizeHalo(ref);
    applyDim();
  }

  const edgeKey = (e) => e.from + '\u0000' + e.to;

  /** Every node rectangle, with its column, for the bowing test. */
  function obstacles() {
    return (data.nodes || [])
      .map((n) => ({ id: n.id, ...(placed.positions.get(n.id) || {}) }))
      .filter((o) => o.x !== undefined);
  }

  function drawEdge(e, curve) {
    if (!curve) return null;
    const width = Math.min(5, 1 + Math.log10(Math.max(1, e.calls || 1)));
    const g = el('g', { class: 'map-edge' + (e.errors ? ' is-bad' : '') });
    const path = el('path', {
      class: 'map-edge-path', d: curvePath(curve),
      'stroke-width': width.toFixed(2), 'marker-end': 'url(#map-arrow)',
    }, el('title', {}, edgeTitle(e)));
    g.appendChild(path);
    // The label rides the curve at 40% of its length, clear of the arrowhead.
    const at = cubicAt(curve.p0, curve.c1, curve.c2, curve.p3, 0.4);
    const labelG = el('g', { class: 'map-edge-label-g' + (e.errors ? ' is-bad' : '') });
    const halo = el('rect', { class: 'map-edge-halo', x: at.x - 20, y: at.y - 18, width: 40, height: 14, rx: 3 });
    const label = el('text', { class: 'map-edge-label', x: at.x, y: at.y - 6, 'text-anchor': 'middle' },
      el('tspan', {}, count(e.calls)),
      e.errors ? el('tspan', { class: 'map-edge-err', dx: '5' }, count(e.errors) + ' err') : null);
    labelG.appendChild(halo);
    labelG.appendChild(label);
    edgeRefs.set(edgeKey(e), { g, labelG, path, label, halo });
    return { edge: g, label: labelG };
  }

  /** The halo is sized from the laid-out text, so it never clips the label. */
  function sizeHalo(ref) {
    let box = null;
    try { box = ref.label.getBBox(); } catch (err) { box = null; }
    if (!box || !box.width) return;
    ref.halo.setAttribute('x', box.x - 3);
    ref.halo.setAttribute('y', box.y - 3);
    ref.halo.setAttribute('width', box.width + 6);
    ref.halo.setAttribute('height', box.height + 6);
  }

  function edgeTitle(e) {
    return [
      count(e.calls) + ' calls',
      e.errors ? count(e.errors) + ' errors' : null,
      'avg ' + dur(e.avgMs),
      'p95 ' + dur(e.p95Ms),
    ].filter(Boolean).join(' · ');
  }

  function drawNode(n) {
    const p = placed.positions.get(n.id);
    if (!p) return null;
    const g = el('g', {
      class: 'map-node ' + nodeClass(n),
      transform: `translate(${p.x},${p.y})`,
      'data-kind': n.kind,
      'data-id': n.id,
      tabindex: '0',
      role: 'button',
      'aria-label': n.name + ', ' + n.kind,
    });
    const rect = el('rect', { class: 'map-node-box', width: NODE_W, height: NODE_H, rx: 8 });
    const use = el('use', { class: 'map-node-icon', href: '#i-' + (KIND_ICON[n.kind] || 'service'), x: 12, y: 12, width: 16, height: 16 });
    const name = el('text', { class: 'map-node-name', x: 36, y: 25 },
      ellipsis(n.name, fits(NODE_W - 36 - 12, 12)), el('title', {}, n.name));
    g.appendChild(rect);
    g.appendChild(use);
    g.appendChild(name);
    const full = n.kind === 'service' ? serviceLine(n) : externalLine(n);
    const line = el('text', { class: 'map-node-line', x: 12, y: 46 },
      ellipsis(full, lineChars(n)), el('title', {}, full));
    g.appendChild(line);
    if (n.kind === 'service') g.appendChild(miniHistogram(n.histogram, HIST_X, HIST_Y));
    g.addEventListener('click', () => openNode(n));
    g.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') { ev.preventDefault(); openNode(n); } });
    g.addEventListener('mouseenter', () => { highlighted = n.id; applyDim(); });
    g.addEventListener('mouseleave', () => { highlighted = null; applyDim(); });
    g.addEventListener('focus', () => { highlighted = n.id; applyDim(); });
    g.addEventListener('blur', () => { highlighted = null; applyDim(); });
    nodeRefs.set(n.id, { g, name, line, node: n });
    return g;
  }

  function nodeClass(n) {
    if (n.kind !== 'service') return '';
    if (n.errorRate > 0.01) return 'is-bad';
    if (n.apdex != null && n.apdex < 0.85) return 'is-warn';
    return '';
  }

  /** A service line stops short of the mini histogram; anything else runs the full width. */
  function lineChars(n) {
    const avail = (n.kind === 'service' ? HIST_X - 8 : NODE_W - 12) - 12;
    return fits(avail, 10.5);
  }

  function serviceLine(n) {
    return rate(n.rps || 0) + '/s · ' + dur(n.p95Ms) + ' · ' + pct(n.errorRate || 0);
  }

  function externalLine(n) {
    if (n.kind === 'user') return count(totalUserCalls()) + ' requests';
    return count(n.calls) + ' calls · ' + dur(n.p95Ms);
  }

  function totalUserCalls() {
    return (data.edges || []).filter((e) => e.from === 'user').reduce((s, e) => s + (e.calls || 0), 0);
  }

  /** The 5-bar mini histogram in the node's bottom-right corner, 40x14. */
  function miniHistogram(histogram, x, y) {
    const g = el('g', { class: 'map-hist', transform: `translate(${x},${y})` });
    const values = (histogram || []).slice(0, 5);
    while (values.length < 5) values.push(0);
    const max = Math.max(1, ...values.map((v) => v || 0));
    const colors = bucketVars();
    const pitch = HIST_W / 5;
    values.forEach((v, i) => {
      const hgt = Math.max(v ? 1 : 0, ((v || 0) / max) * HIST_H);
      g.appendChild(el('rect', {
        x: i * pitch, y: HIST_H - hgt, width: pitch - 2, height: hgt, rx: 1, fill: colors[i],
      }));
    });
    return g;
  }

  // --- live update ------------------------------------------------------

  function update() {
    for (const n of (data.nodes || [])) {
      const ref = nodeRefs.get(n.id);
      if (!ref) continue;
      ref.node = n;
      ref.g.setAttribute('class', 'map-node ' + nodeClass(n));
      if (ref.line) {
        const full = n.kind === 'service' ? serviceLine(n) : externalLine(n);
        ref.line.replaceChildren(document.createTextNode(ellipsis(full, lineChars(n))), el('title', {}, full));
      }
      if (n.kind === 'service') {
        const old = ref.g.querySelector('.map-hist');
        if (old) old.replaceWith(miniHistogram(n.histogram, HIST_X, HIST_Y));
      }
    }
    for (const e of (data.edges || [])) {
      const ref = edgeRefs.get(edgeKey(e));
      if (!ref) continue;
      ref.g.setAttribute('class', 'map-edge' + (e.errors ? ' is-bad' : ''));
      ref.labelG.setAttribute('class', 'map-edge-label-g' + (e.errors ? ' is-bad' : ''));
      ref.path.setAttribute('stroke-width', Math.min(5, 1 + Math.log10(Math.max(1, e.calls || 1))).toFixed(2));
      fill(ref.path, el('title', {}, edgeTitle(e)));
      ref.label.replaceChildren(
        el('tspan', {}, count(e.calls)),
        ...(e.errors ? [el('tspan', { class: 'map-edge-err', dx: '5' }, count(e.errors) + ' err')] : []));
      sizeHalo(ref);
    }
    applyDim();
  }

  // --- the drawer -------------------------------------------------------

  function openNode(n) {
    if (drawerChart) { drawerChart.destroy(); drawerChart = null; }
    const body = n.kind === 'service' ? serviceDrawer(n) : externalDrawer(n);
    drawer({
      title: n.name,
      subtitle: n.kind === 'user' ? 'traffic from outside every traced service' : n.kind,
      body,
      onClose: () => { if (drawerChart) { drawerChart.destroy(); drawerChart = null; } },
    });
  }

  function serviceDrawer(n) {
    const chartBody = h('div.chart');
    const q = { ...api.sharedQuery(), service: n.name };
    loadDrawerChart(n.name, chartBody);
    return [
      h('div.stat-row',
        stat(count(n.requests), 'total', 'requests'),
        stat(fmtApdex(n.apdex), '', 'apdex', { class: apdexClass(n.apdex) }),
        stat(pct(n.errorRate || 0), '', 'error rate', { class: n.errorRate > 0.01 ? 'is-bad' : '' }),
        stat(dur(n.p95Ms), '', 'p95'),
        stat(rate(n.rps || 0), '/s', 'requests per second')),
      h('div', h('div.sub-head', { style: { marginBottom: '6px' } }, 'Response summary'), histogramBars(n.histogram)),
      h('div', h('div.sub-head', { style: { marginBottom: '6px' } }, 'Load'), chartBody),
      h('div.row', { style: { gap: '8px' } },
        h('a.btn', { href: router.href(router.detailPath('services', n.name), q) }, 'Service'),
        h('a.btn', { href: router.href('/scatter', q) }, 'Scatter'),
        h('a.btn', { href: router.href('/traces', q) }, 'Traces')),
    ];
  }

  async function loadDrawerChart(name, container) {
    try {
      const res = await api.service(name);
      if (loader.isDestroyed() || !container.isConnected) return;
      // The drawer slides in on the next frame; the chart is measured after that,
      // so it is built against the drawer's real width rather than a default.
      await new Promise((r) => requestAnimationFrame(() => r()));
      if (loader.isDestroyed() || !container.isConnected) return;
      drawerChart = timeSeries(container, throughputSpec(res.series || {}, 'load', { height: 160 }));
    } catch (e) {
      if (container.isConnected) fill(container, h('span.muted', 'The load chart could not be read.'));
    }
  }

  function externalDrawer(n) {
    const callers = (data.edges || []).filter((e) => e.to === n.id);
    const nameOf = (id) => ((data.nodes || []).find((x) => x.id === id) || {}).name || id;
    return [
      h('dl.kv',
        h('dt', 'calls'), h('dd', count(n.calls)),
        h('dt', 'errors'), h('dd', { class: n.errors ? 'bad' : '' }, count(n.errors || 0)),
        h('dt', 'avg'), h('dd', dur(n.avgMs)),
        h('dt', 'p95'), h('dd', dur(n.p95Ms))),
      h('div',
        h('div.sub-head', { style: { marginBottom: '6px' } }, 'Called by'),
        h('dl.kv', callers.length
          ? callers.map((e) => [h('dt', nameOf(e.from)), h('dd', count(e.calls) + ' calls' + (e.errors ? ', ' + count(e.errors) + ' errors' : ''))])
          : [h('dt', '—'), h('dd', 'nothing in this window')])),
    ];
  }

  // --- loading ----------------------------------------------------------

  function paint(res) {
    data = res;
    const nodes = data.nodes || [];
    const edges = data.edges || [];
    if (!edges.length) {
      layoutKey = null;
      nodeRefs.clear();
      edgeRefs.clear();
      fill(svgBox, noDataYet(nodes.length
        ? 'No call has been traced between these nodes in this window. Send some traffic, or widen the range in the top bar.'
        : 'Nothing has been traced yet. Attach Spider Sense to an application, or point any OTLP/HTTP sender at this collector.'));
      return;
    }
    // The edges are part of the layout: a call between services moves a node to a later
    // column, and an edge that comes or goes is drawn or removed. Live only renumbers the rest.
    const key = nodes.map((n) => n.id).sort().join('|') + '#' + edges.map(edgeKey).sort().join('|');
    if (key !== layoutKey) {
      layoutKey = key;
      draw();
    } else {
      update();
    }
  }

  const loader = pageLoader({ fetch: () => api.map(), paint, body: svgBox, onError: () => { layoutKey = null; } });

  loader.load();

  return {
    refresh: loader.load,
    onEscape: () => closeDrawer(),
    destroy: () => {
      loader.destroy();
      removeEventListener('resize', sizePanel);
      if (drawerChart) drawerChart.destroy();
      closeDrawer(true);
    },
  };
}

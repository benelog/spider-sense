// The model behind the Trace page (pages.adoc#trace): the span tree, the self times and depths,
// and the profile's rows, as pure functions of a trace's spans.

/** A span's start as fractional epoch milliseconds: startNs carries the precision, start is the fallback. */
export function startMsOf(span) {
  return span.startNs ? span.startNs / 1e6 : span.start;
}

/** Where the trace's offsets count from: its earliest span, or its own start when it has none. */
export function traceStartMs(trace) {
  const spans = trace.spans || [];
  if (!spans.length) return trace.start;
  // A loop, not Math.min(...starts): a large trace has more spans than a call takes arguments.
  let min = Infinity;
  for (const s of spans) min = Math.min(min, startMsOf(s));
  return min;
}

/**
 * The id of the parent each span hangs under, by its own id, or null for a root: a span whose
 * parent the trace does not hold, and the member of a parent cycle (a span that is its own
 * parent, or two that name each other) that comes first in the spans' order, so the cycle enters
 * the tree there and every view lists the same spans.
 */
function parentsOf(spans) {
  const byId = new Map(spans.map((s) => [s.spanId, s]));
  const order = new Map(spans.map((s, i) => [s.spanId, i]));
  const parents = new Map(spans.map((s) => [s.spanId,
    s.parentSpanId && byId.has(s.parentSpanId) ? s.parentSpanId : null]));
  const ON_PATH = 1, DONE = 2;
  const mark = new Map();
  for (const s of spans) {
    // Climb without recursion (a deep trace nests further than the call stack goes); meeting a
    // span already on this climb closes a cycle.
    const path = [];
    let cur = s.spanId;
    while (cur != null && !mark.has(cur)) {
      mark.set(cur, ON_PATH);
      path.push(cur);
      cur = parents.get(cur);
    }
    if (cur != null && mark.get(cur) === ON_PATH) {
      const cycle = path.slice(path.indexOf(cur));
      parents.set(cycle.reduce((a, b) => (order.get(a) <= order.get(b) ? a : b)), null);
    }
    for (const id of path) mark.set(id, DONE);
  }
  return parents;
}

/** The roots, and the children of each span by its id, both in the spans' own order. */
export function spanTree(spans) {
  const parents = parentsOf(spans);
  const children = new Map();
  const roots = [];
  for (const s of spans) {
    const parent = parents.get(s.spanId);
    if (parent) {
      if (!children.has(parent)) children.set(parent, []);
      children.get(parent).push(s);
    } else {
      roots.push(s);
    }
  }
  return { roots, children };
}

/** The tree depth first, one `{ span, depth, hasChildren }` per row, without the rows under a collapsed span. */
export function flattenTree(tree, collapsed = new Set()) {
  const out = [];
  // An explicit stack, not recursion: a deep trace nests further than the call stack goes.
  const stack = tree.roots.map((span) => ({ span, depth: 0 })).reverse();
  while (stack.length) {
    const { span, depth } = stack.pop();
    const kids = tree.children.get(span.spanId) || [];
    out.push({ span, depth, hasChildren: kids.length > 0 });
    if (collapsed.has(span.spanId)) continue;
    for (let i = kids.length - 1; i >= 0; i--) stack.push({ span: kids[i], depth: depth + 1 });
  }
  return out;
}

/** How much of `from`..`to` the intervals cover together, each `[start, end]` clipped to it. */
function coveredMs(intervals, from, to) {
  const clipped = intervals
    .map(([start, end]) => [Math.max(start, from), Math.min(end, to)])
    .filter(([start, end]) => end > start)
    .sort((a, b) => a[0] - b[0]);
  let covered = 0;
  let reached = from;
  for (const [start, end] of clipped) {
    if (end <= reached) continue;
    covered += end - Math.max(start, reached);
    reached = end;
  }
  return covered;
}

/**
 * Each span's elapsed time less the time its direct children cover within it: two children
 * that run at once take their overlap once, and a child that outlives its parent takes only
 * the part inside it.
 */
export function selfTimes(spans) {
  const parents = parentsOf(spans);
  const childIntervals = new Map();
  for (const s of spans) {
    const parent = parents.get(s.spanId);
    if (!parent) continue;
    if (!childIntervals.has(parent)) childIntervals.set(parent, []);
    const start = startMsOf(s);
    childIntervals.get(parent).push([start, start + (s.durationMs || 0)]);
  }
  const out = new Map();
  for (const s of spans) {
    const duration = s.durationMs || 0;
    const start = startMsOf(s);
    const covered = coveredMs(childIntervals.get(s.spanId) || [], start, start + duration);
    out.set(s.spanId, Math.max(0, duration - covered));
  }
  return out;
}

/** Each span's depth under its root; a parent cycle is a root at its first member, as in spanTree. */
export function depthMap(spans) {
  const parents = parentsOf(spans);
  const depths = new Map();
  for (const s of spans) {
    // Climb to a span whose depth is known or a root, without recursion (a deep trace nests
    // further than the call stack goes), then number the way down.
    const chain = [];
    let base = -1;
    for (let cur = s.spanId; cur != null; cur = parents.get(cur)) {
      if (depths.has(cur)) { base = depths.get(cur); break; }
      chain.push(cur);
    }
    for (let i = chain.length - 1; i >= 0; i--) depths.set(chain[i], ++base);
  }
  return depths;
}

/**
 * The profile's rows (pages.adoc#trace): every span numbered in start order, the longer first
 * of two that start together, with its offset from the trace start, the gap since the latest
 * start before it, its depth and its self time. `sort` is `start`, `elapsed` or `self`; the
 * last two put the largest first.
 */
export function profileRows(trace, sort = 'start') {
  const all = trace.spans || [];
  const spans = all.slice().sort((a, b) => startMsOf(a) - startMsOf(b) || b.durationMs - a.durationMs);
  const depths = depthMap(all);
  const self = selfTimes(all);
  const t0 = traceStartMs(trace);
  let latestStart = t0;
  const rows = spans.map((span, i) => {
    const start = startMsOf(span);
    const gap = start - latestStart;
    latestStart = Math.max(latestStart, start);
    return {
      span, index: i + 1, startOffset: start - t0, gap,
      depth: depths.get(span.spanId) || 0,
      self: self.get(span.spanId) || 0,
    };
  });
  if (sort === 'self') return rows.sort((a, b) => b.self - a.self);
  if (sort === 'elapsed') return rows.sort((a, b) => b.span.durationMs - a.span.durationMs);
  return rows;
}

/** A step is hot when it spent at least this share of the trace in itself. */
const HOT_SPAN_MIN_SHARE = 0.05;

/** The three steps that spent the most time in themselves, each of them at least 5% of the trace. */
export function hotSpanIds(rows, totalMs) {
  return new Set(rows.slice()
    .sort((a, b) => b.self - a.self)
    .filter((r) => r.self > 0 && r.self / totalMs >= HOT_SPAN_MIN_SHARE)
    .slice(0, 3)
    .map((r) => r.span.spanId));
}

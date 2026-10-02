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

/** The parent a span hangs under, or null when the trace does not hold it (the span is then a root). */
function parentIn(byId, span) {
  return span.parentSpanId && byId.has(span.parentSpanId) ? span.parentSpanId : null;
}

/** The roots, and the children of each span by its id, both in the spans' own order. */
export function spanTree(spans) {
  const byId = new Map(spans.map((s) => [s.spanId, s]));
  const children = new Map();
  const roots = [];
  for (const s of spans) {
    const parent = parentIn(byId, s);
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

/** Each span's elapsed time less the durations of its direct children, never below zero. */
export function selfTimes(spans) {
  const byId = new Map(spans.map((s) => [s.spanId, s]));
  const childSum = new Map();
  for (const s of spans) {
    const parent = parentIn(byId, s);
    if (!parent) continue;
    childSum.set(parent, (childSum.get(parent) || 0) + (s.durationMs || 0));
  }
  const out = new Map();
  for (const s of spans) out.set(s.spanId, Math.max(0, (s.durationMs || 0) - (childSum.get(s.spanId) || 0)));
  return out;
}

/** Each span's depth under its root; a parent cycle stops where it closes. */
export function depthMap(spans) {
  const byId = new Map(spans.map((s) => [s.spanId, s]));
  const depths = new Map();
  for (const s of spans) {
    // Climb to a span whose depth is known, a root or the span that closes a cycle, without
    // recursion (a deep trace nests further than the call stack goes), then number the way down.
    const chain = [];
    const seen = new Set();
    let base = -1;
    for (let cur = s; ;) {
      if (depths.has(cur.spanId)) { base = depths.get(cur.spanId); break; }
      if (seen.has(cur.spanId)) { base = 0; break; }
      seen.add(cur.spanId);
      chain.push(cur);
      const parent = parentIn(byId, cur);
      if (!parent) break;
      cur = byId.get(parent);
    }
    for (let i = chain.length - 1; i >= 0; i--) depths.set(chain[i].spanId, ++base);
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

// trace-model.js: the span tree, self times, depths and the profile of the Trace page (pages.adoc#trace).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  startMsOf, traceStartMs, spanTree, flattenTree, selfTimes, depthMap, profileRows, hotSpanIds,
  siblingRuns, FOLD_AFTER,
} from '../../main/resources/public/assets/js/trace-model.js';

const span = (spanId, parentSpanId, start, durationMs) => ({ spanId, parentSpanId, start, durationMs });

// root 0..100 ms: a 10..40, b 50..90 (b.1 55..85 under b); orphan 20..25 names a parent the trace does not hold.
const spans = [
  span('root', null, 1000, 100),
  span('a', 'root', 1010, 30),
  span('b', 'root', 1050, 40),
  span('b1', 'b', 1055, 30),
  span('orphan', 'gone', 1020, 5),
];

test('a span starts at its nanoseconds when it has them', () => {
  assert.equal(startMsOf({ start: 5, startNs: 5_250_000 }), 5.25);
  assert.equal(startMsOf({ start: 5 }), 5);
  assert.equal(traceStartMs({ spans }), 1000);
  assert.equal(traceStartMs({ spans: [], start: 42 }), 42);
});

test('a trace with more spans than a call takes arguments still has a start', () => {
  const many = Array.from({ length: 200_000 }, (_, i) => ({ start: i + 7 }));
  assert.equal(traceStartMs({ spans: many }), 7);
});

test('a span whose parent is missing is a root', () => {
  const tree = spanTree(spans);
  assert.deepEqual(tree.roots.map((s) => s.spanId), ['root', 'orphan']);
  assert.deepEqual(tree.children.get('root').map((s) => s.spanId), ['a', 'b']);
});

test('flattenTree walks depth first and skips what is under a collapsed span', () => {
  const tree = spanTree(spans);
  assert.deepEqual(flattenTree(tree).map((r) => r.span.spanId + ':' + r.depth), ['root:0', 'a:1', 'b:1', 'b1:2', 'orphan:0']);
  const collapsed = flattenTree(tree, new Set(['b']));
  assert.deepEqual(collapsed.map((r) => r.span.spanId), ['root', 'a', 'b', 'orphan']);
  assert.equal(collapsed[2].hasChildren, true);
  assert.equal(collapsed[1].hasChildren, false);
});

test('self time is elapsed less the time the direct children cover', () => {
  const self = selfTimes(spans);
  assert.equal(self.get('root'), 30);
  assert.equal(self.get('b'), 10);
  assert.equal(self.get('b1'), 30);
});

test('children that run at once take their overlap from the parent once', () => {
  const self = selfTimes([span('p', null, 0, 100), span('a', 'p', 10, 60), span('b', 'p', 20, 60)]);
  assert.equal(self.get('p'), 30);
  const same = selfTimes([span('p', null, 0, 10), span('c1', 'p', 0, 8), span('c2', 'p', 0, 8)]);
  assert.equal(same.get('p'), 2);
});

test('a child that outlives its parent takes only the part inside it', () => {
  const self = selfTimes([span('p', null, 100, 50), span('early', 'p', 90, 20), span('late', 'p', 140, 30)]);
  assert.equal(self.get('p'), 30);
  assert.equal(self.get('late'), 30);
  const outside = selfTimes([span('p', null, 0, 10), span('after', 'p', 20, 5)]);
  assert.equal(outside.get('p'), 10);
});

// A chain 10,000 spans deep, each the parent of the next.
const chain = Array.from({ length: 10_000 }, (_, i) => span(String(i), i ? String(i - 1) : null, i, 1));

test('flattenTree walks a trace deeper than the call stack', () => {
  const rows = flattenTree(spanTree(chain));
  assert.equal(rows.length, chain.length);
  assert.equal(rows.at(-1).depth, chain.length - 1);
});

test('depthMap measures a trace deeper than the call stack, children listed first', () => {
  const depths = depthMap(chain.toReversed());
  assert.equal(depths.get('0'), 0);
  assert.equal(depths.get(String(chain.length - 1)), chain.length - 1);
});

test('depthMap survives a parent cycle', () => {
  const depths = depthMap([span('x', 'y', 0, 1), span('y', 'x', 0, 1), span('z', 'x', 0, 1)]);
  assert.equal(depths.size, 3);
  assert.ok([...depths.values()].every((d) => Number.isFinite(d)));
  assert.equal(depths.get('z'), depths.get('x') + 1);
});

test('a parent cycle enters the waterfall at its first member, as deep as the profile has it', () => {
  const rowsOf = (list) => flattenTree(spanTree(list)).map((r) => r.span.spanId + ':' + r.depth);
  const depthsOf = (list) => { const d = depthMap(list); return list.map((s) => s.spanId + ':' + d.get(s.spanId)); };
  const selfParent = [span('root', null, 0, 10), span('loop', 'loop', 1, 5), span('kid', 'loop', 2, 1)];
  assert.deepEqual(rowsOf(selfParent), ['root:0', 'loop:0', 'kid:1']);
  assert.deepEqual(depthsOf(selfParent), ['root:0', 'loop:0', 'kid:1']);
  const cycle = [span('root', null, 0, 10), span('x', 'y', 1, 5), span('y', 'x', 2, 3), span('z', 'x', 3, 1)];
  assert.deepEqual(rowsOf(cycle), ['root:0', 'x:0', 'y:1', 'z:1']);
  assert.deepEqual(depthsOf(cycle), ['root:0', 'x:0', 'y:1', 'z:1']);
  assert.equal(selfTimes(cycle).get('y'), 3, 'the member cut from the cycle is no child of the one it named');
});

test('the profile numbers the steps in start order with offsets and gaps', () => {
  const rows = profileRows({ spans, start: 1000 });
  assert.deepEqual(rows.map((r) => r.span.spanId), ['root', 'a', 'orphan', 'b', 'b1']);
  assert.deepEqual(rows.map((r) => r.index), [1, 2, 3, 4, 5]);
  assert.deepEqual(rows.map((r) => r.startOffset), [0, 10, 20, 50, 55]);
  assert.deepEqual(rows.map((r) => r.gap), [0, 10, 10, 30, 5]);
  assert.deepEqual(rows.map((r) => r.depth), [0, 1, 0, 1, 2]);
});

test('the profile sorts by self time or by elapsed, largest first', () => {
  assert.deepEqual(profileRows({ spans }, 'self').map((r) => r.span.spanId).slice(0, 3), ['root', 'a', 'b1']);
  assert.deepEqual(profileRows({ spans }, 'elapsed').map((r) => r.span.spanId), ['root', 'b', 'a', 'b1', 'orphan']);
});

test('at most three hot spans, each with at least 5% of the trace to itself', () => {
  const rows = profileRows({ spans });
  assert.deepEqual([...hotSpanIds(rows, 100)].sort(), ['a', 'b1', 'root']);
  assert.deepEqual([...hotSpanIds(rows, 1000)], []);
  assert.deepEqual([...hotSpanIds(rows, 600)].sort(), ['a', 'b1', 'root']);
  assert.deepEqual([...hotSpanIds(rows, 601)], []);
});

// An N+1: under root, a SELECT of orders, then five SELECTs of product, one with a newline, and a sixth with a child.
const db = (spanId, start, summary, extra = {}) => ({
  spanId, parentSpanId: 'root', start, durationMs: 2, category: 'db', service: 'orders', summary, ...extra,
});
const nPlusOne = [
  { spanId: 'root', parentSpanId: null, start: 0, durationMs: 100, category: 'http', service: 'orders', summary: 'GET /orders/{id}' },
  db('o', 1, 'SELECT orders (h2)'),
  db('p1', 10, 'SELECT product (h2)'),
  db('p2', 20, 'SELECT  product\n(h2)'),
  db('p3', 30, 'SELECT product (h2)', { durationMs: 4 }),
  db('p4', 40, 'SELECT product (h2)'),
  db('p5', 50, 'SELECT product (h2)'),
  db('p6', 60, 'SELECT product (h2)'),
  { spanId: 'p6c', parentSpanId: 'p6', start: 61, durationMs: 1, category: 'internal', service: 'orders', summary: 'fetch' },
];

test('more than three repeated childless siblings fold into one run, as the CLI collapses them (cli.adoc#trace-rendering)', () => {
  const runs = siblingRuns(spanTree(nPlusOne));
  const run = runs.get('p1');
  assert.equal(FOLD_AFTER, 3);
  assert.deepEqual(run.spans.map((s) => s.spanId), ['p1', 'p2', 'p3', 'p4', 'p5'], 'a span with children ends the run');
  assert.equal(run.key, 'run:p1');
  assert.equal(run.count, 5);
  assert.equal(run.totalMs, 12);
  assert.deepEqual([run.start, run.end], [10, 52]);
  assert.equal(runs.get('p5'), run);
  assert.equal(runs.has('o'), false);
  assert.equal(siblingRuns(spanTree(nPlusOne.filter((s) => !['p4', 'p5'].includes(s.spanId)))).size, 0, 'three are not a run');
});

test('flattenTree shows a run as one row and its spans one level deeper when it is expanded', () => {
  const tree = spanTree(nPlusOne);
  const rowsOf = (expanded) => flattenTree(tree, new Set(), expanded).map((r) => (r.run ? r.run.key : r.span.spanId) + ':' + r.depth);
  assert.deepEqual(rowsOf(new Set()), ['root:0', 'o:1', 'run:p1:1', 'p6:1', 'p6c:2']);
  assert.deepEqual(rowsOf(new Set(['run:p1'])), ['root:0', 'o:1', 'run:p1:1', 'p1:2', 'p2:2', 'p3:2', 'p4:2', 'p5:2', 'p6:1', 'p6c:2']);
});

test('the profile folds a run where its first span stands, with the totals of its spans', () => {
  const rows = profileRows({ spans: nPlusOne });
  assert.deepEqual(rows.map((r) => r.key), ['root', 'o', 'run:p1', 'p6', 'p6c']);
  const run = rows[2];
  assert.deepEqual([run.index, run.lastIndex, run.elapsed, run.self, run.startOffset, run.depth], [3, 7, 12, 12, 10, 1]);
  assert.deepEqual(profileRows({ spans: nPlusOne }, 'elapsed').map((r) => r.key).slice(0, 2), ['root', 'run:p1']);
  const open = profileRows({ spans: nPlusOne }, 'start', new Set(['run:p1']));
  assert.deepEqual(open.map((r) => r.key + ':' + r.depth).slice(2, 8), ['run:p1:1', 'p1:2', 'p2:2', 'p3:2', 'p4:2', 'p5:2']);
  assert.ok(hotSpanIds(rows, 100).has('run:p1'), 'a run is hot on its total');
});

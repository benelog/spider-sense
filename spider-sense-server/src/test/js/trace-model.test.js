// trace-model.js: the span tree, self times, depths and the profile of the Trace page (pages.adoc#trace).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  startMsOf, traceStartMs, spanTree, flattenTree, selfTimes, depthMap, profileRows, hotSpanIds,
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

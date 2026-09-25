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

test('self time is elapsed less the direct children, and never below zero', () => {
  const self = selfTimes(spans);
  assert.equal(self.get('root'), 30);
  assert.equal(self.get('b'), 10);
  assert.equal(self.get('b1'), 30);
  const overlapping = selfTimes([span('p', null, 0, 10), span('c1', 'p', 0, 8), span('c2', 'p', 0, 8)]);
  assert.equal(overlapping.get('p'), 0);
});

test('depthMap survives a parent cycle', () => {
  const depths = depthMap([span('x', 'y', 0, 1), span('y', 'x', 0, 1), span('z', 'x', 0, 1)]);
  assert.equal(depths.size, 3);
  assert.ok([...depths.values()].every((d) => Number.isFinite(d)));
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

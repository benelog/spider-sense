// pages/map.js: the columns of the service map and the curve of an edge (pages.adoc#map).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { layout, edgeCurve, crosses, curveBounds, cubicAt, curvePath } from '../../main/resources/public/assets/js/pages/map.js';

const node = (id, kind, name = id) => ({ id, kind, name });

test('layout puts the user first, services by their longest path, externals last', () => {
  const nodes = [node('db:x', 'db'), node('svc:c', 'service'), node('svc:a', 'service'), node('user', 'user'), node('svc:b', 'service')];
  const edges = [
    { from: 'user', to: 'svc:a' }, { from: 'svc:a', to: 'svc:b' }, { from: 'svc:b', to: 'svc:c' },
    { from: 'svc:a', to: 'svc:c' }, { from: 'svc:c', to: 'db:x' },
  ];
  const { positions, columns } = layout(nodes, edges);
  assert.equal(columns, 5);
  assert.deepEqual(['user', 'svc:a', 'svc:b', 'svc:c', 'db:x'].map((id) => positions.get(id).column), [0, 1, 2, 3, 4]);
});

test('layout sorts a column by name and survives a cycle', () => {
  const nodes = [node('svc:b', 'service', 'b'), node('svc:a', 'service', 'a'), node('db:z', 'db', 'z'), node('db:y', 'db', 'y')];
  const edges = [{ from: 'svc:a', to: 'svc:b' }, { from: 'svc:b', to: 'svc:a' }];
  const { positions, height } = layout(nodes, edges);
  assert.ok(positions.get('db:y').y < positions.get('db:z').y);
  assert.equal(positions.get('db:y').column, positions.get('db:z').column);
  assert.ok(height > 0);
});

test('an edge between neighbouring columns is a plain curve', () => {
  const a = { x: 22, y: 20, column: 0 }, b = { x: 282, y: 20, column: 1 };
  const curve = edgeCurve(a, b, []);
  assert.equal(curve.c1.y, curve.p0.y);
  assert.equal(curve.c2.y, curve.p3.y);
  assert.equal(curvePath(curve), 'M222,52 C262,52 242,52 282,52');
});

test('an edge that skips columns bows around the nodes in between', () => {
  const a = { x: 282, y: 20, column: 1 };
  const b = { x: 1062, y: 20, column: 4 };
  const between = [{ x: 542, y: 20, column: 2 }, { x: 802, y: 20, column: 3 }];
  assert.ok(crosses(edgeCurve(a, b, []), between));
  const curve = edgeCurve(a, b, between);
  assert.equal(crosses(curve, between), false);
  assert.ok(curve.c1.y > curve.p0.y, 'bows below a row of blockers level with it');
  const box = curveBounds(curve);
  assert.ok(box.maxY > 20 + 64);
});

/** Is the point inside the 200x64 node at `o`? */
const inside = (p, o) => p.x > o.x && p.x < o.x + 200 && p.y > o.y && p.y < o.y + 64;

test('a cycle opens no empty column and does not depend on the order the API lists the services', () => {
  for (const order of [['svc:b', 'svc:a'], ['svc:a', 'svc:b']]) {
    const nodes = [node('user', 'user'), ...order.map((id) => node(id, 'service')), node('db:x', 'db')];
    const edges = [{ from: 'user', to: 'svc:a' }, { from: 'svc:a', to: 'svc:b' }, { from: 'svc:b', to: 'svc:a' }, { from: 'svc:b', to: 'db:x' }];
    const { positions, columns } = layout(nodes, edges);
    assert.equal(columns, 4, order.join(','));
    assert.deepEqual(['user', 'svc:a', 'svc:b', 'db:x'].map((id) => positions.get(id).column), [0, 1, 2, 3], order.join(','));
  }
});

test('a cycle with no caller from outside still starts in the first service column', () => {
  const nodes = [node('svc:b', 'service'), node('svc:a', 'service'), node('svc:c', 'service')];
  const edges = [{ from: 'svc:a', to: 'svc:b' }, { from: 'svc:b', to: 'svc:c' }, { from: 'svc:c', to: 'svc:a' }];
  const { positions } = layout(nodes, edges);
  assert.deepEqual(['svc:a', 'svc:b', 'svc:c'].map((id) => positions.get(id).column), [1, 2, 3]);
});

test('an edge back to an earlier column leaves the caller on the left and stays out of both nodes', () => {
  const callee = { x: 282, y: 20, column: 1 }, caller = { x: 542, y: 20, column: 2 };
  const curve = edgeCurve(caller, callee, []);
  assert.equal(curve.p0.x, caller.x, 'leaves the left edge of the caller');
  assert.equal(curve.p3.x, callee.x + 200, 'arrives at the right edge of the callee');
  for (let i = 1; i < 60; i++) {
    const p = cubicAt(curve.p0, curve.c1, curve.c2, curve.p3, i / 60);
    assert.ok(!inside(p, caller) && !inside(p, callee), 'sample ' + i + ' is inside a node');
  }
  const forward = edgeCurve(callee, caller, []);
  assert.notEqual(curve.p0.y, forward.p3.y, 'the back edge does not share an end with the forward one');
  assert.ok(Math.abs(curve.c1.y - curve.p0.y) >= 0.6 * 96, 'it bows off the row');
  const label = cubicAt(curve.p0, curve.c1, curve.c2, curve.p3, 0.4);
  assert.ok(!inside(label, caller) && !inside(label, callee), 'the label is clear of both nodes');
});

test('a back edge that skips a column bows around the node in between', () => {
  const callee = { x: 282, y: 20, column: 1 }, caller = { x: 802, y: 20, column: 3 };
  const middle = { x: 542, y: 20, column: 2 };
  const curve = edgeCurve(caller, callee, [middle]);
  assert.equal(crosses(curve, [middle]), false);
  assert.equal(crosses(curve, [caller, callee], 60, 0.1), false);
});

test('a service calling itself loops off its own node', () => {
  const self = { x: 282, y: 20, column: 1 };
  const curve = edgeCurve(self, self, []);
  for (let i = 1; i < 60; i++) {
    assert.ok(!inside(cubicAt(curve.p0, curve.c1, curve.c2, curve.p3, i / 60), self), 'sample ' + i + ' is inside the node');
  }
});

test('cubicAt starts and ends on the end points', () => {
  const p0 = { x: 0, y: 0 }, p3 = { x: 10, y: 5 };
  assert.deepEqual(cubicAt(p0, { x: 3, y: 9 }, { x: 7, y: 9 }, p3, 0), p0);
  assert.deepEqual(cubicAt(p0, { x: 3, y: 9 }, { x: 7, y: 9 }, p3, 1), p3);
});

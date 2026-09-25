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

test('cubicAt starts and ends on the end points', () => {
  const p0 = { x: 0, y: 0 }, p3 = { x: 10, y: 5 };
  assert.deepEqual(cubicAt(p0, { x: 3, y: 9 }, { x: 7, y: 9 }, p3, 0), p0);
  assert.deepEqual(cubicAt(p0, { x: 3, y: 9 }, { x: 7, y: 9 }, p3, 1), p3);
});

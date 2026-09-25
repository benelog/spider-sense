// ui.svgElement(): the one SVG builder behind the icons, the sparklines and the service map.
import './fake-dom.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { svgElement, icon } from '../../main/resources/public/assets/js/ui.js';
import { sparkline } from '../../main/resources/public/assets/js/charts.js';

const attrs = (node) => [...node.attributes.entries()];

test('attributes are set in the order given, and undefined, null and false are left out', () => {
  const node = svgElement('rect', { x: 0, y: 2, rx: undefined, ry: null, hidden: false, class: 'box' });
  assert.equal(node.tagName.toLowerCase(), 'rect');
  assert.deepEqual(attrs(node), [['x', '0'], ['y', '2'], ['class', 'box']]);
});

test('children may be nodes, text or arrays of them', () => {
  const node = svgElement('text', {}, ['a', svgElement('tspan', {}, 'b')], null, 'c');
  assert.equal(node.textContent, 'abc');
  assert.equal(node.childNodes.length, 3);
});

test('an icon uses the sprite symbol of its name', () => {
  const node = icon('trace', 'big');
  assert.deepEqual(attrs(node), [['class', 'icon big'], ['viewBox', '0 0 16 16'], ['aria-hidden', 'true'], ['focusable', 'false']]);
  assert.deepEqual(attrs(node.firstChild), [['href', '#i-trace']]);
});

test('a sparkline draws an area and a line, or a flat line under two values', () => {
  const node = sparkline([1, 3, 2], { width: 100, height: 20, label: 'Calls' });
  assert.deepEqual(attrs(node), [['class', 'sparkline'], ['viewBox', '0 0 100 20'], ['width', '100'], ['height', '20'], ['role', 'img'], ['aria-label', 'Calls']]);
  assert.deepEqual(node.childNodes.map((c) => c.getAttribute('class')), ['spark-area', 'spark-line']);
  const flat = sparkline([5]);
  assert.deepEqual(attrs(flat.firstChild), [['x1', '0'], ['x2', '120'], ['y1', '27'], ['y2', '27'], ['class', 'spark-flat']]);
});

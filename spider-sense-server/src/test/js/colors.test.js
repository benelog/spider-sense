// ui.js: a stable colour per service, in first-seen order, and in the CSS form an element
// takes, so a theme flip recolours it (ui.adoc#palette).
import './fake-dom.js';
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { serviceColor, serviceVar, seriesColor, seedServices, resetServiceColors, serviceChip } from '../../main/resources/public/assets/js/ui.js';
import { cssColor, legend, sparkline } from '../../main/resources/public/assets/js/charts.js';

beforeEach(() => resetServiceColors());

test('services take the series colours in the order they are first seen', () => {
  assert.equal(serviceColor('orders'), seriesColor(0));
  assert.equal(serviceColor('books'), seriesColor(1));
  assert.equal(serviceColor('orders'), seriesColor(0), 'a service keeps its colour');
});

test('seeding follows the order given, not the order of later lookups', () => {
  seedServices(['books', 'orders']);
  assert.equal(serviceColor('orders'), seriesColor(1));
  assert.equal(serviceColor('books'), seriesColor(0));
});

test('the ninth service wraps around to the first colour', () => {
  seedServices(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']);
  assert.equal(serviceColor('i'), serviceColor('a'));
});

test('no service is drawn in the second colour', () => {
  assert.equal(serviceColor(''), seriesColor(1));
});

test('a reset starts the order over', () => {
  serviceColor('orders');
  resetServiceColors();
  assert.equal(serviceColor('books'), seriesColor(0));
});

test('an element takes a service\'s colour as the variable of the same slot', () => {
  seedServices(['orders', 'books']);
  assert.equal(serviceVar('books'), 'var(--series-2)');
  assert.equal(serviceVar('users'), 'var(--series-3)', 'a new service takes the next slot');
  assert.equal(serviceColor('users'), seriesColor(2));
  assert.equal(serviceVar(''), 'var(--series-2)', 'as serviceColor, no service is the second colour');
});

test('the dots, swatches and sparklines in the page follow the theme without a repaint', () => {
  seedServices(['orders']);
  assert.equal(serviceChip('orders').children[0].style.background, 'var(--series-1)', 'a service chip\'s dot');
  const swatches = legend([{ label: 'p95', color: 'accent' }, { label: 'a pool', color: 'series3' }, { label: 'calls' }]).children
    .map((item) => item.children[0].style.background);
  assert.deepEqual(swatches, ['var(--accent)', 'var(--series-3)', 'var(--silk)'], 'a legend\'s swatches');
  const line = sparkline([1, 3, 2], { color: serviceVar('orders') }).children.find((c) => c.getAttribute('class') === 'spark-line');
  assert.equal(line.style.stroke, 'var(--series-1)', 'a sparkline, in a style the stylesheet does not override');
  assert.equal(cssColor('#123456'), '#123456', 'a literal colour is left as it is');
});

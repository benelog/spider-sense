// ui.js: a stable colour per service, in first-seen order (ui.adoc#palette).
import './fake-dom.js';
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { serviceColor, seriesColor, seedServices, resetServiceColors } from '../../main/resources/public/assets/js/ui.js';

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

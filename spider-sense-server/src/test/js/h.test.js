// ui.h(): a `class` attribute adds to the spec's classes.
import './fake-dom.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { h, chip, statusChip, panel, stat, durationBar } from '../../main/resources/public/assets/js/ui.js';

test('a class attribute adds to the spec classes, each class once', () => {
  assert.equal(h('span.a.b', { class: 'c' }).className, 'a b c');
  assert.equal(h('span.a', { class: 'a b' }).className, 'a b');
  assert.equal(h('span', { class: 'x y' }).className, 'x y');
});

test('an array class drops its empty entries, and an empty or absent class leaves the spec classes', () => {
  assert.equal(h('div.row', { class: ['slow', false, null, 'selected'] }).className, 'row slow selected');
  assert.equal(h('div.row', { class: [false, false] }).className, 'row');
  assert.equal(h('div.row', { class: '' }).className, 'row');
  assert.equal(h('div.row', { class: null }).className, 'row');
});

test('the shared widgets keep their base class beside the one they are given', () => {
  assert.equal(chip('x', { class: 'chip-state' }).className, 'chip chip-state');
  assert.equal(chip('x').className, 'chip');
  assert.equal(statusChip(503).className, 'status-code bad');
  assert.equal(panel({ class: 'cmp-panel' }).className, 'panel cmp-panel');
  assert.equal(stat('1', 'ms', 'p95', { class: 'is-worse' }).className, 'stat is-worse');
  assert.equal(durationBar(1, 2).className, 'dbar');
});

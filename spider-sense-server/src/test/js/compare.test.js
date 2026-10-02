// pages/compare.js: the tiles' verdict, by the API's rule (marks-and-compare.adoc#verdicts).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verdictOf } from '../../main/resources/public/assets/js/pages/compare.js';

test('a p95 is worse when it grew by more than a fifth and by at least 10 ms', () => {
  assert.equal(verdictOf('p95', 40, 50), 'worse');
  assert.equal(verdictOf('p95', 40, 49.9), 'same');
  assert.equal(verdictOf('p95', 100, 120), 'same');
  assert.equal(verdictOf('p95', 100, 120.1), 'worse');
  assert.equal(verdictOf('p95', 1, 9), 'same');
});

test('a p95 is better when it shrank by the same bounds', () => {
  assert.equal(verdictOf('p95', 50, 40), 'better');
  assert.equal(verdictOf('p95', 49.9, 40), 'same');
});

test('errors are worse when they grew and better when they fell', () => {
  assert.equal(verdictOf('errors', 1, 2), 'worse');
  assert.equal(verdictOf('errors', 2, 1), 'better');
  assert.equal(verdictOf('errors', 2, 2), 'same');
});

test('an Apdex is worse when it fell at the two decimals the tile shows', () => {
  assert.equal(verdictOf('apdex', 0.95, 0.9), 'worse');
  assert.equal(verdictOf('apdex', 0.9, 0.95), 'better');
  assert.equal(verdictOf('apdex', 0.95, 0.948), 'same');
  assert.equal(verdictOf('apdex', 0.925, 0.9205), 'worse', '0.93 → 0.92 is a fall');
  assert.equal(verdictOf('apdex', 0.935, 0.9305), 'worse', '0.94 → 0.93 is a fall');
  assert.equal(verdictOf('apdex', 0.9205, 0.925), 'better', '0.92 → 0.93 is a rise');
  assert.equal(verdictOf('apdex', 0.926, 0.934), 'same', '0.93 → 0.93 is no change, though it moved 0.008');
});

test('a side without data has no verdict', () => {
  assert.equal(verdictOf('p95', null, 50), 'same');
  assert.equal(verdictOf('errors', 3, undefined), 'same');
});

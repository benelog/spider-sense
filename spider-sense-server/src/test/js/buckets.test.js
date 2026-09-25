// buckets.js: the response-time buckets from /api/status, and Apdex (ui.adoc#response-buckets).
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { state } from '../../main/resources/public/assets/js/api.js';
import {
  bucketBounds, bucketLabels, apdexClass, fmtApdex, histogramTitle,
} from '../../main/resources/public/assets/js/buckets.js';

afterEach(() => { state.status = null; });

test('the bounds default to 125, 500 and 2000 ms before the status arrives', () => {
  assert.deepEqual(bucketBounds(), [125, 500, 2000]);
  assert.deepEqual(bucketLabels(), ['≤125 ms', '≤500 ms', '≤2 s', '>2 s', 'error']);
});

test('the bounds come from the status thresholds', () => {
  state.status = { thresholds: { responseBucketsMs: [250, 1000, 4000] } };
  assert.deepEqual(bucketLabels(), ['≤250 ms', '≤1 s', '≤4 s', '>4 s', 'error']);
  state.status = { thresholds: { responseBucketsMs: [75, 300, 1500] } };
  assert.deepEqual(bucketLabels(), ['≤75.0 ms', '≤300 ms', '≤1.5 s', '>1.5 s', 'error']);
});

test('malformed bounds fall back to the default', () => {
  state.status = { thresholds: { responseBucketsMs: [1, 2] } };
  assert.deepEqual(bucketBounds(), [125, 500, 2000]);
  state.status = { thresholds: { responseBucketsMs: [0, 2, 3] } };
  assert.deepEqual(bucketBounds(), [125, 500, 2000]);
});

test('apdexClass warns under 0.85 and is bad under 0.7', () => {
  assert.equal(apdexClass(null), '');
  assert.equal(apdexClass(0.85), '');
  assert.equal(apdexClass(0.849), 'is-warn');
  assert.equal(apdexClass(0.7), 'is-warn');
  assert.equal(apdexClass(0.699), 'is-bad');
});

test('fmtApdex has two decimals and a dash for no request', () => {
  assert.equal(fmtApdex(0.934), '0.93');
  assert.equal(fmtApdex(1), '1.00');
  assert.equal(fmtApdex(null), '-');
});

test('histogramTitle names each bucket with its count and share', () => {
  assert.equal(histogramTitle([3, 1, 0, 0, 0]),
    '≤125 ms: 3 (75.0%)\n≤500 ms: 1 (25.0%)\n≤2 s: 0 (0.0%)\n>2 s: 0 (0.0%)\nerror: 0 (0.0%)');
  assert.equal(histogramTitle(null), '≤125 ms: 0\n≤500 ms: 0\n≤2 s: 0\n>2 s: 0\nerror: 0');
});

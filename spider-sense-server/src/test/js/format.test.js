// format.js: the rules of ui.adoc#numbers-and-times.
process.env.TZ = 'UTC';

import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fmt from '../../main/resources/public/assets/js/format.js';

test('count uses thousands separators and rounds', () => {
  assert.equal(fmt.count(0), '0');
  assert.equal(fmt.count(1234567), '1,234,567');
  assert.equal(fmt.count(2.6), '3');
  assert.equal(fmt.count(null), '-');
  assert.equal(fmt.count(NaN), '-');
});

test('dur has one decimal under 100 ms, none above, and seconds from 10 s', () => {
  assert.equal(fmt.dur(0), '0.0 ms');
  assert.equal(fmt.dur(12.34), '12.3 ms');
  assert.equal(fmt.dur(99.94), '99.9 ms');
  assert.equal(fmt.dur(100), '100 ms');
  assert.equal(fmt.dur(1234.5), '1,235 ms');
  assert.equal(fmt.dur(9999), '9,999 ms');
  assert.equal(fmt.dur(10000), '10.0 s');
  assert.equal(fmt.dur(65432), '65.4 s');
  assert.equal(fmt.dur(undefined), '-');
});

test('durBare drops the unit but keeps the seconds suffix', () => {
  assert.equal(fmt.durBare(12.34), '12.3');
  assert.equal(fmt.durBare(250), '250');
  assert.equal(fmt.durBare(12000), '12.0s');
});

test('rate, pct and bytes', () => {
  assert.equal(fmt.rate(1.5), '1.50');
  assert.equal(fmt.pct(0.0125), '1.3%');
  assert.equal(fmt.pct(0), '0.0%');
  assert.equal(fmt.bytes(512), '512 B');
  assert.equal(fmt.bytes(1536), '1.5 KiB');
  assert.equal(fmt.bytes(200 * 1024 * 1024), '200 MiB');
});

test('rel counts back from now in the largest whole unit', () => {
  const now = 1_000_000_000_000;
  assert.equal(fmt.rel(now, now), 'now');
  assert.equal(fmt.rel(now + 5000, now), 'now');
  assert.equal(fmt.rel(now - 12_000, now), '12 s ago');
  assert.equal(fmt.rel(now - 59_000, now), '59 s ago');
  assert.equal(fmt.rel(now - 60_000, now), '1 min ago');
  assert.equal(fmt.rel(now - 3 * 3600_000, now), '3 h ago');
  assert.equal(fmt.rel(now - 3 * 86400_000, now), '3 d ago');
  assert.equal(fmt.rel(null, now), '-');
});

test('time is a clock within the day of now and names the day otherwise', () => {
  const now = Date.UTC(2026, 8, 25, 12, 0, 0);
  assert.equal(fmt.time(Date.UTC(2026, 8, 25, 9, 5, 7), now), '09:05:07');
  assert.equal(fmt.time(Date.UTC(2026, 8, 24, 23, 59, 59), now), 'Sep 24 23:59:59');
  assert.equal(fmt.time(null, now), '-');
  assert.equal(fmt.timeMs(Date.UTC(2026, 8, 25, 9, 5, 7, 42), now), '09:05:07.042');
});

test('full, clock and offset', () => {
  assert.equal(fmt.full(Date.UTC(2026, 0, 2, 3, 4, 5, 6)), 'Jan 2 2026 03:04:05.006');
  assert.equal(fmt.clock(Date.UTC(2026, 0, 2, 3, 4, 5)), '03:04:05');
  assert.equal(fmt.clockShort(Date.UTC(2026, 0, 2, 3, 4, 5)), '03:04');
  assert.equal(fmt.offset(12.34), '+12.3 ms');
  assert.equal(fmt.offset(-5), '-5.0 ms');
});

test('shortId, splitType and truncate', () => {
  assert.equal(fmt.shortId('0123456789abcdef'), '01234567');
  assert.equal(fmt.shortId('abc'), 'abc');
  assert.equal(fmt.shortId(''), '-');
  assert.deepEqual(fmt.splitType('java.lang.IllegalStateException'), { pkg: 'java.lang.', name: 'IllegalStateException' });
  assert.deepEqual(fmt.splitType('Plain'), { pkg: '', name: 'Plain' });
  assert.deepEqual(fmt.splitType(null), { pkg: '', name: '' });
  assert.equal(fmt.truncate('abcdef', 4), 'abc…');
  assert.equal(fmt.truncate('abcd', 4), 'abcd');
  assert.equal(fmt.truncate(null, 4), '');
});

test('apdex has two decimals and a dash for no request', () => {
  assert.equal(fmt.apdex(0.934), '0.93');
  assert.equal(fmt.apdex(1), '1.00');
  assert.equal(fmt.apdex(null), '-');
});

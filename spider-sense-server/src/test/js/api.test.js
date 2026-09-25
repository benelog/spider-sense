// api.js: the window a range implies, and the parameters every read sends.
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  state, windowFor, params, rangeOf, rangeIndex, requestSequence, sharedQuery, compactQuery, queryString, DEFAULT_RANGE,
} from '../../main/resources/public/assets/js/api.js';

const NOW = 1_700_000_000_000;

afterEach(() => {
  Object.assign(state, { service: '', range: '15m', live: false, chart: '', status: null });
});

test('windowFor ends now and starts the range before it', () => {
  assert.deepEqual(windowFor('5m', NOW), { from: NOW - 5 * 60_000, to: NOW });
  assert.deepEqual(windowFor('1h', NOW), { from: NOW - 3600_000, to: NOW });
  assert.deepEqual(windowFor('nonsense', NOW), { from: NOW - 15 * 60_000, to: NOW });
});

test('windowFor all starts at the oldest span, and is the last 15 minutes on an empty store', () => {
  assert.deepEqual(windowFor('all', NOW), { from: NOW - 15 * 60_000, to: NOW });
  state.status = { oldest: { span: 0 } };
  assert.deepEqual(windowFor('all', NOW), { from: NOW - 15 * 60_000, to: NOW });
  state.status = { oldest: { span: NOW - 86400_000 } };
  assert.deepEqual(windowFor('all', NOW), { from: NOW - 86400_000, to: NOW });
});

test('rangeOf and rangeIndex fall back to the default range', () => {
  assert.equal(rangeOf('6h').id, '6h');
  assert.equal(rangeOf('x').id, '15m');
  assert.equal(rangeIndex('5m'), 0);
  assert.equal(rangeIndex('x'), 1);
});

test('params carries the window and the top bar service, and drops empty extras', () => {
  const window = { from: 1, to: 2 };
  assert.deepEqual(params({ q: 'x', empty: '', none: null, gone: undefined, zero: 0 }, { window }), { from: 1, to: 2, q: 'x', zero: 0 });
  state.service = 'orders';
  assert.deepEqual(params({}, { window }), { from: 1, to: 2, service: 'orders' });
  assert.deepEqual(params({}, { window, service: 'books' }), { from: 1, to: 2, service: 'books' });
  assert.deepEqual(params({}, { window, service: null }), { from: 1, to: 2 });
});

test('sharedQuery leaves out what is the default', () => {
  assert.deepEqual(sharedQuery(), {});
  Object.assign(state, { service: 'orders', range: '1h', live: true, chart: 'load' });
  assert.deepEqual(sharedQuery(), { service: 'orders', range: '1h', live: '1', chart: 'load' });
});

test('requestSequence keeps only the newest request current', () => {
  const startRequest = requestSequence();
  const first = startRequest();
  assert.equal(first(), true);
  const second = startRequest();
  assert.equal(first(), false);
  assert.equal(second(), true);
});

test('compactQuery keeps only the entries that carry a value', () => {
  assert.deepEqual(compactQuery({ a: 'x', b: '', c: null, d: undefined, e: false, f: 0, g: true }), { a: 'x', f: 0, g: true });
  assert.deepEqual(compactQuery(null), {});
  assert.equal(queryString({ q: 'a b', empty: '', n: 3 }), 'q=a+b&n=3');
});

test('the default range is the 15 minutes the top bar starts on', () => {
  assert.equal(DEFAULT_RANGE, '15m');
  assert.equal(state.range, DEFAULT_RANGE);
});

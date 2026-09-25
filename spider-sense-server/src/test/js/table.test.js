// ui.table(): rows by key, setRows, keyboard activation and detail rows (ui.adoc#accessibility).
import './fake-dom.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { table, sortFromQuery, nextSort } from '../../main/resources/public/assets/js/ui.js';

const columns = [
  { key: 'name', label: 'Name', render: (r) => r.name },
  { key: 'n', label: 'N', align: 'right', render: (r, i) => String(i + 1) },
];
const rowsOf = (node) => node.all('tbody')[0].children;

test('setRows renders the rows and reuses a row by its key', () => {
  const node = table(columns, { rowKey: (r) => r.id, rows: [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }] });
  const [first] = rowsOf(node);
  assert.equal(first.textContent, 'A1');
  node.setRows([{ id: 'c', name: 'C' }, { id: 'a', name: 'A2' }]);
  const rows = rowsOf(node);
  assert.deepEqual(rows.map((r) => r.textContent), ['C1', 'A22']);
  assert.equal(rows[1], first, 'the row keyed a is the same node');
  assert.equal(rows[0].all('td')[1].className, 'right');
});

test('an empty table says so, in the words it was given', () => {
  let window = 'in the last 15 min';
  const node = table(columns, { empty: () => 'Nothing ' + window + '.' });
  assert.equal(rowsOf(node)[0].textContent, 'Nothing in the last 15 min.');
  window = 'in all the data';
  node.setRows([]);
  assert.equal(rowsOf(node)[0].textContent, 'Nothing in all the data.');
  node.setRows([{ name: 'x' }]);
  assert.equal(rowsOf(node).length, 1);
  assert.equal(rowsOf(node)[0].className, '');
});

test('a clickable row answers Enter and Space on itself, not on a control inside it', () => {
  const opened = [];
  const node = table(columns, { rows: [{ name: 'A' }], onRowClick: (r) => opened.push(r.name) });
  const [row] = rowsOf(node);
  assert.equal(row.getAttribute('tabindex'), '0');
  assert.ok(row.classList.contains('clickable'));
  row.dispatch('keydown', { key: 'Enter' });
  row.dispatch('keydown', { key: ' ' });
  row.dispatch('keydown', { key: 'x' });
  assert.deepEqual(opened, ['A', 'A']);
  const link = document.createElement('a');
  row.all('td')[0].appendChild(link);
  link.dispatch('keydown', { key: 'Enter' });
  link.click();
  assert.deepEqual(opened, ['A', 'A']);
});

test('a detail row opens and closes under its row, by click and by keyboard', () => {
  const expanded = new Set();
  const node = table(columns, {
    rowKey: (r) => r.id, expanded, detailClass: 'log-detail',
    detail: (r) => 'detail of ' + r.name,
    rows: [{ id: 1, name: 'A' }, { id: 2, name: 'B' }],
  });
  let rows = rowsOf(node);
  assert.equal(rows[0].getAttribute('aria-expanded'), 'false');
  rows[0].dispatch('keydown', { key: 'Enter' });
  rows = rowsOf(node);
  assert.deepEqual(rows.map((r) => r.textContent), ['A1', 'detail of A', 'B2']);
  assert.equal(rows[0].getAttribute('aria-expanded'), 'true');
  assert.equal(rows[1].className, 'log-detail');
  assert.equal(rows[1].all('td')[0].getAttribute('colspan'), '2');
  assert.deepEqual([...expanded], ['1']);
  rows[0].click();
  assert.deepEqual(rowsOf(node).map((r) => r.textContent), ['A1', 'B2']);
});

test('an open detail row survives new rows and is rebuilt only when its key changes', () => {
  let built = 0;
  const node = table(columns, {
    rowKey: (r) => r.id, detailKey: (r) => r.version,
    detail: (r) => { built++; return 'v' + r.version; },
    rows: [{ id: 1, name: 'A', version: 1 }],
  });
  rowsOf(node)[0].click();
  const detail = rowsOf(node)[1];
  node.setRows([{ id: 1, name: 'A', version: 1 }]);
  assert.equal(rowsOf(node)[1], detail);
  assert.equal(built, 1);
  node.setRows([{ id: 1, name: 'A', version: 2 }]);
  assert.equal(rowsOf(node)[1].textContent, 'v2');
  assert.equal(built, 2);
});

test('a segmented switch presses one button and reports a change of choice only', async () => {
  const { segmented } = await import('../../main/resources/public/assets/js/ui.js');
  const chosen = [];
  const node = segmented({ label: 'Chart mode', options: [['requests', 'Requests'], ['load', 'Load']], value: 'load', onChange: (v) => chosen.push(v) });
  const [requests, load] = node.all('button');
  assert.equal(node.getAttribute('role'), 'group');
  assert.equal(node.getAttribute('aria-label'), 'Chart mode');
  assert.deepEqual([requests.getAttribute('aria-pressed'), load.getAttribute('aria-pressed')], ['false', 'true']);
  load.click();
  requests.click();
  assert.deepEqual(chosen, ['requests']);
  assert.equal(node.value(), 'requests');
  node.set('load');
  assert.deepEqual(chosen, ['requests']);
  assert.equal(load.getAttribute('aria-pressed'), 'true');
});

test('a sort comes from the hash query, and a second click on its header turns it', () => {
  assert.deepEqual(sortFromQuery({}, 'requests'), { key: 'requests', dir: 'desc' });
  assert.deepEqual(sortFromQuery({ sort: 'p95Ms', dir: 'asc' }, 'requests'), { key: 'p95Ms', dir: 'asc' });
  const sort = { key: 'p95Ms', dir: 'desc' };
  assert.deepEqual(nextSort(sort, 'p95Ms'), { key: 'p95Ms', dir: 'asc' });
  assert.deepEqual(nextSort({ key: 'p95Ms', dir: 'asc' }, 'p95Ms'), { key: 'p95Ms', dir: 'desc' });
  assert.deepEqual(nextSort(sort, 'name'), { key: 'name', dir: 'desc' });
});

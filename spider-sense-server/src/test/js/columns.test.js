// columns.js and buckets.apdexCell: the cells one API shape gets on every page.
import './browser-env.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  statementColumn, errorTypeColumn, messageColumn, seenColumn, durationColumn, countColumn, fitTable, endpointCell,
} from '../../main/resources/public/assets/js/columns.js';
import { apdexCell } from '../../main/resources/public/assets/js/buckets.js';

test('statementColumn shows one line cut at its length, the whole statement in the title', () => {
  const cell = statementColumn(20).render({ statement: 'select a, b from orders where id = ?' });
  assert.equal(cell.textContent.length, 20);
  assert.equal(cell.getAttribute('title'), 'select a, b from orders where id = ?');
});

test('statementColumn says in the title when the store may have cut the statement', () => {
  const cell = statementColumn(20).render({ statement: 'select a from t where id in (?,?', statementCut: true });
  assert.equal(cell.getAttribute('title'), 'select a from t where id in (?,?… (cut at 2,000 characters)');
});

test('errorTypeColumn mutes the package, or shows the simple name alone', () => {
  const row = { type: 'java.lang.IllegalStateException' };
  const split = errorTypeColumn({ width: '260px' });
  assert.equal(split.width, '260px');
  assert.equal(split.cls, null);
  const cell = split.render(row);
  assert.equal(cell.children[0].textContent, 'java.lang.');
  assert.equal(cell.textContent, 'java.lang.IllegalStateException');
  assert.equal(errorTypeColumn().cls, 'wide');
  const short = errorTypeColumn({ short: true }).render(row);
  assert.equal(short.textContent, 'IllegalStateException');
  assert.equal(short.getAttribute('title'), 'java.lang.IllegalStateException');
});

test('the value columns align right and format their key', () => {
  assert.equal(durationColumn('p95Ms', 'p95').render({ p95Ms: 12.34 }), '12.3 ms');
  assert.equal(countColumn('calls', 'Calls').render({ calls: 1234 }), '1,234');
  assert.equal(countColumn('calls', 'Calls').align, 'right');
  assert.equal(messageColumn(5).render({ message: 'abcdefgh' }).textContent, 'abcd…');
  const seen = seenColumn('firstSeen', 'First seen');
  assert.equal(seen.key, 'firstSeen');
  assert.equal(seen.render({ firstSeen: Date.now() }).textContent, 'now');
});

test('a factory leaves sortable to the table unless told', () => {
  assert.equal('sortable' in durationColumn('p50Ms', 'p50'), false);
  assert.equal(seenColumn('lastSeen', 'Last seen', '92px', { sortable: false }).sortable, false);
});

test('apdexCell grades as apdexClass does', () => {
  assert.equal(apdexCell(0.95).className, '');
  assert.equal(apdexCell(0.8).className, 'warned');
  assert.equal(apdexCell(0.5).className, 'bad');
  assert.equal(apdexCell(0.5).textContent, '0.50');
  assert.equal(apdexCell(0.95, 'b').tagName, 'B');
});

test('fitTable fixes the layout and keeps the wide column at least its minimum (ui.adoc#narrow-screens)', () => {
  const wrap = document.createElement('div');
  wrap.appendChild(document.createElement('table'));
  const columns = [statementColumn(), durationColumn('avgMs', 'avg', '70px'), countColumn('calls', 'Calls', '54px')];
  assert.equal(fitTable(wrap, columns, 200), wrap);
  assert.ok(wrap.classList.contains('table-fit'));
  assert.equal(wrap.querySelector('table').style.minWidth, '324px');
});

test('endpointCell links an endpoint with a page and leaves the others plain (api.adoc#callers)', () => {
  const linked = endpointCell('GET /orders/{id}', 'abc123');
  assert.equal(linked.tagName, 'A');
  assert.equal(linked.getAttribute('href'), '#/endpoints/abc123');
  const plain = endpointCell('(no endpoint)', null);
  assert.equal(plain.tagName, 'SPAN');
  assert.equal(plain.textContent, '(no endpoint)');
});

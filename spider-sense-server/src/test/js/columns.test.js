// columns.js and buckets.apdexCell: the cells one API shape gets on every page.
import './fake-dom.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  statementColumn, errorTypeColumn, messageColumn, seenColumn, durationColumn, countColumn,
} from '../../main/resources/public/assets/js/columns.js';
import { apdexCell } from '../../main/resources/public/assets/js/buckets.js';

test('statementColumn shows one line cut at its length, the whole statement in the title', () => {
  const cell = statementColumn(20).render({ statement: 'select a, b from orders where id = ?' });
  assert.equal(cell.textContent.length, 20);
  assert.equal(cell.getAttribute('title'), 'select a, b from orders where id = ?');
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

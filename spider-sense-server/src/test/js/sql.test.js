// sql.js: the statement pretty-printer and its one-line form.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatSql, oneLineSql } from '../../main/resources/public/assets/js/sql.js';

test('formatSql puts each major clause on its own line, joins included', () => {
  assert.equal(
    formatSql('select a.id, b.name from orders a left join books b on a.book_id = b.id where a.status = ? order by a.id desc limit ?'),
    'SELECT a.id, b.name\nFROM orders a\nLEFT JOIN books b ON a.book_id = b.id\nWHERE a.status = ?\nORDER BY a.id DESC\nLIMIT ?');
});

test('formatSql indents AND and OR, but keeps the AND of a BETWEEN on its line', () => {
  assert.equal(
    formatSql('select * from t where a = ? and b between ? and ? or c = ?'),
    'SELECT *\nFROM t\nWHERE a = ?\n  AND b BETWEEN ? AND ?\n  OR c = ?');
});

test('formatSql leaves a subquery in its parentheses on one line', () => {
  assert.equal(
    formatSql('SELECT count(*) FROM t WHERE x IN (SELECT y FROM u WHERE z = ?) OR w = ?'),
    'SELECT COUNT(*)\nFROM t\nWHERE x IN (SELECT y FROM u WHERE z = ?)\n  OR w = ?');
});

test('formatSql glues a call to its name and keeps the space after VALUES', () => {
  assert.equal(formatSql('insert into book (id, title) values (?, ?)'), 'INSERT INTO book(id, title)\nVALUES (?, ?)');
});

test('formatSql keeps comments and starts over after a semicolon', () => {
  assert.equal(formatSql('/* hint */ select 1 from dual'), '/* hint */\nSELECT 1\nFROM dual');
  assert.equal(
    formatSql('update t set a = ? where id = ?; delete from t where id = ?'),
    'UPDATE t\nSET a = ?\nWHERE id = ?;\nDELETE FROM t\nWHERE id = ?');
});

test('formatSql leaves string literals as they are', () => {
  assert.equal(formatSql("select 'from where' from t"), "SELECT 'from where'\nFROM t");
});

test('oneLineSql collapses whitespace, uppercases keywords and cuts at max', () => {
  assert.equal(oneLineSql('select   a\n  from t\twhere x = ?'), 'SELECT a FROM t WHERE x = ?');
  assert.equal(oneLineSql('insert into book (id) values (?)'), 'INSERT INTO book(id) VALUES (?)');
  const cut = oneLineSql('select a.id, b.name from orders a left join books b on a.book_id = b.id', 30);
  assert.equal(cut.length, 30);
  assert.ok(cut.endsWith('…'));
});

test('an empty statement formats as nothing', () => {
  assert.equal(formatSql(''), '');
  assert.equal(formatSql(null), '');
  assert.equal(oneLineSql(undefined), '');
});

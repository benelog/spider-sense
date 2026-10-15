// sql.js: the statement pretty-printer and its one-line form.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatSql, oneLineSql, storedSql } from '../../main/resources/public/assets/js/sql.js';

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

test('a backtick-quoted name passes through whole, as MySQL writes it', () => {
  assert.equal(formatSql('select `id` from `orders` where `id` = ?'), 'SELECT `id`\nFROM `orders`\nWHERE `id` = ?');
  assert.equal(oneLineSql('select o.`order id` from `orders` o'), 'SELECT o.`order id` FROM `orders` o');
});

// Each run of the printed text without whitespace is one or more whole runs of the statement's:
// the printer never puts a space into what the statement wrote together.
function assertNothingSplit(sql, printed) {
  const source = sql.toLowerCase().split(/\s+/).filter(Boolean);
  let k = 0;
  for (const run of printed.toLowerCase().split(/\s+/).filter(Boolean)) {
    let joined = '';
    while (joined.length < run.length && k < source.length) joined += source[k++];
    assert.equal(joined, run, `${JSON.stringify(run)} in ${JSON.stringify(printed)}`);
  }
  assert.equal(k, source.length, `the whole of ${JSON.stringify(sql)} in ${JSON.stringify(printed)}`);
}

test('an operator, a cast or a placeholder stays whole, with a space only where the statement had one', () => {
  assert.equal(
    formatSql('select o.status, sum(o.total) from orders o where o.created_at >= :since and o.kind != ? and o.state <> ?'),
    'SELECT o.status, SUM(o.total)\nFROM orders o\nWHERE o.created_at >= :since\n  AND o.kind != ?\n  AND o.state <> ?');
  assert.equal(
    formatSql('select b1_0.id,b1_0.title from book b1_0 where b1_0.price>=? and b1_0.price<=? and b1_0.status<>?'),
    'SELECT b1_0.id,b1_0.title\nFROM book b1_0\nWHERE b1_0.price>=?\n  AND b1_0.price<=?\n  AND b1_0.status<>?');
  assert.equal(
    oneLineSql("select first_name || ' ' || last_name, created_at::date, doc->>'name', a<=>b from author"),
    "SELECT first_name || ' ' || last_name, created_at::date, doc->>'name', a<=>b FROM author");
  assert.equal(
    oneLineSql('select * from orders where id = $1 and status = %s and kind = :kind and at > @p0 and x = %(x)s'),
    'SELECT * FROM orders WHERE id = $1 AND status = %s AND kind = :kind AND at > @p0 AND x = %(x)s');
});

test('a named placeholder or a qualified name that spells a clause does not start one', () => {
  assert.equal(formatSql('select * from t where a = :from and b = :to'), 'SELECT *\nFROM t\nWHERE a = :from\n  AND b = :to');
  assert.equal(formatSql('select t.from, o.count from t'), 'SELECT t.from, o.count\nFROM t');
});

test('a name in any script is one word', () => {
  assert.equal(formatSql('select 이름, größe from 사용자 where größe > ?'), 'SELECT 이름, größe\nFROM 사용자\nWHERE größe > ?');
  assert.equal(oneLineSql('select café from carte'), 'SELECT café FROM carte');
});

test('a -- comment ends its line, and on one line it reads as a block comment', () => {
  assert.equal(formatSql('select a, -- the note\n b from t where c = ?'), 'SELECT a, -- the note\n  b\nFROM t\nWHERE c = ?');
  assert.equal(formatSql('select a -- note\nfrom t where c = ? -- last'), 'SELECT a -- note\nFROM t\nWHERE c = ? -- last');
  assert.equal(oneLineSql('select a -- note\nfrom t where c = ?'), 'SELECT a /* note */ FROM t WHERE c = ?');
});

test('neither form puts a space into what the statement wrote together', () => {
  for (const sql of [
    'select b1_0.id,b1_0.title from book b1_0 where b1_0.price>=? and b1_0.price<=? and b1_0.status<>?',
    "SELECT first_name||' '||last_name FROM author WHERE id=$1 OR created_at::date=:day",
    'insert into book (author_id,price,title,id) values (?,?,?,?)',
    'SELECT 이름, größe FROM 사용자 WHERE x->>\'k\' != %s AND y:=@v',
    "select '(  x' from t where a = (select max(b) from u) -- end",
    'update t set a=a+1,b=-1 where c in (?,?)',
  ]) {
    assertNothingSplit(sql, formatSql(sql));
    const uncommented = sql.replace(/--.*/, '');
    assertNothingSplit(uncommented, oneLineSql(uncommented, 1000));
  }
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

test('storedSql marks a statement the store may have cut, and leaves a whole one alone', () => {
  assert.equal(storedSql('SELECT x\nFROM t\nWHERE id IN (?, ?', true), 'SELECT x\nFROM t\nWHERE id IN (?, ?… (cut at 2,000 characters)');
  assert.equal(storedSql('SELECT x\nFROM t', false), 'SELECT x\nFROM t');
  assert.equal(storedSql('SELECT x\nFROM t', undefined), 'SELECT x\nFROM t');
});

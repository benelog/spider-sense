// SQL pretty-printer: a statement on lines by clause, or on one line for a table cell.

import { truncate } from './format.js';

const KEYWORDS = new Set(`select from where group by having order limit offset insert into values update set
delete create table alter drop index view join inner left right full outer cross on using union all distinct
as and or not in exists between like ilike is null asc desc case when then else end with recursive
returning conflict do nothing primary key foreign references constraint default unique check
count sum avg min max coalesce cast interval over partition row_number rank dense_rank
for update fetch next rows only top lateral natural merge upsert truncate begin commit rollback`.split(/\s+/));

// Clauses that start a new line; longest first so "order by" beats "order".
const MAJOR = [
  'select', 'from', 'inner join', 'left outer join', 'left join', 'right outer join', 'right join',
  'full outer join', 'full join', 'cross join', 'join', 'where', 'group by', 'having', 'order by',
  'limit', 'offset', 'union all', 'union', 'values', 'set', 'insert into', 'update', 'delete from',
  'returning', 'on conflict', 'fetch next', 'for update', 'with',
];

const CONTINUATION = ['and', 'or'];

// After these words a "(" keeps its space; after anything else it is a call or a group.
const SPACE_BEFORE_PAREN = new Set(['in', 'values', 'and', 'or', 'not', 'on', 'between', 'all', 'any', 'exists', 'union', 'when', 'then', 'else', 'by', 'from', 'where', 'select']);

const trimEnd = (s) => s.replace(/\s+$/, '');

function tokenize(sql) {
  const tokens = [];
  const re = /('(?:''|[^'])*')|("(?:""|[^"])*")|(--[^\n]*)|(\/\*[\s\S]*?\*\/)|([A-Za-z_][A-Za-z_0-9$#]*)|(\d+(?:\.\d+)?)|(\s+)|(.)/g;
  let m;
  while ((m = re.exec(sql)) !== null) {
    if (m[1] || m[2]) tokens.push({ type: 'string', text: m[0] });
    else if (m[3] || m[4]) tokens.push({ type: 'comment', text: m[0] });
    else if (m[5]) tokens.push({ type: 'word', text: m[0] });
    else if (m[6]) tokens.push({ type: 'number', text: m[0] });
    else if (m[7]) tokens.push({ type: 'space', text: ' ' });
    else tokens.push({ type: 'punct', text: m[0] });
  }
  return tokens;
}

/**
 * Uppercase keywords, one line per major clause, indent inside parentheses.
 * The agent has already replaced literals with `?`, so this is purely cosmetic.
 */
export function formatSql(sql) {
  if (!sql) return '';
  const tokens = tokenize(sql).filter((t) => t.type !== 'space');
  const words = tokens.map((t) => (t.type === 'word' ? t.text.toLowerCase() : null));
  const out = [];
  let line = '';
  let depth = 0;
  let indent = 0;      // indent of the line currently being built
  let pendingBetween = false;
  let prevWord = null;

  const flush = () => { if (line.trim()) out.push('  '.repeat(Math.max(0, indent)) + line.trim()); line = ''; };

  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t.type === 'punct') {
      if (t.text === '(') {
        depth++;
        line = (prevWord && !SPACE_BEFORE_PAREN.has(prevWord) ? trimEnd(line) : line) + '(';
        prevWord = null;
        continue;
      }
      if (t.text === ')') { depth--; line = trimEnd(line) + ') '; prevWord = null; continue; }
      if (t.text === ',') { line = trimEnd(line) + ', '; prevWord = null; continue; }
      if (t.text === '.') { line = trimEnd(line) + '.'; prevWord = null; continue; }
      if (t.text === ';') { line = trimEnd(line) + ';'; flush(); indent = 0; prevWord = null; continue; }
      line += t.text + ' ';
      continue;
    }
    if (t.type === 'word') {
      const lower = words[i];
      let matched = null;
      if (depth === 0) {
        for (const clause of MAJOR) {
          const parts = clause.split(' ');
          if (parts.every((p, k) => words[i + k] === p)) { matched = clause; break; }
        }
      }
      if (matched) {
        flush();
        indent = 0;
        const parts = matched.split(' ');
        line = parts.map((p) => p.toUpperCase()).join(' ') + ' ';
        prevWord = parts[parts.length - 1];
        i += parts.length - 1;
        continue;
      }
      if (lower === 'between') pendingBetween = true;
      if (pendingBetween && lower === 'and') {
        pendingBetween = false;
        line += t.text.toUpperCase() + ' ';
        prevWord = lower;
        continue;
      }
      if (depth === 0 && CONTINUATION.includes(lower) && line.trim()) {
        flush();
        indent = 1;
        line = lower.toUpperCase() + ' ';
        prevWord = lower;
        continue;
      }
      line += (KEYWORDS.has(lower) ? t.text.toUpperCase() : t.text) + ' ';
      prevWord = lower;
      continue;
    }
    line += t.text + ' ';
  }
  flush();
  return out.join('\n').replace(/ +$/gm, '').replace(/\(\s+/g, '(');
}

/** One line for a table cell: collapsed whitespace, keywords uppercased. */
export function oneLineSql(sql, max = 200) {
  if (!sql) return '';
  const tokens = tokenize(sql).filter((t) => t.type !== 'space');
  let s = '';
  let prev = null;
  for (const t of tokens) {
    if (t.type === 'punct') {
      if (t.text === '(') { s = (prev && !SPACE_BEFORE_PAREN.has(prev) ? trimEnd(s) : s) + '('; prev = null; continue; }
      if (t.text === '.') { s = trimEnd(s) + '.'; prev = null; continue; }
      if (t.text === ',' || t.text === ')' || t.text === ';') { s = trimEnd(s) + t.text + ' '; prev = null; continue; }
      s += t.text + ' ';
      prev = null;
      continue;
    }
    const lower = t.type === 'word' ? t.text.toLowerCase() : null;
    s += (lower && KEYWORDS.has(lower) ? t.text.toUpperCase() : t.text) + ' ';
    prev = lower;
  }
  return truncate(s.replace(/\s+/g, ' ').trim(), max);
}

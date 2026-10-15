// SQL pretty-printer: a statement on lines by clause, or on one line for a table cell.
// Both change the case of keywords and the whitespace between tokens, and put a space only where
// the statement had one, so the text they give is a statement the database still parses.

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

/**
 * The statement as tokens, each with `gap`, whether whitespace came before it in the statement.
 * A string or a quoted name, `"…"` or MySQL's `` `…` ``, is one token, so it passes through whole.
 * So are a comment, a placeholder (`?`, `?1`, `$1`, `:1`, `:name`, `@name`, `%s`, `%(name)s`), a word
 * in any script, a number, and an operator of two or three characters (`>=`, `<=`, `<>`, `!=`, `<=>`,
 * `||`, `::`, `:=`, `->`, `->>`); any other character is a token of its own.
 */
function tokenize(sql) {
  const tokens = [];
  const re = /('(?:''|[^'])*')|("(?:""|[^"])*"|`(?:``|[^`])*`)|(--[^\n]*)|(\/\*[\s\S]*?\*\/)|(\?\d*|\$\d+|:\d+|:[\p{L}_][\p{L}\p{M}\p{N}_$#]*|@@?[\p{L}_][\p{L}\p{M}\p{N}_$#@]*|%s|%\([^()\s]+\)s)|([\p{L}_][\p{L}\p{M}\p{N}_$#]*)|(\d+(?:\.\d+)?)|(<=>|->>|->|<>|<=|>=|!=|\|\||::|:=)|(\s+)|([\s\S])/gu;
  let gap = false;
  let m;
  while ((m = re.exec(sql)) !== null) {
    if (m[9]) { gap = true; continue; }
    let type = 'punct';
    if (m[1] || m[2]) type = 'string';
    else if (m[3] || m[4]) type = 'comment';
    else if (m[5]) type = 'placeholder';
    else if (m[6]) type = 'word';
    else if (m[7]) type = 'number';
    tokens.push({ type, text: m[0], gap });
    gap = false;
  }
  return tokens;
}

/** A word right after a dot is part of a qualified name, such as `t.from` or `o.count`, and never a keyword. */
const keywordOf = (tokens, i) => (tokens[i].type === 'word' && !(i > 0 && tokens[i - 1].text === '.') ? tokens[i].text.toLowerCase() : null);

/**
 * Whether a space goes between two tokens of a line: only where the statement had one, and then
 * not before a comma, a closing parenthesis, a semicolon or a dot, not after an opening parenthesis
 * or a dot, and not between a name and the parenthesis of its call or column list.
 */
function spaced(prev, t) {
  if (prev.text === '(' || prev.text === '.') return false;
  if (t.text === ')' || t.text === ',' || t.text === ';' || t.text === '.') return false;
  if (t.text === '(' && prev.type === 'word' && !SPACE_BEFORE_PAREN.has(prev.text.toLowerCase())) return false;
  return t.gap;
}

/**
 * Uppercase keywords, one line per major clause, indent inside parentheses.
 * A `--` comment ends its line, so a clause after it is not commented out.
 */
export function formatSql(sql) {
  if (!sql) return '';
  const tokens = tokenize(sql);
  const words = tokens.map((t, i) => keywordOf(tokens, i));
  const out = [];
  let line = '';
  let prev = null;     // the token before, on the line being built
  let depth = 0;
  let indent = 0;      // indent of the line currently being built
  let pendingBetween = false;

  const flush = () => { if (line.trim()) out.push('  '.repeat(Math.max(0, indent)) + line.trim()); line = ''; prev = null; };
  const put = (t, text = t.text) => { line += (prev && spaced(prev, t) ? ' ' : '') + text; prev = t; };

  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t.type === 'punct') {
      if (t.text === '(') depth++;
      if (t.text === ')') depth--;
      put(t);
      if (t.text === ';') { flush(); indent = 0; }
      continue;
    }
    if (t.type === 'comment') {
      put(t);
      if (t.text.startsWith('--')) { flush(); indent = Math.max(indent, 1); }
      continue;
    }
    const lower = words[i];
    if (lower === null) { put(t); continue; }
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
      line = parts.map((p) => p.toUpperCase()).join(' ');
      i += parts.length - 1;
      prev = tokens[i];
      continue;
    }
    if (lower === 'between') pendingBetween = true;
    if (pendingBetween && lower === 'and') {
      pendingBetween = false;
      put(t, t.text.toUpperCase());
      continue;
    }
    if (depth === 0 && CONTINUATION.includes(lower) && line.trim()) {
      flush();
      indent = 1;
      line = lower.toUpperCase();
      prev = t;
      continue;
    }
    put(t, KEYWORDS.has(lower) ? t.text.toUpperCase() : t.text);
  }
  flush();
  return out.join('\n');
}

/**
 * One line for a table cell: collapsed whitespace, keywords uppercased, and a `--` comment
 * closed as a block comment, so the rest of the line does not read as part of it.
 */
export function oneLineSql(sql, max = 200) {
  if (!sql) return '';
  const tokens = tokenize(sql);
  let s = '';
  tokens.forEach((t, i) => {
    const lower = keywordOf(tokens, i);
    const text = t.type === 'comment' && t.text.startsWith('--') ? `/* ${t.text.slice(2).trim().replace(/\*\//g, '* /')} */`
      : lower && KEYWORDS.has(lower) ? t.text.toUpperCase() : t.text;
    s += (i > 0 && spaced(tokens[i - 1], t) ? ' ' : '') + text;
  });
  return truncate(s.replace(/\s+/g, ' ').trim(), max);
}

/**
 * A statement as the store kept it: one the store may have cut at 2,000 characters
 * (api.adoc's `statementCut`) ends the way the text renderings end it, so the text
 * shown or copied never reads as the whole statement.
 */
export function storedSql(text, cut) {
  return cut ? text + '… (cut at 2,000 characters)' : text;
}

#!/usr/bin/env node
// The published demo's data path (docs/design.md, "The published demo"). The demo's
// rows live in a DoltHub database with the tables of docs/storage.md, plus one table
// of the answers the UI asks for, and this script moves them, one CSV per table,
// between H2, a running Spider Sense and DoltHub:
//
//   export    the tables of a window out of an H2 database into CSV files
//   load      the CSV files into the H2 database of a running Spider Sense
//   capture   every answer the UI asks that Spider Sense for, over the recording's
//             window, into answer.csv beside the tables
//   push      the CSV files into the DoltHub database, one commit per table
//   pull      the DoltHub tables back into CSV files
//   assemble  the UI as one static directory that reads the DoltHub database, and the
//             agent demo's pages beside it
//   agent-push  the agent demo's recorded sessions (scripts/agent-demo.sh) into the same
//             database, as the rows the agent demo's page replays
//
// scripts/demo-site.sh runs them in order. H2 is driven through org.h2.tools.Shell from
// the server jar nested in the Spider Sense jar, so nothing here needs Java beyond it.

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PUBLIC = join(root, 'spider-sense-server/src/main/resources/public');
const DATA = join(root, 'build/demo-data');
const DOLTHUB = { owner: 'benelog', database: 'spider-sense-demo', branch: 'main' };

// --- the tables: storage.md's schema, as DoltHub holds it -----------------------
//
// `columns` is the H2 column order; `bool` names the BOOLEAN columns, written as 1
// and 0 so that both sides read them; `required` names the NOT NULL text columns,
// which load reads as '' when a cell is empty; `window` says which column the
// recording's window cuts on (`trace`: the spans of the traces that started in it;
// `series`: the series that have a point in it; `meta`: every row, plus the
// recording's own rows; null: every row, in `order`, or by name). `ddl` is the same schema in MySQL
// types: VARCHAR(65535) and the 4096-wide columns are TEXT, which is what Dolt's
// row size allows, and the JSON columns stay text so a row reads back byte for byte.

const TABLES = [
  { name: 'service', key: ['name'], window: null,
    columns: 'name language pid first_seen last_seen resource', required: 'name resource',
    ddl: `name VARCHAR(255) NOT NULL, language VARCHAR(64), pid BIGINT, first_seen BIGINT NOT NULL,
          last_seen BIGINT NOT NULL, resource TEXT NOT NULL` },
  { name: 'span', key: ['id'], window: 'trace',
    columns: `id trace_id span_id parent_span_id service name kind start_ms start_ns duration_ns status status_message
              entry error slow category endpoint endpoint_id http_method http_route http_status db_system db_statement
              db_namespace db_operation db_table query_id error_type error_message error_id scope attributes events`,
    bool: 'entry error slow', required: 'trace_id span_id service name kind status category attributes events',
    ddl: `id BIGINT NOT NULL, trace_id CHAR(32) NOT NULL, span_id CHAR(16) NOT NULL, parent_span_id CHAR(16),
          service VARCHAR(255) NOT NULL, name VARCHAR(1024) NOT NULL, kind VARCHAR(8) NOT NULL, start_ms BIGINT NOT NULL,
          start_ns BIGINT NOT NULL, duration_ns BIGINT NOT NULL, status VARCHAR(5) NOT NULL, status_message TEXT,
          entry BOOLEAN NOT NULL, error BOOLEAN NOT NULL, slow BOOLEAN NOT NULL, category VARCHAR(10) NOT NULL,
          endpoint VARCHAR(1024), endpoint_id CHAR(12), http_method VARCHAR(16), http_route VARCHAR(1024), http_status INT,
          db_system VARCHAR(64), db_statement TEXT, db_namespace VARCHAR(255), db_operation VARCHAR(64), db_table VARCHAR(255),
          query_id CHAR(12), error_type VARCHAR(512), error_message TEXT, error_id CHAR(12), scope VARCHAR(255),
          attributes TEXT NOT NULL, events TEXT NOT NULL` },
  { name: 'trace', key: ['trace_id'], window: 'start_ms',
    columns: `trace_id start_ms end_ms duration_ns root_span_id root_name root_service root_kind services span_count
              error_count db_count http_status slow error`,
    bool: 'slow error', required: 'trace_id root_name root_service root_kind services',
    ddl: `trace_id CHAR(32) NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL, duration_ns BIGINT NOT NULL,
          root_span_id CHAR(16), root_name VARCHAR(1024) NOT NULL, root_service VARCHAR(255) NOT NULL,
          root_kind VARCHAR(8) NOT NULL, services TEXT NOT NULL, span_count INT NOT NULL, error_count INT NOT NULL,
          db_count INT NOT NULL, http_status INT, slow BOOLEAN NOT NULL, error BOOLEAN NOT NULL` },
  { name: 'log', key: ['id'], window: 'at_ms',
    columns: 'id at_ms service severity_number severity body logger trace_id span_id attributes',
    required: 'service severity body attributes',
    ddl: `id BIGINT NOT NULL, at_ms BIGINT NOT NULL, service VARCHAR(255) NOT NULL, severity_number INT NOT NULL,
          severity VARCHAR(8) NOT NULL, body TEXT NOT NULL, logger VARCHAR(512), trace_id CHAR(32), span_id CHAR(16),
          attributes TEXT NOT NULL` },
  { name: 'metric', key: ['name'], window: null,
    columns: 'name type unit description monotonic temporality', bool: 'monotonic', required: 'name type',
    ddl: `name VARCHAR(255) NOT NULL, type VARCHAR(12) NOT NULL, unit VARCHAR(64), description VARCHAR(1024),
          monotonic BOOLEAN NOT NULL, temporality VARCHAR(12)` },
  { name: 'metric_series', key: ['id'], window: 'series',
    columns: 'id service name attr_hash attributes', required: 'service name attr_hash attributes',
    ddl: `id BIGINT NOT NULL, service VARCHAR(255) NOT NULL, name VARCHAR(255) NOT NULL, attr_hash CHAR(12) NOT NULL,
          attributes TEXT NOT NULL` },
  { name: 'metric_point', key: ['series_id', 'at_ms'], window: 'at_ms',
    columns: 'series_id at_ms value count sum min max buckets',
    ddl: `series_id BIGINT NOT NULL, at_ms BIGINT NOT NULL, value DOUBLE, count BIGINT, sum DOUBLE, min DOUBLE,
          max DOUBLE, buckets TEXT` },
  { name: 'tingle', key: ['id'], window: 'at_ms',
    columns: 'id at_ms kind service title detail trace_id span_id duration_ms', required: 'kind service title detail',
    ddl: `id BIGINT NOT NULL, at_ms BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, service VARCHAR(255) NOT NULL,
          title VARCHAR(1024) NOT NULL, detail TEXT NOT NULL, trace_id CHAR(32), span_id CHAR(16), duration_ms DOUBLE` },
  { name: 'mark', key: ['id'], window: 'at_ms',
    columns: 'id at_ms name service note', required: 'name',
    ddl: `id BIGINT NOT NULL, at_ms BIGINT NOT NULL, name VARCHAR(64) NOT NULL, service VARCHAR(255), note VARCHAR(1024)` },
  { name: 'ack', key: ['finding_id'], window: 'at_ms',
    columns: 'finding_id at_ms note resolved', bool: 'resolved', required: 'finding_id',
    ddl: 'finding_id VARCHAR(64) NOT NULL, at_ms BIGINT NOT NULL, note VARCHAR(1024), resolved BOOLEAN NOT NULL' },
  // The index catalog, every row: the extension reads a table's indexes once per
  // process, so the row behind a window's schema blocks was usually written before
  // the window began. Loaded with the rest, it gives capture's slow-query and
  // n-plus-one answers their schema block (docs/agent.md). A recording pushed before
  // the table travelled has none, which pull reads as no rows (`optional`).
  { name: 'db_table', key: ['service', 'schema_name', 'table_name'], window: null, optional: true,
    order: 'service, schema_name, table_name',
    columns: 'service schema_name table_name product indexes seen_ms', required: 'service schema_name table_name indexes',
    ddl: `service VARCHAR(255) NOT NULL, schema_name VARCHAR(255) NOT NULL, table_name VARCHAR(255) NOT NULL,
          product VARCHAR(64), indexes TEXT NOT NULL, seen_ms BIGINT NOT NULL` },
  // schema_version and created_at as H2 wrote them, plus the recording's own rows:
  // recording.from and recording.to (the window the page shows), recording.at and
  // recording.version. meta is pushed last, so a push that broke off has no new one.
  { name: 'meta', key: ['key'], window: 'meta',
    columns: 'key value', required: 'key value',
    ddl: '`key` VARCHAR(64) NOT NULL, `value` TEXT NOT NULL' },
  // Not an H2 table: what capture asked a Spider Sense loaded with the rows above,
  // keyed by path and query without from/to, plus /manifest (the window, the services
  // and the keys). The page reads the answers from here and the rows it lists from
  // the tables (assets/js/dev/replay.js).
  { name: 'answer', key: ['key'], h2: false, window: null,
    columns: 'key body', required: 'key body',
    ddl: '`key` VARCHAR(768) NOT NULL, body LONGTEXT NOT NULL' },
];
for (const t of TABLES) {
  t.h2 = t.h2 !== false;
  t.columns = t.columns.split(/\s+/).filter(Boolean);
  t.bool = (t.bool || '').split(/\s+/).filter(Boolean);
  t.required = (t.required || '').split(/\s+/).filter(Boolean);
}

function options(args) {
  const out = { _: [] };
  for (const arg of args) {
    const m = /^--([^=]+)=(.*)$/.exec(arg);
    if (m) out[m[1]] = m[2];
    else if (arg.startsWith('--')) out[arg.slice(2)] = 'true';
    else out._.push(arg);
  }
  return out;
}

const tablesDir = (opts) => resolve(root, opts.tables || join(DATA, 'tables'));
const csvOf = (opts, table) => join(tablesDir(opts), table.name + '.csv');

// --- H2 ----------------------------------------------------------------------

function senseJar(opts) {
  if (opts.jar) return resolve(root, opts.jar);
  const version = /^version=(.+)$/m.exec(readFileSync(join(root, 'gradle.properties'), 'utf8'))[1].trim();
  const jar = join(root, 'spider-sense-agent/build/libs/spider-sense-' + version + '.jar');
  if (!existsSync(jar)) throw new Error(jar + ' is not built; ./gradlew :spider-sense-agent:senseJar, or --jar=');
  return jar;
}

/** The server jar nested in the Spider Sense jar, which carries H2 and its tools. */
function serverJar(opts) {
  const out = join(DATA, 'server.jar');
  mkdirSync(DATA, { recursive: true });
  const unzip = spawnSync('unzip', ['-p', senseJar(opts), 'spider-sense/server.jar'], { maxBuffer: 256 * 1024 * 1024 });
  if (unzip.status !== 0 || !unzip.stdout.length) throw new Error('could not read spider-sense/server.jar out of the jar: ' + unzip.stderr);
  writeFileSync(out, unzip.stdout);
  return out;
}

/** The JDBC URL a --db means, the way docs/design.md's spidersense.db does. */
function jdbcUrl(db) {
  if (!db) throw new Error('--db=<path or jdbc url> is required');
  let url = db.startsWith('jdbc:') ? db : 'jdbc:h2:' + db.replace(/^~(?=\/|$)/, homedir()) + ';AUTO_SERVER=TRUE';
  if (!url.toUpperCase().includes('NON_KEYWORDS')) url += ';NON_KEYWORDS=KEY,VALUE';
  return url;
}

/** Runs statements through H2's shell; a statement that fails is an error here too. */
function h2(opts, sql) {
  const run = spawnSync('java', ['-cp', serverJar(opts), 'org.h2.tools.Shell',
    '-url', jdbcUrl(opts.db), '-user', 'sa', '-password', '', '-sql', sql], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const text = (run.stdout || '') + (run.stderr || '');
  if (run.status !== 0 || /^Error: /m.test(text)) throw new Error('H2: ' + text.trim().split('\n').filter((l) => /Error|Exception/.test(l)).join('\n'));
  return text;
}

const sqlString = (s) => "'" + String(s).replace(/'/g, "''") + "'";

// --- export ------------------------------------------------------------------

/** The window's rows of every table, one CSV each, with the home directory anonymised. */
function exportTables(opts) {
  const since = Number(opts.since);
  const until = Number(opts.until);
  const from = Number(opts.from || since);
  const to = Number(opts.to || until);
  if (!since || !until || since >= until) throw new Error('export needs --since=<epoch ms> and --until=<epoch ms>, the rows to take');
  if (from < since || to > until || from >= to) throw new Error('--from and --to, the window the page shows, must lie within --since and --until');
  const version = /^version=(.+)$/m.exec(readFileSync(join(root, 'gradle.properties'), 'utf8'))[1].trim();
  const dir = tablesDir(opts);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });

  const statements = [];
  for (const table of TABLES) {
    if (!table.h2) continue;
    const select = table.columns.map((c) => (table.bool.includes(c) ? 'CASE WHEN ' + c + ' THEN 1 ELSE 0 END' : c) + ' AS "' + c + '"').join(', ');
    let query;
    if (table.window === 'meta') {
      query = 'SELECT ' + select + ' FROM meta'
        + ` UNION ALL SELECT 'recording.from', '${from}' UNION ALL SELECT 'recording.to', '${to}'`
        + ` UNION ALL SELECT 'recording.at', '${Date.now()}' UNION ALL SELECT 'recording.version', ${sqlString(version)}`;
    } else if (table.window === 'trace') {
      // By the trace, not by the clock: a trace that started inside the window is
      // taken whole, so one that straddles its end has its root and its last spans.
      query = 'SELECT ' + select + ' FROM span WHERE trace_id IN (SELECT trace_id FROM trace WHERE start_ms BETWEEN ' + since + ' AND ' + until + ') ORDER BY start_ms, id';
    } else if (table.window === 'series') {
      query = 'SELECT ' + select + ' FROM metric_series WHERE id IN (SELECT DISTINCT series_id FROM metric_point WHERE at_ms BETWEEN ' + since + ' AND ' + until + ') ORDER BY id';
    } else if (table.window) {
      query = 'SELECT ' + select + ' FROM ' + table.name + ' WHERE ' + table.window + ' BETWEEN ' + since + ' AND ' + until + ' ORDER BY ' + table.window;
    } else {
      query = 'SELECT ' + select + ' FROM ' + table.name + ' ORDER BY ' + (table.order || 'name');
    }
    statements.push('CALL CSVWRITE(' + sqlString(join(dir, table.name + '.csv')) + ', ' + sqlString(query) + ", 'charset=UTF-8')");
  }
  console.log('exporting ' + jdbcUrl(opts.db) + ' over ' + new Date(since).toISOString() + ' .. ' + new Date(until).toISOString() + ' into ' + dir);
  h2(opts, statements.join(';\n'));

  const home = homedir();
  for (const table of TABLES) {
    if (!table.h2) continue;
    const file = join(dir, table.name + '.csv');
    const text = readFileSync(file, 'utf8');
    writeFileSync(file, text.split(home).join('/home/me'));
    console.log('  ' + table.name + ': ' + rowsIn(text) + ' rows, ' + (text.length / 1024).toFixed(0) + ' KB');
  }
  writeRecording(opts, {});
}

/** recording.json beside the data: the window and what made it, for capture and the page. */
function writeRecording(opts, source) {
  const recording = { ...recordingOf(readMeta(opts)), source: { url: dolt.page(), ...source } };
  const file = resolve(root, opts.recording || join(DATA, 'recording.json'));
  writeFileSync(file, JSON.stringify(recording, null, 1));
  return { recording, file };
}

// --- CSV, the little of it that is needed ---------------------------------------

function csvCell(value) {
  const s = value === null || value === undefined ? '' : String(value);
  return /[",\r\n]/.test(s) || s === '' ? '"' + s.replace(/"/g, '""') + '"' : s;
}

function toCsv(columns, rows) {
  const lines = [columns.join(',')];
  for (const row of rows) lines.push(columns.map((c) => csvCell(row[c])).join(','));
  return lines.join('\n') + '\n';
}

/** RFC 4180 with quoted newlines; the first record is the header. */
function parseCsv(text) {
  const records = [];
  let record = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false;
      } else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { record.push(field); field = ''; }
    else if (c === '\n') { record.push(field); records.push(record); record = []; field = ''; }
    else if (c !== '\r') field += c;
  }
  if (field !== '' || record.length) { record.push(field); records.push(record); }
  return records;
}

/** The records of a CSV text, its header not counted. */
const rowsIn = (text) => Math.max(parseCsv(text).length - 1, 0);

/** meta.csv as a map. */
function readMeta(opts) {
  const records = parseCsv(readFileSync(csvOf(opts, TABLES.find((t) => t.name === 'meta')), 'utf8'));
  const header = records.shift() || [];
  const key = header.indexOf('key');
  const value = header.indexOf('value');
  if (key < 0 || value < 0) throw new Error('meta.csv has no key and value columns');
  return Object.fromEntries(records.filter((r) => r.length > value).map((r) => [r[key], r[value]]));
}

function recordingOf(meta) {
  const from = Number(meta['recording.from']);
  const to = Number(meta['recording.to']);
  if (!from || !to) throw new Error('meta has no recording.from and recording.to: nothing has been recorded');
  return { window: { from, to }, recordedAt: Number(meta['recording.at']) || null, version: meta['recording.version'] || null, schemaVersion: meta.schema_version || null };
}

// --- DoltHub -----------------------------------------------------------------

const dolt = {
  api: () => 'https://www.dolthub.com/api/v2/databases/' + DOLTHUB.owner + '/' + DOLTHUB.database,
  page: () => 'https://www.dolthub.com/repositories/' + DOLTHUB.owner + '/' + DOLTHUB.database,
  csv: (ref, table) => 'https://www.dolthub.com/csv/' + DOLTHUB.owner + '/' + DOLTHUB.database + '/' + ref + '/' + table,
  read: (ref, q) => 'https://www.dolthub.com/api/v1alpha1/' + DOLTHUB.owner + '/' + DOLTHUB.database + '/' + ref + '?q=' + encodeURIComponent(q),
};

/** fetch that rides out a dropped connection or a 5xx: DoltHub is far away and the parts are big. */
async function fetchRetry(url, init = {}, tries = 5) {
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(url, init);
      if (res.status < 500 || res.status === 501 || attempt >= tries) return res;
      await res.text().catch(() => '');
      console.warn('  retry ' + attempt + ' of ' + url.replace(/\?.*$/, '') + ': ' + res.status);
    } catch (e) {
      if (attempt >= tries) throw e;
      console.warn('  retry ' + attempt + ' of ' + url.replace(/\?.*$/, '') + ': ' + (e.cause && e.cause.code || e.message));
    }
    await new Promise((r) => setTimeout(r, 2000 * attempt));
  }
}

function token() {
  const t = process.env.DOLTHUB_TOKEN;
  if (!t) throw new Error('DOLTHUB_TOKEN is not set (an API token of a writer of ' + dolt.page() + ')');
  return t;
}

async function doltJson(url, init = {}) {
  const res = await fetchRetry(url, {
    ...init,
    headers: { authorization: 'Bearer ' + token(), 'content-type': 'application/json', accept: 'application/json', ...(init.headers || {}) },
  });
  const text = await res.text();
  if (!res.ok) throw new Error((init.method || 'GET') + ' ' + url + ': ' + res.status + ' ' + text.slice(0, 500));
  return JSON.parse(text);
}

/** A read over a ref, unauthenticated: the rows as objects of strings. */
async function doltQuery(ref, q) {
  const res = await fetchRetry(dolt.read(ref, q), { headers: { accept: 'application/json' } });
  const body = await res.json();
  if (body.query_execution_status !== 'Success') throw new Error('DoltHub: ' + q + ': ' + body.query_execution_message);
  return body.rows;
}

/** Polls an async operation until it ends; throws when it fails. */
async function doltWait(operation, what) {
  const started = Date.now();
  for (;;) {
    const body = await doltJson(operation.href);
    const status = body.data && body.data.status;
    if (status === 'succeeded') return body.data;
    if (status && !['pending', 'running', 'queued', 'in_progress'].includes(status)) {
      const detail = (body.data.error && body.data.error.detail) || '';
      // A table the recording left as it was is not a failure: DoltHub says so of an import that changed nothing.
      if (status === 'failed' && detail.includes('did not modify')) return body.data;
      throw new Error(what + ' ' + status + ' on DoltHub: ' + JSON.stringify(body.data));
    }
    if (Date.now() - started > 20 * 60 * 1000) throw new Error(what + ' did not finish on DoltHub in 20 min');
    await new Promise((r) => setTimeout(r, 3000));
  }
}

async function doltWrite(sql, what) {
  const body = await doltJson(dolt.api() + '/sql-writes', {
    method: 'POST',
    body: JSON.stringify({ from_branch: DOLTHUB.branch, to_branch: DOLTHUB.branch, q: sql }),
  });
  return doltWait(body.data, what);
}

const v1 = () => 'https://www.dolthub.com/api/v1alpha1/' + DOLTHUB.owner + '/' + DOLTHUB.database;

/** The branches, newest change first. */
async function doltBranches() {
  const res = await fetchRetry(dolt.api() + '/branches', { headers: { accept: 'application/json' } });
  const body = await res.json();
  return ((body && body.data) || []).slice().sort((a, b) => String(b.last_updated_at).localeCompare(String(a.last_updated_at)));
}

/**
 * Merges a branch into the branch, through the v1alpha1 write endpoint: a write with
 * no query from one branch to another is a merge there, and it is the one merge the
 * API offers a token (DOLT_MERGE is refused by the SQL write, and pull requests need
 * a permission an API token does not get).
 */
async function doltMerge(from, into) {
  const res = await fetchRetry(v1() + '/write/' + encodeURIComponent(from) + '/' + encodeURIComponent(into), {
    method: 'POST', headers: { authorization: 'token ' + token(), accept: 'application/json' },
  });
  const body = await res.json();
  if (!body.operation_name) throw new Error('merge of ' + from + ' into ' + into + ': ' + JSON.stringify(body).slice(0, 300));
  const started = Date.now();
  for (;;) {
    await new Promise((r) => setTimeout(r, 3000));
    const poll = await (await fetchRetry(v1() + '/write?operationName=' + encodeURIComponent(body.operation_name), {
      headers: { authorization: 'token ' + token(), accept: 'application/json' },
    })).json();
    if (poll.done) {
      const details = poll.res_details || {};
      if (details.query_execution_status && details.query_execution_status !== 'Success') {
        throw new Error('merge of ' + from + ' into ' + into + ' failed: ' + details.query_execution_message);
      }
      return details;
    }
    if (Date.now() - started > 20 * 60 * 1000) throw new Error('merge of ' + from + ' did not finish in 20 min');
  }
}

/** Best effort: the API has no branch delete a token may call, so a failure is only reported. */
async function doltDeleteBranch(name) {
  try {
    const body = await doltJson(dolt.api() + '/sql-writes', {
      method: 'POST',
      body: JSON.stringify({ from_branch: DOLTHUB.branch, to_branch: DOLTHUB.branch, q: "CALL DOLT_BRANCH('-D', " + sqlString(name) + ')' }),
    });
    await doltWait(body.data, 'delete of ' + name);
    return true;
  } catch (e) {
    return false;
  }
}

async function doltHead(ref) {
  const res = await fetchRetry(dolt.api() + '/branches', { headers: { accept: 'application/json' } });
  const body = await res.json();
  const branch = ((body && body.data) || []).find((b) => b.name === ref);
  return branch ? branch.head_commit_sha : null;
}

/** The column definitions of a table's DDL, by name, split on the commas outside parentheses. */
function columnsOf(ddl) {
  const out = new Map();
  let depth = 0;
  let from = 0;
  const text = ddl.replace(/\s+/g, ' ').trim();
  for (let i = 0; i <= text.length; i++) {
    const c = text[i];
    if (c === '(') depth++;
    else if (c === ')') depth--;
    else if ((c === ',' && depth === 0) || i === text.length) {
      const def = text.slice(from, i).trim();
      if (def) out.set(def.split(' ')[0].replace(/`/g, ''), def);
      from = i + 1;
    }
  }
  return out;
}

/**
 * A column the schema gained since the table was created, as ALTER TABLE adds it:
 * a NOT NULL column gets the default the rows already there need.
 */
function addedColumn(def) {
  if (!/NOT NULL/i.test(def) || /DEFAULT/i.test(def)) return def;
  if (/^\S+ (BOOLEAN|INT|BIGINT|DOUBLE)/i.test(def)) return def + ' DEFAULT 0';
  if (/^\S+ (VARCHAR|CHAR)/i.test(def)) return def + " DEFAULT ''";
  return def.replace(/ NOT NULL/i, '');
}

/** Creates the tables that are not there yet and adds the columns a table lacks, one statement each. */
async function doltEnsureTables(tables = TABLES) {
  let existing = [];
  try {
    existing = (await doltQuery(DOLTHUB.branch, 'SHOW TABLES')).map((row) => Object.values(row)[0]);
  } catch (e) {
    existing = [];   // an empty database has no branch yet; the first write creates it
  }
  for (const table of tables) {
    if (existing.includes(table.name)) {
      const have = new Set((await doltQuery(DOLTHUB.branch, 'SHOW COLUMNS FROM `' + table.name + '`'))
        .map((row) => String(row.Field || Object.values(row)[0])));
      for (const [name, def] of columnsOf(table.ddl)) {
        if (have.has(name)) continue;
        console.log('alter ' + table.name + ': add ' + name);
        await doltWrite('ALTER TABLE `' + table.name + '` ADD COLUMN ' + addedColumn(def), 'ALTER TABLE ' + table.name + ' ADD ' + name);
      }
      continue;
    }
    const ddl = 'CREATE TABLE `' + table.name + '` (' + table.ddl.replace(/\s+/g, ' ').trim()
      + ', PRIMARY KEY (' + table.key.map((k) => '`' + k + '`').join(', ') + '))';
    console.log('create ' + table.name);
    await doltWrite(ddl, 'CREATE TABLE ' + table.name);
  }
}

/** Uploads one CSV and replaces the table's rows with it, as one commit on the branch. */
async function doltImport(table, bytes, message) {
  const PART = 16 * 1024 * 1024;
  const parts = [];
  for (let at = 0; at < bytes.length; at += PART) parts.push(bytes.subarray(at, Math.min(at + PART, bytes.length)));
  if (parts.length === 0) parts.push(Buffer.alloc(0));

  const session = (await doltJson(dolt.api() + '/imports/uploads', {
    method: 'POST',
    body: JSON.stringify({ content_length: bytes.length, num_parts: parts.length, file_type: 'csv' }),
  })).data;

  const completed = [];
  const digests = [];
  for (const part of session.parts) {
    const chunk = parts[part.part_number - 1];
    const res = await fetchRetry(part.url, { method: session.http_method || 'PUT', headers: session.headers || {}, body: chunk });
    if (!res.ok) throw new Error('upload part ' + part.part_number + ' of ' + table.name + ': ' + res.status + ' ' + (await res.text()).slice(0, 300));
    completed.push({ part_number: part.part_number, etag: (res.headers.get('etag') || '').replace(/"/g, '') });
    digests.push(createHash('md5').update(chunk).digest());
  }

  const operation = (await doltJson(dolt.api() + '/imports', {
    method: 'POST',
    body: JSON.stringify({
      branch_name: DOLTHUB.branch,
      table_name: table.name,
      file_name: table.name + '.csv',
      file_size: bytes.length,
      file_type: 'csv',
      import_operation: 'replace',
      token: session.token,
      contents_key: session.contents_key,
      completed_parts: completed,
      file_parts_md5: createHash('md5').update(Buffer.concat(digests)).digest('base64'),
      primary_keys: table.key,
      commit_message: message,
    }),
  })).data;
  const done = await doltWait(operation, 'import of ' + table.name);
  return !(done.error && String(done.error.detail || '').includes('did not modify'));
}

/**
 * One table into the branch: DoltHub runs every import on a branch of its own
 * (`<owner>/import-<words>`), so the branch that appeared is merged into the
 * branch, and then deleted when the API lets it be.
 */
async function doltImportInto(table, bytes, message) {
  const before = new Set((await doltBranches()).map((b) => b.name));
  const changed = await doltImport(table, bytes, message);
  if (!changed) {
    console.log('  ' + table.name + ' is as it was');
    return;
  }
  const branch = (await doltBranches()).find((b) => !before.has(b.name) && /\/import-/.test(b.name));
  if (!branch) throw new Error('the import of ' + table.name + ' succeeded but no import branch appeared to merge');
  await doltMerge(branch.name, DOLTHUB.branch);
  const deleted = await doltDeleteBranch(branch.name);
  console.log('  ' + table.name + ': merged ' + branch.name + (deleted ? ', deleted' : ' (left; delete it on the web)'));
}

// --- push and pull -------------------------------------------------------------

async function push(opts) {
  const recording = recordingOf(readMeta(opts));
  const when = new Date(recording.recordedAt || Date.now()).toISOString().slice(0, 16).replace('T', ' ');
  const message = 'Recording of ' + when + ' UTC, Spider Sense ' + recording.version;
  console.log('pushing ' + tablesDir(opts) + ' to ' + dolt.page() + ' (' + DOLTHUB.branch + ')');
  await doltEnsureTables();
  const only = opts.only ? new Set(opts.only.split(',')) : null;
  for (const table of TABLES) {
    if (only && !only.has(table.name)) continue;
    const bytes = readFileSync(csvOf(opts, table));
    console.log('import ' + table.name + ' (' + (bytes.length / 1024).toFixed(0) + ' KB)');
    await doltImportInto(table, bytes, message + ': ' + table.name);
  }
  console.log('pushed; the branch head is ' + (await doltHead(DOLTHUB.branch)));
}

async function pull(opts) {
  const ref = opts.ref || DOLTHUB.branch;
  const dir = tablesDir(opts);
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  console.log('pulling ' + dolt.page() + ' (' + ref + ') into ' + dir);
  for (const table of TABLES) {
    const res = await fetchRetry(dolt.csv(ref, table.name));
    if (!res.ok && table.optional) {
      writeFileSync(csvOf(opts, table), table.columns.join(',') + '\n');
      console.log('  ' + table.name + ': not in ' + ref + ' (' + res.status + '), no rows');
      continue;
    }
    if (!res.ok) throw new Error(dolt.csv(ref, table.name) + ': ' + res.status);
    const text = await res.text();
    writeFileSync(csvOf(opts, table), text);
    console.log('  ' + table.name + ': ' + rowsIn(text) + ' rows, ' + (text.length / 1024).toFixed(0) + ' KB');
  }
  const { recording, file } = writeRecording(opts, { branch: ref, commit: await doltHead(ref) });
  console.log('wrote ' + file + ' (commit ' + recording.source.commit + ')');
}

// --- load --------------------------------------------------------------------

/** The CSV files into the running Spider Sense's database, which created the schema. */
async function load(opts) {
  const url = (opts.url || 'http://127.0.0.1:4090').replace(/\/$/, '');
  const meta = readMeta(opts);
  const res = await fetch(url + '/api/sql', {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ sql: "SELECT value FROM meta WHERE key = 'schema_version'", limit: 1 }),
  });
  const answer = await res.json();
  const schema = answer.rows && answer.rows[0] ? String(answer.rows[0][0]) : null;
  if (!schema) throw new Error('no Spider Sense at ' + url + ': ' + JSON.stringify(answer).slice(0, 200));
  if (schema !== meta.schema_version) {
    throw new Error('the recording is schema version ' + meta.schema_version + ' and this Spider Sense is ' + schema + '; record it again');
  }

  const statements = [];
  for (const table of TABLES) {
    if (!table.h2) continue;
    const read = 'CSVREAD(' + sqlString(csvOf(opts, table)) + ", NULL, 'charset=UTF-8')";
    const cells = table.columns.map((c) => {
      const cell = c.toUpperCase();   // H2 reads the header's names as unquoted identifiers
      if (table.bool.includes(c)) return 'CAST(' + cell + ' AS BOOLEAN)';
      if (table.required.includes(c)) return "COALESCE(" + cell + ", '')";
      return cell;
    }).join(', ');
    if (table.window === 'meta') {
      statements.push('MERGE INTO meta (key, value) SELECT ' + cells + ' FROM ' + read + " WHERE KEY LIKE 'recording.%'");
    } else {
      statements.push('INSERT INTO ' + table.name + ' (' + table.columns.join(', ') + ') SELECT ' + cells + ' FROM ' + read);
    }
  }
  console.log('loading ' + tablesDir(opts) + ' into ' + jdbcUrl(opts.db));
  const out = h2(opts, statements.join(';\n'));
  const counts = [...out.matchAll(/^\(Update count: (\d+)/gm)].map((m) => Number(m[1]));
  console.log('  ' + TABLES.filter((t) => t.h2).map((t, i) => t.name + ' ' + (counts[i] ?? '?')).join(', '));
}

// --- capture -----------------------------------------------------------------
//
// What the page cannot make from the tables by itself: every aggregation, over the
// window, unfiltered and per service, plus every endpoint, query, error and metric
// series they mention, every pair of marks for compare, and the text rendering of
// every finding, query and error of the unfiltered lists. The lists the page makes
// from the tables (traces, one trace, logs, marks, acks) are not asked.

const QUERY_SORTS = ['total', 'calls', 'avg', 'p95', 'max'];
const MAX_METRICS_PER_SERVICE = 120;
const CONCURRENCY = 4;

async function capture(opts) {
  const url = (opts.url || 'http://127.0.0.1:4090').replace(/\/$/, '');
  const info = JSON.parse(readFileSync(resolve(root, opts.recording || join(DATA, 'recording.json')), 'utf8'));
  const { from, to } = info.window;
  const home = homedir();
  const answers = new Map();

  function keyOf(path, params) {
    const names = Object.keys(params).filter((k) => params[k] !== undefined && params[k] !== null && params[k] !== '').sort();
    return path + (names.length ? '?' + names.map((k) => k + '=' + params[k]).join('&') : '');
  }

  async function get(path, params = {}, windowed = true) {
    const key = keyOf(path, params);
    if (answers.has(key)) return answers.get(key).body;
    const usp = new URLSearchParams();
    if (windowed) { usp.set('from', String(from)); usp.set('to', String(to)); }
    for (const [k, v] of Object.entries(params)) {
      if (v === undefined || v === null || v === '') continue;
      usp.set(k, String(v));
    }
    const full = url + path + (usp.size ? '?' + usp.toString() : '');
    const res = await fetch(full, { headers: { accept: 'application/json' } });
    let text = await res.text();
    if (!res.ok) {
      console.warn('skip ' + key + ': ' + res.status);
      return null;
    }
    text = text.split(home).join('/home/me');
    let body = null;
    try { body = JSON.parse(text); } catch (e) { body = null; }
    answers.set(key, { text, body });
    return body;
  }

  async function each(items, fn) {
    const queue = [...items];
    const workers = [];
    for (let i = 0; i < CONCURRENCY; i++) {
      workers.push((async () => {
        while (queue.length) await fn(queue.shift());
      })());
    }
    await Promise.all(workers);
  }

  console.log('capturing ' + url + ' over ' + new Date(from).toISOString() + ' .. ' + new Date(to).toISOString());

  const status = await get('/api/status', {}, false);
  if (!status) throw new Error('no Spider Sense at ' + url);
  const servicesRes = await get('/api/services');
  const services = (servicesRes && servicesRes.services || []).map((s) => s.name);
  await get('/api/overview');
  await get('/api/map');
  for (const name of services) await get('/api/services/' + encodeURIComponent(name));

  const scopes = ['', ...services];
  const endpoints = new Set();
  const queries = new Set();
  const errors = new Set();
  const metrics = new Map();
  const findings = new Set();
  for (const service of scopes) {
    const s = { service };
    const listed = await get('/api/findings', { ...s, limit: 100 });
    if (!service) for (const f of (listed && listed.findings) || []) findings.add(f.id);
    await get('/api/findings', { ...s, limit: 5, hideAcked: 'true' });
    const eps = await get('/api/endpoints', s);
    for (const e of (eps && eps.endpoints) || []) endpoints.add(e.endpointId);
    await get('/api/scatter', { ...s, limit: 5000 });
    for (const sort of QUERY_SORTS) {
      const q = await get('/api/queries', { ...s, sort, limit: 100 });
      for (const row of (q && q.queries) || []) queries.add(row.queryId);
    }
    const errs = await get('/api/errors', { ...s, limit: 100 });
    for (const row of (errs && errs.errors) || []) errors.add(row.errorId);
    await get('/api/jvm', s);
    const catalog = await get('/api/metrics', s, false);
    metrics.set(service, ((catalog && catalog.metrics) || []).map((m) => m.name).slice(0, MAX_METRICS_PER_SERVICE));
  }

  await each([...endpoints], (id) => get('/api/endpoints/' + encodeURIComponent(id)));
  await each([...queries], (id) => get('/api/queries/' + encodeURIComponent(id)));
  await each([...errors], (id) => get('/api/errors/' + encodeURIComponent(id)));
  // The text renderings Copy as Markdown asks for (docs/ui.md), over the whole window.
  const TEXT = { format: 'text' };
  await each([...findings], (id) => get('/api/findings/' + encodeURIComponent(id), TEXT));
  await each([...queries], (id) => get('/api/queries/' + encodeURIComponent(id), TEXT));
  await each([...errors], (id) => get('/api/errors/' + encodeURIComponent(id), TEXT));

  const series = [];
  for (const [service, names] of metrics) {
    for (const name of names) {
      series.push({ service, name });
      series.push({ service, name, rate: '1' });
    }
  }
  await each(series, (p) => get('/api/metrics/series', p));

  const marksRes = await fetch(url + '/api/marks?limit=50').then((r) => r.json()).catch(() => null);
  const marks = ((marksRes && marksRes.marks) || []).map((m) => m.name);
  const selectors = [...new Set([...marks, 'start'])];
  for (const service of scopes) {
    for (const before of selectors) {
      for (const after of selectors) {
        if (before === after) continue;
        await get('/api/compare', { before, after, service }, false);
      }
    }
  }

  const manifest = {
    capturedAt: Date.now(),
    recordedAt: info.recordedAt,
    window: { from, to },
    version: status.version || null,
    services,
    keys: [...answers.keys()],
  };
  const rows = [{ key: '/manifest', body: JSON.stringify(manifest) }];
  for (const [key, value] of answers) rows.push({ key, body: value.text });
  const table = TABLES.find((t) => t.name === 'answer');
  mkdirSync(tablesDir(opts), { recursive: true });
  const csv = toCsv(table.columns, rows);
  writeFileSync(csvOf(opts, table), csv);
  console.log('captured ' + answers.size + ' answers, ' + services.length + ' services, into ' + csvOf(opts, table)
    + ' (' + (csv.length / 1024).toFixed(0) + ' KB)');
}

// --- the agent demo ------------------------------------------------------------
//
// scripts/agent-demo.sh keeps what Claude Code (`-p --output-format stream-json`) or
// Codex CLI (`exec --json`) printed, each line stamped with the time it arrived, as
// build/agent-demo/<agent>-<lang>.jsonl. agent-push reads them as one row per session
// and one row per thing the terminal would have shown (the prompt, a message, a spell
// of thinking, a tool call with its output), with the home directory anonymised, and
// replaces the two tables on DoltHub with them, keeping the sessions it was not given.

const AGENT_TABLES = [
  { name: 'agent_session', key: ['id'],
    columns: 'id agent lang agent_version model cwd prompt recorded_at duration_ms turns summary',
    ddl: `id VARCHAR(32) NOT NULL, agent VARCHAR(16) NOT NULL, lang VARCHAR(8) NOT NULL, agent_version VARCHAR(64),
          model VARCHAR(128), cwd VARCHAR(1024), prompt TEXT NOT NULL, recorded_at BIGINT NOT NULL,
          duration_ms BIGINT NOT NULL, turns INT, summary TEXT` },
  { name: 'agent_event', key: ['session_id', 'seq'],
    columns: 'session_id seq at_ms end_ms kind tool title input output error',
    ddl: `session_id VARCHAR(32) NOT NULL, seq INT NOT NULL, at_ms BIGINT NOT NULL, end_ms BIGINT, kind VARCHAR(16) NOT NULL,
          tool VARCHAR(64), title TEXT, input TEXT, output LONGTEXT, error BOOLEAN NOT NULL` },
];
for (const t of AGENT_TABLES) t.columns = t.columns.split(/\s+/).filter(Boolean);

/** A string with the home directory anonymised, in the path and in the dashed form Claude Code's temp paths use. */
function anonymised(text) {
  if (text === null || text === undefined) return text;
  const home = homedir();
  return String(text).split(home).join('/home/me').split(home.replace(/\//g, '-')).join('-home-me');
}

function stampedLines(file) {
  return readFileSync(file, 'utf8').split('\n').filter(Boolean).map((line) => {
    const tab = line.indexOf('\t');
    try {
      return { t: Number(line.slice(0, tab)), e: JSON.parse(line.slice(tab + 1)) };
    } catch (e) {
      return null;
    }
  }).filter(Boolean);
}

/** The text of a Claude tool_result's content, which is a string or a list of blocks. */
function resultText(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content.map((c) => (c.type === 'text' ? c.text : c.type === 'tool_reference' ? c.tool_name : '')).join('\n');
}

/** Claude Code's stream: assistant blocks in order, each tool_use closed by the tool_result that answers it. */
function claudeSession(lines, started) {
  const session = { agent: 'claude', model: null, agent_version: null, cwd: null, duration_ms: 0, turns: null, summary: null };
  const events = [];
  const open = new Map();
  const hidden = new Set(['ToolSearch']);   // plumbing the terminal does not show as a step of the work
  for (const { t, e } of lines) {
    const at = t - started;
    if (e.type === 'system' && e.subtype === 'init') {
      session.model = e.model;
      session.agent_version = e.claude_code_version || null;
      session.cwd = e.cwd;
    } else if (e.type === 'assistant') {
      for (const block of e.message.content || []) {
        if (block.type === 'text' && block.text.trim()) {
          events.push({ at_ms: at, kind: 'text', output: block.text });
        } else if (block.type === 'thinking' || block.type === 'redacted_thinking') {
          const last = events[events.length - 1];
          if (last && last.kind === 'thinking') last.end_ms = at;
          else events.push({ at_ms: at, end_ms: at, kind: 'thinking', output: block.thinking || '' });
        } else if (block.type === 'tool_use') {
          if (hidden.has(block.name)) { open.set(block.id, null); continue; }
          const input = block.input || {};
          const title = input.command ?? input.file_path ?? input.pattern ?? input.description ?? input.prompt ?? '';
          const ev = { at_ms: at, kind: 'tool', tool: block.name, title: String(title), input: JSON.stringify(input), output: '', error: false };
          events.push(ev);
          open.set(block.id, ev);
        }
      }
    } else if (e.type === 'user' && Array.isArray(e.message && e.message.content)) {
      for (const block of e.message.content) {
        if (block.type !== 'tool_result') continue;
        const ev = open.get(block.tool_use_id);
        if (!ev) continue;
        ev.end_ms = at;
        ev.output = resultText(block.content);
        ev.error = !!block.is_error;
      }
    } else if (e.type === 'result') {
      session.duration_ms = at;   // on the stamps' clock, which the events are on; the result's own starts later
      session.turns = e.num_turns || null;
      session.summary = e.result || null;
      events.push({ at_ms: at, kind: 'end', output: '' });
    }
  }
  return { session, events };
}

/** `/bin/bash -lc '…'` as the command itself. */
function shellCommand(command) {
  const m = /^(?:\/\S*\/)?(?:bash|sh|zsh) -l?c (['"])([\s\S]*)\1$/.exec(String(command).trim());
  if (!m) return String(command);
  return m[1] === "'" ? m[2].replace(/'\\''/g, "'") : m[2].replace(/\\"/g, '"');
}

/** Codex CLI's stream: items as they complete, a command with its output. */
function codexSession(lines, started) {
  const session = { agent: 'codex', model: null, agent_version: null, cwd: root, duration_ms: 0, turns: 0, summary: null };
  const events = [];
  const byId = new Map();
  for (const { t, e } of lines) {
    const at = t - started;
    if (e.type === 'turn.started') session.turns++;
    if (e.type === 'turn.completed') {
      session.duration_ms = at;
      events.push({ at_ms: at, kind: 'end', output: '' });
    }
    if (!e.item || !String(e.type).startsWith('item.')) continue;
    const item = e.item;
    let ev = byId.get(item.id);
    if (!ev) {
      if (item.type === 'agent_message') ev = { at_ms: at, kind: 'text', output: '' };
      else if (item.type === 'reasoning') ev = { at_ms: at, kind: 'thinking', output: '' };
      else if (item.type === 'command_execution') ev = { at_ms: at, kind: 'tool', tool: 'shell', title: '', output: '', error: false };
      else if (item.type === 'file_change') ev = { at_ms: at, kind: 'tool', tool: 'apply_patch', title: '', output: '', error: false };
      else if (item.type === 'mcp_tool_call') ev = { at_ms: at, kind: 'tool', tool: 'mcp', title: '', output: '', error: false };
      else if (item.type === 'web_search') ev = { at_ms: at, kind: 'tool', tool: 'web_search', title: '', output: '', error: false };
      else if (item.type === 'todo_list') ev = { at_ms: at, kind: 'plan', output: '' };
      else continue;
      byId.set(item.id, ev);
      events.push(ev);
    }
    if (item.type === 'agent_message' || item.type === 'reasoning') ev.output = item.text || '';
    else if (item.type === 'command_execution') {
      ev.title = shellCommand(item.command || '');
      ev.input = JSON.stringify({ command: item.command });
      ev.output = item.aggregated_output || '';
      if (item.exit_code !== undefined && item.exit_code !== null) ev.error = item.exit_code !== 0;
    } else if (item.type === 'file_change') {
      ev.title = (item.changes || []).map((c) => c.kind + ' ' + c.path).join(', ');
      ev.input = JSON.stringify(item.changes || []);
    } else if (item.type === 'mcp_tool_call') {
      ev.title = (item.server || '') + '.' + (item.tool || '');
      ev.input = JSON.stringify(item.arguments || {});
      ev.output = JSON.stringify(item.result || item.error || '');
    } else if (item.type === 'web_search') ev.title = item.query || '';
    else if (item.type === 'todo_list') ev.output = JSON.stringify(item.items || []);
    if (e.type === 'item.completed') ev.end_ms = at;
  }
  const last = events.filter((ev) => ev.kind === 'text').pop();
  session.summary = last ? last.output : null;
  return { session, events };
}

function agentVersion(agent) {
  const r = spawnSync(agent, ['--version'], { encoding: 'utf8' });
  const m = /(\d+\.\d+\.\d+)/.exec((r.stdout || '') + (r.stderr || ''));
  return m ? m[1] : null;
}

function codexModel() {
  try {
    const text = readFileSync(join(homedir(), '.codex/config.toml'), 'utf8');
    const model = /^model\s*=\s*"([^"]+)"/m.exec(text);
    const effort = /^model_reasoning_effort\s*=\s*"([^"]+)"/m.exec(text);
    return model ? model[1] + (effort ? ' ' + effort[1] : '') : null;
  } catch (e) {
    return null;
  }
}

/** One recorded session as its rows. */
function agentRows(dir, id) {
  const [agent, lang] = id.split('-');
  const started = Number(readFileSync(join(dir, id + '.started'), 'utf8').trim());
  const prompt = readFileSync(join(dir, id + '.prompt'), 'utf8').trim();
  const lines = stampedLines(join(dir, id + '.jsonl'));
  if (!lines.length) throw new Error(id + ': the stream is empty; see ' + join(dir, id + '.err'));
  const { session, events } = agent === 'claude' ? claudeSession(lines, started) : codexSession(lines, started);
  if (agent === 'codex') {
    // What agent-demo.sh wrote down when it ran the session, else what is installed now.
    const noted = (ext) => (existsSync(join(dir, id + ext)) ? readFileSync(join(dir, id + ext), 'utf8').trim() : '');
    session.agent_version = noted('.version') || agentVersion('codex');
    session.model = noted('.model') || codexModel();
  }
  if (!session.duration_ms) session.duration_ms = lines[lines.length - 1].t - started;
  events.unshift({ at_ms: 0, kind: 'user', output: prompt });
  const sessionRow = {
    id, agent, lang, agent_version: session.agent_version, model: session.model, cwd: anonymised(session.cwd),
    prompt, recorded_at: started, duration_ms: session.duration_ms, turns: session.turns, summary: anonymised(session.summary),
  };
  const eventRows = events.map((ev, i) => ({
    session_id: id, seq: i, at_ms: Math.max(0, ev.at_ms), end_ms: ev.end_ms ?? null, kind: ev.kind, tool: ev.tool ?? null,
    title: anonymised(ev.title ?? null), input: anonymised(ev.input ?? null), output: anonymised(ev.output ?? ''),
    error: ev.error ? 1 : 0,
  }));
  return { sessionRow, eventRows };
}

function rowsCsv(table, rows) {
  return toCsv(table.columns, rows);
}

async function agentPush(opts) {
  const dir = resolve(root, opts.dir || 'build/agent-demo');
  const ids = (existsSync(dir) ? readdirSync(dir) : []).filter((f) => f.endsWith('.jsonl')).map((f) => f.slice(0, -6)).sort();
  if (!ids.length) throw new Error('no recorded session in ' + dir + ' (scripts/agent-demo.sh record)');
  const sessions = [];
  const events = [];
  for (const id of ids) {
    const { sessionRow, eventRows } = agentRows(dir, id);
    console.log(id + ': ' + eventRows.length + ' events over ' + (sessionRow.duration_ms / 1000).toFixed(0) + ' s, '
      + sessionRow.agent + ' ' + sessionRow.agent_version + ', ' + sessionRow.model);
    sessions.push(sessionRow);
    events.push(...eventRows);
    // What the page reads, for a look at a session before it is pushed (agent-demo.js, ?rows=).
    writeFileSync(join(dir, id + '.rows.json'), JSON.stringify({ session: sessionRow, events: eventRows }));
  }
  if (opts['dry-run']) {
    writeFileSync(join(dir, 'agent_session.csv'), rowsCsv(AGENT_TABLES[0], sessions));
    writeFileSync(join(dir, 'agent_event.csv'), rowsCsv(AGENT_TABLES[1], events));
    console.log('wrote ' + join(dir, 'agent_session.csv') + ' and agent_event.csv; nothing pushed');
    return;
  }
  // The sessions on DoltHub that were not recorded here stay: an import replaces a table.
  await doltEnsureTables(AGENT_TABLES);
  const kept = new Set(ids);
  const otherIds = (await doltQuery(DOLTHUB.branch, 'SELECT id FROM agent_session')).map((r) => r.id).filter((id) => !kept.has(id));
  for (const id of otherIds) {
    console.log(id + ': kept as it is on DoltHub');
    sessions.push(...await doltQuery(DOLTHUB.branch, 'SELECT * FROM agent_session WHERE id = ' + sqlString(id)));
    for (let from = 0; ; from += 1000) {
      const rows = await doltQuery(DOLTHUB.branch, 'SELECT * FROM agent_event WHERE session_id = ' + sqlString(id)
        + ' ORDER BY seq LIMIT 1000 OFFSET ' + from);
      events.push(...rows);
      if (rows.length < 1000) break;
    }
  }
  const message = 'Agent demo: ' + ids.join(', ') + ' recorded ' + new Date(Math.max(...sessions.map((s) => Number(s.recorded_at)))).toISOString().slice(0, 16).replace('T', ' ') + ' UTC';
  for (const [table, rows] of [[AGENT_TABLES[0], sessions], [AGENT_TABLES[1], events]]) {
    const bytes = Buffer.from(rowsCsv(table, rows));
    console.log('import ' + table.name + ' (' + rows.length + ' rows, ' + (bytes.length / 1024).toFixed(0) + ' KB)');
    await doltImportInto(table, bytes, message + ': ' + table.name);
  }
  console.log('pushed; the branch head is ' + (await doltHead(DOLTHUB.branch)));
}

// --- assemble ----------------------------------------------------------------

/** The UI as a static page that reads the DoltHub database: nothing else is copied. */
function assemble(opts) {
  const out = resolve(root, opts._[0] || 'build/demo-site');
  const ref = opts.ref || DOLTHUB.branch;
  const source = DOLTHUB.owner + '/' + DOLTHUB.database + '@' + ref;
  rmSync(out, { recursive: true, force: true });
  mkdirSync(out, { recursive: true });
  cpSync(PUBLIC, out, { recursive: true });
  const index = readFileSync(join(PUBLIC, 'index.html'), 'utf8')
    .replace('<html lang="en"', '<html lang="en" data-dolthub="' + source + '"')
    .replace('<title>Spider Sense</title>', '<title>Spider Sense demo</title>');
  if (!index.includes('data-dolthub')) throw new Error('index.html has no <html lang="en" to mark');
  writeFileSync(join(out, 'index.html'), index);
  console.log('assembled ' + out + ' over ' + source + ' (open it through any static file server, e.g. python3 -m http.server -d ' + out + ')');
  assembleAgentDemo(join(dirname(out), 'agent-demo'), source);
}

/**
 * The agent demo beside the UI demo: one page per recorded session, at
 * agent-demo/{claude-code,codex}/ in English and at …/ko/ in Korean, over the assets
 * they share; agent-demo/ itself leads to the first.
 */
function assembleAgentDemo(out, source) {
  const from = join(root, 'scripts/agent-demo');
  rmSync(out, { recursive: true, force: true });
  mkdirSync(join(out, 'assets'), { recursive: true });
  for (const file of ['agent-demo.js', 'agent-demo.css']) cpSync(join(from, file), join(out, 'assets', file));
  cpSync(join(PUBLIC, 'assets/logo.svg'), join(out, 'assets/logo.svg'));
  const template = readFileSync(join(from, 'index.html'), 'utf8');
  for (const [dir, agent] of [['claude-code', 'claude'], ['codex', 'codex']]) {
    for (const lang of ['en', 'ko']) {
      const base = lang === 'en' ? '../' : '../../';
      const page = template
        .replace('<html lang="en" data-session="claude-en" data-dolthub="benelog/spider-sense-demo@main">',
          '<html lang="' + lang + '" data-session="' + agent + '-' + lang + '" data-dolthub="' + source + '" data-base="' + base + '">')
        .replaceAll('{{assets}}', base + 'assets')
        .replaceAll('{{root}}', base + '../');
      if (!page.includes('data-base=')) throw new Error('scripts/agent-demo/index.html has no <html> to mark');
      const dirOut = join(out, dir, lang === 'en' ? '' : 'ko');
      mkdirSync(dirOut, { recursive: true });
      writeFileSync(join(dirOut, 'index.html'), page);
    }
  }
  writeFileSync(join(out, 'index.html'), '<!doctype html><meta charset="utf-8"><title>Spider Sense agent demo</title>'
    + '<meta http-equiv="refresh" content="0; url=claude-code/"><a href="claude-code/">Claude Code</a> · <a href="codex/">Codex CLI</a>\n');
  console.log('assembled ' + out + ': claude-code/, claude-code/ko/, codex/, codex/ko/');
}

// --- main --------------------------------------------------------------------

const opts = options(process.argv.slice(3));
if (opts.dolthub) {
  const [owner, database] = opts.dolthub.split('/');
  if (owner && database) Object.assign(DOLTHUB, { owner, database });
}
if (opts.branch) DOLTHUB.branch = opts.branch;

const command = process.argv[2];
try {
  if (command === 'export') exportTables(opts);
  else if (command === 'push') await push(opts);
  else if (command === 'pull') await pull(opts);
  else if (command === 'load') await load(opts);
  else if (command === 'capture') await capture(opts);
  else if (command === 'agent-push') await agentPush(opts);
  else if (command === 'assemble') assemble(opts);
  else {
    console.error([
      'usage: demo-site.mjs export --db=<h2 path> --since=<ms> --until=<ms> [--from=<ms> --to=<ms>] [--jar=] [--tables=<dir>]',
      '       demo-site.mjs load --db=<h2 path> [--url=<spider sense>] [--jar=] [--tables=<dir>]',
      '       demo-site.mjs capture [--url=<spider sense>] [--recording=<file>] [--tables=<dir>]',
      '       demo-site.mjs push [--only=<table,...>] [--tables=<dir>] [--dolthub=owner/database] [--branch=]   (DOLTHUB_TOKEN)',
      '       demo-site.mjs pull [--tables=<dir>] [--recording=<file>] [--ref=<branch or commit>]',
      '       demo-site.mjs agent-push [--dir=build/agent-demo] [--dry-run] [--dolthub=owner/database] [--branch=]   (DOLTHUB_TOKEN)',
      '       demo-site.mjs assemble [<out dir>] [--dolthub=owner/database] [--ref=<branch or commit>]',
    ].join('\n'));
    process.exit(2);
  }
} catch (e) {
  console.error('demo-site: ' + (e && e.message ? e.message : e));
  process.exit(1);
}

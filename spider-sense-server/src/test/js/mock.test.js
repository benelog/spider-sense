// dev/mock.js answers in the shapes api.adoc names, so a page developed against ?mock=1 meets
// what the server sends (ui.adoc#mock).
import './browser-env.js';
import { test, before } from 'node:test';
import assert from 'node:assert/strict';

const get = async (path) => {
  const res = await fetch(path);
  return { status: res.status, body: await res.json() };
};

before(async () => {
  const log = console.log;
  console.log = () => {};          // the mock announces itself
  await import('../../main/resources/public/assets/js/dev/mock.js');
  console.log = log;
});

test('a query group, a compare row and a finding say whether the statement was cut', async () => {
  const { body: queries } = await get('/api/queries');
  assert.ok(queries.queries.length > 0);
  for (const q of queries.queries) assert.equal(q.statementCut, false, q.queryId);
  const now = Date.now();
  const { body: compare } = await get(`/api/compare?before=${now - 10 * 60000}&after=${now - 5 * 60000}&until=${now}`);
  assert.ok(compare.queries.length > 0);
  for (const q of compare.queries) assert.equal(q.statementCut, false, q.queryId);
  const { body: findings } = await get('/api/findings?limit=3');
  assert.ok(findings.findings.length > 0);
  for (const f of findings.findings) assert.equal(f.statementCut, false, f.id);
});

test('the findings say how many the list was cut from', async () => {
  const { body } = await get('/api/findings?limit=2');
  assert.equal(typeof body.total, 'number');
  assert.ok(body.total >= body.findings.length);
  const { body: all } = await get('/api/findings?limit=100');
  assert.equal(body.total, all.findings.length, 'counted before the limit');
});

test('the status names the endpoints left out', async () => {
  const { body } = await get('/api/status');
  assert.ok(Array.isArray(body.ignore.endpoints));
});

test('a query or an endpoint with no call in the window is 404', async () => {
  const { body: queries } = await get('/api/queries?limit=1');
  const { body: endpoints } = await get('/api/endpoints');
  const empty = 'from=1000&to=2000';
  assert.equal((await get('/api/queries/' + queries.queries[0].queryId + '?' + empty)).status, 404);
  assert.equal((await get('/api/endpoints/' + endpoints.endpoints[0].endpointId + '?' + empty)).status, 404);
  assert.equal((await get('/api/queries/' + queries.queries[0].queryId)).status, 200, 'and 200 over a window with calls');
});

test('a database span\'s summary is the operation and the table, as the server writes it', async () => {
  const { body: list } = await get('/api/traces?limit=50');
  const summaries = [];
  for (const t of list.traces) {
    const { body } = await get('/api/traces/' + t.traceId);
    for (const s of body.spans) if (s.category === 'db') summaries.push(s.summary);
    if (summaries.length) break;
  }
  assert.ok(summaries.length > 0, 'a trace with a database span');
  for (const s of summaries) assert.match(s, /^[A-Z]+ \w+$/, s);
});

// Last, since it clears what the tests above read.
test('Clear data deletes the marks but each service\'s newest start mark, as DELETE /api/data does (api.adoc#data)', async () => {
  const post = (body) => fetch('/api/marks', { method: 'POST', body: JSON.stringify(body) });
  await post({ name: 'start', service: 'silk-bookstore', at: Date.now() - 1000 });
  await post({ name: 'checkpoint' });
  const before = (await get('/api/marks')).body.marks;
  assert.ok(before.filter((mk) => mk.name === 'start' && mk.service === 'silk-bookstore').length >= 2);
  assert.equal((await fetch('/api/data', { method: 'DELETE' })).status, 204);
  const after = (await get('/api/marks')).body.marks;
  assert.deepEqual(after.map((mk) => [mk.name, mk.service]).sort(), [['start', 'silk-bookstore'], ['start', 'spring-orders']]);
  const newest = before.filter((mk) => mk.name === 'start' && mk.service === 'silk-bookstore').sort((a, b) => b.at - a.at)[0];
  assert.equal(after.find((mk) => mk.service === 'silk-bookstore').id, newest.id);
});

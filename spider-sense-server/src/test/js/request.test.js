// api.js: every request reads its answer and its refusal one way (api.adoc#errors).
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  getJSON, getText, postJSON, clearData, unackFinding, ackFinding, ApiError,
} from '../../main/resources/public/assets/js/api.js';

const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

/** A fetch that records each call and answers with `status` and `body`. */
function answer(status, body, statusText = 'Not Found') {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return new Response(body, { status, statusText: status < 300 ? 'OK' : statusText });
  };
  return calls;
}

test('getJSON sends the query without its empty values and parses the answer', async () => {
  const calls = answer(200, '{"a":1}');
  assert.deepEqual(await getJSON('/api/x', { q: 'a b', none: '', n: 0 }), { a: 1 });
  assert.equal(calls[0].url, '/api/x?q=a+b&n=0');
  assert.equal(calls[0].init.method, 'GET');
  assert.equal(calls[0].init.headers.accept, 'application/json');
});

test('getJSON asks once for the same URL asked twice at once', async () => {
  const calls = answer(200, '{}');
  await Promise.all([getJSON('/api/same'), getJSON('/api/same')]);
  assert.equal(calls.length, 1);
});

test('a refusal carries the server sentence, whatever the verb', async () => {
  answer(404, '{"error":"No such acknowledgement: f1"}');
  await assert.rejects(unackFinding('f1'), (e) => e instanceof ApiError && e.status === 404 && e.message === 'No such acknowledgement: f1');
  answer(409, '{"error":"The store is locked"}', 'Conflict');
  await assert.rejects(clearData(), { message: 'The store is locked' });
  answer(400, '{"error":"bad note"}', 'Bad Request');
  await assert.rejects(ackFinding('f1', 'x'), { message: 'bad note' });
});

test('a refusal without a JSON body says the status line', async () => {
  answer(502, 'upstream gone', 'Bad Gateway');
  await assert.rejects(getJSON('/api/y'), { message: '502 Bad Gateway' });
});

test('postJSON sends JSON, and a DELETE answers null', async () => {
  let calls = answer(200, '{"findingId":"f1"}');
  assert.deepEqual(await ackFinding('a/b', ''), { findingId: 'f1' });
  assert.equal(calls[0].url, '/api/findings/a%2Fb/ack');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.headers['content-type'], 'application/json');
  assert.equal(calls[0].init.body, '{"note":null}');
  calls = answer(204, null);
  assert.equal(await unackFinding('f1'), null);
  assert.equal(calls[0].init.method, 'DELETE');
  calls = answer(200, '');
  assert.equal(await postJSON('/api/z'), null);
  assert.equal(calls[0].init.body, '{}');
});

test('getText asks for the text rendering and answers it as it came', async () => {
  const calls = answer(200, '# Findings\n');
  assert.equal(await getText('/api/findings', { from: 1 }), '# Findings\n');
  assert.equal(calls[0].url, '/api/findings?from=1&format=text');
  assert.equal(calls[0].init.headers.accept, 'text/markdown');
});

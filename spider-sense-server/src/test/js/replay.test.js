// dev/replay.js: the recorded demo answers the lists from the recording's tables with the
// filters the API takes (ui.adoc#recording), here against a DoltHub that records the SQL.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import './browser-env.js';

const asked = [];
const answers = { '/manifest': { keys: [], window: { from: 1000, to: 61000 } } };
const loggerRow = {
  id: '7', at_ms: '2000', service: 'silk-bookstore', severity: 'ERROR', severity_number: '17',
  body: 'Payment declined', logger: 'net.benelog.Checkout', trace_id: null, span_id: null, attributes: '{}',
};

globalThis.fetch = async (input) => {
  const url = new URL(typeof input === 'string' ? input : input.url);
  const query = decodeURIComponent(url.search.slice('?q='.length));
  asked.push(query);
  let rows = [];
  const key = /FROM answer WHERE `key` = '([^']*)'/.exec(query);
  if (key) rows = answers[key[1]] ? [{ body: JSON.stringify(answers[key[1]]) }] : [];
  else if (/FROM log WHERE/.test(query)) {
    // the row matches only through its logger: the body does not repeat it
    const matches = /LOWER\(logger\) LIKE '%net\.benelog\.checkout%'/.test(query);
    rows = query.includes('COUNT(*)') ? [{ n: matches ? '1' : '0' }] : matches ? [loggerRow] : [];
  }
  return new Response(JSON.stringify({ query_execution_status: 'Success', rows }), { status: 200 });
};

await import('../../main/resources/public/assets/js/dev/replay.js');

test('q on the logs matches the logger, as the API does, so a log-error finding finds its records', async () => {
  const res = await fetch('/api/logs?severity=ERROR&q=net.benelog.Checkout');
  const body = await res.json();
  assert.equal(body.total, 1);
  assert.deepEqual(body.logs.map((l) => l.logger), ['net.benelog.Checkout']);
});

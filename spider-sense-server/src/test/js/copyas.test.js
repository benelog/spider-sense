// copyas.js: the CLI line a page hands to an agent (pages.adoc#copy-as-markdown).
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { state } from '../../main/resources/public/assets/js/api.js';
import { cliLine } from '../../main/resources/public/assets/js/copyas.js';

afterEach(() => { state.status = null; });

test('cliLine names the window in epoch milliseconds and the service when there is one', () => {
  assert.equal(cliLine('findings', { from: 1000.4, to: 2000.6 }), 'java -jar spider-sense.jar findings --since=1000 --until=2001');
  assert.equal(cliLine('errors', { from: 1, to: 2 }, 'spring-orders'), 'java -jar spider-sense.jar errors --since=1 --until=2 --service=spring-orders');
});

test('cliLine quotes a jar path or a service name the shell would split', () => {
  state.status = { jar: "/home/me/my jars/spider-sense.jar" };
  assert.equal(cliLine('queries', { from: 1, to: 2 }, "it's"),
    "java -jar '/home/me/my jars/spider-sense.jar' queries --since=1 --until=2 --service='it'\\''s'");
});

// copyas.js: the CLI line a page hands to an agent (pages.adoc#copy-as-markdown).
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { state } from '../../main/resources/public/assets/js/api.js';
import { cliLine, commandLine } from '../../main/resources/public/assets/js/copyas.js';

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

test('cliLine takes the jar it names when handed one', () => {
  assert.equal(cliLine('check', { from: 1, to: 2 }, '', 'sense.jar'), 'java -jar sense.jar check --since=1 --until=2');
});

test('a command line names --url when this Spider Sense is not the CLI default (pages.adoc#copy-as-markdown)', () => {
  state.status = { endpoint: 'http://127.0.0.1:4000', jar: '/opt/spider-sense-0.1.0.jar' };
  assert.equal(cliLine('findings', { from: 1, to: 2 }), 'java -jar /opt/spider-sense-0.1.0.jar findings --since=1 --until=2');
  assert.equal(commandLine('mark before'), 'java -jar /opt/spider-sense-0.1.0.jar mark before');
  state.status = { endpoint: 'http://127.0.0.1:4001', jar: '/opt/spider-sense-0.1.0.jar' };
  assert.equal(cliLine('findings', { from: 1, to: 2 }, 'orders'),
    'java -jar /opt/spider-sense-0.1.0.jar findings --since=1 --until=2 --service=orders --url=http://127.0.0.1:4001');
  assert.equal(commandLine('mark before'), 'java -jar /opt/spider-sense-0.1.0.jar mark before --url=http://127.0.0.1:4001');
});

// frames.js: the two renderings of a stack trace share one line classifier (pages.adoc#stack-traces).
import './fake-dom.js';
import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { state } from '../../main/resources/public/assets/js/api.js';
import { classifyLine, stackTrace, foldedStack } from '../../main/resources/public/assets/js/frames.js';

const TRACE = [
  'java.lang.IllegalStateException: boom',
  '\tat com.example.orders.Orders.ship(Orders.java:41)',
  '\tat org.springframework.web.Servlet.service(Servlet.java:1)',
  '\tat org.springframework.web.Filter.doFilter(Filter.java:2)',
  '\tat org.apache.catalina.Valve.invoke(Valve.java:3)',
  'Caused by: java.io.IOException: closed',
  '\tat com.example.orders.Store.read(Store.java:9)',
  '\t... 3 more',
].join('\n');

const classes = (pre) => pre.children.map((span) => span.className);

afterEach(() => { state.status = null; });

test('classifyLine tells frames, causes, more lines and heads apart', () => {
  assert.equal(classifyLine('\tat com.example.A.b(A.java:1)'), 'frame');
  assert.equal(classifyLine('Caused by: java.io.IOException'), 'cause');
  assert.equal(classifyLine('\tSuppressed: java.lang.Exception'), 'cause');
  assert.equal(classifyLine('\t... 12 more'), 'more');
  assert.equal(classifyLine('java.lang.IllegalStateException: boom'), 'head');
  assert.equal(classifyLine('  a message that goes on'), 'head');
});

test('stackTrace marks the frames of the first non-JDK package as own and dims the rest', () => {
  const pre = stackTrace(TRACE);
  assert.equal(pre.className, 'stack');
  assert.deepEqual(classes(pre), ['st-head', 'st-own', 'st-frame', 'st-frame', 'st-frame', 'st-cause', 'st-own', 'st-frame']);
  assert.equal(pre.children[1].textContent, '\tat com.example.orders.Orders.ship(Orders.java:41)\n');
});

test('foldedStack folds a run of framework frames and classifies the rest the same way', () => {
  state.status = { codeFrames: { appPackages: [], frameworkPrefixes: ['org.springframework.', 'org.apache.'] } };
  const pre = foldedStack(TRACE, 'app');
  assert.deepEqual(classes(pre), ['st-head', 'st-own', 'st-fold', 'st-run', 'st-cause', 'st-own', 'st-frame']);
  assert.equal(pre.children[2].textContent, '\t⋯ 3 frames from org.springframework, org.apache\n');
  assert.deepEqual(classes(foldedStack(TRACE, 'all')),
    ['st-head', 'st-own', 'st-frame', 'st-frame', 'st-frame', 'st-cause', 'st-own', 'st-frame']);
});

test('without the rules nothing is folded or highlighted', () => {
  assert.deepEqual(classes(foldedStack(TRACE, 'app')),
    ['st-head', 'st-frame', 'st-frame', 'st-frame', 'st-frame', 'st-cause', 'st-frame', 'st-frame']);
});

test('foldedStack takes the rules from a status handed in', () => {
  const status = { codeFrames: { appPackages: ['com.example.'], frameworkPrefixes: [] } };
  assert.deepEqual(classes(foldedStack(TRACE, 'app', status)),
    ['st-head', 'st-own', 'st-fold', 'st-run', 'st-cause', 'st-own', 'st-frame']);
});

// frames.js: editor links and the stack-trace line rules (pages.adoc#code-frames, #stack-traces).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  editorHref, frameOf, frameworkOf, foldLabel, framesMode,
} from '../../main/resources/public/assets/js/frames.js';

test('editorHref opens IntelliJ IDEA with the path encoded', () => {
  assert.equal(editorHref('/home/me/src/A B.java', 41, 'idea'), 'idea://open?file=%2Fhome%2Fme%2Fsrc%2FA%20B.java&line=41');
});

test('editorHref opens VS Code on an absolute path', () => {
  assert.equal(editorHref('/home/me/src/A B.java', 41, 'vscode'), 'vscode://file/home/me/src/A%20B.java:41');
  assert.equal(editorHref('C:/src/A.java', 7, 'vscode'), 'vscode://file/C:/src/A.java:7');
});

test('frameOf reads the frame of an at line and nothing else', () => {
  assert.equal(frameOf('\tat com.example.Orders.ship(Orders.java:41)'), 'com.example.Orders.ship(Orders.java:41)');
  assert.equal(frameOf('java.lang.IllegalStateException: boom'), null);
  assert.equal(frameOf('Caused by: java.io.IOException'), null);
  assert.equal(frameOf('\t... 12 more'), null);
});

test('frameOf drops the module or class loader in front and the jar behind', () => {
  assert.equal(frameOf('\tat java.base/java.lang.Thread.run(Thread.java:1583)'), 'java.lang.Thread.run(Thread.java:1583)');
  assert.equal(frameOf('\tat app//com.example.Orders.ship(Orders.java:41)'), 'com.example.Orders.ship(Orders.java:41)');
  assert.equal(
    frameOf('\tat org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:167) ~[tomcat-embed-core-10.1.jar:10.1]'),
    'org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:167)');
});

test('frameworkOf folds by the framework prefixes, or by an application allowlist', () => {
  const byPrefix = { app: [], framework: ['org.springframework.', 'java.'] };
  assert.equal(frameworkOf('org.springframework.web.Servlet.service(Servlet.java:1)', byPrefix), 'org.springframework');
  assert.equal(frameworkOf('com.example.Orders.ship(Orders.java:41)', byPrefix), null);
  const byApp = { app: ['com.example.'], framework: [] };
  assert.equal(frameworkOf('com.example.Orders.ship(Orders.java:41)', byApp), null);
  assert.equal(frameworkOf('org.hibernate.internal.Session.load(Session.java:9)', byApp), 'org.hibernate');
});

test('foldLabel counts the frames and names at most three packages', () => {
  const run = (pkgs) => pkgs.map((pkg) => ({ line: '', pkg }));
  assert.equal(foldLabel(run(['org.a', 'org.a', 'org.b'])), '3 frames from org.a, org.b');
  assert.equal(foldLabel(run(['p1', 'p2', 'p3', 'p4', 'p5'])), '5 frames from p1, p2, p3 +2');
});

test('framesMode is app unless the query says all', () => {
  assert.equal(framesMode({}), 'app');
  assert.equal(framesMode(null), 'app');
  assert.equal(framesMode({ frames: 'all' }), 'all');
  assert.equal(framesMode({ frames: 'other' }), 'app');
});

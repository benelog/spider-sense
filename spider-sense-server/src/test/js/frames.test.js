// frames.js: editor links and the stack-trace line rules (pages.adoc#code-frames, #stack-traces).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  editorHref, frameOf, frameworkOf, foldLabel, framesMode, appFrames,
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

test('frameOf keeps the name of a hidden class and drops its address', () => {
  assert.equal(frameOf('\tat com.acme.Foo$$Lambda/0x000073e4a00a2000.run(Unknown Source)'),
    'com.acme.Foo$$Lambda.run(Unknown Source)');
  assert.equal(frameOf('\tat com.acme.Foo$$Lambda$14/0x0000000800c02000.apply(Unknown Source)'),
    'com.acme.Foo$$Lambda$14.apply(Unknown Source)', 'JDK 17');
  assert.equal(frameOf('\tat java.base/java.lang.invoke.LambdaForm$DMH/0x0000000800c0c400.invokeVirtual(LambdaForm$DMH)'),
    'java.lang.invoke.LambdaForm$DMH.invokeVirtual(LambdaForm$DMH)', 'a module in front as well');
  const status = { codeFrames: { appPackages: [], frameworkPrefixes: ['org.springframework.'] } };
  const stack = [
    '\tat com.acme.Foo.bar(Foo.java:12)',
    '\tat org.springframework.aop.framework.CglibAopProxy$$Lambda/0x00007f0a1c0f3b28.proceed(Unknown Source)',
  ].join('\n');
  assert.deepEqual(appFrames(stack, status), ['com.acme.Foo.bar(Foo.java:12)'], 'a framework lambda is the framework\'s');
});

test('frameworkOf folds by the framework prefixes, or by an application allowlist', () => {
  const byPrefix = { app: [], framework: ['org.springframework.', 'java.'] };
  assert.equal(frameworkOf('org.springframework.web.Servlet.service(Servlet.java:1)', byPrefix), 'org.springframework');
  assert.equal(frameworkOf('com.example.Orders.ship(Orders.java:41)', byPrefix), null);
  const byApp = { app: ['com.example.'], framework: [] };
  assert.equal(frameworkOf('com.example.Orders.ship(Orders.java:41)', byApp), null);
  assert.equal(frameworkOf('org.hibernate.internal.Session.load(Session.java:9)', byApp), 'org.hibernate');
});

test('frameworkOf folds a generated class under generated, whatever its package', () => {
  const generated = ['$HibernateProxy', '$$SpringCGLIB$$', '(<generated>)'];
  const byPrefix = { app: [], framework: ['java.'], generated };
  assert.equal(frameworkOf('orders.domain.Product$HibernateProxy.getName(Unknown Source)', byPrefix), 'generated');
  assert.equal(frameworkOf('orders.OrderService$$SpringCGLIB$$0.load(<generated>)', byPrefix), 'generated');
  assert.equal(frameworkOf('orders.Thing.run(<generated>)', byPrefix), 'generated');
  assert.equal(frameworkOf('orders.OrderService.load(OrderService.java:78)', byPrefix), null);
  const byApp = { app: ['orders.'], framework: [], generated };
  assert.equal(frameworkOf('orders.domain.Product$HibernateProxy.getName(Unknown Source)', byApp), 'generated');
  const status = { codeFrames: { appPackages: [], frameworkPrefixes: ['java.'], generatedMarkers: generated } };
  const stack = [
    '\tat orders.domain.Product$HibernateProxy.getName(Unknown Source)',
    '\tat orders.OrderService.load(OrderService.java:78)',
    '\tat orders.OrderService$$SpringCGLIB$$0.load(<generated>)',
  ].join('\n');
  assert.deepEqual(appFrames(stack, status), ['orders.OrderService.load(OrderService.java:78)'],
    'what the span drawer lists is the finding\'s code');
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

test('appFrames lists the application frames of a captured stack, top first, each once, at most five', () => {
  const status = { codeFrames: { appPackages: [], frameworkPrefixes: ['org.h2.', 'java.'] } };
  const stack = [
    '\tat org.h2.jdbc.JdbcPreparedStatement.executeQuery(JdbcPreparedStatement.java:120)',
    '\tat orders.OrderRepository.load(OrderRepository.java:64)',
    '\tat orders.OrderRepository.load(OrderRepository.java:64)',
    '\tat java.base/java.lang.Thread.run(Thread.java:1583)',
    '\tat orders.OrderService.report(OrderService.java:18)',
  ].join('\n');
  assert.deepEqual(appFrames(stack, status), [
    'orders.OrderRepository.load(OrderRepository.java:64)', 'orders.OrderService.report(OrderService.java:18)']);
  const deep = Array.from({ length: 8 }, (_, i) => '\tat orders.A.m' + i + '(A.java:' + i + ')').join('\n');
  assert.equal(appFrames(deep, status).length, 5);
  assert.deepEqual(appFrames(stack, {}), [], 'without the rules nothing is told apart');
});

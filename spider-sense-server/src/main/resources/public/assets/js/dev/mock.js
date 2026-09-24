// A fetch and EventSource shim that answers every endpoint in api.adoc with
// generated data. Loaded only when the page URL carries ?mock=1.

const START = Date.now() - 15 * 60 * 1000;
const VERSION = '0.1.0';

let seed = 20260916;
function rnd() {
  seed = (seed * 1664525 + 1013904223) % 4294967296;
  return seed / 4294967296;
}
const pick = (arr) => arr[Math.floor(rnd() * arr.length)];
const between = (a, b) => a + rnd() * (b - a);
const gauss = () => (rnd() + rnd() + rnd() + rnd() - 2) / 2;
const hex = (n) => {
  let s = '';
  while (s.length < n) s += Math.floor(rnd() * 16).toString(16);
  return s.slice(0, n);
};
const hash = (s) => {
  let x = 2166136261;
  for (let i = 0; i < s.length; i++) { x ^= s.charCodeAt(i); x = Math.imul(x, 16777619); }
  return (x >>> 0).toString(16).padStart(8, '0');
};

// --- the world ----------------------------------------------------------

const SERVICES = [
  {
    name: 'silk-bookstore',
    language: 'java',
    embedded: false,
    hasJvm: true,
    resource: {
      'service.name': 'silk-bookstore',
      'telemetry.sdk.name': 'opentelemetry',
      'telemetry.sdk.language': 'java',
      'telemetry.sdk.version': '1.54.0',
      'process.runtime.name': 'OpenJDK 64-Bit Server VM',
      'process.runtime.version': '21.0.4+7',
      'process.pid': '48211',
      'host.name': 'benelog-dev',
      'host.arch': 'amd64',
      'os.type': 'linux',
    },
  },
  {
    name: 'spring-orders',
    language: 'java',
    embedded: false,
    hasJvm: true,
    resource: {
      'service.name': 'spring-orders',
      'telemetry.sdk.name': 'opentelemetry',
      'telemetry.sdk.language': 'java',
      'telemetry.sdk.version': '1.54.0',
      'process.runtime.name': 'OpenJDK 64-Bit Server VM',
      'process.runtime.version': '21.0.4+7',
      'process.pid': '48377',
      'host.name': 'benelog-dev',
      'host.arch': 'amd64',
      'os.type': 'linux',
    },
  },
];

const QUERIES = [
  { service: 'silk-bookstore', system: 'h2', namespace: 'bookstore', operation: 'SELECT', table: 'books', statement: 'select id, title, author, price from books where id = ?', base: 1.4, jitter: 0.8 },
  { service: 'silk-bookstore', system: 'h2', namespace: 'bookstore', operation: 'SELECT', table: 'books', statement: "select id, title, author from books where lower(title) like ? order by title", base: 240, jitter: 90 },
  { service: 'silk-bookstore', system: 'h2', namespace: 'bookstore', operation: 'SELECT', table: 'reviews', statement: 'select id, book_id, rating, body from reviews where book_id = ?', base: 1.1, jitter: 0.6 },
  { service: 'silk-bookstore', system: 'h2', namespace: 'bookstore', operation: 'SELECT', table: 'books', statement: 'select sleep(?) from books limit ?', base: 620, jitter: 120 },
  { service: 'spring-orders', system: 'h2', namespace: 'orders', operation: 'SELECT', table: 'orders', statement: 'select o.id, o.customer_id, o.total, o.status from orders o where o.customer_id = ?', base: 2.2, jitter: 1.4 },
  { service: 'spring-orders', system: 'h2', namespace: 'orders', operation: 'SELECT', table: 'orders', statement: 'select o.id, sum(l.amount) from orders o join order_lines l on l.order_id = o.id where o.created_at between ? and ? group by o.id order by ? desc', base: 310, jitter: 140 },
  { service: 'spring-orders', system: 'h2', namespace: 'orders', operation: 'SELECT', table: 'customers', statement: 'select c.id, c.name, c.email from customers c where c.id = ?', base: 1.0, jitter: 0.5 },
  { service: 'spring-orders', system: 'h2', namespace: 'orders', operation: 'UPDATE', table: 'orders', statement: 'update orders set status = ?, shipped_at = ? where id = ?', base: 3.4, jitter: 2.0 },
  { service: 'spring-orders', system: 'h2', namespace: 'orders', operation: 'INSERT', table: 'order_lines', statement: 'insert into order_lines (order_id, book_id, amount, price) values (?, ?, ?, ?)', base: 2.0, jitter: 1.2 },
];

const queryOf = (statement) => QUERIES.find((q) => q.statement === statement);

/**
 * The schema block of findings.adoc#schema, by index into QUERIES: the tables' indexes as
 * the extension read them, the columns the statement filters on, and the ones no
 * index leads with. A statement the parse cannot vouch for carries none, and so
 * does a query group whose service never ran under the extension.
 */
const SCHEMAS = {
  0: { tables: [{ table: 'books', schema: 'public', indexes: [{ name: 'primary_key_2', unique: true, columns: ['id'] }] }],
    predicates: ['books.id'], unindexed: [] },
  1: { tables: [{ table: 'books', schema: 'public', indexes: [{ name: 'primary_key_2', unique: true, columns: ['id'] }, { name: 'idx_books_author', unique: false, columns: ['author', 'title'] }] }],
    predicates: ['books.title'], unindexed: ['books.title'] },
  2: { tables: [{ table: 'reviews', schema: 'public', indexes: [{ name: 'primary_key_5', unique: true, columns: ['id'] }] }],
    predicates: ['reviews.book_id'], unindexed: ['reviews.book_id'] },
  3: { tables: [{ table: 'books', schema: 'public', indexes: [{ name: 'primary_key_2', unique: true, columns: ['id'] }] }],
    predicates: [], unindexed: [] },
  4: { tables: [{ table: 'orders', schema: 'public', indexes: [{ name: 'primary_key_8', unique: true, columns: ['id'] }, { name: 'idx_orders_customer', unique: false, columns: ['customer_id'] }] }],
    predicates: ['orders.customer_id'], unindexed: [] },
  6: { tables: [{ table: 'customers', schema: 'public', indexes: [{ name: 'primary_key_a', unique: true, columns: ['id'] }] }],
    predicates: ['customers.id'], unindexed: [] },
  7: { tables: [{ table: 'orders', schema: 'public', indexes: [{ name: 'primary_key_8', unique: true, columns: ['id'] }, { name: 'idx_orders_customer', unique: false, columns: ['customer_id'] }] }],
    predicates: ['orders.id'], unindexed: [] },
  8: { tables: [{ table: 'order_lines', schema: 'public', indexes: [] }], predicates: [], unindexed: [] },
};
QUERIES.forEach((q, i) => { q.schema = SCHEMAS[i] || null; });

const ERRORS = [
  {
    service: 'spring-orders',
    type: 'com.example.orders.OrderAlreadyShippedException',
    message: 'Order ? is already shipped',
    sample: 'Order 4821 is already shipped',
    stack: [
      'com.example.orders.OrderAlreadyShippedException: Order 4821 is already shipped',
      '\tat com.example.orders.ShipmentService.ship(ShipmentService.java:74)',
      '\tat com.example.orders.OrderController.ship(OrderController.java:112)',
      '\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)',
      '\tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:255)',
      '\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)',
      '\tat com.example.orders.OrderController.ship(OrderController.java:108)',
      '\t... 42 more',
    ].join('\n'),
  },
  {
    service: 'spring-orders',
    type: 'java.lang.IllegalStateException',
    message: 'Flaky downstream refused the call',
    sample: 'Flaky downstream refused the call',
    stack: [
      'java.lang.IllegalStateException: Flaky downstream refused the call',
      '\tat com.example.orders.FlakyController.call(FlakyController.java:38)',
      '\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)',
      'Caused by: java.net.SocketTimeoutException: Read timed out',
      '\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:280)',
      '\tat com.example.orders.FlakyController.call(FlakyController.java:33)',
      '\t... 18 more',
    ].join('\n'),
  },
  {
    service: 'silk-bookstore',
    type: 'java.lang.ArithmeticException',
    message: '/ by zero',
    sample: '/ by zero',
    stack: [
      'java.lang.ArithmeticException: / by zero',
      '\tat net.benelog.bookstore.PriceCalculator.average(PriceCalculator.java:29)',
      '\tat net.benelog.bookstore.BookHandler.stats(BookHandler.java:96)',
      '\tat net.benelog.spidersilk.web.Router.handle(Router.java:141)',
      '\tat org.eclipse.jetty.server.Server.handle(Server.java:563)',
    ].join('\n'),
  },
];

/** Endpoint templates: base latency in ms, the queries each one runs, error and slow rates. */
const ENDPOINTS = [
  { service: 'silk-bookstore', method: 'GET', route: '/api/books/{id}', weight: 30, base: 6, jitter: 4, queries: [0, 2], errorRate: 0.004 },
  { service: 'silk-bookstore', method: 'GET', route: '/api/books', weight: 14, base: 12, jitter: 8, queries: [0, 0, 2], errorRate: 0.003 },
  { service: 'silk-bookstore', method: 'GET', route: '/api/books/search', weight: 8, base: 20, jitter: 10, queries: [1], errorRate: 0.004 },
  { service: 'silk-bookstore', method: 'GET', route: '/api/books/stats', weight: 5, base: 9, jitter: 5, queries: [2], errorRate: 0.06, errorIndex: 2 },
  { service: 'silk-bookstore', method: 'GET', route: '/slow', weight: 3, base: 40, jitter: 20, queries: [3], errorRate: 0.002 },
  { service: 'silk-bookstore', method: 'GET', route: '/books/{id}/reviews', weight: 9, base: 14, jitter: 9, queries: [2, 2, 2, 2, 2, 2], errorRate: 0.002 },
  { service: 'spring-orders', method: 'GET', route: '/orders/{id}', weight: 22, base: 9, jitter: 6, queries: [4, 6], calls: '/api/books/{id}', errorRate: 0.004 },
  { service: 'spring-orders', method: 'GET', route: '/orders/report', weight: 6, base: 30, jitter: 14, queries: [5], errorRate: 0.005 },
  { service: 'spring-orders', method: 'POST', route: '/orders/{id}/ship', weight: 7, base: 12, jitter: 7, queries: [4, 7], errorRate: 0.10, errorIndex: 0 },
  { service: 'spring-orders', method: 'POST', route: '/orders', weight: 9, base: 18, jitter: 10, queries: [6, 8, 8], calls: '/api/books/{id}', errorRate: 0.006 },
  { service: 'spring-orders', method: 'GET', route: '/orders/flaky', weight: 5, base: 11, jitter: 30, queries: [4], errorRate: 0.20, errorIndex: 1 },
  { service: 'spring-orders', method: 'GET', route: '/customers/{id}/orders', weight: 11, base: 13, jitter: 8, queries: [6, 4, 4], errorRate: 0.003 },
];

for (const e of ENDPOINTS) {
  e.name = e.method + ' ' + e.route;
  e.endpointId = hash(e.service + '|' + e.name);
}
for (const q of QUERIES) q.queryId = hash(q.service + '|' + q.system + '|' + q.statement);
for (const e of ERRORS) e.errorId = hash(e.service + '|' + e.type + '|' + e.message);

const WEIGHTED = ENDPOINTS.flatMap((e) => Array(e.weight).fill(e));

const LOGGERS = {
  'silk-bookstore': ['n.b.bookstore.BookHandler', 'n.b.bookstore.BookRepository', 'n.b.s.web.Router', 'o.e.j.s.Server'],
  'spring-orders': ['c.e.orders.OrderController', 'c.e.orders.OrderRepository', 'o.s.web.client.RestClient', 'o.h.engine.jdbc.spi.SqlStatementLogger'],
};

const LOG_BODIES = [
  'Loaded {n} rows in {ms} ms',
  'Cache miss for key order-{n}',
  'Calling silk-bookstore for book {n}',
  'Transaction committed in {ms} ms',
  'Rejecting request: payload too large',
  'Retrying after a failed downstream call',
];

const SLOW_REQUEST_MS = 500;
const SLOW_QUERY_MS = 100;

// --- generation ---------------------------------------------------------

const traces = [];
const logs = [];
const tingles = [];
let logId = 1;

function spanId() { return hex(16); }

function makeSpan(o) {
  return {
    spanId: o.spanId || spanId(),
    parentSpanId: o.parentSpanId || null,
    service: o.service,
    name: o.name,
    kind: o.kind || 'INTERNAL',
    start: Math.round(o.start),
    startNs: Math.round(o.start) * 1e6,
    durationMs: Math.round(o.durationMs * 100) / 100,
    durationNs: Math.round(o.durationMs * 1e6),
    status: o.status || 'UNSET',
    statusMessage: o.statusMessage || null,
    attributes: o.attributes || {},
    events: o.events || [],
    scope: o.scope || 'io.opentelemetry.spider-sense-mock',
    category: o.category || 'internal',
    summary: o.summary || o.name,
    slow: !!o.slow,
    error: !!o.error,
  };
}

function dbSpan(q, parent, start, service, slowBoost) {
  let ms = Math.max(0.2, q.base + gauss() * q.jitter);
  if (slowBoost && rnd() < 0.25) ms *= between(1.6, 3.2);
  const slow = ms >= SLOW_QUERY_MS;
  return makeSpan({
    parentSpanId: parent,
    service,
    name: q.operation + ' ' + (q.namespace || '') + '.' + q.table,
    kind: 'CLIENT',
    start,
    durationMs: ms,
    category: 'db',
    summary: q.operation + ' ' + q.table + ' (' + q.system + ')',
    slow,
    scope: 'io.opentelemetry.jdbc',
    attributes: {
      'db.system': q.system,
      'db.name': q.namespace,
      'db.operation': q.operation,
      'db.sql.table': q.table,
      'db.statement': q.statement,
      'server.address': 'localhost',
      'server.port': 9092,
    },
  });
}

function makeTrace(at) {
  const ep = pick(WEIGHTED);
  const traceId = hex(32);
  const rootId = spanId();
  const spans = [];
  const isError = rnd() < ep.errorRate;
  const heavy = rnd() < 0.06;

  let cursor = at + between(0.2, 1.2);
  const children = [];

  // an outgoing HTTP call that lands in the other service
  if (ep.calls) {
    const remote = ENDPOINTS.find((e) => e.route === ep.calls);
    const clientId = spanId();
    const serverId = spanId();
    const remoteStart = cursor + between(0.4, 1.4);
    const remoteSpans = [];
    let rCursor = remoteStart + between(0.2, 0.8);
    for (const qi of remote.queries) {
      const s = dbSpan(QUERIES[qi], serverId, rCursor, remote.service, heavy);
      remoteSpans.push(s);
      rCursor += s.durationMs + between(0.1, 0.5);
    }
    const remoteSelf = Math.max(1, remote.base + gauss() * remote.jitter);
    const remoteDur = (rCursor - remoteStart) + remoteSelf;
    remoteSpans.unshift(makeSpan({
      spanId: serverId, parentSpanId: clientId, service: remote.service,
      name: remote.name, kind: 'SERVER', start: remoteStart, durationMs: remoteDur,
      category: 'http', summary: remote.name + ' → 200',
      scope: 'io.opentelemetry.spider-silk',
      attributes: {
        'http.request.method': remote.method, 'http.route': remote.route,
        'url.path': remote.route.replace('{id}', String(Math.floor(between(1, 9000)))),
        'http.response.status_code': 200, 'server.port': 8081, 'client.address': '127.0.0.1',
      },
    }));
    const clientDur = remoteDur + between(1.2, 4.0);
    children.push(makeSpan({
      spanId: clientId, parentSpanId: rootId, service: ep.service,
      name: 'GET', kind: 'CLIENT', start: cursor, durationMs: clientDur,
      category: 'http', summary: 'GET http://localhost:8081' + remote.route + ' → 200',
      scope: 'io.opentelemetry.java-http-client',
      attributes: {
        'http.request.method': 'GET', 'url.full': 'http://localhost:8081' + remote.route,
        'http.response.status_code': 200, 'server.address': 'localhost', 'server.port': 8081,
      },
    }));
    children.push(...remoteSpans);
    cursor += clientDur + between(0.2, 0.8);
  }

  for (const qi of ep.queries) {
    const s = dbSpan(QUERIES[qi], rootId, cursor, ep.service, heavy);
    children.push(s);
    cursor += s.durationMs + between(0.1, 0.6);
  }

  const self = Math.max(1, ep.base + Math.abs(gauss()) * ep.jitter);
  let total = (cursor - at) + self;
  if (heavy) total *= between(1.4, 2.6);
  const slow = total >= SLOW_REQUEST_MS;

  const errorDef = isError ? ERRORS[ep.errorIndex != null ? ep.errorIndex : 2] : null;
  const statusCode = isError ? (errorDef && errorDef.type.includes('Already') ? 409 : 500) : 200;

  const root = makeSpan({
    spanId: rootId, service: ep.service, name: ep.name, kind: 'SERVER',
    start: at, durationMs: total, category: 'http',
    summary: ep.name + ' → ' + statusCode,
    status: isError ? 'ERROR' : 'UNSET',
    statusMessage: isError ? errorDef.sample : null,
    slow, error: isError,
    scope: ep.service === 'spring-orders' ? 'io.opentelemetry.spring-webmvc-6.0' : 'io.opentelemetry.spider-silk',
    attributes: {
      'http.request.method': ep.method,
      'http.route': ep.route,
      'url.path': ep.route.replace('{id}', String(Math.floor(between(1, 9000)))),
      'http.response.status_code': statusCode,
      'server.port': ep.service === 'spring-orders' ? 8082 : 8081,
      'client.address': '127.0.0.1',
      'user_agent.original': 'load-gen/1.0',
      'thread.name': 'qtp' + Math.floor(between(100, 999)) + '-' + Math.floor(between(10, 60)),
    },
    events: isError ? [{
      name: 'exception',
      time: Math.round(at + total * 0.8),
      attributes: {
        'exception.type': errorDef.type,
        'exception.message': errorDef.sample,
        'exception.stacktrace': errorDef.stack,
      },
    }] : [],
  });

  spans.push(root, ...children);
  spans.sort((a, b) => a.start - b.start);

  const services = [...new Set(spans.map((s) => s.service))];
  const trace = {
    traceId,
    start: root.start,
    end: root.start + Math.round(total),
    durationMs: root.durationMs,
    rootName: ep.name,
    rootService: ep.service,
    rootKind: 'SERVER',
    services,
    spanCount: spans.length,
    errorCount: spans.filter((s) => s.error).length,
    dbCount: spans.filter((s) => s.category === 'db').length,
    httpStatus: statusCode,
    slow,
    error: isError,
    spans,
    endpointId: ep.endpointId,
    errorId: errorDef ? errorDef.errorId : null,
  };

  // a couple of logs, one of them carrying the trace id
  const n = isError ? 3 : rnd() < 0.5 ? 1 : 2;
  for (let i = 0; i < n; i++) {
    const severity = isError && i === n - 1 ? 'ERROR' : rnd() < 0.12 ? 'WARN' : rnd() < 0.2 ? 'DEBUG' : 'INFO';
    logs.push({
      id: logId++,
      at: Math.round(at + between(0, total)),
      service: ep.service,
      severity,
      severityNumber: { TRACE: 1, DEBUG: 5, INFO: 9, WARN: 13, ERROR: 17 }[severity],
      body: severity === 'ERROR'
        ? errorDef.type + ': ' + errorDef.sample
        : pick(LOG_BODIES).replace('{n}', String(Math.floor(between(1, 9000)))).replace('{ms}', String(Math.round(between(1, 400)))),
      logger: pick(LOGGERS[ep.service]),
      traceId,
      spanId: rootId,
      attributes: severity === 'ERROR'
        ? { 'thread.name': root.attributes['thread.name'], 'exception.type': errorDef.type, 'exception.stacktrace': errorDef.stack }
        : { 'thread.name': root.attributes['thread.name'] },
    });
  }

  if (isError) {
    tingles.push({ kind: 'error', at: root.start, service: ep.service, title: ep.name, detail: errorDef.type.split('.').pop() + ': ' + errorDef.sample, traceId, spanId: rootId, durationMs: root.durationMs });
  } else if (slow) {
    tingles.push({ kind: 'slow-request', at: root.start, service: ep.service, title: ep.name, detail: fmtMs(total), traceId, spanId: rootId, durationMs: root.durationMs });
  }
  const slowQuery = spans.find((s) => s.category === 'db' && s.slow);
  if (slowQuery) {
    tingles.push({ kind: 'slow-query', at: slowQuery.start, service: slowQuery.service, title: slowQuery.summary, detail: slowQuery.attributes['db.statement'], traceId, spanId: slowQuery.spanId, durationMs: slowQuery.durationMs });
  }

  return trace;
}

function fmtMs(ms) { return new Intl.NumberFormat('en-US').format(Math.round(ms)) + ' ms'; }

// seed 15 minutes, roughly 400 traces
for (let i = 0; i < 400; i++) {
  traces.push(makeTrace(START + (i / 400) * 15 * 60 * 1000 + between(0, 1800)));
}
traces.sort((a, b) => a.start - b.start);
logs.sort((a, b) => a.at - b.at);
tingles.sort((a, b) => a.at - b.at);

// --- live ---------------------------------------------------------------

const listeners = new Set();
let spanTotal = traces.reduce((n, t) => n + t.spanCount, 0);

function emit(type, data) {
  for (const es of listeners) es._dispatch(type, data);
}

setInterval(() => {
  const n = 1 + Math.floor(rnd() * 3);
  const before = tingles.length;
  for (let i = 0; i < n; i++) {
    const t = makeTrace(Date.now() - between(0, 900));
    traces.push(t);
    spanTotal += t.spanCount;
  }
  traces.sort((a, b) => a.start - b.start);
  logs.sort((a, b) => a.at - b.at);
  const cutoff = Date.now() - 60 * 60 * 1000;
  while (traces.length && traces[0].start < cutoff) traces.shift();
  for (const t of tingles.slice(before)) emit('tingle', t);
  emit('stats', {
    at: Date.now(),
    spans: spanTotal,
    traces: traces.length,
    logs: logs.length,
    droppedSpans: 0,
    perSecond: { spans: Math.round(n * 8 * 10) / 10, logs: Math.round(n * 1.5 * 10) / 10 },
  });
}, 3000);

// --- aggregation --------------------------------------------------------

function windowOf(q) {
  const to = q.to ? +q.to : Date.now();
  const from = q.from ? +q.from : to - 15 * 60 * 1000;
  const spanMs = Math.max(1000, to - from);
  const bucketMs = spanMs <= 5 * 60000 ? 5000 : spanMs <= 15 * 60000 ? 15000 : spanMs <= 3600000 ? 60000 : spanMs <= 6 * 3600000 ? 300000 : 900000;
  return { from, to, bucketMs };
}

function inWindow(w, service) {
  return traces.filter((t) => t.start >= w.from && t.start <= w.to && (!service || t.services.includes(service)));
}

const RESPONSE_BUCKETS = [SLOW_REQUEST_MS / 4, SLOW_REQUEST_MS, SLOW_REQUEST_MS * 4];

/** Four response-time buckets and the errors, as api.adoc#buckets defines them. */
function bucketOf(durationMs) {
  if (durationMs <= RESPONSE_BUCKETS[0]) return 0;
  if (durationMs <= RESPONSE_BUCKETS[1]) return 1;
  if (durationMs <= RESPONSE_BUCKETS[2]) return 2;
  return 3;
}

function histogramOf(entries) {
  const out = [0, 0, 0, 0, 0];
  for (const e of entries) {
    if (e.error) out[4]++;
    else out[bucketOf(e.durationMs)]++;
  }
  return out;
}

/** (h0 + h1 + h2 / 2) / requests, null when nothing was requested. */
function apdexOf(histogram) {
  const total = histogram.reduce((a, b) => a + b, 0);
  if (!total) return null;
  return Math.round(((histogram[0] + histogram[1] + histogram[2] / 2) / total) * 1000) / 1000;
}

function percentile(sorted, p) {
  if (!sorted.length) return 0;
  const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return Math.round(sorted[i] * 100) / 100;
}

function entrySpans(list, service) {
  const out = [];
  for (const t of list) {
    for (const s of t.spans) {
      if (s.kind !== 'SERVER') continue;
      if (service && s.service !== service) continue;
      out.push({ span: s, trace: t });
    }
  }
  return out;
}

function bucketsOf(w) {
  const out = [];
  for (let t = Math.floor(w.from / w.bucketMs) * w.bucketMs; t <= w.to; t += w.bucketMs) out.push(t);
  return out;
}

function seriesFor(entries, w) {
  const t = bucketsOf(w);
  const index = new Map(t.map((x, i) => [x, i]));
  const requests = t.map(() => 0);
  const errors = t.map(() => 0);
  const durations = t.map(() => []);
  const histogram = [t.map(() => 0), t.map(() => 0), t.map(() => 0), t.map(() => 0)];
  for (const { span } of entries) {
    const b = Math.floor(span.start / w.bucketMs) * w.bucketMs;
    const i = index.get(b);
    if (i === undefined) continue;
    requests[i]++;
    if (span.error) errors[i]++;
    else histogram[bucketOf(span.durationMs)][i]++;
    durations[i].push(span.durationMs);
  }
  const pct = (p) => durations.map((d) => (d.length ? percentile(d.slice().sort((a, b) => a - b), p) : null));
  return { t, requests, errors, p50Ms: pct(50), p95Ms: pct(95), p99Ms: pct(99), histogram };
}

function summaryFor(service, w) {
  const def = SERVICES.find((s) => s.name === service);
  const entries = entrySpans(inWindow(w, service), service);
  const durations = entries.map((e) => e.span.durationMs).sort((a, b) => a - b);
  const errors = entries.filter((e) => e.span.error).length;
  const series = seriesFor(entries, w);
  const secs = Math.max(1, (w.to - w.from) / 1000);
  const histogram = histogramOf(entries.map((e) => e.span));
  return {
    name: service,
    language: def.language,
    embedded: def.embedded,
    firstSeen: START,
    lastSeen: entries.length ? Math.max(...entries.map((e) => e.span.start)) : START,
    requests: entries.length,
    errors,
    errorRate: entries.length ? errors / entries.length : 0,
    rps: Math.round((entries.length / secs) * 100) / 100,
    p50Ms: percentile(durations, 50),
    p95Ms: percentile(durations, 95),
    p99Ms: percentile(durations, 99),
    maxMs: durations.length ? durations[durations.length - 1] : 0,
    apdex: apdexOf(histogram),
    histogram,
    sparkline: series.requests,
    hasJvm: def.hasJvm,
  };
}

function endpointStats(w, service) {
  const list = inWindow(w, service);
  const byId = new Map();
  for (const t of list) {
    for (const s of t.spans) {
      if (s.kind !== 'SERVER') continue;
      if (service && s.service !== service) continue;
      const ep = ENDPOINTS.find((e) => e.name === s.name && e.service === s.service);
      if (!ep) continue;
      let agg = byId.get(ep.endpointId);
      if (!agg) {
        agg = { ep, durations: [], errors: 0, statusCodes: {}, calls: [] };
        byId.set(ep.endpointId, agg);
      }
      agg.calls.push(s);
      agg.durations.push(s.durationMs);
      if (s.error) agg.errors++;
      const code = String(s.attributes['http.response.status_code'] || 200);
      agg.statusCodes[code] = (agg.statusCodes[code] || 0) + 1;
    }
  }
  const secs = Math.max(1, (w.to - w.from) / 1000);
  return [...byId.values()].map(({ ep, durations, errors, statusCodes, calls }) => {
    const sorted = durations.slice().sort((a, b) => a - b);
    const total = durations.reduce((a, b) => a + b, 0);
    const histogram = histogramOf(calls);
    return {
      endpointId: ep.endpointId, service: ep.service, method: ep.method, route: ep.route, name: ep.name, kind: 'SERVER',
      calls: durations.length, errors, errorRate: durations.length ? errors / durations.length : 0,
      rps: Math.round((durations.length / secs) * 100) / 100,
      avgMs: Math.round((total / Math.max(1, durations.length)) * 100) / 100,
      p50Ms: percentile(sorted, 50), p95Ms: percentile(sorted, 95), p99Ms: percentile(sorted, 99),
      maxMs: sorted.length ? sorted[sorted.length - 1] : 0,
      totalMs: Math.round(total * 100) / 100,
      apdex: apdexOf(histogram),
      histogram,
      statusCodes,
    };
  }).sort((a, b) => b.totalMs - a.totalMs);
}

function queryStats(w, service) {
  const list = inWindow(w, service);
  const byId = new Map();
  for (const t of list) {
    const rootEp = ENDPOINTS.find((e) => e.endpointId === t.endpointId);
    for (const s of t.spans) {
      if (s.category !== 'db') continue;
      if (service && s.service !== service) continue;
      const q = queryOf(s.attributes['db.statement']);
      if (!q) continue;
      let agg = byId.get(q.queryId);
      if (!agg) { agg = { q, durations: [], slow: 0, callers: new Map(), lastSeen: 0, traces: new Set() }; byId.set(q.queryId, agg); }
      agg.durations.push(s.durationMs);
      if (s.slow) agg.slow++;
      agg.lastSeen = Math.max(agg.lastSeen, s.start);
      agg.traces.add(t.traceId);
      if (rootEp) {
        const key = rootEp.service + '|' + rootEp.name;
        agg.callers.set(key, (agg.callers.get(key) || 0) + 1);
      }
    }
  }
  return [...byId.values()].map(({ q, durations, slow, callers, lastSeen }) => {
    const sorted = durations.slice().sort((a, b) => a - b);
    const total = durations.reduce((a, b) => a + b, 0);
    return {
      queryId: q.queryId, service: q.service, system: q.system, namespace: q.namespace,
      operation: q.operation, table: q.table, statement: q.statement,
      calls: durations.length, errors: 0,
      avgMs: Math.round((total / Math.max(1, durations.length)) * 100) / 100,
      p50Ms: percentile(sorted, 50), p95Ms: percentile(sorted, 95),
      maxMs: sorted.length ? sorted[sorted.length - 1] : 0,
      totalMs: Math.round(total * 100) / 100,
      slowCalls: slow,
      schema: q.schema || null,
      callers: [...callers.entries()].map(([k, calls]) => ({ endpoint: k.split('|')[1], service: k.split('|')[0], calls })).sort((a, b) => b.calls - a.calls),
      lastSeen,
    };
  });
}

function errorGroups(w, service) {
  const list = inWindow(w, service).filter((t) => t.error);
  const byId = new Map();
  for (const t of list) {
    const def = ERRORS.find((e) => e.errorId === t.errorId);
    if (!def) continue;
    if (service && def.service !== service) continue;
    let agg = byId.get(def.errorId);
    if (!agg) { agg = { def, count: 0, first: Infinity, last: 0, endpoints: new Map(), traces: [] }; byId.set(def.errorId, agg); }
    agg.count++;
    agg.first = Math.min(agg.first, t.start);
    agg.last = Math.max(agg.last, t.start);
    agg.endpoints.set(t.rootName, (agg.endpoints.get(t.rootName) || 0) + 1);
    agg.traces.push(t);
  }
  return [...byId.values()].map(({ def, count, first, last, endpoints, traces: ts }) => {
    const sample = ts[ts.length - 1];
    return {
      errorId: def.errorId, service: def.service, type: def.type, message: def.message,
      count, firstSeen: first, lastSeen: last,
      endpoints: [...endpoints.entries()].map(([name, c]) => ({ name, count: c })).sort((a, b) => b.count - a.count),
      sample: sample ? { traceId: sample.traceId, spanId: sample.spans[0].spanId, at: sample.start, message: def.sample, stacktrace: def.stack } : null,
      _traces: ts,
    };
  }).sort((a, b) => b.count - a.count);
}

function summary(t) {
  const { spans, endpointId, errorId, ...rest } = t;
  return rest;
}

function dependencies(w, service) {
  const list = inWindow(w, service);
  const by = new Map();
  for (const t of list) {
    for (const s of t.spans) {
      if (s.service !== service || s.kind !== 'CLIENT') continue;
      const kind = s.category === 'db' ? 'db' : 'http';
      const target = kind === 'db'
        ? s.attributes['db.system'] + ':' + s.attributes['db.name']
        : s.attributes['server.address'] + ':' + s.attributes['server.port'];
      const key = kind + '|' + target;
      let agg = by.get(key);
      if (!agg) { agg = { kind, target, durations: [], errors: 0 }; by.set(key, agg); }
      agg.durations.push(s.durationMs);
      if (s.error) agg.errors++;
    }
  }
  return [...by.values()].map(({ kind, target, durations, errors }) => {
    const sorted = durations.slice().sort((a, b) => a - b);
    return {
      kind, target, calls: durations.length, errors,
      avgMs: Math.round((durations.reduce((a, b) => a + b, 0) / Math.max(1, durations.length)) * 100) / 100,
      p95Ms: percentile(sorted, 95),
    };
  }).sort((a, b) => b.calls - a.calls);
}

/**
 * The topology of the window: the user, one node per service, one per database or
 * external host, and the edges between them. api.adoc#map.
 */
function mapView(w) {
  const list = inWindow(w);
  const edges = new Map();
  const external = new Map();
  const addEdge = (from, to, span) => {
    const key = from + '|' + to;
    let agg = edges.get(key);
    if (!agg) { agg = { from, to, durations: [], errors: 0 }; edges.set(key, agg); }
    agg.durations.push(span.durationMs);
    if (span.error) agg.errors++;
  };
  for (const t of list) {
    const byId = new Map(t.spans.map((s) => [s.spanId, s]));
    const entryParent = new Set(t.spans.filter((s) => s.kind === 'SERVER' && s.parentSpanId).map((s) => s.parentSpanId));
    for (const s of t.spans) {
      if (s.kind === 'SERVER') {
        const parent = s.parentSpanId ? byId.get(s.parentSpanId) : null;
        if (!parent) addEdge('user', 'svc:' + s.service, s);
        else if (parent.service !== s.service) addEdge('svc:' + parent.service, 'svc:' + s.service, s);
        continue;
      }
      if (s.kind !== 'CLIENT') continue;
      // an outbound call that lands in another traced service is that service edge
      if (entryParent.has(s.spanId)) continue;
      const kind = s.category === 'db' ? 'db' : 'http';
      const name = kind === 'db'
        ? s.attributes['db.system'] + ':' + s.attributes['db.name']
        : s.attributes['server.address'] + ':' + s.attributes['server.port'];
      const id = kind + ':' + name;
      let node = external.get(id);
      if (!node) { node = { id, kind, name, durations: [], errors: 0 }; external.set(id, node); }
      node.durations.push(s.durationMs);
      if (s.error) node.errors++;
      addEdge('svc:' + s.service, id, s);
    }
  }
  const stats = (durations) => {
    const sorted = durations.slice().sort((a, b) => a - b);
    return {
      calls: durations.length,
      avgMs: Math.round((durations.reduce((a, b) => a + b, 0) / Math.max(1, durations.length)) * 100) / 100,
      p95Ms: percentile(sorted, 95),
    };
  };
  const used = new Set();
  for (const e of edges.values()) { used.add(e.from); used.add(e.to); }
  const nodes = [];
  if (used.has('user')) nodes.push({ id: 'user', kind: 'user', name: 'Clients' });
  for (const def of SERVICES) {
    const id = 'svc:' + def.name;
    if (!used.has(id)) continue;
    const sum = summaryFor(def.name, w);
    nodes.push({
      id, kind: 'service', name: def.name,
      requests: sum.requests, errors: sum.errors, errorRate: sum.errorRate, rps: sum.rps,
      p50Ms: sum.p50Ms, p95Ms: sum.p95Ms, p99Ms: sum.p99Ms, maxMs: sum.maxMs,
      apdex: sum.apdex, histogram: sum.histogram, hasJvm: sum.hasJvm,
    });
  }
  for (const node of external.values()) {
    if (!used.has(node.id)) continue;
    const { durations, errors, ...rest } = node;
    nodes.push({ ...rest, errors, ...stats(durations) });
  }
  return {
    window: w,
    nodes,
    edges: [...edges.values()].map(({ from, to, durations, errors }) => ({ from, to, errors, ...stats(durations) })),
  };
}

// --- JVM and metrics ----------------------------------------------------

const JVM_POOLS = ['G1 Eden Space', 'G1 Old Gen', 'G1 Survivor Space'];

function jvmView(service, w) {
  const step = Math.max(5000, w.bucketMs);
  const t = [];
  for (let x = Math.floor(w.from / step) * step; x <= w.to; x += step) t.push(x);
  const wave = (i, base, amp, period) => base + Math.sin((i / period) * Math.PI * 2) * amp;
  const jitter = () => (Math.sin(t.length * 12.9898) + 1) * 0.5;
  const def = SERVICES.find((s) => s.name === service);
  const heapBase = service === 'spring-orders' ? 210 : 120;
  const used = t.map((x, i) => Math.round((wave(i, heapBase, heapBase * 0.35, 9) + (i % 7) * 3) * 1024 * 1024));
  return {
    service,
    runtime: {
      jvm: def.resource['process.runtime.name'] + ' ' + def.resource['process.runtime.version'],
      pid: +def.resource['process.pid'],
      host: def.resource['host.name'],
      cpuCount: 8,
    },
    heap: {
      t,
      used,
      committed: t.map(() => Math.round((heapBase * 2.1) * 1024 * 1024)),
      limit: t.map(() => Math.round(1024 * 1024 * 1024)),
    },
    nonHeap: {
      t,
      used: t.map((x, i) => Math.round(wave(i, 86, 4, 23) * 1024 * 1024)),
      committed: t.map(() => Math.round(104 * 1024 * 1024)),
    },
    pools: JVM_POOLS.map((name, k) => ({
      name, type: 'heap', t,
      used: t.map((x, i) => Math.round(wave(i + k * 3, heapBase * (k === 1 ? 0.5 : k === 0 ? 0.35 : 0.08), heapBase * 0.2, 7 + k) * 1024 * 1024)),
    })),
    gc: [
      { name: 'G1 Young Generation', action: 'end of minor GC', t, count: t.map((x, i) => (i % 3 === 0 ? 2 : 1)), durationMs: t.map((x, i) => Math.round(wave(i, 12, 6, 5) * 10) / 10) },
      { name: 'G1 Concurrent GC', action: 'end of concurrent GC', t, count: t.map((x, i) => (i % 9 === 0 ? 1 : 0)), durationMs: t.map((x, i) => (i % 9 === 0 ? 28.4 : 0)) },
    ],
    threads: { t, count: t.map((x, i) => Math.round(wave(i, service === 'spring-orders' ? 48 : 32, 5, 11))), daemon: t.map((x, i) => Math.round(wave(i, service === 'spring-orders' ? 38 : 24, 3, 11))) },
    cpu: { t, utilization: t.map((x, i) => Math.round(Math.max(0.01, wave(i, 0.22, 0.14, 13)) * 1000) / 1000), systemLoad1m: t.map((x, i) => Math.round(wave(i, 1.4, 0.7, 17) * 100) / 100) },
    classes: { t, loaded: t.map((x, i) => 11800 + i * 3 + (service === 'spring-orders' ? 4200 : 0)) },
    // Only the Spring application runs a JDBC pool the agent instruments.
    connectionPools: service === 'spring-orders'
      ? [{
        name: 'HikariPool-1',
        t,
        used: t.map((x, i) => Math.max(0, Math.round(wave(i, 4, 3, 6)))),
        idle: t.map((x, i) => Math.max(0, 10 - Math.round(wave(i, 4, 3, 6)))),
        max: t.map(() => 10),
        pending: t.map((x, i) => (i % 11 === 0 ? 2 : i % 5 === 0 ? 1 : 0)),
      }]
      : [],
  };
}

const METRIC_CATALOG = [
  { name: 'jvm.memory.used', type: 'gauge', unit: 'By', description: 'Measure of memory used', series: 6 },
  { name: 'jvm.memory.committed', type: 'gauge', unit: 'By', description: 'Measure of memory committed', series: 6 },
  { name: 'jvm.thread.count', type: 'gauge', unit: '{thread}', description: 'Number of executing platform threads', series: 2 },
  { name: 'jvm.class.count', type: 'gauge', unit: '{class}', description: 'Number of classes currently loaded', series: 2 },
  { name: 'jvm.cpu.recent_utilization', type: 'gauge', unit: '1', description: 'Recent CPU utilization for the process', series: 2 },
  { name: 'jvm.gc.duration', type: 'histogram', unit: 's', description: 'Duration of JVM garbage collection actions', series: 4 },
  { name: 'http.server.request.duration', type: 'histogram', unit: 's', description: 'Duration of HTTP server requests', series: 8 },
  { name: 'jvm.cpu.time', type: 'sum', unit: 's', description: 'CPU time used by the process', series: 2 },
].map((m) => ({ ...m, services: SERVICES.map((s) => s.name) }));

function metricSeries(name, service, w, rateOn) {
  const step = Math.max(5000, w.bucketMs);
  const t = [];
  for (let x = Math.floor(w.from / step) * step; x <= w.to; x += step) t.push(x);
  const names = service ? [service] : SERVICES.map((s) => s.name);
  const meta = METRIC_CATALOG.find((m) => m.name === name) || { type: 'gauge', unit: '' };
  const out = [];
  for (const svc of names) {
    const variants = name === 'jvm.memory.used' || name === 'jvm.memory.committed'
      ? [{ 'jvm.memory.type': 'heap' }, { 'jvm.memory.type': 'non_heap' }]
      : name === 'jvm.gc.duration'
        ? [{ 'jvm.gc.name': 'G1 Young Generation' }, { 'jvm.gc.name': 'G1 Concurrent GC' }]
        : name === 'http.server.request.duration'
          ? ENDPOINTS.filter((e) => e.service === svc).slice(0, 3).map((e) => ({ 'http.route': e.route, 'http.request.method': e.method }))
          : [{}];
    for (const [k, attributes] of variants.entries()) {
      const base = meta.type === 'histogram' ? 0.02 + k * 0.05 : name.includes('memory') ? 1.6e8 * (k + 1) : name.includes('thread') ? 40 : name.includes('class') ? 12000 : name.includes('utilization') ? 0.25 : 120;
      const amp = base * 0.25;
      const v = t.map((x, i) => Math.round((base + Math.sin((i / 8) * Math.PI * 2 + k) * amp) * 1000) / 1000);
      const s = { service: svc, attributes, t, v };
      if (meta.type === 'histogram') {
        s.count = t.map((x, i) => 20 + ((i + k) % 9));
        s.p95 = v.map((x) => Math.round(x * 2.4 * 1000) / 1000);
        s.max = v.map((x) => Math.round(x * 4.1 * 1000) / 1000);
      }
      if (meta.type === 'sum' && rateOn) s.v = v.map((x) => Math.round((x / 60) * 1000) / 1000);
      out.push(s);
    }
  }
  return { name, type: meta.type, unit: meta.unit, series: out };
}

// --- the routes ---------------------------------------------------------

const ENDPOINT_BASE = location.origin;

function statusBody() {
  return {
    name: 'Spider Sense',
    version: VERSION,
    mode: 'standalone',
    startedAt: START,
    now: Date.now(),
    endpoint: ENDPOINT_BASE,
    otlp: {
      traces: ENDPOINT_BASE + '/v1/traces',
      metrics: ENDPOINT_BASE + '/v1/metrics',
      logs: ENDPOINT_BASE + '/v1/logs',
    },
    embeddedService: null,
    codeFrames: { appPackages: [], frameworkPrefixes: FRAMEWORK },
    jar: '/home/me/tools/spider-sense-' + VERSION + '.jar',
    thresholds: { slowRequestMs: SLOW_REQUEST_MS, slowQueryMs: SLOW_QUERY_MS, responseBucketsMs: RESPONSE_BUCKETS },
    retention: { hours: 24, spans: 1_000_000 },
    ingest: { maxSpansPerSecond: null },
    storage: {
      url: 'jdbc:h2:file:~/db/spider-sense/store;AUTO_SERVER=TRUE',
      path: '/home/benelog/db/spider-sense/store.mv.db',
      sizeBytes: 38_412_288 + traces.length * 1024,
      fallback: false,
      fallbackReason: null,
      droppedBatches: 0,
      droppedSpans: 0,
      queued: 0,
    },
    counts: {
      spans: spanTotal,
      traces: traces.length,
      logs: logs.length,
      metricSeries: METRIC_CATALOG.reduce((n, m) => n + m.series, 0),
      services: SERVICES.length,
    },
    oldest: { span: traces.length ? traces[0].start : START, log: logs.length ? logs[0].at : START },
  };
}

/**
 * Where a sample of traces spent its time, as the server's `Queries.timeSplit`
 * computes it (findings.adoc#time): the three hottest
 * summaries and the four shares, over the spans of one service only.
 */
function timeSplit(traces, service) {
  const spans = [];
  for (const trace of traces) {
    for (const span of trace.spans) if (span.service === service) spans.push(span);
  }
  if (!spans.length) return { hotSpans: [], breakdown: {} };
  const known = new Set(spans.map((s) => s.spanId));
  const childMs = new Map();
  for (const span of spans) {
    if (span.parentSpanId && known.has(span.parentSpanId)) {
      childMs.set(span.parentSpanId, (childMs.get(span.parentSpanId) || 0) + span.durationMs);
    }
  }
  let total = 0;
  const shares = { db: 0, http: 0, internal: 0, self: 0 };
  const hottest = new Map();
  for (const span of spans) {
    const self = Math.max(0, span.durationMs - (childMs.get(span.spanId) || 0));
    if (!span.parentSpanId || !known.has(span.parentSpanId)) {
      total += span.durationMs;
      shares.self += self;
      continue;
    }
    shares[span.category === 'db' || span.category === 'http' ? span.category : 'internal'] += self;
    const name = String(span.summary).replace(/\d+/g, '?');
    const row = hottest.get(name) || { name, category: span.category, selfMs: 0, count: 0 };
    row.selfMs += self;
    row.count++;
    hottest.set(name, row);
  }
  if (total <= 0) return { hotSpans: [], breakdown: {} };
  const breakdown = {};
  for (const bucket of ['db', 'http', 'internal', 'self']) {
    breakdown[bucket] = Math.min(1, shares[bucket] / total);
  }
  const hotSpans = [...hottest.values()]
    .sort((a, b) => b.selfMs - a.selfMs || a.name.localeCompare(b.name))
    .slice(0, 3)
    .map((row) => ({
      name: row.name, category: row.category,
      selfMs: Math.round(row.selfMs * 100) / 100,
      share: Math.min(1, row.selfMs / total), count: row.count,
    }));
  return { hotSpans, breakdown };
}

// --- marks, findings and compare (findings.adoc) -------------------------

/** Two automatic start marks and the pair a person made around a change. */
const marks = [
  { id: 1, at: START, name: 'start', service: 'silk-bookstore', note: 'pid 48211' },
  { id: 2, at: START + 20 * 1000, name: 'start', service: 'spring-orders', note: 'pid 48377' },
  { id: 3, at: START + 4 * 60 * 1000, name: 'before', service: null, note: 'before the fix' },
  { id: 4, at: START + 9 * 60 * 1000, name: 'after', service: 'spring-orders', note: 'after the fix' },
];
let markId = marks.length;

/** The selectors of marks-and-compare.adoc#time-selectors: a duration, epoch millis, a mark name, start or now. */
function resolveSelector(selector, fallback, service) {
  if (!selector) return fallback;
  if (selector === 'now') return Date.now();
  if (/^\d{13,}$/.test(selector)) return +selector;
  const duration = /^(\d+)(s|m|h|d)$/.exec(selector);
  if (duration) {
    const unit = { s: 1000, m: 60000, h: 3600000, d: 86400000 }[duration[2]];
    return fallback - +duration[1] * unit;
  }
  const named = marks
    .filter((mk) => mk.name === selector && (!service || !mk.service || mk.service === service))
    .sort((a, b) => b.at - a.at)[0];
  return named ? named.at : fallback;
}

const FRAMEWORK = ['java.', 'javax.', 'jdk.', 'sun.', 'com.sun.', 'jakarta.', 'org.springframework.',
  'org.hibernate.', 'org.eclipse.jetty.', 'org.apache.', 'io.opentelemetry.', 'com.zaxxer.', 'org.h2.',
  'net.benelog.spidersilk.', 'kotlin.', 'scala.', 'reactor.', 'io.netty.', 'ch.qos.logback.', 'org.slf4j.',
  'org.junit.', 'gg.jte.'];

/** A stack trace as its exception chain, innermost first, as api.adoc#cause answers it. */
function chainOf(stack) {
  const causes = [];
  for (const line of (stack || '').split('\n')) {
    const text = line.trim();
    if (!text) continue;
    const more = /^\.\.\. (\d+) more/.exec(text);
    if (text.startsWith('at ')) {
      if (!causes.length) causes.push({ type: '', message: '', frames: [], more: 0 });
      causes[causes.length - 1].frames.push(text.slice(3).replace(/^.*\/(?=[^/]*\()/, ''));
    } else if (more && causes.length) {
      causes[causes.length - 1].more = +more[1];
    } else {
      const header = text.replace(/^Caused by: /, '');
      const colon = header.indexOf(': ');
      causes.push({ type: colon < 0 ? header : header.slice(0, colon), message: colon < 0 ? '' : header.slice(colon + 2), frames: [], more: 0 });
    }
  }
  return causes.reverse();
}

/** The application frames of a stack trace, root cause first, as findings.adoc#code reduces them. */
function appFrames(stack) {
  const frames = chainOf(stack).flatMap((cause) => cause.frames)
    .filter((frame) => !FRAMEWORK.some((prefix) => frame.startsWith(prefix)));
  return [...new Set(frames)].slice(0, 5);
}

const SEVERITY_RANK = { high: 0, medium: 1, low: 2 };

/** Every field of a finding's subject (api.adoc#findings); a mock names only the one that applies. */
const NO_SUBJECT = {
  endpointId: null, queryId: null, errorId: null, pool: null, job: null,
  target: null, logger: null, jvm: null,
};

function findingId(kind, service, subject) {
  return kind + ':' + (hash(kind + '|' + service + '|' + subject) + hash(subject)).slice(0, 12);
}

function shortType(type) {
  const i = (type || '').lastIndexOf('.');
  return i < 0 ? type : type.slice(i + 1);
}

/**
 * Acknowledged findings (findings.adoc#acknowledgements), by finding id.
 *
 * <p>One is here from the start — the report endpoint everybody knows is slow —
 * so the dimmed row and its Unacknowledge button are on the screen without
 * anybody having to click anything first.
 */
const acks = new Map();
{
  const report = ENDPOINTS.find((e) => e.route === '/orders/report');
  if (report) {
    acks.set(findingId('slow-endpoint', report.service, report.endpointId),
      { at: START + 3 * 60 * 1000, note: 'slow by design until the report is precomputed' });
  }
}

/** Every kind of findings.adoc#kinds, over the generated window. */
function findingsFor(w, service, limit, hideAcked) {
  const found = [];
  const entries = entrySpans(inWindow(w, service), service);
  const requests = entries.length;

  for (const group of errorGroups(w, service)) {
    const def = ERRORS.find((e) => e.errorId === group.errorId);
    found.push({
      id: findingId('error', group.service, group.errorId),
      kind: 'error', severity: 'high', service: group.service,
      title: shortType(group.type) + ' on ' + (group.endpoints[0] ? group.endpoints[0].name : group.service),
      why: group.count + ' occurrences, the last one ' + Math.round((Date.now() - group.lastSeen) / 1000) + ' s ago',
      subject: { ...NO_SUBJECT, errorId: group.errorId },
      numbers: {
        count: group.count, firstSeen: group.firstSeen, lastSeen: group.lastSeen,
        type: group.type, message: group.message, endpoints: group.endpoints,
      },
      statement: null,
      code: appFrames(def && def.stack),
      traces: group._traces.slice(-3).reverse().map((t) => t.traceId),
      impact: group.count,
    });
  }

  const nPlusOne = ENDPOINTS.find((e) => e.route === '/books/{id}/reviews');
  const repeated = QUERIES[2];
  if ((!service || service === nPlusOne.service) && requests) {
    const affected = Math.max(1, Math.round(requests * 0.06));
    found.push({
      id: findingId('n-plus-one', nPlusOne.service, nPlusOne.endpointId + '|' + repeated.queryId),
      kind: 'n-plus-one', severity: 'high', service: nPlusOne.service,
      title: nPlusOne.name + ' runs SELECT reviews 6 times per request',
      why: affected + ' of ' + affected + ' requests repeated it; 6, 6 and 5 times; 7.4 ms per request in that statement',
      subject: { ...NO_SUBJECT, endpointId: nPlusOne.endpointId, queryId: repeated.queryId },
      numbers: { requests: affected, affected, medianRepeats: 6, maxRepeats: 6, msPerRequest: 7.4 },
      statement: repeated.statement,
      schema: repeated.schema,
      code: ['net.benelog.bookstore.ReviewRepository.findByBook(ReviewRepository.java:41)',
        'net.benelog.bookstore.BookHandler.reviews(BookHandler.java:74)'],
      traces: inWindow(w, nPlusOne.service).filter((t) => t.endpointId === nPlusOne.endpointId).slice(-3).map((t) => t.traceId),
      impact: 6 * affected,
    });
  }

  for (const q of queryStats(w, service)) {
    if (q.p95Ms <= SLOW_QUERY_MS) continue;
    const def = QUERIES.find((x) => x.queryId === q.queryId);
    found.push({
      id: findingId('slow-query', q.service, q.queryId),
      kind: 'slow-query', severity: q.p95Ms > SLOW_QUERY_MS * 10 ? 'high' : 'medium', service: q.service,
      title: (def ? def.operation + ' ' + def.table : 'a query') + ' is slow',
      why: 'p95 ' + fmtMs(q.p95Ms) + ' over ' + q.calls + ' calls, ' + fmtMs(q.totalMs) + ' in all',
      subject: { ...NO_SUBJECT, queryId: q.queryId },
      numbers: {
        calls: q.calls, slowCalls: q.slowCalls, p50Ms: q.p50Ms, p95Ms: q.p95Ms, maxMs: q.maxMs,
        totalMs: q.totalMs, callers: q.callers,
      },
      statement: q.statement,
      schema: q.schema,
      code: ['net.benelog.bookstore.BookRepository.search(BookRepository.java:58)'],
      traces: inWindow(w, q.service).slice(-3).map((t) => t.traceId),
      impact: q.totalMs,
    });
  }

  for (const e of endpointStats(w, service)) {
    if (e.p95Ms <= SLOW_REQUEST_MS) continue;
    const sample = inWindow(w, e.service).filter((t) => t.endpointId === e.endpointId)
      .slice().sort((a, b) => b.durationMs - a.durationMs).slice(0, 20);
    const split = timeSplit(sample, e.service);
    found.push({
      id: findingId('slow-endpoint', e.service, e.endpointId),
      kind: 'slow-endpoint', severity: e.p95Ms > SLOW_REQUEST_MS * 4 ? 'high' : 'medium', service: e.service,
      title: e.name + ' is slow',
      why: 'p95 ' + fmtMs(e.p95Ms) + ' over ' + e.calls + ' calls; ' + fmtMs(e.totalMs) + ' in all',
      subject: { ...NO_SUBJECT, endpointId: e.endpointId },
      numbers: {
        calls: e.calls, p50Ms: e.p50Ms, p95Ms: e.p95Ms, maxMs: e.maxMs, totalMs: e.totalMs,
        apdex: e.apdex, dbCallsPerRequest: 2.4, dbMsPerRequest: 310.2, dbShare: 0.62,
        hotSpan: { name: 'SELECT reviews', category: 'db', selfMs: 312.4, share: 0.62 },
        hotSpans: split.hotSpans, breakdown: split.breakdown,
      },
      statement: null,
      code: [],
      traces: inWindow(w, e.service).filter((t) => t.endpointId === e.endpointId)
        .slice().sort((a, b) => b.durationMs - a.durationMs).slice(0, 3).map((t) => t.traceId),
      impact: e.totalMs,
    });
  }

  if (!service || service === 'spring-orders') {
    const job = 'OrderReportJob.run';
    found.push({
      id: findingId('slow-job', 'spring-orders', job),
      kind: 'slow-job', severity: 'medium', service: 'spring-orders',
      title: job + ' is slow',
      why: 'p95 2.1 s over 14 runs; 24.8 s in all, 71% of it in the database',
      subject: { ...NO_SUBJECT, job },
      numbers: {
        runs: 14, p50Ms: 1480, p95Ms: 2130, maxMs: 2890, totalMs: 24800,
        dbCallsPerRun: 38.5, dbMsPerRun: 1260.4, dbShare: 0.71,
        hotSpan: { name: 'SELECT order_line', category: 'db', selfMs: 1260.4, share: 0.59 },
        hotSpans: [
          { name: 'SELECT order_line (h2)', category: 'db', selfMs: 17645.6, share: 0.59, count: 539 },
          { name: 'SELECT customers (h2)', category: 'db', selfMs: 3570.2, share: 0.12, count: 196 },
          { name: 'OrderReportJob.render', category: 'internal', selfMs: 2380.1, share: 0.08, count: 14 },
        ],
        breakdown: { db: 0.71, http: 0.0, internal: 0.08, self: 0.21 },
      },
      statement: null,
      code: ['com.example.orders.OrderReportJob.run(OrderReportJob.java:36)'],
      traces: inWindow(w, 'spring-orders').slice(-2).map((t) => t.traceId),
      impact: 24800,
    });

    found.push({
      id: findingId('pool-exhausted', 'spring-orders', 'HikariPool-1'),
      kind: 'pool-exhausted', severity: 'high', service: 'spring-orders',
      title: 'HikariPool-1 ran out of connections',
      why: 'up to 10 of 10 connections in use and up to 4 requests waiting',
      subject: { ...NO_SUBJECT, pool: 'HikariPool-1' },
      numbers: { pool: 'HikariPool-1', max: 10, usedMax: 10, pendingMax: 4, at: w.to - 60000 },
      statement: null,
      code: [],
      traces: [],
      impact: 4,
    });
  }

  if (!service || service === 'spring-orders') {
    const call = 'GET localhost:8081/api/books/?';
    found.push({
      id: findingId('n-plus-one-http', 'spring-orders', 'GET /orders/{id}|' + call),
      kind: 'n-plus-one-http', severity: 'high', service: 'spring-orders',
      title: 'GET /orders/{id} calls ' + call + ' 6 times per request',
      why: '14 of 22 requests repeated it; 8, 7 and 6 times; 412.0 ms per request in that call',
      subject: {
        ...NO_SUBJECT,
        endpointId: (ENDPOINTS.find((e) => e.route === '/orders/{id}') || {}).endpointId,
        target: 'localhost:8081',
      },
      numbers: { requests: 22, affected: 14, medianRepeats: 6, maxRepeats: 8, msPerRequest: 412.0 },
      statement: null,
      code: ['com.example.orders.OrderEnricher.enrich(OrderEnricher.java:48)'],
      traces: inWindow(w, 'spring-orders').slice(-3).map((t) => t.traceId),
      impact: 6 * 14,
    });

    found.push({
      id: findingId('slow-external', 'spring-orders', 'localhost:8081|GET'),
      kind: 'slow-external', severity: 'medium', service: 'spring-orders',
      title: 'GET localhost:8081 is slow',
      why: 'p95 820.0 ms over 96 calls, 2 errors; 51.4 s in total',
      subject: { ...NO_SUBJECT, target: 'localhost:8081' },
      numbers: {
        calls: 96, errors: 2, p50Ms: 310.4, p95Ms: 820.0, maxMs: 1240.8, totalMs: 51400,
        callers: [{ endpoint: 'GET /api/orders/{id}/enriched', service: 'spring-orders', calls: 96 }],
      },
      statement: null,
      code: ['com.example.orders.BookClient.fetch(BookClient.java:29)'],
      traces: inWindow(w, 'spring-orders').slice(-1).map((t) => t.traceId),
      impact: 51400,
    });

    found.push({
      id: findingId('log-error', 'spring-orders', 'com.example.orders.PaymentService|payment gateway timeout'),
      kind: 'log-error', severity: 'high', service: 'spring-orders',
      title: 'ERROR in PaymentService: Payment gateway timed out for order ?',
      why: '18 records in POST /api/orders, none of them on a failed trace; Payment gateway timed out for order ?',
      subject: { ...NO_SUBJECT, logger: 'com.example.orders.PaymentService' },
      numbers: {
        count: 18, firstSeen: w.to - 540000, lastSeen: w.to - 20000,
        logger: 'com.example.orders.PaymentService',
        message: 'Payment gateway timed out for order ?',
        endpoints: [{ name: 'POST /api/orders', count: 18 }],
      },
      statement: null,
      code: ['com.example.orders.PaymentService.charge(PaymentService.java:61)'],
      traces: inWindow(w, 'spring-orders').slice(-3).map((t) => t.traceId),
      impact: 18,
    });

    found.push({
      id: findingId('gc-pause', 'spring-orders', 'gc:G1 Young Generation|end of minor GC'),
      kind: 'gc-pause', severity: 'high', service: 'spring-orders',
      title: 'G1 Young Generation paused for 612.0 ms',
      why: 'the longest single collection took 612.0 ms over 214 collections; 14.2% of an export interval at worst',
      subject: { ...NO_SUBJECT, jvm: 'gc:G1 Young Generation' },
      numbers: {
        gc: 'G1 Young Generation', action: 'end of minor GC', worstMs: 612, shareMax: 0.142,
        collections: 214, at: w.to - 120000,
      },
      statement: null, code: [], traces: [],
      impact: 612,
    });

    found.push({
      id: findingId('heap-pressure', 'spring-orders', 'heap'),
      kind: 'heap-pressure', severity: 'high', service: 'spring-orders',
      title: 'heap at 94.0% of its limit',
      why: '962.6 MiB of 1,024.0 MiB in use at the worst point',
      subject: { ...NO_SUBJECT, jvm: 'heap' },
      numbers: {
        usedMax: 1009438720, limit: 1073741824, ratioMax: 0.94, at: w.to - 90000,
      },
      statement: null, code: [], traces: [],
      impact: 0.94,
    });

    found.push({
      id: findingId('thread-growth', 'spring-orders', 'threads'),
      kind: 'thread-growth', severity: 'medium', service: 'spring-orders',
      title: 'threads grew from 42 to 187',
      why: '145 threads more than at the start of the window, peaking at 187',
      subject: { ...NO_SUBJECT, jvm: 'threads' },
      numbers: { first: 42, last: 187, max: 187, at: w.to - 5000 },
      statement: null, code: [], traces: [],
      impact: 145,
    });
  }

  found.sort((a, b) => (SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity])
    || (b.impact - a.impact) || a.id.localeCompare(b.id));

  // Acknowledged findings last, in the same order among themselves (findings.adoc#acknowledgements).
  // The generated traffic never stops, so a resolved finding here is always back:
  // a regression, ranked first. Every other finding is ongoing, since the mock has
  // no previous run to compare with.
  const regressions = [];
  const open = [];
  const accepted = [];
  let acked = 0;
  for (const finding of found) {
    const row = acks.get(finding.id);
    if (!row) {
      open.push({ ...finding, state: 'ongoing', ack: null, resolution: null });
      continue;
    }
    if (row.resolved) {
      const resolution = { at: row.at, note: row.note };
      regressions.push({
        ...finding, kind: 'regression', severity: 'high', state: 'regressed', ack: null, resolution,
        why: 'came back after it was resolved' + (row.note ? ' (' + row.note + ')' : '') + '; ' + finding.why,
        numbers: { resolvedAt: row.at, note: row.note, originalKind: finding.kind, ...finding.numbers },
      });
      continue;
    }
    acked++;
    if (!hideAcked) accepted.push({ ...finding, state: 'ongoing', ack: { at: row.at, note: row.note }, resolution: null });
  }
  const ranked = regressions.concat(open, accepted);
  // `schema` is on every finding, null on the kinds that never carry one (api.adoc#findings).
  return { requests, acked, resolved: 0, findings: ranked.slice(0, limit).map(({ impact, ...rest }) => ({ schema: null, ...rest })) };
}

/** The Totals of api.adoc#compare, over one window. */
function totalsOf(w, service) {
  const entries = entrySpans(inWindow(w, service), service);
  const durations = entries.map((e) => e.span.durationMs).sort((a, b) => a - b);
  const errors = entries.filter((e) => e.span.error).length;
  const histogram = histogramOf(entries.map((e) => e.span));
  const secs = Math.max(1, (w.to - w.from) / 1000);
  return {
    requests: entries.length, errors,
    errorRate: entries.length ? errors / entries.length : 0,
    rps: Math.round((entries.length / secs) * 100) / 100,
    p50Ms: percentile(durations, 50), p95Ms: percentile(durations, 95), p99Ms: percentile(durations, 99),
    maxMs: durations.length ? durations[durations.length - 1] : 0,
    apdex: apdexOf(histogram),
    histogram,
  };
}

const VERDICT_ORDER = { worse: 0, new: 1, same: 2, better: 3, gone: 4 };

function verdictOf(before, after, key, step) {
  if (!before && !after) return 'same';
  if (!before) return 'new';
  if (!after) return 'gone';
  if ((after.errors || 0) > (before.errors || 0)) return 'worse';
  if ((after.errors || 0) < (before.errors || 0)) return 'better';
  const b = before[key] || 0, a = after[key] || 0;
  if (a > b * 1.2 && a - b > step) return 'worse';
  if (b > a * 1.2 && b - a > step) return 'better';
  return 'same';
}

/** The two windows side by side, with every verdict represented. */
function compareOf(before, after, until, service) {
  const first = { from: before, to: Math.max(before, after - 1), bucketMs: 15000 };
  const second = { from: after, to: Math.max(after, until), bucketMs: 15000 };

  const beforeEndpoints = new Map(endpointStats(first, service).map((e) => [e.endpointId, e]));
  const afterEndpoints = new Map(endpointStats(second, service).map((e) => [e.endpointId, e]));
  const sideOf = (e) => (e ? {
    calls: e.calls, errors: e.errors, p50Ms: e.p50Ms, p95Ms: e.p95Ms, maxMs: e.maxMs,
    dbCallsPerRequest: Math.round((2 + rnd()) * 100) / 100,
    dbMsPerRequest: Math.round(e.p50Ms * 40) / 100,
  } : null);

  const ids = [...new Set([...beforeEndpoints.keys(), ...afterEndpoints.keys()])];
  const endpoints = ids.map((id, i) => {
    const ep = ENDPOINTS.find((e) => e.endpointId === id);
    let b = sideOf(beforeEndpoints.get(id));
    let a = sideOf(afterEndpoints.get(id));
    // the mock exercises every verdict, so the first rows are forced apart
    if (i === 0 && a) a = { ...a, p95Ms: Math.round(a.p95Ms * 24) / 10, errors: (a.errors || 0) + 3 };
    if (i === 1 && a && b) a = { ...a, p95Ms: Math.round((b.p95Ms / 2.5) * 10) / 10, errors: 0 };
    if (i === 2) b = null;
    if (i === 3) a = null;
    return {
      endpointId: id, service: ep ? ep.service : '', name: ep ? ep.name : id,
      before: b, after: a, verdict: verdictOf(b, a, 'p95Ms', 10),
      _total: (a ? a.calls * a.p95Ms : 0) + (b ? b.calls * b.p95Ms : 0),
    };
  });

  const beforeRequests = totalsOf(first, service).requests;
  const afterRequests = totalsOf(second, service).requests;
  const beforeQueries = new Map(queryStats(first, service).map((q) => [q.queryId, q]));
  const afterQueries = new Map(queryStats(second, service).map((q) => [q.queryId, q]));
  const queryIds = [...new Set([...beforeQueries.keys(), ...afterQueries.keys()])];
  const querySide = (q, requests) => (q ? {
    calls: q.calls,
    callsPerRequest: Math.round((q.calls / Math.max(1, requests)) * 100) / 100,
    p95Ms: q.p95Ms, totalMs: q.totalMs,
  } : null);
  const queries = queryIds.map((id, i) => {
    const def = QUERIES.find((q) => q.queryId === id);
    let b = querySide(beforeQueries.get(id), beforeRequests);
    let a = querySide(afterQueries.get(id), afterRequests);
    if (i === 0 && a && b) a = { ...a, callsPerRequest: Math.round(b.callsPerRequest * 300) / 100, p95Ms: Math.round(a.p95Ms * 22) / 10 };
    if (i === 1 && a && b) a = { ...a, callsPerRequest: Math.round((b.callsPerRequest / 4) * 100) / 100, p95Ms: Math.round((a.p95Ms / 3) * 10) / 10 };
    if (i === 2) b = null;
    return {
      queryId: id, service: def ? def.service : '', statement: def ? def.statement : id,
      before: b, after: a, verdict: verdictOf(b, a, 'p95Ms', 10),
      _total: (a ? a.totalMs : 0) + (b ? b.totalMs : 0),
    };
  });

  const beforeErrors = new Map(errorGroups(first, service).map((e) => [e.errorId, e]));
  const afterErrors = new Map(errorGroups(second, service).map((e) => [e.errorId, e]));
  const errorIds = [...new Set([...beforeErrors.keys(), ...afterErrors.keys()])];
  const errors = errorIds.map((id, i) => {
    const def = ERRORS.find((e) => e.errorId === id);
    const b = beforeErrors.has(id) ? beforeErrors.get(id).count : (i === 1 ? 0 : null);
    const a = afterErrors.has(id) ? afterErrors.get(id).count : (i === 2 ? 0 : null);
    return {
      errorId: id, service: def ? def.service : '', type: def ? def.type : id, message: def ? def.message : '',
      before: b, after: a,
      verdict: b == null ? 'new' : a == null ? 'gone' : a > b ? 'worse' : a < b ? 'better' : 'same',
      _total: (a || 0) + (b || 0),
    };
  });

  const order = (list) => list
    .sort((x, y) => (VERDICT_ORDER[x.verdict] - VERDICT_ORDER[y.verdict]) || (y._total - x._total))
    .map(({ _total, ...rest }) => rest);

  return {
    before: { from: first.from, to: first.to },
    after: { from: second.from, to: second.to },
    totals: { before: totalsOf(first, service), after: totalsOf(second, service) },
    endpoints: order(endpoints),
    queries: order(queries),
    errors: order(errors),
  };
}

const ROUTES = [
  [/^\/api\/status$/, () => statusBody()],

  [/^\/api\/overview$/, (m, q) => {
    const w = windowOf(q);
    const entries = entrySpans(inWindow(w), null);
    const durations = entries.map((e) => e.span.durationMs).sort((a, b) => a - b);
    const errors = entries.filter((e) => e.span.error).length;
    const series = seriesFor(entries, w);
    const secs = Math.max(1, (w.to - w.from) / 1000);
    const histogram = histogramOf(entries.map((e) => e.span));
    return {
      window: w,
      totals: {
        requests: entries.length, errors,
        errorRate: entries.length ? errors / entries.length : 0,
        rps: Math.round((entries.length / secs) * 100) / 100,
        p50Ms: percentile(durations, 50), p95Ms: percentile(durations, 95), p99Ms: percentile(durations, 99),
        maxMs: durations.length ? durations[durations.length - 1] : 0,
        apdex: apdexOf(histogram),
        histogram,
      },
      services: SERVICES.map((s) => summaryFor(s.name, w)),
      tingles: tingles.filter((t) => t.at >= w.from && t.at <= w.to).slice(-50).reverse(),
      series: { t: series.t, requests: series.requests, errors: series.errors, p95Ms: series.p95Ms, histogram: series.histogram },
    };
  }],

  [/^\/api\/services$/, (m, q) => ({ services: SERVICES.map((s) => summaryFor(s.name, windowOf(q))) })],

  [/^\/api\/services\/([^/]+)$/, (m, q) => {
    const name = decodeURIComponent(m[1]);
    const def = SERVICES.find((s) => s.name === name);
    if (!def) return { status: 404, body: { error: 'no such service: ' + name } };
    const w = windowOf(q);
    return {
      service: summaryFor(name, w),
      resource: def.resource,
      window: w,
      series: seriesFor(entrySpans(inWindow(w, name), name), w),
      endpoints: endpointStats(w, name),
      queries: queryStats(w, name).sort((a, b) => b.totalMs - a.totalMs).slice(0, 10),
      errors: errorGroups(w, name).slice(0, 10).map(strip),
      dependencies: dependencies(w, name),
    };
  }],

  [/^\/api\/endpoints$/, (m, q) => ({ endpoints: endpointStats(windowOf(q), q.service) })],

  [/^\/api\/endpoints\/([^/]+)$/, (m, q) => {
    const id = m[1];
    const w = windowOf(q);
    const ep = ENDPOINTS.find((e) => e.endpointId === id);
    if (!ep) return { status: 404, body: { error: 'no such endpoint' } };
    const stats = endpointStats(w, ep.service).find((e) => e.endpointId === id)
      || { endpointId: id, service: ep.service, method: ep.method, route: ep.route, name: ep.name, kind: 'SERVER', calls: 0, errors: 0, errorRate: 0, rps: 0, avgMs: 0, p50Ms: 0, p95Ms: 0, p99Ms: 0, maxMs: 0, totalMs: 0, apdex: null, histogram: [0, 0, 0, 0, 0], statusCodes: {} };
    const list = inWindow(w, ep.service).filter((t) => t.endpointId === id);
    const entries = list.map((t) => ({ span: t.spans[0], trace: t }));
    return {
      endpoint: stats,
      series: seriesFor(entries, w),
      queries: queryStats(w, ep.service).filter((qs) => ep.queries.some((i) => QUERIES[i].queryId === qs.queryId)),
      errors: errorGroups(w, ep.service).filter((e) => e.endpoints.some((x) => x.name === ep.name)).map(strip),
      traces: list.slice().sort((a, b) => b.durationMs - a.durationMs).slice(0, 20).map(summary),
      recent: list.slice().sort((a, b) => b.start - a.start).slice(0, 20).map(summary),
      breakdown: timeSplit(
        list.slice().sort((a, b) => b.durationMs - a.durationMs).slice(0, 20), ep.service).breakdown,
    };
  }],

  [/^\/api\/traces$/, (m, q) => {
    const w = windowOf(q);
    let list = inWindow(w, q.service);
    if (q.endpointId) list = list.filter((t) => t.endpointId === q.endpointId);
    if (q.minMs) list = list.filter((t) => t.durationMs >= +q.minMs);
    if (q.maxMs) list = list.filter((t) => t.durationMs <= +q.maxMs);
    if (q.status === 'error') list = list.filter((t) => t.error);
    if (q.status === 'ok') list = list.filter((t) => !t.error);
    if (q.q) {
      const needle = q.q.toLowerCase();
      list = list.filter((t) => t.spans.some((s) => s.name.toLowerCase().includes(needle)
        || Object.values(s.attributes).some((v) => typeof v === 'string' && v.toLowerCase().includes(needle))));
    }
    list = list.slice().sort((a, b) => b.start - a.start || (a.traceId < b.traceId ? 1 : a.traceId > b.traceId ? -1 : 0));
    const total = list.length;
    if (q.before) list = list.filter((t) => t.start < +q.before || (q.beforeId && t.start === +q.before && t.traceId < q.beforeId));
    return { traces: list.slice(0, +(q.limit || 50)).map(summary), total, window: w };
  }],

  [/^\/api\/traces\/([0-9a-f]+)$/, (m) => {
    const t = traces.find((x) => x.traceId === m[1]);
    if (!t) return { status: 404, body: { error: 'no such trace' } };
    return {
      traceId: t.traceId, start: t.start, end: t.end, durationMs: t.durationMs,
      services: t.services,
      spans: t.spans,
      logs: logs.filter((l) => l.traceId === t.traceId).sort((a, b) => a.at - b.at),
    };
  }],

  [/^\/api\/scatter$/, (m, q) => {
    const w = windowOf(q);
    let list = inWindow(w, q.service);
    if (q.endpointId) list = list.filter((t) => t.endpointId === q.endpointId);
    const limit = +(q.limit || 5000);
    const truncated = list.length > limit;
    list = list.slice(-limit);
    return {
      window: w,
      truncated,
      points: list.map((t) => {
        let flags = 0;
        if (t.error) flags |= 1;
        if (t.slow) flags |= 2;
        if (t.spans.some((s) => s.category === 'db' && s.slow)) flags |= 4;
        return [t.start, t.durationMs, t.rootService, t.rootName, t.traceId, flags];
      }),
    };
  }],

  [/^\/api\/map$/, (m, q) => mapView(windowOf(q))],

  [/^\/api\/queries$/, (m, q) => {
    const w = windowOf(q);
    const key = { total: 'totalMs', avg: 'avgMs', p95: 'p95Ms', max: 'maxMs', calls: 'calls' }[q.sort || 'total'] || 'totalMs';
    return { queries: queryStats(w, q.service).sort((a, b) => b[key] - a[key]).slice(0, +(q.limit || 100)) };
  }],

  [/^\/api\/queries\/([^/]+)$/, (m, q) => {
    const w = windowOf(q);
    const def = QUERIES.find((x) => x.queryId === m[1]);
    if (!def) return { status: 404, body: { error: 'no such query' } };
    const stats = queryStats(w, def.service).find((x) => x.queryId === m[1])
      || { queryId: m[1], service: def.service, system: def.system, namespace: def.namespace, operation: def.operation, table: def.table, statement: def.statement, calls: 0, errors: 0, avgMs: 0, p50Ms: 0, p95Ms: 0, maxMs: 0, totalMs: 0, slowCalls: 0, callers: [], lastSeen: 0 };
    const t = bucketsOf(w);
    const index = new Map(t.map((x, i) => [x, i]));
    const calls = t.map(() => 0);
    const buckets = t.map(() => []);
    const holders = [];
    for (const tr of inWindow(w, def.service)) {
      let has = false;
      for (const s of tr.spans) {
        if (s.category !== 'db' || s.attributes['db.statement'] !== def.statement) continue;
        has = true;
        const b = Math.floor(s.start / w.bucketMs) * w.bucketMs;
        const i = index.get(b);
        if (i === undefined) continue;
        calls[i]++;
        buckets[i].push(s.durationMs);
      }
      if (has) holders.push(tr);
    }
    return {
      query: stats,
      series: { t, calls, p95Ms: buckets.map((d) => (d.length ? percentile(d.slice().sort((a, b) => a - b), 95) : null)) },
      traces: holders.sort((a, b) => b.durationMs - a.durationMs).slice(0, 20).map(summary),
    };
  }],

  // Every frame resolves, under a project directory the mock makes up; the lines say where they are.
  [/^\/api\/source$/, (m, q) => {
    const f = /^([\w$.]+)\.[^.()\s]+\(([\w$-]+\.(?:java|kt|groovy|scala)):(\d+)\)$/.exec(q.frame || '');
    if (!f) return { status: 404, body: { error: 'No source for frame: ' + (q.frame || '') } };
    const pkg = f[1].split('.').slice(0, -1).join('/');
    const line = +f[3];
    const start = Math.max(1, line - 2);
    const lines = [];
    for (let n = start; n <= line + 2; n++) {
      lines.push(n === line ? '        return load(id); // ' + f[2] + ':' + n : '        // ' + f[2] + ':' + n);
    }
    return { frame: q.frame, file: '/home/me/project/src/main/java/' + (pkg ? pkg + '/' : '') + f[2], line, start, lines };
  }],

  [/^\/api\/errors$/, (m, q) => {
    const w = windowOf(q);
    return { errors: errorGroups(w, q.service).slice(0, +(q.limit || 100)).map((g) => ({ ...strip(g), series: errorSeries(g, w) })) };
  }],

  [/^\/api\/errors\/([^/]+)$/, (m, q) => {
    const w = windowOf(q);
    const group = errorGroups(w, null).find((e) => e.errorId === m[1]);
    if (!group) return { status: 404, body: { error: 'no such error group' } };
    const t = bucketsOf(w);
    const index = new Map(t.map((x, i) => [x, i]));
    const counts = t.map(() => 0);
    for (const tr of group._traces) {
      const i = index.get(Math.floor(tr.start / w.bucketMs) * w.bucketMs);
      if (i !== undefined) counts[i]++;
    }
    return {
      error: strip(group),
      code: appFrames(group.sample && group.sample.stacktrace),
      chain: chainOf(group.sample && group.sample.stacktrace),
      series: { t, count: counts },
      traces: group._traces.slice().sort((a, b) => b.start - a.start).slice(0, 20).map(summary),
    };
  }],

  [/^\/api\/marks$/, (m, q) => ({
    marks: marks.slice().sort((a, b) => b.at - a.at).slice(0, +(q.limit || 50)),
  })],

  [/^\/api\/findings$/, (m, q) => {
    const w = windowOf(q);
    const limit = Math.min(100, +(q.limit || 20));
    const { requests, acked, resolved, findings } = findingsFor(w, q.service, limit, q.hideAcked === 'true');
    return { window: w, requests, acked, resolved, findings };
  }],

  [/^\/api\/findings\/([^/]+)$/, (m, q) => {
    const w = windowOf(q);
    const id = decodeURIComponent(m[1]);
    const { requests, findings } = findingsFor(w, q.service, 1000, false);
    const rank = findings.findIndex((f) => f.id === id) + 1;
    if (!rank) return { status: 404, body: { error: 'No such finding in this window: ' + id } };
    return { window: w, requests, rank, finding: findings[rank - 1] };
  }],

  [/^\/api\/acks$/, (m, q) => ({
    acks: Array.from(acks).filter(([, ack]) => !ack.resolved)
      .map(([findingId, ack]) => ({ findingId, at: ack.at, note: ack.note }))
      .sort((a, b) => b.at - a.at)
      .slice(0, +(q.limit || 200)),
  })],

  [/^\/api\/compare$/, (m, q) => {
    if (!q.before || !q.after) {
      return { status: 400, body: { error: 'compare needs both before and after, as marks or time selectors' } };
    }
    const until = resolveSelector(q.until, Date.now(), q.service);
    const after = resolveSelector(q.after, until - 5 * 60000, q.service);
    const before = resolveSelector(q.before, after - 5 * 60000, q.service);
    return compareOf(before, after, until, q.service);
  }],

  [/^\/api\/logs$/, (m, q) => {
    const w = windowOf(q);
    const min = { TRACE: 1, DEBUG: 5, INFO: 9, WARN: 13, ERROR: 17 }[q.severity] || 0;
    let list = logs.filter((l) => l.at >= w.from && l.at <= w.to
      && (!q.service || l.service === q.service)
      && (!q.traceId || l.traceId === q.traceId)
      && l.severityNumber >= min
      && (!q.q || l.body.toLowerCase().includes(q.q.toLowerCase()) || (l.logger || '').toLowerCase().includes(q.q.toLowerCase())));
    list = list.slice().sort((a, b) => b.at - a.at || b.id - a.id);
    const total = list.length;
    if (q.before) list = list.filter((l) => l.at < +q.before || (q.beforeId && l.at === +q.before && l.id < +q.beforeId));
    return { logs: list.slice(0, +(q.limit || 200)), total };
  }],

  [/^\/api\/metrics$/, () => ({ metrics: METRIC_CATALOG })],

  [/^\/api\/metrics\/series$/, (m, q) => metricSeries(q.name, q.service, windowOf(q), q.rate === 'true')],

  [/^\/api\/jvm$/, (m, q) => {
    const service = q.service || SERVICES[0].name;
    return jvmView(service, windowOf(q));
  }],

  [/^\/api\/export$/, (m, q) => {
    if (q.traceId) {
      const t = traces.find((x) => x.traceId === q.traceId);
      return { traces: t ? [{ traceId: t.traceId, start: t.start, end: t.end, durationMs: t.durationMs, services: t.services, spans: t.spans, logs: logs.filter((l) => l.traceId === t.traceId) }] : [] };
    }
    const w = windowOf(q);
    return { traces: inWindow(w, q.service).map((t) => ({ traceId: t.traceId, start: t.start, end: t.end, durationMs: t.durationMs, services: t.services, spans: t.spans })) };
  }],
];

/** Occurrences per bucket, the window's buckets merged until there are at most 30 (api.adoc#error-group). */
function errorSeries(group, w) {
  const t = bucketsOf(w);
  const merge = Math.ceil(t.length / 30);
  const out = new Array(Math.ceil(t.length / merge)).fill(0);
  for (const tr of group._traces) {
    const i = Math.floor((tr.start - t[0]) / w.bucketMs);
    if (i >= 0 && i < t.length) out[Math.floor(i / merge)]++;
  }
  return out;
}

/**
 * The mock has no text renderer: a request for one answers a stand-in that names the path
 * and carries the JSON, so the Copy as Markdown buttons have something to copy.
 */
function textStandIn(path, body) {
  return '# ' + path + '  (mock: the server renders this as cli.adoc#text-rendering says)\n\n```json\n'
    + JSON.stringify(body, null, 2) + '\n```\n';
}

function strip(group) {
  const { _traces, ...rest } = group;
  return rest;
}

// --- shims --------------------------------------------------------------

const realFetch = globalThis.fetch.bind(globalThis);

globalThis.fetch = async function mockFetch(input, init) {
  const url = new URL(typeof input === 'string' ? input : input.url, location.href);
  const method = ((init && init.method) || (typeof input !== 'string' && input.method) || 'GET').toUpperCase();
  if (!url.pathname.startsWith('/api/')) return realFetch(input, init);

  if (url.pathname === '/api/data' && method === 'DELETE') {
    traces.length = 0;
    logs.length = 0;
    tingles.length = 0;
    acks.clear();
    spanTotal = 0;
    return new Response(null, { status: 204 });
  }

  const resolvePath = /^\/api\/findings\/([^/]+)\/resolve$/.exec(url.pathname);
  if (resolvePath && method === 'POST') {
    let body = {};
    try { body = JSON.parse((init && init.body) || '{}') || {}; } catch (e) { body = {}; }
    const findingId = decodeURIComponent(resolvePath[1]);
    const resolution = { at: Date.now(), note: body.note ? String(body.note) : null, resolved: true };
    acks.set(findingId, resolution);
    return new Response(JSON.stringify({ findingId, at: resolution.at, note: resolution.note }), {
      status: 201, headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }
  if (resolvePath && method === 'DELETE') {
    const findingId = decodeURIComponent(resolvePath[1]);
    if (!(acks.get(findingId) || {}).resolved) {
      return new Response(JSON.stringify({ error: 'No such resolution: ' + findingId }),
        { status: 404, headers: { 'content-type': 'application/json; charset=utf-8' } });
    }
    acks.delete(findingId);
    return new Response(null, { status: 204 });
  }

  const ackPath = /^\/api\/findings\/([^/]+)\/ack$/.exec(url.pathname);
  if (ackPath && method === 'POST') {
    let body = {};
    try { body = JSON.parse((init && init.body) || '{}') || {}; } catch (e) { body = {}; }
    const findingId = decodeURIComponent(ackPath[1]);
    const ack = { at: Date.now(), note: body.note ? String(body.note) : null };
    acks.set(findingId, ack);
    return new Response(JSON.stringify({ findingId, at: ack.at, note: ack.note }), {
      status: 201, headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }
  if (ackPath && method === 'DELETE') {
    const findingId = decodeURIComponent(ackPath[1]);
    if ((acks.get(findingId) || {}).resolved || !acks.delete(findingId)) {
      return new Response(JSON.stringify({ error: 'No such acknowledgement: ' + findingId }),
        { status: 404, headers: { 'content-type': 'application/json; charset=utf-8' } });
    }
    return new Response(null, { status: 204 });
  }

  if (url.pathname === '/api/marks' && method === 'POST') {
    let body = {};
    try { body = JSON.parse((init && init.body) || '{}') || {}; } catch (e) { body = {}; }
    const name = String(body.name || '');
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(name)) {
      return new Response(JSON.stringify({ error: 'a mark name matches [A-Za-z0-9._-]{1,64}' }),
        { status: 400, headers: { 'content-type': 'application/json; charset=utf-8' } });
    }
    const mark = {
      id: ++markId, at: body.at ? +body.at : Date.now(), name,
      service: body.service || null, note: body.note || null,
    };
    marks.push(mark);
    return new Response(JSON.stringify(mark), {
      status: 201, headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }

  const query = Object.fromEntries(url.searchParams.entries());
  for (const [re, handler] of ROUTES) {
    const m = re.exec(url.pathname);
    if (!m) continue;
    await new Promise((r) => setTimeout(r, 12 + rnd() * 40));
    const result = handler(m, query);
    const status = result && result.status ? result.status : 200;
    const body = result && result.status ? result.body : result;
    if (query.format === 'text' && status === 200) {
      return new Response(textStandIn(url.pathname, body), {
        status, headers: { 'content-type': 'text/markdown; charset=utf-8' },
      });
    }
    return new Response(JSON.stringify(body), {
      status,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }
  return new Response(JSON.stringify({ error: 'not found: ' + url.pathname }), {
    status: 404, headers: { 'content-type': 'application/json; charset=utf-8' },
  });
};

class MockEventSource {
  constructor(url) {
    this.url = url;
    this.readyState = 1;
    this._handlers = new Map();
    listeners.add(this);
    setTimeout(() => emit('stats', { at: Date.now(), spans: spanTotal, traces: traces.length, logs: logs.length, perSecond: { spans: 11.2, logs: 2.4 } }), 300);
  }
  addEventListener(type, fn) {
    if (!this._handlers.has(type)) this._handlers.set(type, new Set());
    this._handlers.get(type).add(fn);
  }
  removeEventListener(type, fn) {
    const set = this._handlers.get(type);
    if (set) set.delete(fn);
  }
  _dispatch(type, data) {
    const set = this._handlers.get(type);
    if (!set) return;
    const ev = { type, data: JSON.stringify(data) };
    for (const fn of set) { try { fn(ev); } catch (e) { console.error(e); } }
  }
  close() { this.readyState = 2; listeners.delete(this); }
}
MockEventSource.CONNECTING = 0;
MockEventSource.OPEN = 1;
MockEventSource.CLOSED = 2;
globalThis.EventSource = MockEventSource;

console.info('[spider-sense] mock data: %d traces, %d logs, %d services', traces.length, logs.length, SERVICES.length);

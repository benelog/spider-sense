# Findings, and the fix each one wants

```
java -jar spider-sense.jar findings [--since=] [--until=] [--service=] [--limit=20] [--json] [--full]
```

A finding is one thing worth fixing, found by rules over the window.
Findings are ranked by severity (`high` before `medium` before `low`), then by impact within a kind, then by id, so the list is stable between two calls over the same data.
The list is bounded: 20 by default, 100 at most.

## The rules

| Kind | Rule | Severity | Impact |
|---|---|---|---|
| `error` | an error group with at least one occurrence in the window | `high` | count |
| `n-plus-one` | in one trace, the same query group runs 5 or more times under the same entry span; aggregated per (endpoint, query group) over the window | `high` when the repeats reach 20 or their summed time exceeds `slow.request.ms`, else `medium` | affected requests × median repeats |
| `slow-query` | a query group whose p95 exceeds `slow.query.ms` | `high` when p95 exceeds ten times the threshold, else `medium` | total time |
| `slow-endpoint` | an endpoint whose p95 exceeds `slow.request.ms` | `high` when p95 exceeds four times the threshold, else `medium` | total time |
| `pool-exhausted` | a JDBC pool with a point in the window where pending requests are above zero, or used equals max | `high` | pending, then used |

The thresholds are the server's: `slow.request.ms` is 500 by default and `slow.query.ms` is 100.
`status` prints the ones in force.

## What every finding carries

| Field | How to read it |
|---|---|
| `id` | kind plus 12 hex characters over (kind, service, subject); stable across windows, so the same problem keeps its id between runs |
| `title` | one line, the claim |
| `why` | the numbers that justify it, in a sentence: quote this rather than restating it |
| `subject` | the `endpointId`, `queryId`, `errorId` or `pool` to pass to `endpoints`, `queries`, `errors` or the API |
| `numbers` | kind-specific, listed below |
| `statement` | the statement as the OpenTelemetry agent sanitised it, literals already `?`; cut at 200 characters unless `--full` |
| `code` | application frames, most specific first, at most 5; empty when none is known |
| `traces` | at most 3 trace ids: the evidence, and what `trace <id>` opens |

`traces` are the three slowest traces for `slow-*`, the three newest for `error`, the three most recent affected for `n-plus-one`, and none for `pool-exhausted`.

In the text output the ranked table comes first and these fields follow as one numbered block per row, in that order and mostly without labels:
`<n>. <id> — <why>`, then the `numbers` on one line as `name value, name value`, then the `statement` when there is one, then the `code` frames one per line, then `traces: <id> <id>`.

**About `code`.** The OpenTelemetry Java agent does not record where a span was started from, so `code` comes only from the `exception.stacktrace` of an error and the `code.function` / `code.namespace` attributes that a few instrumentations set.
It is therefore reliable for `error` findings and often empty for the others; when it is empty, open a trace from `traces` and read the tree, which names the endpoint and the statement even when it cannot name the line.
A stack trace is reduced to its application frames by dropping known framework prefixes (`java.`, `jakarta.`, `org.springframework.`, `org.hibernate.`, `org.apache.`, `com.zaxxer.`, `org.h2.`, `io.opentelemetry.` and others).
When that heuristic guesses wrong, `-Dspidersense.app.packages=com.acme,org.acme` replaces it with an allowlist.

---

## `n-plus-one`

`numbers`: `requests` (entry spans of the endpoint in the window), `affected` (of them, how many repeated), `medianRepeats`, `maxRepeats`, `msPerRequest` (summed time of the repeated statement, per affected request).

Read it as: this endpoint ran that statement `medianRepeats` times in one request, and that cost `msPerRequest`.
`affected` well below `requests` means only some inputs trigger it, which usually points at a branch or a lazily loaded collection that is only touched sometimes.
Open a trace from `traces`: the repeated database spans collapse into one `× n` line under the entry span, and the span above them is the code path that loops.

### What to do

**JPA / Hibernate.** A lazy association touched inside a loop is the usual cause; the fix is to load it with the parent.

```java
// Before: each order's lines are loaded on first access, one SELECT per order
List<Order> orders = repository.findByCustomerId(customerId);
for (Order order : orders) {
    total = total.add(order.lines().stream()...);   // N queries
}

// After: one query with a fetch join
@Query("select distinct o from Order o join fetch o.lines where o.customer.id = :customerId")
List<Order> findByCustomerIdWithLines(@Param("customerId") long customerId);
```

An entity graph (`@EntityGraph(attributePaths = "lines")` on the repository method) does the same without JPQL.
When a fetch join would multiply rows across two collections, set batch loading instead and let Hibernate fetch the collections in `IN` batches:

```java
@BatchSize(size = 50)                       // on the collection, or the entity
private List<OrderLine> lines = new ArrayList<>();
```

Or globally: `spring.jpa.properties.hibernate.default_batch_fetch_size=100`.
That turns 42 queries into one or two, which `compare` shows as the query's `calls/req` column falling.

**spring-jdbc or plain JDBC.** The loop is explicit, so replace it with one query over the whole key set.

```java
// Before
for (long orderId : orderIds) {
    lines.addAll(jdbc.query("select * from order_line where order_id = ?", mapper, orderId));
}

// After: one statement, grouped in memory
var params = new MapSqlParameterSource("ids", orderIds);
Map<Long, List<OrderLine>> byOrder = namedJdbc
        .query("select * from order_line where order_id in (:ids)", params, mapper)
        .stream().collect(groupingBy(OrderLine::orderId));
```

A very large key set is chunked (a thousand at a time) rather than sent as one enormous `IN`.
Note that an `IN` list with a varying number of parameters produces a new statement text per size, so Spider Sense will group those calls separately.

After the fix, `compare --before=before --after=after` should show the query's `calls/req` down to 1 and the endpoint's verdict `better`; `check --max-queries-per-request=` locks it in.

---

## `slow-query`

`numbers`: `calls`, `slowCalls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `callers` (endpoint, service and calls, the nearest entry span up the parent chain).

A p95 far above p50 is a query that is fast for most inputs and slow for some, which usually means a plan that degrades with the data rather than a statement that is always wrong.
`callers` says which endpoints pay for it, and the `queries` table carries the same under its own `callers` column, as `GET /api/reports/revenue ×3`; the trace ids in `traces` give the individual calls.

### What to do

**A missing index** is the first thing to test, and a leading wildcard defeats one.

```sql
-- A full scan over 200,000 rows
select b.* from book b where b.title like '%graph%';

-- With an index on the column and a prefix match, the index is usable
create index idx_book_title on book(title);
select b.* from book b where b.title like 'graph%';
```

Full-text search that genuinely needs an infix match belongs in a search index, not in a `LIKE`.

**A rewrite** when the shape is the problem: a correlated subquery per row becomes a join, `select count(*)` over everything becomes a bounded count, an `order by` over an unindexed column becomes an indexed one.

**Not selecting what is not needed.** A JPA repository returning entities loads every column and puts each row in the persistence context; a projection does not.

```java
public interface OrderSummary {              // a Spring Data projection
    long getId();
    BigDecimal getTotal();
}

@Query("select o.id as id, o.total as total from Order o where o.createdAt >= :since")
List<OrderSummary> summaries(@Param("since") Instant since);
```

The spring-jdbc equivalent is naming the columns instead of `select *` and mapping only those.

**Paging** when the endpoint does not need the whole result: `Pageable` in JPA, `limit`/`offset` or a keyset predicate in SQL.

Re-run the same exercise and check the query's `p95` and `total` columns in `compare`, not only the endpoint's.

---

## `slow-endpoint`

`numbers`: `calls`, `p50Ms`, `p95Ms`, `maxMs`, `totalMs`, `apdex`, `dbCallsPerRequest`, `dbMsPerRequest`, `dbShare` (0..1 in the JSON, the part of the endpoint's total time spent in database spans of the same trace and service; the text prints it as a percentage, `93.0%`).

`dbShare` is the fork in the road.

- **High `dbShare`** (most of the time in the database): the endpoint is not the problem, its queries are. Look for an `n-plus-one` or `slow-query` finding with this endpoint among its `callers`, or run `queries --service=` over the same window, and fix it there.
- **Low `dbShare`**: the time is elsewhere. Open the slowest trace from `traces` and read the tree: a `CLIENT` span to another service or an external host that dominates the duration is the answer, and so is a long stretch with no child spans at all, which is the endpoint's own code.

A `CLIENT` span whose child `SERVER` span is nearly as long moves the question to the other service; the same loop applies there, with `--service=` set to it.
A `CLIENT` span much longer than the `SERVER` span it wraps is connection setup, queueing or serialisation, not the callee.
For the endpoint's own code, the usual suspects are work done per request that could be done once (compiling a pattern, building a client, reading a file), an unbounded collection built in memory, or a sleep.

```java
// Before: a new RestClient per request, so a new connection pool per request
public Book fetch(long id) {
    return RestClient.create().get().uri(url, id).retrieve().body(Book.class);
}

// After: one client, built once
private final RestClient client;   // constructed in the configuration, reused
```

An endpoint that fans out to several independent calls can run them together rather than in sequence; the trace tree shows sequential `CLIENT` spans laid end to end when it does not.

`check --endpoint="GET /orders/report" --max-p95-ms=` scopes the verdict to the one endpoint being worked on.

---

## `error`

`numbers`: `count`, `firstSeen`, `lastSeen`, `type`, `message` (normalised, digits replaced by `?`), `endpoints` (name and count).

The group is `(service, exception type or `error.type`, normalised message)`, so one row is one failure mode, not one occurrence.
`code` is at its best here: the application frames of the sample stack trace, innermost first, so the **top frame is where to look**.
`traces` are the three newest occurrences; `trace <id>` shows the exception span with its message and frames, and the trace's log lines underneath, which usually carry the context the message leaves out.
`logs --trace=<traceId>` on its own gives the full log for that request.

### What to do

Read the top application frame, then decide which of the three it is.

- **A bug**: the code cannot handle an input it will receive. Fix the code, and add the test that reproduces it.
- **An expected condition answered as a failure**: a business rule violated, an entity not found. It should still be visible, but it should answer the client properly rather than escape as a 500.
- **A dependency failing**: the trace will show the `CLIENT` span that failed first.

```java
// The condition is real, the 500 is not
@ExceptionHandler(OrderAlreadyShippedException.class)
ResponseEntity<Problem> handle(OrderAlreadyShippedException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Problem.of(e.getMessage()));
}
```

A message that carries the identifier (`Order 42 is already shipped`) groups correctly, because Spider Sense normalises the digits away, and it is what makes the log line useful.
`check --max-errors=0` after the fix, over a window that starts at the restart (`--since=start`).

---

## `pool-exhausted`

`numbers`: `pool`, `max`, `usedMax`, `pendingMax`, `at` (the worst point).
There are no `traces`: this comes from the pool's metrics, not from a span.

`pendingMax` above zero means threads waited for a connection, and `usedMax == max` means the pool was full.
A pool at its limit is a symptom, and there are only two causes worth considering.

**Connections not returned.** A connection taken outside a `try`-with-resources, a transaction left open, or a stream consumed after the transaction that produced it ended.

```java
// Before: a leak on any exception
Connection connection = dataSource.getConnection();
doWork(connection);
connection.close();

// After
try (Connection connection = dataSource.getConnection()) {
    doWork(connection);
}
```

In Spring, work that opens a transaction and then makes a slow HTTP call holds a connection for the length of that call; move the call outside the transactional method.
A lazily loaded association read inside a controller rather than a service does the same thing through `OpenEntityManagerInView`.

**A pool too small for the concurrency.** Real, but check the first cause before raising the ceiling, because a leak will exhaust any size.

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.leak-detection-threshold=20000
```

`leak-detection-threshold` is what proves which of the two it is: it logs the stack that took a connection and did not give it back, and those log lines are in `logs`.
A pool at its limit also shows as endpoints whose p95 rises while their `dbMsPerRequest` does not: the wait happens before any database span starts, so it lands in the endpoint's time and not in its `dbShare`.

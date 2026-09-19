# servlet-warehouse

A warehouse on a plain Jakarta Servlet stack: embedded Tomcat 11, servlets registered by hand, plain JDBC over Tomcat's own connection pool, an H2 file database.
There is no Spring, no Spider Silk and no router of any kind — nothing sits between the servlet and the OpenTelemetry agent.
That is the point of it.
The other two examples both name their own spans, one through a Spring Boot resource detector and one through a `beforeRoute` filter; this one names nothing, so what Spider Sense shows is what the agent makes of a Servlet application on its own.
Service name `servlet-warehouse`, port 8083, its own Spider Sense on port 4002.

Like the others it misbehaves deliberately, because an observability tool with nothing to show is not worth looking at.

## The data

The database is `~/db/spider-sense/warehouse.mv.db`, opened as `jdbc:h2:~/db/spider-sense/warehouse;AUTO_SERVER=TRUE`.
The first start seeds 1,000 suppliers, 400,000 items and 8,000 movements in batches of 1,000 rows inside one transaction, which takes about 1.9 s and leaves an 85 MB file; every later start finds the table populated, says how many items are already there, and skips it.
Only the first 200 items get movements, forty each, which is what makes `/items/SKU-000001` an N+1 worth looking at.
Delete the file to seed again, or point somewhere else with `-Dwarehouse.db`.
`-Dwarehouse.seed.items` sets the item count and `-Dwarehouse.port` the port; the tests seed 300 items into `jdbc:h2:mem:warehouse-test`.

An item is a SKU, a three-word name such as `copper hinge 20mm`, a category, a supplier, a quantity, a price and a bay.
The names are drawn from ten adjectives, ten nouns and six sizes, and the categories from a list of six, so any search for one of those words matches a tenth of the table and still has to read all of it.
SKUs are the item's own id in `SKU-%06d`, from `SKU-000001` up, which makes every URL in this README predictable.

There is deliberately no index on `items.name` or `items.category`.
That absence is what makes the search and the report slow, so do not "fix" it.
The only index besides the primary keys and the unique SKU is on `movements(item_id)`, so that the N+1 page is slow for the number of queries it runs rather than for the cost of each one.

Two things about H2 are worth knowing here, because both were found by measuring and both changed the code.
H2 keeps the result of a repeated identical query per session and hands it back untouched until the table is written to, which made the second call to `/api/report` cost nothing at all and the endpoint look fast; the pool therefore opens every connection with `QUERY_CACHE_SIZE=0`, and each call does the work.
And 400,000 items is not the round number it looks like: an item row is narrow, so H2 scans 100,000 of them in about 30 ms, comfortably under the 100 ms at which Spider Sense calls a query slow, and it takes four times as many rows before the unindexed search and the report are the hundreds of milliseconds this example is for.

## Routes

The mapping in the first column is also the endpoint name, because that is what the agent puts in `http.route`.

| Route | Mapping | Behaviour | Slow? |
|---|---|---|---|
| `GET /` | `""` | Index: the three counts and a link to everything below | no, ~2 ms |
| `GET /items?page=` | `/items` | Fifty items as a table, a window over the primary key | no, ~4 ms |
| `GET /items?q=hinge` | `/items` | `where lower(name) like '%q%' or lower(category) like '%q%'`, ordered by an unindexed column so the database cannot stop early | **yes, ~240-330 ms** |
| `GET /items/{sku}` | `/items/*` | One item, its forty movements, then one `select name from suppliers where id = ?` per movement | **yes, N+1: 42 queries** |
| `GET /api/stock/{sku}` | `/api/stock/*` | The stock of one SKU as JSON, one query on the unique index | no, ~1 ms |
| `POST /api/movements` | `/api/movements` | Form fields `sku`, `delta`, `note`; locks the row, writes the movement, updates the quantity, all in one transaction | no, ~3 ms |
| `GET /api/report` | `/api/report` | `group by category` and `group by supplier_id` over every item, neither indexed | **yes, ~460-520 ms** |
| `GET /api/async?ms=` | `/api/async` | `startAsync()`, answered from another thread after `ms`; over the 1500 ms deadline it is a 503 | **yes, by request, up to 1500 ms** |
| `GET /api/flaky` | `/api/flaky` | Throws `IllegalStateException("Label printer offline")` about 30% of the time, and nothing catches it | no, but fails |
| `GET /api/health` | `/api/health` | `{"status":"ok"}` | no |
| `/error` | `/error` | Where Tomcat's error pages land; turns a status into `{"status":…,"message":…}` | no |

A missing SKU is 404, a movement that would take the stock below zero is 409 with the message `Stock of SKU-000012 would go negative`, and a `delta` that is not a whole number is 400.
All four of those statuses have an `ErrorPage` pointing at `/error`, so every one of them is JSON.
Only the 500 is an error in Spider Sense's sense; the rest are statuses the caller asked for.

`RequestLogFilter` is mapped at `/*` and prints one line per request — method, path with query string, status, elapsed ms — so watching the terminal shows what the load generator is doing.
It also sets the response header `X-Warehouse: 1`, and it logs in a `finally`, so the exception from `/api/flaky` carries on to Tomcat instead of being swallowed by the logging.

## Running it

Build the start scripts once:

```bash
./gradlew :examples:servlet-warehouse:installDist
```

Plain, with no agent attached:

```bash
examples/servlet-warehouse/build/install/servlet-warehouse/bin/servlet-warehouse
```

With Spider Sense attached, forwarding to a collector on port 4000:

```bash
JAVA_OPTS="-javaagent:/path/to/spider-sense.jar -Dspidersense.collector=http://localhost:4000 -Dotel.service.name=servlet-warehouse" \
  examples/servlet-warehouse/build/install/servlet-warehouse/bin/servlet-warehouse
```

With its own embedded Spider Sense on port 4002, which is what `./gradlew :examples:servlet-warehouse:run` does through the Gradle plugin:

```bash
JAVA_OPTS="-javaagent:/path/to/spider-sense.jar -Dspidersense.port=4002" \
  examples/servlet-warehouse/build/install/servlet-warehouse/bin/servlet-warehouse
```

`-Dotel.service.name=servlet-warehouse` is already in the start script's `applicationDefaultJvmArgs`, because the OpenTelemetry agent reads that property in `premain`, long before `main` could set it; restating it in `JAVA_OPTS` is harmless and wins, since the script appends `JAVA_OPTS` after the defaults.

## The tests

```bash
./gradlew :examples:servlet-warehouse:test
```

Fourteen tests, about four seconds.
They start the application on port 0 against `jdbc:h2:mem:warehouse-test;DB_CLOSE_DELAY=-1` with 300 items and drive it over `java.net.http.HttpClient`, because everything worth checking is container behaviour: the servlet mappings, the error pages, the filter, the transaction and the async deadline.

## What the Servlet stack shows

**An endpoint is a servlet mapping.**
The agent takes `http.route` from the mapping the container matched, so `endpoints` reads `GET /items/*`, `GET /api/stock/*`, `GET /items`, `GET /` and so on.
Every one of 400,000 SKUs is the same endpoint `GET /items/*`, which is the honest name for it: they are one piece of code.
This is the stack where the agent gets endpoint names right with no help, and it is worth comparing with `silk-bookstore`, where one servlet is mapped at `/*` and the application has to rename its own spans or have exactly one endpoint.

**The filter is inside the server span, not beside it.**
`RequestLogFilter` appears in the stack traces Spider Sense attaches to a finding — a slow query on `/items` is reported at `ItemListServlet.append`, called from `doGet`, called from `RequestLogFilter.doFilter` — because the server span opens before the filter chain and closes after it.
The elapsed time the filter prints is therefore always a little less than the span's.

**An async span lasts until `complete()`.**
`GET /api/async?ms=700` is a 700 ms span, not the one millisecond `doGet` took to hand the request to another thread.
A request that overruns the 1500 ms deadline is answered 503 and shows as a 503 of about 1,502 ms.

That last number is arranged, and the reason is worth reading if you ever instrument an async servlet.
The servlet arms two deadlines for the same moment: the container's, with `setTimeout(1500)` and an `AsyncListener.onTimeout` that answers 503, and its own, a task on the same scheduler.
The container's alone was not enough for either half of the sentence above.
Tomcat looks for expired async requests on its poller tick, about once a second, so its 503 arrives 400-500 ms late and a request that overruns by a little is often completed before the check notices — `?ms=2000` came back 200 as often as 503.
And the OpenTelemetry agent registers its own `AsyncListener` inside `startAsync()`, before the application's, and ends the server span in `onTimeout`, reading the status before the application has set it: a container timeout was recorded as a 200 however the client had been answered.
A plain `complete()` is read in `onComplete` instead, after the status is set, so the application's own deadline — which is what a service with an objective enforces anyway — is the one that makes the trace say what the caller was told.

**The exception behind a 500 is the one the error page received.**
`/api/flaky` throws and nothing catches it; Tomcat forwards to `/error`, and the finding reads `IllegalStateException in GET /api/flaky`, `Label printer offline`, at `warehouse.web.FlakyServlet.doGet(FlakyServlet.java:22)`.
The forward to `/error` is part of the same server span, so `/error` never appears as an endpoint of its own, and the 404s and 409s that go through it are statuses on their original endpoint rather than requests to `/error`.

**The connection pool is on the JVM page.**
The pool is `org.apache.tomcat.jdbc.pool.DataSource` rather than H2's own, with `maxActive` 8 and `maxWait` 5 s, because the agent instruments it: the connections in use, idle and waiting are metrics beside the heap, and an endpoint that holds a connection across several statements — `POST /api/movements` holds one across a `select … for update`, an insert and an update — is visible as such.

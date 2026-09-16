# silk-bookstore

A Spider Silk demo application for Spider Sense: jte pages and a JSON API over spring-jdbc and an H2 file database.
Its job is to behave badly in interesting ways, so the APM has something worth showing.
Service name `silk-bookstore`, port 8081.

## The data

The database is `~/db/spider-sense/bookstore.mv.db`, opened as `jdbc:h2:~/db/spider-sense/bookstore;AUTO_SERVER=TRUE`.
The first start seeds 200,000 books, 200 authors, and 2,000 reviews in batches of 1,000 rows, which takes well under a second (670 ms here); every later start finds the table populated and skips it.
Only the first hundred books get reviews, twenty each, which is what makes `/books/1` an N+1 worth looking at.
Delete the file to seed again.

There is deliberately no index on `books.title`, `books.description` or `books.author`.
That absence is what makes the search and the report slow, so do not "fix" it.
The schema also registers `create alias if not exists sleep for 'java.lang.Thread.sleep(long)'`, which gives the stats endpoint a query that is slow by decree rather than by accident.
H2 2.x needs the single-quoted form and the explicit `(long)`: `sleep(long)` and `sleep(Duration)` have the same parameter count, and H2 matches Java methods by count alone.

## Routes

| Route | Behaviour | Slow? |
|---|---|---|
| `GET /` | Home: the three counts and a link to everything below | no |
| `GET /books?page&q` | Paginated list. Without `q` it is an indexed window over the primary key | no |
| `GET /books?q=dragon` | With `q` it is `where lower(title) like '%q%' or lower(description) like '%q%'`, a full scan of 200,000 rows | **yes, ~350-450 ms** |
| `GET /books/{id}` | One book and its reviews, then one query per review for the reviewer's name | **yes, N+1: 22 queries for a seeded book** |
| `GET /api/books/{id}` | One book as JSON, by primary key. This is what spring-orders calls over HTTP | no, ~1 ms |
| `GET /api/books/search?q=` | The same full scan as the page, as JSON | **yes, ~350-450 ms** |
| `GET /api/books/stats` | `select author, count(*), avg(price) ... group by author` over every row, then `select sleep(300)` | **yes, ~450-550 ms** |
| `GET /api/slow?ms=` | Sleeps `ms` (default 800) in Java, no database at all: a slow URL that is not a slow query | **yes, by request** |
| `GET /api/flaky` | Throws `IllegalStateException("Inventory service unavailable")` about 30% of the time, so a 500; otherwise JSON | no, but fails |
| `GET /api/books/{id}/missing` | Always 404 through `HttpException`: a 4xx the caller asked for, which is not an error | no |
| `POST /api/reviews` | Inserts a review inside a transaction. A `rating` outside 1-5 throws `IllegalArgumentException`, so 400 | no |
| `GET /api/health` | `{"status":"ok"}` | no |

Every route is registered with a description, so `app.routes()` reads as the table above.

## Running it

Build the start scripts once:

```bash
./gradlew :examples:silk-bookstore:installDist
```

Plain, with no agent attached:

```bash
examples/silk-bookstore/build/install/silk-bookstore/bin/silk-bookstore
```

With Spider Sense attached, forwarding to a collector on port 4000:

```bash
JAVA_OPTS="-javaagent:/path/to/spider-sense.jar -Dspidersense.collector=http://localhost:4000 -Dotel.service.name=silk-bookstore" \
  examples/silk-bookstore/build/install/silk-bookstore/bin/silk-bookstore
```

`-Dotel.service.name=silk-bookstore` is already in the start script's `applicationDefaultJvmArgs`, because the OpenTelemetry agent reads that property in `premain`, long before `main` could set it; restating it in `JAVA_OPTS` is harmless and wins, since the script appends `JAVA_OPTS` after the defaults.

From Gradle, with jte reading templates from the source tree so an edit shows on refresh:

```bash
./gradlew :examples:silk-bookstore:run --args=--dev
```

## Naming the spans

The OpenTelemetry agent instruments Jetty and the Servlet API, where one servlet is mapped at `/*` and Spider Silk's own router does the routing above it.
Left alone the agent names every server span `GET /*`, and an APM that groups by endpoint then has exactly one endpoint.
`bookstore/web/Tracing.java` installs a `beforeRoute` filter that renames the current span to `METHOD /route/{template}` and sets `http.route`, where the template is `req.route().path()`: the entry of `app.routes()` the router chose for this request.
The same class records the exception behind a 500 on the span from the request logger, where `completion.exception()` is what the handler threw; a 400 for a bad rating stays off the span, and a 404 thrown as `HttpException` never appears there, since it is a status rather than a failure.
With no agent attached `Span.current()` is the API's no-op span, so both cost a few field reads and do nothing.

A `requestLogger` prints one line per request — method, path with query string, status, elapsed ms — so watching the terminal shows what the load generator is doing.

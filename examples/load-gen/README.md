# load-gen

A plain Java 21 program that sends a steady, jittered trickle of requests to `silk-bookstore` (8081), `spring-orders` (8082) and `servlet-warehouse` (8083), so the Spider Sense dashboards fill up without anyone clicking.
It has no dependencies beyond the JDK and talks HTTP through `java.net.http.HttpClient`, which the OpenTelemetry agent instruments, so running it under the agent makes some traces start at the client rather than in a server.

## Run

```bash
./gradlew :examples:load-gen:installDist
build/install/load-gen/bin/load-gen --rps=4 --duration=0
```

Under the agent, so the client side of each call is traced too:

```bash
JAVA_OPTS="-javaagent:../../spider-sense-agent/build/libs/spider-sense-0.1.0.jar -Dspidersense.collector=http://localhost:4000 -Dotel.service.name=load-gen" \
  build/install/load-gen/bin/load-gen --rps=4
```

## Options

| Option | Default | Meaning |
|---|---|---|
| `--bookstore=` | `http://localhost:8081` | base URL of silk-bookstore |
| `--orders=` | `http://localhost:8082` | base URL of spring-orders |
| `--warehouse=` | `http://localhost:8083` | base URL of servlet-warehouse |
| `--rps=` | `4` | target requests per second; the gap between requests is jittered between half and one and a half times the nominal interval |
| `--duration=` | `0` | seconds to run; `0` means until Ctrl-C |
| `--seed=` | current nanoseconds | random seed, for a repeatable mix |
| `--concurrency=` | `4` | how many requests may be in flight at once |
| `--wait=` | `60` | seconds to wait for each app's `/api/health` before sending traffic |

## Behaviour

At startup it polls `/api/health` on all three apps for up to `--wait` seconds, printing `waiting for …` once per app, and then sends traffic regardless; an app that never comes up simply produces counted failures.
Each request has a ten second timeout, and a connection failure is counted, never fatal.
Every ten seconds one status line is printed with sent, ok, 4xx, 5xx, failed, and the average and p95 over the last 1 000 requests.
Ctrl-C stops it and a shutdown hook prints the totals.

## The scenario mix

Weights are relative and add up to 148; the table lives in `Scenarios.java`.

| Scenario | Weight | Request |
|---|---|---|
| `bookstore.book-page` | 20 | `GET /books/{id}`, four times in five an id in 1-100 so the page really runs its N+1 |
| `bookstore.api-book` | 15 | `GET /api/books/{id}`, id in 1-200000 |
| `bookstore.search` | 8 | `GET /books?q=<word>`, the unindexed scan over 200 000 rows |
| `bookstore.stats` | 2 | `GET /api/books/stats` |
| `bookstore.slow` | 2 | `GET /api/slow?ms=` |
| `bookstore.flaky` | 5 | `GET /api/flaky` |
| `bookstore.missing` | 2 | `GET /api/books/{id}/missing`, always 404 |
| `bookstore.post-review` | 3 | `POST /api/reviews`, one review in ten with a rating outside 1-5 so the app answers 400 |
| `orders.page` | 15 | `GET /api/orders?page&size` |
| `orders.by-id` | 10 | `GET /api/orders/{id}`, id in 1-50000 |
| `orders.enriched` | 8 | `GET /api/orders/{id}/enriched`, which fans out to the bookstore |
| `orders.revenue-report` | 2 | `GET /api/reports/revenue?days=` |
| `orders.customer-search` | 5 | `GET /api/customers/search?q=` |
| `orders.checkout` | 4 | `POST /api/orders`, then pay, then ship, chaining the id from the first response |
| `orders.pay-shipped` | 2 | `POST /api/orders/{id}/pay` on a random order, usually a 409 |
| `orders.flaky` | 5 | `GET /api/flaky` |
| `orders.slow` | 1 | `GET /api/slow?ms=` |
| `warehouse.item-page` | 12 | `GET /items/SKU-nnnnnn`, four times in five a sku in 1-200 so the page really runs its N+1 |
| `warehouse.stock` | 10 | `GET /api/stock/SKU-nnnnnn`, sku in 1-400000 |
| `warehouse.search` | 5 | `GET /items?q=<word>`, the full scan over the item names |
| `warehouse.flaky` | 4 | `GET /api/flaky`, 500 about three times in ten |
| `warehouse.async` | 3 | `GET /api/async?ms=`, 300-1900 ms against a 1 500 ms timeout, so about one in four answers 503 |
| `warehouse.movement` | 3 | `POST /api/movements` as a form, delta in -50 to +20, so a stock that would go negative answers 409 |
| `warehouse.report` | 2 | `GET /api/report` |

## Test

```bash
./gradlew :examples:load-gen:test
```

The tests check that the weights add up, that every scenario builds an absolute URI against the right base URL with the right content type, that the checkout sequence substitutes the created order's id, and that the duration ring keeps only the last 1 000 samples and computes the right p95.

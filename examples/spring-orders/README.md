# spring-orders

A Spring Boot 4.1 example for Spider Sense: Spring MVC, Spring Data JPA over Hibernate, an H2 file database, and an HTTP call to `silk-bookstore` so one trace spans two services.
It listens on port 8082 and calls the bookstore on port 8081.
Several endpoints are slow or broken on purpose; that is the point, because an APM with nothing to show is not worth looking at.

## Data

The database is `jdbc:h2:~/db/spider-sense/orders;AUTO_SERVER=TRUE` and the schema is created by `ddl-auto=update`.
On the first start a `CommandLineRunner` seeds 500 customers, 200 products and 50 000 orders of one to five lines each, in batches of 500 with `hibernate.order_inserts=true`, which takes about 1.7 s.
On later starts the seed is skipped and the log says how many orders were already there.
Delete `~/db/spider-sense/orders.mv.db` to start over.
The seed sizes are the properties `orders.seed.customers`, `orders.seed.products` and `orders.seed.orders`, and the tests set them to 10, 10 and 50.

## Routes

| Route | What it does | Behaviour |
|---|---|---|
| `GET /` | HTML index linking to everything | fast |
| `GET /api/orders?page&size` | Page of orders; one query for the id page, one that fetch-joins customer, lines and products | fast, about 50 ms |
| `GET /api/orders/{id}` | One order, walking `line.getProduct().getName()` with no fetch join | **N+1 on purpose**: 1 + 1 + 1 + one query per line |
| `GET /api/orders/{id}/enriched` | Loads the order and calls `GET http://localhost:8081/api/books/{productId}` with `RestClient` for the first three lines | **cross-service trace**; when the bookstore is down every `book` is `null` and the status is still 200 |
| `GET /api/reports/revenue?days=90` | Revenue grouped by status and day over every order joined with every line, plus the top ten products by quantity | **slow on purpose**, 350-500 ms; the first query is `nativeQuery = true` so the SQL is in the trace verbatim |
| `GET /api/customers/search?q=` | `lower(name) like '%q%'` over 500 customers | fast, about 15 ms, for contrast with the report |
| `POST /api/orders` | Body `{"customerId":1,"lines":[{"productId":1,"quantity":2}]}`; creates a `NEW` order and computes the total | 201, or 400 when the body or the ids are wrong |
| `POST /api/orders/{id}/pay` | `NEW` to `PAID` | **409** from any other state, with the message `Order 42 is not payable from SHIPPED` |
| `POST /api/orders/{id}/ship` | `PAID` to `SHIPPED` | **sleeps 300-900 ms** to stand in for a carrier call; 409 when the order is not `PAID` |
| `GET /api/flaky` | A payment gateway that is not reliable | **500 one time in five**, an `IllegalStateException` left to propagate so the trace shows the error |
| `GET /api/slow?ms=1200` | `Thread.sleep`, no database at all | **slow on purpose**, a long span with nothing in it |
| `GET /api/health` | `{"status":"ok"}` | fast |

A missing order is 404, a business rule violation is 409, and a bad request body is 400; everything else propagates and becomes a 500, on purpose.

## Logging

The default Logback configuration writes one INFO line per created, paid and shipped order, and a WARN line each time the flaky endpoint fails.
Under the Spider Sense agent those records are shipped as OTLP logs carrying the trace id, so the log stream lines up with the traces.
Hibernate's own SQL logging is off, because the SQL belongs in the trace and not in the console.

## Actuator metrics

`spring-boot-starter-actuator` is on the class path, `management.endpoints.web.exposure.include=health,metrics` exposes the two endpoints over HTTP, and every Micrometer meter Spring Boot registers goes into the global registry: `http.server.requests`, `hikaricp.*` and `jdbc.*`, `spring.data.repository.invocations`, `tomcat.sessions.*`, `logback.events`, `executor.*`, `process.*`, `system.*`, `disk.*`, and Micrometer's own `jvm.*`.
The OpenTelemetry agent bridges that registry to OTLP only when told to, so the application is started with `-Dotel.instrumentation.micrometer.enabled=true`; `bootRun` carries the option in `build.gradle`, and the demo scripts pass it on the command line.
In Spider Sense they land on the Metrics page as about ninety metrics for `spring-orders`, beside the agent's own; the JVM page keeps reading the agent's `jvm.*` metrics, and Micrometer's `jvm.threads.live` and `jvm.memory.used` by `area` sit next to them in the explorer.
`GET /actuator/health` and `GET /actuator/metrics` answer as usual, and Spider Sense does not count them as requests, because `/actuator/**` is in `spidersense.ignore.endpoints` by default.

## Build and run

```bash
./gradlew :examples:spring-orders:test          # 11 tests against jdbc:h2:mem:orders-test, about 7 s
./gradlew :examples:spring-orders:bootJar       # build/libs/spring-orders-0.1.0.jar
java -jar build/libs/spring-orders-0.1.0.jar    # plain, no agent
```

With Spider Sense, from this directory:

```bash
java -javaagent:../../spider-sense-agent/build/libs/spider-sense-0.1.0.jar \
     -Dspidersense.collector=http://localhost:4000 \
     -jar build/libs/spring-orders-0.1.0.jar
```

The service name does not have to be passed: `spring.application.name=spring-orders` is in `application.properties` inside the jar, and the OpenTelemetry agent's Spring Boot resource detector reads it when `otel.service.name` is unset.
Pass `-Dotel.service.name=spring-orders` anyway if you want to be explicit, and `--orders.bookstore.base-url=http://host:port` to point the enriched endpoint somewhere else.

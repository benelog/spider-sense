# batch-worker

A worker with no web server at all: a plain Java 21 program that runs four scheduled jobs against an H2 file database through HikariCP, and logs through Logback.
Service name `batch-worker`, no port.
The other two examples answer HTTP, so the questions they raise are about endpoints; this one answers nothing, and the questions are about jobs, pools, threads and logs instead.
It exists to show that a service with zero requests still appears in Spider Sense, with its JVM, its logs and its jobs.

## The data

The database is `~/db/spider-sense/worker.mv.db`, opened as `jdbc:h2:~/db/spider-sense/worker;AUTO_SERVER=TRUE`.
The first start seeds 2,000 accounts and 300,000 events in batches of 1,000 inside one transaction, which takes about a second; every later start finds the events and says how many were already there.
Delete the file to seed again.

An event amount is drawn between -200 and +500 and negated for the two kinds that take money out (`purchase` and `fee`), so a reconciled balance is as likely to be negative as positive and `send-reminders` never runs out of overdrawn accounts.
There is deliberately no index on `events.account_id`.
That absence is what makes the reconcile scan all 300,000 rows, so do not "fix" it.

The pool is three connections wide, and that is on purpose too.
Eight archive slices asking three connections for a second each is what turns the pool into the bottleneck, which is the whole subject of the `pool-exhausted` finding.

## The jobs

| Job | Schedule | What it does | What it shows |
|---|---|---|---|
| `reconcile-balances` | every 15 s, first at 5 s | `select account_id, sum(amount) from events group by account_id` over 300,000 unindexed rows, then one `update accounts set balance=? where id=?` per account: 2,000 statements in one transaction, on purpose not batched **`slow-query`** on the group-by, and the shape a `slow-job` has: 2,000 write statements under one root span. H2 keeps 300,000 rows in its cache, so a warm run finishes in tens of milliseconds and stays under the 500 ms threshold; it is the first job to cross it when the table grows |
| `send-reminders` | every 5 s | Takes the twenty most neglected overdrawn accounts and calls `ReminderGateway.send`, which sleeps 5-20 ms and throws `IOException("SMTP 451 mailbox busy, try later")` one time in eight; the failure is caught per account and logged at ERROR, and the job carries on. Every successful send starts a non-daemon thread `reminder-ack-<n>` that waits on a latch nobody ever counts down | **`log-error`** (an ERROR record with a trace id, on a span that is not in error) and **`thread-growth`** (the thread count climbs every five seconds until the cap) |
| `archive-events` | every 10 s | Cuts the id range into 8 slices and submits them to a fixed pool of 8 `archive-N` threads; each slice takes a connection, runs `select count(*), sum(amount) from events where id between ? and ?`, then sleeps 800-1600 ms "uploading the slice to cold storage" **while still holding the connection**. The job waits on all eight futures and rethrows the first failure | **`pool-exhausted`** (three connections, eight slices), and an intermittent **`error`** when a slice waits longer than the 2,000 ms connection timeout and gets a `SQLTransientConnectionException` |
| `rebuild-report` | every 20 s, first at 10 s | Streams every event (`select id, account_id, amount, kind, occurred_at from events order by id`, fetch size 1,000) into a CSV `StringBuilder` in memory, logs its size in MB, and drops it | a second **`slow-job`** (p95 about 800 ms), a **`slow-query`** on the full-table read, and enough allocation churn that the JVM page moves |

Each job is a public method annotated `@WithSpan`, called from a scheduler thread with no span above it, so under the agent every run is a root `INTERNAL` span.
The archive slices are the exception: the agent's executor instrumentation carries the context into a submitted task, so an `archive-slice` span is a child of the `archive-events` span that submitted it, and the trace shows all eight under one root.

The four jobs share the three connections, so `archive-events` does not only hurt itself.
A `send-reminders` run that asks for a connection while eight slices are holding them waits its two seconds and gives up, which is why the job's own p95 sits at the connection timeout and why its failures are in the log next to the reminder ones.
That is the point of a pool finding: the job that holds the connections is rarely the job that notices.

A job that throws does not take the schedule with it.
A wrapper around every job catches the exception, logs it at ERROR with the job's name, and lets the next tick happen.

## Configuration

| Property | Default | What it is |
|---|---|---|
| `worker.db` | `jdbc:h2:~/db/spider-sense/worker;AUTO_SERVER=TRUE` | where the events live |
| `worker.seed.events` | `300000` | how many events the first start writes |
| `worker.once` | `false` | run every job once, in order, on the main thread, then exit 0 |
| `worker.leak.max` | `150` | how many acknowledgement threads `send-reminders` may leak before it stops leaking |
| `worker.archive.holdMs` | `800` | the shortest time an archive slice holds its connection; the longest is twice it |

## Running it

Build the start scripts once:

```bash
./gradlew :examples:batch-worker:installDist
```

Plain, with no agent attached:

```bash
examples/batch-worker/build/install/batch-worker/bin/batch-worker
```

With Spider Sense attached, forwarding to a collector on port 4000:

```bash
JAVA_OPTS="-javaagent:/path/to/spider-sense.jar -Dspidersense.collector=http://localhost:4000 -Dotel.service.name=batch-worker" \
  examples/batch-worker/build/install/batch-worker/bin/batch-worker
```

`-Dotel.service.name=batch-worker` is already in the start script's `applicationDefaultJvmArgs`, because the OpenTelemetry agent reads that property in `premain`, long before `main` could set it; restating it in `JAVA_OPTS` is harmless and wins, since the script appends `JAVA_OPTS` after the defaults.
Leave the collector out and the agent starts a Spider Sense of its own on port 4003, which is what the Gradle plugin's `spiderSense { port = 4003 }` configures for `run`.

One pass over every job, for a quick look or for a smoke test:

```bash
JAVA_OPTS="-Dworker.once=true" examples/batch-worker/build/install/batch-worker/bin/batch-worker
```

The worker never exits on its own.
Ctrl-C runs the shutdown hook, which stops the scheduler and closes the pool.

## Tests

```bash
./gradlew :examples:batch-worker:test
```

`ReminderGatewayTest` sends 800 reminders through a gateway seeded from its constructor and checks that about one in eight failed.
`WorkerOnceTest` runs `WorkerApp.runOnce` against `jdbc:h2:mem:worker-test` with 3,000 events, no leaked threads and a 20 ms archive hold, then checks that every account's balance is the sum of its events, that at least one account was reminded, and that the report had a size.
It calls `runOnce` rather than `main`, because `main` ends in `System.exit` and the test JVM would go with it.

## What a worker shows

**A root `INTERNAL` span is a job, never a request.**
Spider Sense stores it, puts it in the trace list and draws its tree, but it is not an endpoint: this service has no endpoints, no request count and no Apdex, and `check` has nothing to judge, so it is not the tool for a worker.
`findings` is.
The kinds a worker earns are `slow-job` for a tick that takes too long, `log-error` for a failure that only the log knows about, `pool-exhausted` for a pool that everyone is queueing at, `thread-growth` for threads that arrive and never leave, and `error` for an exception that got out.

**Why the reminder failure is a `log-error` and the archive failure is an `error`.**
`send-reminders` catches the `IOException` per account and logs it; the job returns normally, so its span is fine and the trace is green.
Nothing but the log record knows anything went wrong, which is exactly the case `log-error` exists for: an ERROR record whose trace has no error span.
`archive-events` does the opposite — it rethrows the first slice that failed, so the exception leaves the span, the span is marked in error, and the finding is an `error` with the `SQLTransientConnectionException` and its stack trace.
Neither has an endpoint, because a worker has none; both findings name the job instead.

**The held connection is the mistake behind the pool finding.**
An archive slice takes a connection, reads its rows in a millisecond, and then holds that connection for another second and a half while it uploads.
Nothing about the upload needs the database, and the connection is doing nothing but being unavailable.
Three connections and eight slices is what makes it visible; the same code against a pool of fifty would only be slower for everyone else and harder to see.

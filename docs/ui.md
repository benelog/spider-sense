# Spider Sense: UI specification

The UI lives in `spider-sense-server/src/main/resources/public` and is served by Spider Silk's static files.
No build step: plain HTML, one CSS file, ES modules, and [uPlot](https://github.com/leeoniya/uPlot) (MIT, vendored under `public/assets/vendor/`) for time-series charts and the scatter; the service map is hand-drawn SVG.
All data comes from [api.md](api.md); the page never renders server-side.

## Identity

**Name.** "Spider Sense" is the sibling of "Spider Silk": Silk is the web a spider builds, Sense is what the spider feels through it, the tingle when something on the web moves.
The UI leans on that image without being cute about it: the product tells you when something is slow or broken before you go looking.

**Logo** (`notes/logo.svg`, also served as `/assets/logo.svg`).
The Spider Silk family motif remains: a spider, a web, and the tingle, in a 200×200 viewBox with a transparent background.
Four closed, scalloped web rings and eight continuous radial strands in `#8a93a6` extend to the hub behind the larger eight-legged spider in `#e2603f`.
The web stays complete in every quadrant, with the same geometry as Spider Silk; the strands use a 2.2-unit stroke and the outer ring a 3-unit stroke.
Three evenly spaced coral sensing arcs overlay the intact north-east quarter.
The anomaly dot sits at (159.4, 40.6), where the north-east radial meets the outer ring, outside the animated group so it stays attached to the web during a pulse.
The sidebar repeats this geometry inline, with the spider in `currentColor`, the web in `--text-muted`, and the sensing arcs and dot in `--accent`.
The `.sense-arcs` group retains the event-driven pulse, centred on the spider's head at (100, 96), and respects reduced motion.
A monochrome variant (`logo-mono.svg`) uses `currentColor` throughout; when embedded as an image it defaults to black.
The 32×32 favicon uses the same complete web, spider and three sensing arcs on a rounded dark tile, with a light spider for contrast.
Keep the README and served full-colour SVGs identical, and update the inline sidebar and monochrome geometry together.

**Palette** (the same tokens as the Spider Silk badges, extended for a dark UI):

| Token | Dark | Light | Use |
|---|---|---|---|
| `--bg` | `#171a21` | `#f6f7f9` | page |
| `--bg-panel` | `#1f232c` | `#ffffff` | cards, tables |
| `--bg-raised` | `#2b303b` | `#eef0f4` | hovers, inputs, sidebar |
| `--line` | `#343a47` | `#dde1e8` | borders, grid |
| `--text` | `#e6e9ef` | `#1f232c` | |
| `--text-muted` | `#8a93a6` | `#5f6878` | secondary text, the strand grey |
| `--accent` | `#e2603f` | `#d1532f` | the tingle: primary actions, the active nav item, slow markers |
| `--ok` | `#5ec27f` | `#2e9e57` | healthy |
| `--warn` | `#f0b64b` | `#c98c14` | slow |
| `--err` | `#e5484d` | `#c9363b` | errors |
| `--silk` | `#8a93a6` | `#8a93a6` | strands, neutral chart lines |
| `--spider` | `#5a3a22` | `#5a3a22` | rarely, the logo only |

Dark is the default; `prefers-color-scheme: light` and a toggle in the top bar (persisted in `localStorage`) switch.
Fonts: system UI stack for text, a monospace stack for ids, statements, and stack traces.
Series colours for services and span categories come from a fixed 8-colour list derived from the palette (accent, silk, ok, warn, then four muted hues), assigned per service in first-seen order.

**Response-time buckets** (`/api/status.thresholds.responseBucketsMs`, four buckets and errors, see api.md) have one fixed colour each, used wherever a histogram or a load chart appears: `--bucket-1` = `--ok`, `--bucket-2` = `--series-8` (teal), `--bucket-3` = `--warn`, `--bucket-4` = `--accent`, errors `--err`.
Their labels are built from the bounds: `≤125 ms`, `≤500 ms`, `≤2 s`, `>2 s`, `error`.
**Apdex** is shown with one decimal less than the API carries (`0.93`), coloured `--err` below 0.7 and `--warn` below 0.85, plain otherwise; the tile's caption says `apdex` and its title says the T it was computed with.

**Tone of the copy.** Short and factual, no exclamation marks. Empty states explain how to send data (the OTLP endpoint from `/api/status` in a copyable snippet) rather than say "No data".

## Layout

```
┌──────────────┬──────────────────────────────────────────────────────────────┐
│ ◉ Spider     │  ‹page title›     [⚑ Mark] [Service ▾] [Last 15 min ▾] [● Live] │
│   Sense      ├──────────────────────────────────────────────────────────────┤
│              │                                                              │
│ Overview     │                                                              │
│ Findings     │                                                              │
│ Compare      │                        page content                          │
│ Map          │                                                              │
│ Services     │                                                              │
│ Scatter      │                                                              │
│ Traces       │                                                              │
│ Queries      │                                                              │
│ Errors       │                                                              │
│ Logs         │                                                              │
│ JVM          │                                                              │
│ Metrics      │                                                              │
│              │                                                              │
│ ──────────   │                                                              │
│ ⚡ tingles    │                                                              │
│ standalone   │                                                              │
│ :4000  v0.1  │                                                              │
└──────────────┴──────────────────────────────────────────────────────────────┘
```

- Sidebar 220 px, collapsible to icons at < 1100 px; a hamburger drawer under 720 px.
- Top bar: page title on the left; on the right the **Mark** button, the **service filter** (all services or one; applies to every page that takes `service`), the **time range** (`5m`, `15m`, `1h`, `6h`, `all`; `all` means `from = /api/status.oldest.span`), and the **Live** toggle.
- **Mark** opens a small dialog: a name field (`[A-Za-z0-9._-]{1,64}`, prefilled with `before` when no mark of the window is named `before`, else `after`), an optional note, and the current service filter as the mark's `service` when one is chosen; OK does `POST /api/marks` and shows a toast naming the mark. It is the person's half of the agent's loop (agent.md): mark, exercise, compare.
- **Marks on charts**: every time-series chart draws the marks that fall inside its window as a vertical dashed line the full height of the plot with the name as a small label at the top, `--text-muted` for the automatic `start` marks and `--accent` for the others; a mark with a `service` is drawn only when the service filter is off or is that service. The marks come from `GET /api/marks?limit=50`, fetched by the shell on every route change and on every Live refresh and kept in the shared state, so a page never fetches them itself and every chart gets them for free.
- **Live** on: the page re-fetches every 5 s and the window's `to` moves with the clock; the SSE `stats` event drives a small "spans/s" readout beside the toggle, and every `tingle` event increments the badge on the sidebar's "tingles" entry and prepends to the Overview feed without a refetch.
- The sidebar foot shows the mode, the port, the version, and a "how to send data" link that opens the snippet dialog.
- The **tingle badge**: the logo's accent-coloured sensing arcs pulse once (CSS animation, 600 ms) when a `tingle` SSE event arrives. Once, not continuously: the tingle is a signal, not a decoration.

Routing is hash-based (`#/`, `#/services/spring-orders`, `#/traces/<id>`, ...). The service filter and time range live in the hash query (`#/traces?service=x&range=1h`) so a URL can be shared.

## Pages

### Overview `#/`

1. **Stat tiles** in one row: requests, Apdex, error rate, p50 / p95 / p99, rps. Each has the number large, the unit small, and a 1-word caption. Colour only where it carries meaning: error rate > 1% is `--err`, p95 above the slow threshold is `--warn`, Apdex as above.
2. **Findings**: the top five of `GET /api/findings?from&to&service&limit=5`, one row each: a severity dot (`--err` for `high`, `--warn` for `medium`, `--text-muted` for `low`), the kind as a chip, the service chip, the title in bold, and the `why` sentence dimmed on a second line; the first `code` frame in monospace under it when there is one. A row click goes where the finding points: the endpoint page for an `endpointId`, the query page for a `queryId`, the error page for an `errorId`, the JVM page for a `pool`, the first evidence trace for a `job` (a job has no page of its own), and the first evidence trace when there is nothing else. The panel head links to **All findings** (`#/findings`). When there is no finding the panel says `Nothing worth fixing in this window.`
3. **Throughput & latency** chart (uPlot, 220 px) with a two-way toggle in the panel head, **Requests** | **Load**, remembered in the hash query (`chart=load`). Requests: bars for requests per bucket (silk grey), errors stacked in `--err`. Load (a Pinpoint-style load chart): the same bars split into the four response-time buckets stacked bottom-up in the bucket colours, errors on top in `--err`. In both, p95 is a line on a second y-axis in accent. Beside it, one third of the width, the **Response summary**: five vertical bars (the four buckets and errors) in the bucket colours, the count above each bar and the label beneath, the share as a tooltip; the tallest bar sets the scale. Under 900 px the two stack.
4. **Services**: one card per service: name, language chip, `embedded` chip when applicable, rps / p95 / error rate / Apdex, a sparkline (inline SVG, 120×28), and a "JVM" link when `hasJvm`. Click → service page.
5. **Tingles**: the feed, newest first: icon by kind (turtle for slow request, database for slow query, bolt for error), service chip, title, detail, relative time. Click → trace. Live events prepend with a 300 ms slide.

Empty state (no service yet): the logo large, one sentence, and the snippet dialog's content inline: the `-javaagent` line, the `OTEL_EXPORTER_OTLP_ENDPOINT`/`PROTOCOL` pair, and a `curl` for OTLP/JSON.

### Findings `#/findings`

The agent's primary answer (agent.md), for people: `GET /api/findings?from&to&service&limit=100`, ranked as the API ranks it.
A table: `#`, severity (the dot and the word), kind chip, service chip, title, and the impact number the kind is ranked by in a right-aligned column (`count` for `error`, `medianRepeats × affected` shown as `42 × 3` for `n-plus-one`, `totalMs` for `slow-query`, `slow-endpoint` and `slow-job`, `pendingMax` for `pool-exhausted`).
A row click expands it in place into the evidence: the `why` sentence, the kind's `numbers` as a key/value grid (durations, counts and rates formatted as everywhere else; `callers` and `endpoints` as a short list), the `statement` pretty-printed when there is one, the `code` frames as a monospace list, and the `traces` as links to the trace page.
The row also carries a **Go to** link to the subject's page, as the Overview panel's row click does.
The expanded row survives a Live refresh when the finding is still in the list (keyed by `id`).
Empty state: `Nothing worth fixing in this window.` with the window named, or the snippet when there was no request at all.

### Compare `#/compare?before=<selector>&after=<selector>&until=<selector>`

The agent's `compare` (agent.md), for people: two windows side by side, `[before, after)` and `[after, until)`.
A bar above the content: **Before** and **After** selects listing the marks of `GET /api/marks` newest first (`name · time · service`, plus `start` marks), **Until** as a select of the same marks plus `now` (the default), and a **Compare** button; the three selectors live in the hash query and the page fetches `GET /api/compare?before&after&until&service` when both are set.
When the query names neither, the page picks the two newest marks (`after` the newest, `before` the one before it) and fills the selects; with fewer than two marks it shows an empty state that says two marks are needed, with the **Mark** button's dialog reachable from it and the CLI's `mark before` line in a copyable block.

1. **Totals**: a row of tiles, each showing `before → after` with the after value large: requests, errors, p95, Apdex; the arrow and the after value coloured `--err` when the side got worse by the API's rule (errors grew, p95 grew by more than 20% and 10 ms, Apdex fell) and `--ok` when it got better, plain otherwise.
2. **Endpoints**: a table in the API's order (worst first): verdict chip (`worse` in `--err`, `better` in `--ok`, `new` and `gone` in `--text-muted`, `same` plain), name with the service chip, then `calls`, `errors`, `p50`, `p95`, `max`, `db calls / request`, `db ms / request`, each cell `before → after` with `—` for a missing side. Row click → the endpoint page.
3. **Queries**: the same shape with the statement (monospace, one line, full text in the title), `calls`, `calls / request`, `p95`, `total`. Row click → the query page.
4. **Errors**: verdict, type, message, service chip, `before → after` counts. Row click → the error page.

Live is honoured as on every page, but only `until = now` moves with the clock; the marks do not.

### Map `#/map`

A Pinpoint-style server map, over `/api/map`.
A full-height panel holding one SVG, drawn by hand (no library), laid out left to right in columns: the `user` node first, then services ordered by the longest path from a source (a service nobody traced calls is a source), then the external nodes (databases, hosts, destinations) in the last column; within a column nodes are sorted by name and spaced evenly.
The layout is computed once per node set and kept while Live refreshes the numbers, so nodes do not jump.

- **Nodes**: a rounded rectangle (200×64) with a kind icon (service, database, trace for http, log for messaging, a person for `user`), the name (ellipsised, full name in a `<title>`), and for a service a second line `rps · p95 · error rate` (ellipsised to the space left) with a 5-bar mini histogram (40×14) in the bucket colours in the bottom-right corner, never overlapping the text. The border is `--line`; it turns `--err` when the error rate is over 1% and `--warn` when the Apdex is under 0.85. The service filter in the top bar dims every node and edge that is not the chosen service or its neighbour.
- **Edges**: a cubic curve from the right edge of the caller to the left edge of the callee with an arrowhead, stroke width `1 + log10(calls)` capped at 5, `--silk` at 60% or `--err` when the edge has errors. An edge that skips a column bows around the nodes in between (its control points are pushed up or down by 0.6 row pitches, away from the nearest node it would otherwise cross) so no edge runs under a node. The label sits above the curve at 40% of its length on a small `--bg-panel` halo so it never touches the arrowhead: `calls` (and `errors` in `--err` when any); avg and p95 are the hover tooltip.
- **Click a node** → the drawer (as the span drawer, 420 px): the name and kind, for a service the stat tiles (requests, Apdex, error rate, p95, rps), the **Response summary** bars, the **Load** chart for the window (fetched from `/api/services/{name}`, the same chart as on the Overview in load mode), and buttons: Service, Scatter, Traces (each filtered to the service). For an external node: calls, errors, avg, p95, and the services that call it with their counts.
- Hover a node or an edge highlights it and its neighbours.
- Empty state (no edge at all): the usual sentence and the snippet.
- The SVG has a minimum width of `columns × 260` px and scrolls horizontally inside the panel on narrow screens; the panel height is the viewport minus the top bar, at least 420 px.

### Services `#/services` and `#/services/{name}`

List: a table (name, language, requests, rps, error rate, Apdex, p50, p95, p99, sparkline, last seen). Row click → detail.

Detail:

1. Header: name, resource chips (`telemetry.sdk.language`, `process.runtime.name` + version, `host.name`, `process.pid`), "JVM" button when applicable, "Traces" button (→ Traces filtered), and on the right the compact **Response summary** (the five bars, 120×28, counts in the tooltip).
2. **Stat tiles**: requests, Apdex, error rate, p50, p95, p99, rps, as on the Overview.
3. Three charts in a row (RED): requests, latency p50/p95/p99, error rate %. The first has the **Requests** | **Load** toggle of the Overview and defaults to Load; the choice is shared with the Overview (`chart=` in the hash query).
4. **Endpoints** table: method chip, route, calls, rps, Apdex, avg, p50, p95, p99, max, errors, a status-code mini-bar (2xx green / 4xx warn / 5xx err). Sortable by clicking a header; default total time. Row click → endpoint page.
5. Two half-width panels: **Top queries** (statement truncated to one line in monospace, calls, avg, p95, total) and **Top errors** (type, message, count, last seen). Both link to their pages.
6. **Dependencies**: kind icon, target, calls, errors, avg, p95.
7. **Resource attributes**: a collapsed key/value table.

### Endpoint `#/endpoints/{endpointId}`

Header (method, route, service, Apdex and the compact Response summary), the RED charts (with the same Requests | Load toggle), then tabs: **Slowest traces**, **Recent traces**, **Queries**, **Errors**. Trace rows as on the Traces page.

### Scatter `#/scatter`

A Scouter- and Pinpoint-style scatter chart (Scouter calls it the XLog), named for what it is: every request is a point on time × response time.
A full-height uPlot chart in one of two modes, **Dots** | **Heatmap**, chosen with a toggle in the bar and remembered in the hash query (`mode=heatmap`):

- **Dots** (default): x = time, y = response time (log scale toggle, default linear with the y-max at the window's p99 × 1.5 and a "▲ n above" note for clipped points), one dot per request, 3 px, service colour, error points drawn as `--err` crosses on top, slow points ringed in `--warn`.
- **Heatmap** (a Pinpoint-style alternative for dense windows): the same axes, the plot area divided into cells 6 px wide and 24 rows tall (rows follow the y scale, so log-spaced under log scale), each cell filled in accent with an alpha proportional to the square root of its count over the densest cell; a cell with any error gets a 1 px `--err` outline. Hover shows the cell's time span, response-time span, count and errors.

Above the chart: a legend of services (click to hide/show), the **Success** and **Failed** toggles (Pinpoint-style; both on by default, one off hides those points, and the counts, the selection and the y-max follow), the Log scale toggle, the mode toggle, counts (points, errors, slow), and the "truncated" notice when the API cut the list.
Drag a rectangle in either mode → the selection becomes the filter of a trace list beneath (`/api/traces` with `from`, `to`, `minMs`, `maxMs` from the rectangle, plus the current service and `status=error|ok` when only one of Success/Failed is on). Esc clears.
Live on: points stream in from the right every 2 s (refetch of the last 10 s merged in, deduplicated by traceId); the x-axis slides.
Hover in Dots: a tooltip with endpoint, service, duration, time; click → trace.

### Traces `#/traces` and `#/traces/{traceId}`

List page:

- **Query bar**: free text (`q`), a duration range (`minMs` and `maxMs`, two small number fields joined by "–"), status (`all`/`error`/`ok`), the endpoint (from the current service's endpoints, when a service is selected). Enter or a 400 ms debounce applies.
- **Table**: time, root name (bold) + service chip(s), duration with a proportional bar (width relative to the slowest in the list, colour by slow/error), spans, DB calls, status code, error/slow markers. Newest first, "Load more" pages with `before`.

Detail page:

1. Header: root name, trace id (monospace, click to copy), start time, total duration, services, span count, error count. Buttons: **Waterfall** / **Profile** view toggle, **Export** (`/api/export?traceId=`), **Logs** count anchor.
2. **Waterfall** (default): a tree of spans, indentation by depth, collapsible nodes, each row: service colour bar, name, category icon, the bar on a shared time axis (offset and duration proportional), duration label. Errors have a red bar and a bolt; slow DB spans a warn ring. Row click opens the **span drawer** on the right (420 px): name, service, kind, timing (start offset, duration, % of trace, self time), status, then attributes as a key/value table (long values in `<pre>`, `db.statement` pretty-printed SQL keywords uppercased and line-broken on major clauses), events with their attributes and the stack trace in a scrollable `<pre>`, and the scope name.
3. **Profile** (a Scouter-style profile with Pinpoint-style call-tree columns): the same spans as a flat table: `#`, start offset (`+12.3 ms`), gap since the previous step, elapsed, **self** (elapsed minus the durations of the direct children, never below zero), **%** (self as a share of the trace), depth as indentation, one-line summary (`SELECT orders … (h2)`, `GET http://localhost:8081/api/books/{id} → 200`), service. Chronological by default; the Elapsed and Self headers sort descending (the gap column shows `-` then), the Start header restores the order. The three largest self times that are at least 5% of the trace are marked `hot` (a `--accent` tint and a bold value), so the step that actually spent the time is visible without reading every row. Steps over the slow threshold are tinted `--warn`, errors `--err`. This is the view for reading what a request did, in order, and where the time went.
4. **Logs**: log records of the trace, oldest first, each with severity chip, time offset, logger, body.

### Queries `#/queries` and `#/queries/{queryId}`

List: sort selector (total / avg / p95 / max / calls), a text filter on the statement, then a table: statement (monospace, one line, full text in a title tooltip), system chip, operation, table, calls, avg, p95, max, total, slow calls (accent when > 0), last seen. The statement cell is the wide one.
Detail: the full statement pretty-printed, stats tiles, a calls/p95 chart, the callers list (endpoint → count), the slowest traces table.

### Errors `#/errors` and `#/errors/{errorId}`

List: type (monospace, package dimmed), message, service chip, count, first seen, last seen, endpoints (chips, first two + "+n"). Row click → detail.
Detail: header, count chart, the sample stack trace (`<pre>`, the app's own frames highlighted: frames whose package matches the first frame's top-level package), endpoints, recent traces.

### Logs `#/logs`

A query bar: text (`q`), minimum severity, a `traceId` field (prefilled when arriving from a trace). A dense table: time (ms precision), severity chip (colour by level), service chip, logger (dimmed), body (wrapped, preserves newlines), trace id link when present. Live on: tail mode, new lines at the top, the scroll position preserved unless at the top. "Load more" with `before`. Click a row to expand attributes and the stack trace.

### JVM `#/jvm`

Requires a service (the top-bar filter; when "all", a picker of services with `hasJvm`). Runtime header (JVM, pid, host, cpu count), then a 2-column grid of uPlot charts: heap used/committed/limit, non-heap used/committed, memory pools (one line per pool), GC count and duration per bucket (bars), threads, CPU utilisation (%, 0–100) with system load, loaded classes, and one **Connection pool** chart per pool in `connectionPools` (a Pinpoint-style data source panel): used as an accent area, idle as a silk line, max as a dashed warn line, pending requests as `--err` bars on a right axis; the panel title carries the pool name. Units on the axes (`MiB`, `ms`, `%`, connections).

### Metrics `#/metrics`

Explorer: left a searchable catalog list (name, type chip, unit, series count); right the chart of the selected metric for the window, one line per series, a legend of the series' attributes, and a `rate` toggle for sums. A histogram shows mean and p95 with count as bars beneath.

### Dialogs

- **How to send data**: three tabs, `-javaagent`, environment variables, `curl`; each a copyable block with the actual endpoint from `/api/status`.
- **Clear data**: confirm, then `DELETE /api/data`.

## Behaviour and quality

- Every list re-renders in place without losing scroll or selection when Live refreshes it.
- Numbers: durations with 1 decimal under 100 ms, 0 decimals above, `s` above 10 s; counts with thousands separators; rates with 2 decimals; percentages with 1 decimal. A count axis only ever shows whole numbers (uPlot `incrs` of 1, 2, 5, 10, ...), so a series that stays at 0 or 1 does not print the same tick three times.
- Times: `HH:mm:ss` within today, `MMM d HH:mm:ss` otherwise; relative ("12 s ago") in feeds, absolute in tables, both in tooltips.
- Keyboard: `/` focuses the query bar, `Esc` closes a drawer or clears a scatter selection, `L` toggles Live, `[`/`]` step the time range, `M` opens the Mark dialog.
- The mock (`?mock=1`, `assets/js/dev/mock.js`) answers every endpoint in api.md including `/api/map`, `/api/scatter`, `/api/findings`, `/api/marks` (`GET` and `POST`), `/api/compare`, the histograms and the connection pools, so every page can be developed without a server.
- The page works at 360 px wide: tables scroll horizontally inside their panel, charts shrink, the drawer becomes a full-screen sheet.
- Accessibility: every icon-only control has an `aria-label`; colour is never the only carrier of meaning (errors also get a bolt, slow also gets a ring or a turtle).
- No external requests at all: fonts are system fonts, uPlot is vendored, the logo is inline.
- The whole UI is under 300 KB uncompressed excluding uPlot.

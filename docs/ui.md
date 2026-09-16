# Spider Sense: UI specification

The UI lives in `spider-sense-server/src/main/resources/public` and is served by Spider Silk's static files.
No build step: plain HTML, one CSS file, ES modules, and [uPlot](https://github.com/leeoniya/uPlot) (MIT, vendored under `public/assets/vendor/`) for time-series charts and the XLog scatter.
All data comes from [api.md](api.md); the page never renders server-side.

## Identity

**Name.** "Spider Sense" is the sibling of "Spider Silk": Silk is the web a spider builds, Sense is what the spider feels through it, the tingle when something on the web moves.
The UI leans on that image without being cute about it: the product tells you when something is slow or broken before you go looking.

**Logo** (`notes/logo.svg`, also served as `/assets/logo.svg` and as the favicon).
The Spider Silk logo unchanged as the base: eight radials and four sagging spiral rings in `#8a93a6`, the spider at the hub in `#5a3a22`, 200×200 viewBox.
Added on top: three concentric arcs of "sense" above the spider (centred on its head, spanning roughly 10 o'clock to 2 o'clock, radii 18/28/38 in the logo's units), stroke `#e2603f`, widths 3/2.4/1.8, opacities 0.95/0.7/0.45, round caps.
That is the whole difference: same web, same spider, and the tingle.
A monochrome variant (`logo-mono.svg`, strands and arcs in `currentColor`) for the sidebar at small sizes.

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

**Tone of the copy.** Short and factual, no exclamation marks. Empty states explain how to send data (the OTLP endpoint from `/api/status` in a copyable snippet) rather than say "No data".

## Layout

```
┌──────────────┬──────────────────────────────────────────────────────────────┐
│ ◉ Spider     │  ‹page title›              [Service ▾] [Last 15 min ▾] [● Live] │
│   Sense      ├──────────────────────────────────────────────────────────────┤
│              │                                                              │
│ Overview     │                                                              │
│ Services     │                        page content                          │
│ XLog         │                                                              │
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
- Top bar: page title on the left; on the right the **service filter** (all services or one; applies to every page that takes `service`), the **time range** (`5m`, `15m`, `1h`, `6h`, `all`; `all` means `from = /api/status.oldest.span`), and the **Live** toggle.
- **Live** on: the page re-fetches every 5 s and the window's `to` moves with the clock; the SSE `stats` event drives a small "spans/s" readout beside the toggle, and every `tingle` event increments the badge on the sidebar's "tingles" entry and prepends to the Overview feed without a refetch.
- The sidebar foot shows the mode, the port, the version, and a "how to send data" link that opens the snippet dialog.
- The **tingle badge**: the accent-coloured dot on the logo's arcs pulses once (CSS animation, 600 ms) when a `tingle` SSE event arrives. Once, not continuously: the tingle is a signal, not a decoration.

Routing is hash-based (`#/`, `#/services/spring-orders`, `#/traces/<id>`, ...). The service filter and time range live in the hash query (`#/traces?service=x&range=1h`) so a URL can be shared.

## Pages

### Overview `#/`

1. **Stat tiles** in one row: requests, error rate, p50 / p95 / p99, rps. Each has the number large, the unit small, and a 1-word caption. Colour only where it carries meaning: error rate > 1% is `--err`, p95 above the slow threshold is `--warn`.
2. **Throughput & latency** chart (uPlot, full width, 220 px): bars for requests per bucket (silk grey), errors stacked in `--err`, and p95 as a line on a second y-axis in accent.
3. **Services**: one card per service: name, language chip, `embedded` chip when applicable, rps / p95 / error rate, a sparkline (inline SVG, 120×28), and a "JVM" link when `hasJvm`. Click → service page.
4. **Tingles**: the feed, newest first: icon by kind (turtle for slow request, database for slow query, bolt for error), service chip, title, detail, relative time. Click → trace. Live events prepend with a 300 ms slide.

Empty state (no service yet): the logo large, one sentence, and the snippet dialog's content inline: the `-javaagent` line, the `OTEL_EXPORTER_OTLP_ENDPOINT`/`PROTOCOL` pair, and a `curl` for OTLP/JSON.

### Services `#/services` and `#/services/{name}`

List: a table (name, language, requests, rps, error rate, p50, p95, p99, sparkline, last seen). Row click → detail.

Detail:

1. Header: name, resource chips (`telemetry.sdk.language`, `process.runtime.name` + version, `host.name`, `process.pid`), "JVM" button when applicable, "Traces" button (→ Traces filtered).
2. Three charts in a row (RED): requests/errors, latency p50/p95/p99, error rate %.
3. **Endpoints** table: method chip, route, calls, rps, avg, p50, p95, p99, max, errors, a status-code mini-bar (2xx green / 4xx warn / 5xx err). Sortable by clicking a header; default total time. Row click → endpoint page.
4. Two half-width panels: **Top queries** (statement truncated to one line in monospace, calls, avg, p95, total) and **Top errors** (type, message, count, last seen). Both link to their pages.
5. **Dependencies**: kind icon, target, calls, errors, avg, p95.
6. **Resource attributes**: a collapsed key/value table.

### Endpoint `#/endpoints/{endpointId}`

Header (method, route, service), the RED charts, then tabs: **Slowest traces**, **Recent traces**, **Queries**, **Errors**. Trace rows as on the Traces page.

### XLog `#/xlog`

The Scouter view. A full-height uPlot scatter: x = time, y = response time (log scale toggle, default linear with the y-max at the window's p99 × 1.5 and a "▲ n above" note for clipped points), one dot per request, 3 px, service colour, error points drawn as `--err` crosses on top, slow points ringed in `--warn`.
Drag a rectangle → the selection becomes the filter of a trace list beneath (`/api/traces` with `from`, `to`, `minMs`, `maxMs` from the rectangle, plus the current service). Esc clears.
Live on: points stream in from the right every 2 s (refetch of the last 10 s merged in, deduplicated by traceId); the x-axis slides.
Hover: a tooltip with endpoint, service, duration, time; click → trace.
Above the chart: a legend of services (click to hide/show), counts (points, errors, slow), and the "truncated" notice when the API cut the list.

### Traces `#/traces` and `#/traces/{traceId}`

List page:

- **Query bar**: free text (`q`), min duration (`minMs`), status (`all`/`error`/`ok`), the endpoint (from the current service's endpoints, when a service is selected). Enter or a 400 ms debounce applies.
- **Table**: time, root name (bold) + service chip(s), duration with a proportional bar (width relative to the slowest in the list, colour by slow/error), spans, DB calls, status code, error/slow markers. Newest first, "Load more" pages with `before`.

Detail page:

1. Header: root name, trace id (monospace, click to copy), start time, total duration, services, span count, error count. Buttons: **Waterfall** / **Profile** view toggle, **Export** (`/api/export?traceId=`), **Logs** count anchor.
2. **Waterfall** (default): a tree of spans, indentation by depth, collapsible nodes, each row: service colour bar, name, category icon, the bar on a shared time axis (offset and duration proportional), duration label. Errors have a red bar and a bolt; slow DB spans a warn ring. Row click opens the **span drawer** on the right (420 px): name, service, kind, timing (start offset, duration, % of trace), status, then attributes as a key/value table (long values in `<pre>`, `db.statement` pretty-printed SQL keywords uppercased and line-broken on major clauses), events with their attributes and the stack trace in a scrollable `<pre>`, and the scope name.
3. **Profile** (Scouter-style): the same spans as a flat chronological table: `#`, start offset (`+12.3 ms`), gap since the previous step, elapsed, depth as indentation, one-line summary (`SELECT orders … (h2)`, `GET http://localhost:8081/api/books/{id} → 200`), service. Steps over the slow threshold are tinted `--warn`, errors `--err`. This is the view for reading what a request did, in order.
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

Requires a service (the top-bar filter; when "all", a picker of services with `hasJvm`). Runtime header (JVM, pid, host, cpu count), then a 2-column grid of uPlot charts: heap used/committed/limit, non-heap used/committed, memory pools (one line per pool), GC count and duration per bucket (bars), threads, CPU utilisation (%, 0–100) with system load, loaded classes. Units on the axes (`MiB`, `ms`, `%`).

### Metrics `#/metrics`

Explorer: left a searchable catalog list (name, type chip, unit, series count); right the chart of the selected metric for the window, one line per series, a legend of the series' attributes, and a `rate` toggle for sums. A histogram shows mean and p95 with count as bars beneath.

### Dialogs

- **How to send data**: three tabs, `-javaagent`, environment variables, `curl`; each a copyable block with the actual endpoint from `/api/status`.
- **Clear data**: confirm, then `DELETE /api/data`.

## Behaviour and quality

- Every list re-renders in place without losing scroll or selection when Live refreshes it.
- Numbers: durations with 1 decimal under 100 ms, 0 decimals above, `s` above 10 s; counts with thousands separators; rates with 2 decimals; percentages with 1 decimal.
- Times: `HH:mm:ss` within today, `MMM d HH:mm:ss` otherwise; relative ("12 s ago") in feeds, absolute in tables, both in tooltips.
- Keyboard: `/` focuses the query bar, `Esc` closes a drawer, `L` toggles Live, `[`/`]` step the time range.
- The page works at 360 px wide: tables scroll horizontally inside their panel, charts shrink, the drawer becomes a full-screen sheet.
- Accessibility: every icon-only control has an `aria-label`; colour is never the only carrier of meaning (errors also get a bolt, slow also gets a ring or a turtle).
- No external requests at all: fonts are system fonts, uPlot is vendored, the logo is inline.
- The whole UI is under 300 KB uncompressed excluding uPlot.

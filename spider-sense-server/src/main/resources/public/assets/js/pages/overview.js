// Overview: stat tiles, throughput and latency, service cards, the tingle feed.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, stat, chip, serviceChip, serviceColor, renderList, spinner, errorBox, emptyState, snippetBlocks, seedServices } from '../ui.js';
import { timeSeries, sparkline, legend } from '../charts.js';
import { histogramBars, apdexClass, fmtApdex } from '../buckets.js';
import { chartMode, loadToggle, throughputSpec, throughputLegend } from '../loadchart.js';
import { severityDot, kindChip, goToFinding } from './findings.js';
import { dur, count, rate, pct, rel, bothTimes } from '../format.js';

const KIND_ICON = { 'slow-request': 'turtle', 'slow-query': 'database', error: 'bolt' };
const KIND_LABEL = { 'slow-request': 'Slow request', 'slow-query': 'Slow query', error: 'Error' };

/** The seven tiles of pages.adoc#overview item 1; the Service page shows the same row. */
export function statTiles(totals, thresholds) {
  const t = totals || {};
  const slow = (thresholds && thresholds.slowRequestMs) || 500;
  return [
    stat(count(t.requests), 'total', 'requests'),
    stat(fmtApdex(t.apdex), '', 'apdex', { class: apdexClass(t.apdex), title: 'Apdex, T = ' + dur(slow) }),
    stat(pct(t.errorRate || 0), '', 'error rate', { class: t.errorRate > 0.01 ? 'is-bad' : '' }),
    stat(dur(t.p50Ms), '', 'p50'),
    stat(dur(t.p95Ms), '', 'p95', { class: t.p95Ms > slow ? 'is-warn' : '' }),
    stat(dur(t.p99Ms), '', 'p99'),
    stat(rate(t.rps || 0), '/s', 'requests per second'),
  ];
}

export function render(root, ctx) {
  let destroyed = false;
  const latest = api.requestSequence();
  let chart = null;
  let tingles = [];

  let mode = chartMode(ctx.query, 'requests');
  let lastSeries = {};
  let findings = [];

  const statsRow = h('div.stat-row', h('div.stat', h('div.stat-caption', 'Loading')));
  const chartBody = h('div.chart');
  const chartLegend = h('div');
  const modeBox = h('div.row', { style: { gap: '2px' } });
  const chartPanel = panel({ title: 'Throughput and latency', actions: modeBox }, chartLegend, chartBody);
  const summaryBody = h('div');
  const summaryPanel = panel({ title: 'Response summary' }, summaryBody);
  const chartRow = h('div.grid-2-1', chartPanel, summaryPanel);

  function paintModeToggle() {
    fill(modeBox, loadToggle(mode, (next) => {
      mode = next;
      router.setQuery({ chart: next === 'requests' ? '' : next });
      paintModeToggle();
      paintChart(lastSeries);
    }));
  }
  paintModeToggle();
  const findingsBody = h('div.findings');
  const findingsPanel = panel({
    title: 'Findings',
    actions: h('a.link-btn', { href: router.href('/findings', api.sharedQuery()) }, 'All findings'),
  }, findingsBody);
  const servicesBody = h('div.service-cards');
  const servicesPanel = panel({
    title: 'Services',
    actions: h('a.link-btn', { href: router.href('/services', api.sharedQuery()) }, 'All services'),
  }, servicesBody);
  const tingleBody = h('div.tingles');
  const tinglePanel = panel({ title: 'Tingles' }, tingleBody);

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } });
  root.appendChild(page);
  fill(page, spinner());

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, statsRow, findingsPanel, chartRow, servicesPanel, tinglePanel);
  }

  function paintStats(totals, thresholds) {
    fill(statsRow, statTiles(totals, thresholds));
  }

  function paintChart(series) {
    lastSeries = series;
    const spec = throughputSpec(series, mode, { height: 220, p95: true });
    fill(chartLegend, legend(throughputLegend(mode, { p95: true })));
    if (chart) chart.update(spec);
    else chart = timeSeries(chartBody, spec);
  }

  /** The top five findings, each a row that goes where the finding points (pages.adoc#overview). */
  function paintFindings(list) {
    renderList(findingsBody, list, {
      key: (f) => f.id,
      create: (f) => findingRow(f),
      update: (node, f) => node.replaceChildren(...findingRow(f).childNodes),
    });
    if (!list.length) {
      fill(findingsBody, h('div', { style: { padding: '18px', textAlign: 'center' } },
        h('span.muted', 'Nothing worth fixing in this window.')));
    }
  }

  function findingRow(f) {
    const frame = (f.code || [])[0];
    const open = () => goToFinding(f);
    return h('div.finding', {
      'data-severity': f.severity || 'low',
      tabindex: 0,
      role: 'link',
      onclick: open,
      onkeydown: (e) => { if (e.key === 'Enter') open(); },
    },
      severityDot(f.severity),
      h('div.f-main',
        h('div.f-head', kindChip(f.kind), serviceChip(f.service), h('b.f-title', f.title)),
        f.why ? h('div.f-why.muted', f.why) : null,
        frame ? h('div.mono.f-frame', frame) : null));
  }

  function paintServices(list) {
    seedServices(list.map((s) => s.name));
    renderList(servicesBody, list, {
      key: (s) => s.name,
      create: (s) => card(s),
      update: (node, s) => node.replaceChildren(...card(s).childNodes),
    });
    if (!list.length) fill(servicesBody, h('div.service-card', h('span.muted', 'No service in this window.')));
  }

  function card(s) {
    const color = serviceColor(s.name);
    const node = h('div.service-card', {
      style: { borderLeftColor: color },
      tabindex: 0,
      role: 'link',
      onclick: () => router.go('/services/' + encodeURIComponent(s.name), api.sharedQuery()),
      onkeydown: (e) => { if (e.key === 'Enter') router.go('/services/' + encodeURIComponent(s.name), api.sharedQuery()); },
    },
      h('div.sc-head',
        h('span.dot', { style: { background: color, width: '8px', height: '8px', borderRadius: '50%', flex: 'none' } }),
        h('span.sc-name', s.name),
        s.language ? chip(s.language) : null,
        s.embedded ? chip('embedded', { class: 'chip-accent' }) : null),
      h('div.sc-stats',
        h('div', h('b', rate(s.rps || 0)), 'rps'),
        h('div', h('b', dur(s.p95Ms)), 'p95'),
        h('div', h('b', { class: s.errorRate > 0.01 ? 'bad' : '' }, pct(s.errorRate || 0)), 'errors'),
        h('div', h('b', { class: apdexClass(s.apdex) === 'is-bad' ? 'bad' : apdexClass(s.apdex) === 'is-warn' ? 'warned' : '' }, fmtApdex(s.apdex)), 'apdex')),
      h('div.sc-foot',
        sparkline(s.sparkline || [], { color, label: s.name + ' requests per bucket' }),
        s.hasJvm ? h('a.link-btn', {
          href: router.href('/jvm', { ...api.sharedQuery(), service: s.name }),
          onclick: (e) => e.stopPropagation(),
        }, 'JVM') : null));
    return node;
  }

  function paintTingles() {
    renderList(tingleBody, tingles.slice(0, 50), {
      key: (t) => (t.traceId || '') + ':' + t.at + ':' + t.kind,
      create: (t) => tingleRow(t),
      update: (node, t) => {
        const when = node.querySelector('.t-when');
        if (when) { when.textContent = rel(t.at); when.title = bothTimes(t.at); }
      },
      enter: (node, t) => { if (t.fresh) node.classList.add('fresh'); },
    });
    if (!tingles.length) fill(tingleBody, h('div', { style: { padding: '18px', textAlign: 'center' } }, h('span.muted', 'Nothing noteworthy in this window.')));
  }

  function tingleRow(t) {
    return h('div.tingle', {
      'data-kind': t.kind,
      tabindex: 0,
      role: 'link',
      title: KIND_LABEL[t.kind] || t.kind,
      onclick: () => t.traceId && router.go('/traces/' + t.traceId, api.sharedQuery()),
      onkeydown: (e) => { if (e.key === 'Enter' && t.traceId) router.go('/traces/' + t.traceId, api.sharedQuery()); },
    },
      h('span.t-icon', icon(KIND_ICON[t.kind] || 'bolt')),
      h('div.t-title', serviceChip(t.service), h('span', t.title)),
      h('span.t-when', { class: 't-when', title: bothTimes(t.at) }, rel(t.at)),
      h('div.t-detail', t.detail || ''));
  }

  async function load() {
    const current = latest();
    try {
      const [data, found] = await Promise.all([
        api.overview(),
        // hideAcked: the top five are the unacknowledged ones (pages.adoc#overview).
        api.findings({ limit: 5, hideAcked: true }).catch(() => ({ findings: [] })),
      ]);
      if (destroyed || !current()) return;
      findings = found.findings || [];
      const services = data.services || [];
      const requests = (data.totals || {}).requests || 0;
      if (!requests) {
        built = false;
        const s = api.state.status || {};
        const neverSeen = !services.length;
        fill(page, panel({}, emptyState(
          neverSeen
            ? 'Nothing has arrived yet. Attach Spider Sense to an application, or point any OTLP/HTTP sender at this collector.'
            : 'No request in this time range. Send some traffic, or widen the range in the top bar.',
          h('div', { style: { display: 'grid', gap: '10px', justifyItems: 'center', width: '100%' } },
            neverSeen ? h('img.empty-hero', { src: 'assets/logo.svg', alt: '', width: '96', height: '96' }) : null,
            snippetBlocks(s.endpoint || location.origin)))));
        return;
      }
      build();
      // a same-page hash change only calls refresh(), so `chart` is re-read here
      const wanted = chartMode(router.currentRoute().query, 'requests');
      if (wanted !== mode) { mode = wanted; paintModeToggle(); }
      paintStats(data.totals || {}, (api.state.status || {}).thresholds);
      paintFindings(findings);
      paintChart(data.series || {});
      fill(summaryBody, histogramBars((data.totals || {}).histogram));
      paintServices(services);
      tingles = data.tingles || [];
      paintTingles();
    } catch (e) {
      if (destroyed || !current()) return;
      built = false;
      fill(page, errorBox(e, load));
    }
  }

  load();

  return {
    refresh: load,
    onTingle: (t) => {
      if (!built) return;
      tingles = [{ ...t, fresh: true }, ...tingles].slice(0, 50);
      paintTingles();
    },
    destroy: () => { destroyed = true; if (chart) chart.destroy(); },
  };
}

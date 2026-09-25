// Overview: stat tiles, throughput and latency, service cards, the tingle feed.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, chip, serviceChip, serviceColor, renderList, emptyState, snippetBlocks, placeholder, seedServices } from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { chartBox, sparkline } from '../charts.js';
import { histogramBars, apdexCell, ERROR_RATE_BAD } from '../buckets.js';
import { chartModeSwitch, throughputSpec } from '../throughput.js';
import { statTiles, severityDot, kindChip, goToFinding } from '../widgets.js';
import { dur, rate, pct, rel, bothTimes } from '../format.js';

const TINGLE_ICON = { 'slow-request': 'turtle', 'slow-query': 'database', error: 'bolt' };
const TINGLE_LABEL = { 'slow-request': 'Slow request', 'slow-query': 'Slow query', error: 'Error' };

export function render(root, ctx) {
  let tingles = [];
  let lastSeries = {};
  let findings = [];

  const statsRow = h('div.stat-row', h('div.stat', h('div.stat-caption', 'Loading')));
  const modeSwitch = chartModeSwitch('requests', () => paintChart(lastSeries));
  const chart = chartBox({ title: 'Throughput and latency', actions: modeSwitch });
  const summaryBody = h('div');
  const summaryPanel = panel({ title: 'Response summary' }, summaryBody);
  const chartRow = h('div.grid-2-1', chart.node, summaryPanel);
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

  const layout = skeleton(root, () => [statsRow, findingsPanel, chartRow, servicesPanel, tinglePanel]);

  function paintStats(totals, thresholds) {
    fill(statsRow, statTiles(totals, thresholds));
  }

  function paintChart(series) {
    lastSeries = series;
    chart.show(throughputSpec(series, modeSwitch.value(), { height: 220, p95: true }));
  }

  /** The top five findings, each a row that goes where the finding points (pages.adoc#overview). */
  function paintFindings(list) {
    renderList(findingsBody, list, {
      key: (f) => f.id,
      create: (f) => findingRow(f),
      update: (node, f) => node.replaceChildren(...findingRow(f).childNodes),
    });
    if (!list.length) {
      fill(findingsBody, placeholder('Nothing worth fixing in this window.'));
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
      onclick: () => router.openDetail('services', s.name),
      // Only the card's own Enter: one on the JVM link inside it is the link's.
      onkeydown: (e) => {
        if (e.key === 'Enter' && e.target === e.currentTarget) router.openDetail('services', s.name);
      },
    },
      h('div.sc-head',
        h('span.dot.service-dot', { style: { background: color } }),
        h('span.sc-name', s.name),
        s.language ? chip(s.language) : null,
        s.embedded ? chip('embedded', { class: 'chip-accent' }) : null),
      h('div.sc-stats',
        h('div', h('b', rate(s.rps || 0)), 'rps'),
        h('div', h('b', dur(s.p95Ms)), 'p95'),
        h('div', h('b', { class: s.errorRate > ERROR_RATE_BAD ? 'bad' : '' }, pct(s.errorRate || 0)), 'errors'),
        h('div', apdexCell(s.apdex, 'b'), 'apdex')),
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
    if (!tingles.length) fill(tingleBody, placeholder('Nothing noteworthy in this window.'));
  }

  function tingleRow(t) {
    return h('div.tingle', {
      'data-kind': t.kind,
      tabindex: 0,
      role: 'link',
      title: TINGLE_LABEL[t.kind] || t.kind,
      onclick: () => t.traceId && router.openDetail('traces', t.traceId),
      onkeydown: (e) => { if (e.key === 'Enter' && t.traceId) router.openDetail('traces', t.traceId); },
    },
      h('span.t-icon', icon(TINGLE_ICON[t.kind] || 'bolt')),
      h('div.t-title', serviceChip(t.service), h('span', t.title)),
      h('span.t-when', { class: 't-when', title: bothTimes(t.at) }, rel(t.at)),
      h('div.t-detail', t.detail || ''));
  }

  function paint([data, found]) {
    findings = found.findings || [];
    const services = data.services || [];
    const requests = (data.totals || {}).requests || 0;
    if (!requests) {
      const neverSeen = !services.length;
      layout.replace(panel({}, emptyState(
        neverSeen
          ? 'Nothing has arrived yet. Attach Spider Sense to an application, or point any OTLP/HTTP sender at this collector.'
          : 'No request in this time range. Send some traffic, or widen the range in the top bar.',
        h('div', { style: { display: 'grid', gap: '10px', justifyItems: 'center', width: '100%' } },
          neverSeen ? h('img.empty-hero', { src: 'assets/logo.svg', alt: '', width: '96', height: '96' }) : null,
          snippetBlocks(api.collectorBase())))));
      return;
    }
    layout.build();
    modeSwitch.sync();
    paintStats(data.totals || {}, (api.state.status || {}).thresholds);
    paintFindings(findings);
    paintChart(data.series || {});
    fill(summaryBody, histogramBars((data.totals || {}).histogram));
    paintServices(services);
    tingles = data.tingles || [];
    paintTingles();
  }

  const loader = pageLoader({
    fetch: () => Promise.all([
      api.overview(),
      // hideAcked: the top five are the unacknowledged ones (pages.adoc#overview).
      api.findings({ limit: 5, hideAcked: true }).catch(() => ({ findings: [] })),
    ]),
    paint,
    body: layout,
  });

  loader.load();

  return {
    refresh: loader.load,
    onTingle: (t) => {
      if (!layout.built) return;
      tingles = [{ ...t, fresh: true }, ...tingles].slice(0, 50);
      paintTingles();
    },
    destroy: () => { loader.destroy(); chart.destroy(); },
  };
}

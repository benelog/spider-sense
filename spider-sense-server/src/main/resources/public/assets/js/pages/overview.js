// Overview: stat tiles, throughput and latency, service cards, the tingle feed.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, stat, chip, serviceChip, serviceColor, renderList, spinner, errorBox, emptyState, snippetBlocks, seedServices } from '../ui.js';
import { timeSeries, sparkline, legend } from '../charts.js';
import { dur, count, rate, pct, rel, bothTimes } from '../format.js';

const KIND_ICON = { 'slow-request': 'turtle', 'slow-query': 'database', error: 'bolt' };
const KIND_LABEL = { 'slow-request': 'Slow request', 'slow-query': 'Slow query', error: 'Error' };

export function render(root, ctx) {
  let destroyed = false;
  let chart = null;
  let tingles = [];

  const statsRow = h('div.stat-row', h('div.stat', h('div.stat-caption', 'Loading')));
  const chartBody = h('div.chart');
  const chartLegend = h('div');
  const chartPanel = panel({ title: 'Throughput and latency' }, chartLegend, chartBody);
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
    fill(page, statsRow, chartPanel, servicesPanel, tinglePanel);
  }

  function paintStats(totals, thresholds) {
    const slow = (thresholds && thresholds.slowRequestMs) || 500;
    fill(statsRow,
      stat(count(totals.requests), 'total', 'requests'),
      stat(pct(totals.errorRate || 0), '', 'error rate', { class: totals.errorRate > 0.01 ? 'is-bad' : '' }),
      stat(dur(totals.p50Ms), '', 'p50'),
      stat(dur(totals.p95Ms), '', 'p95', { class: totals.p95Ms > slow ? 'is-warn' : '' }),
      stat(dur(totals.p99Ms), '', 'p99'),
      stat(rate(totals.rps || 0), '/s', 'requests per second'));
  }

  function paintChart(series) {
    const spec = {
      height: 220,
      t: series.t || [],
      series: [
        { label: 'Requests', values: series.requests || [], color: 'silk', type: 'bar', scale: 'y' },
        { label: 'Errors', values: series.errors || [], color: 'err', type: 'bar', scale: 'y' },
        { label: 'p95', values: series.p95Ms || [], color: 'accent', type: 'line', scale: 'ms', width: 2 },
      ],
      axes: [
        { scale: 'y', side: 3, label: 'Requests' },
        { scale: 'ms', side: 1, label: 'p95 (ms)', color: 'accent' },
      ],
    };
    fill(chartLegend, legend([
      { label: 'Requests per bucket', color: 'silk' },
      { label: 'Errors', color: 'err' },
      { label: 'p95 response time, right axis', color: 'accent' },
    ]));
    if (chart) chart.update(spec);
    else chart = timeSeries(chartBody, spec);
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
        h('div', h('b', { class: s.errorRate > 0.01 ? 'bad' : '' }, pct(s.errorRate || 0)), 'errors')),
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
    try {
      const data = await api.overview();
      if (destroyed) return;
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
      paintStats(data.totals || {}, (api.state.status || {}).thresholds);
      paintChart(data.series || {});
      paintServices(services);
      tingles = data.tingles || [];
      paintTingles();
    } catch (e) {
      if (destroyed) return;
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

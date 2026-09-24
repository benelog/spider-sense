// Metrics explorer: a searchable catalog on the left, the selected metric on the right.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, renderList, debounce, spinner, errorBox, emptyState, seriesColor } from '../ui.js';
import { timeSeries, legend } from '../charts.js';
import { count } from '../format.js';

/**
 * Every series carries its own `t` (api.adoc#metrics), and two services export at different
 * offsets, so the chart's axis is the union of their timestamps.
 */
function alignedTimes(series) {
  const all = new Set();
  for (const s of series) for (const x of s.t || []) all.add(x);
  return Array.from(all).sort((a, b) => a - b);
}

/** One array of the series (`v`, `p95`, `count`) on the shared axis, `null` where it has no point. */
function alignTo(t, s, key) {
  const values = s[key] || [];
  const byTime = new Map();
  (s.t || []).forEach((x, i) => { byTime.set(x, values[i] == null ? null : values[i]); });
  return t.map((x) => (byTime.has(x) ? byTime.get(x) : null));
}

export function render(root, ctx) {
  let destroyed = false;
  const latest = api.requestSequence();
  const latestSeries = api.requestSequence();
  let catalog = [];
  let selected = ctx.query.metric || '';
  let rateOn = ctx.query.rate === '1';
  let filterText = ctx.query.find || '';
  let chart = null;
  let countChart = null;

  const search = h('input', { type: 'search', placeholder: 'Find a metric', value: filterText, 'aria-label': 'Find a metric' });
  const listBox = h('div.catalog', spinner());
  const catalogPanel = panel({ title: 'Catalog' },
    h('div.querybar', h('div.search', icon('search'), search)),
    listBox);

  const chartBody = h('div.chart');
  const chartLegend = h('div');
  const rateBtn = h('button.btn', {
    type: 'button', 'aria-pressed': String(rateOn),
    onclick: () => {
      rateOn = !rateOn;
      rateBtn.setAttribute('aria-pressed', String(rateOn));
      router.setQuery({ rate: rateOn ? '1' : '' });
      loadSeries();
    },
  }, 'Per second');
  rateBtn.hidden = true;
  const detailTitle = h('h2.panel-title', 'Metric');
  const detailPanel = h('section.panel',
    h('div.panel-head', detailTitle, h('div.panel-actions', rateBtn)),
    chartLegend, chartBody);
  const countBody = h('div.chart');
  const countPanel = panel({ title: 'Sample count' }, countBody);
  countPanel.hidden = true;

  const layout = h('div.metrics-layout', catalogPanel, h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, detailPanel, countPanel));
  root.appendChild(layout);

  const applySearch = debounce(() => {
    filterText = search.value.trim();
    router.setQuery({ find: filterText });
    paintCatalog();
  }, 250);
  search.addEventListener('input', applySearch);

  function filtered() {
    if (!filterText) return catalog;
    const needle = filterText.toLowerCase();
    return catalog.filter((m) => m.name.toLowerCase().includes(needle) || (m.description || '').toLowerCase().includes(needle));
  }

  function paintCatalog() {
    const list = filtered();
    renderList(listBox, list, {
      key: (m) => m.name,
      create: (m) => item(m),
      update: (node, m) => {
        node.setAttribute('aria-selected', String(m.name === selected));
        node.replaceChildren(...item(m).childNodes);
      },
    });
    if (!list.length) fill(listBox, h('div', { style: { padding: '18px', textAlign: 'center' } }, h('span.muted', 'No metric matches.')));
  }

  function item(m) {
    return h('div.catalog-item', {
      role: 'option',
      tabindex: 0,
      'aria-selected': String(m.name === selected),
      onclick: () => select(m.name),
      onkeydown: (e) => { if (e.key === 'Enter') select(m.name); },
    },
      h('span.catalog-name', m.name),
      h('div.catalog-meta',
        h('span', m.type),
        m.unit ? h('span', m.unit) : null,
        h('span', count(m.series) + ' series')));
  }

  function select(name) {
    selected = name;
    router.setQuery({ metric: name });
    paintCatalog();
    loadSeries();
  }

  async function loadSeries() {
    const current = latestSeries();
    if (!selected) {
      detailTitle.textContent = 'Metric';
      rateBtn.hidden = true;
      countPanel.hidden = true;
      fill(chartBody, emptyState('Pick a metric on the left.'));
      fill(chartLegend);
      return;
    }
    try {
      const meta = catalog.find((m) => m.name === selected) || {};
      const data = await api.metricSeries({ name: selected, rate: rateOn ? 'true' : '' });
      if (destroyed || !current()) return;
      const series = data.series || [];
      detailTitle.textContent = selected + (data.unit ? ' (' + data.unit + ')' : '');
      rateBtn.hidden = data.type !== 'sum';
      if (!series.length) {
        fill(chartBody, emptyState('No point for this metric in the window.'));
        fill(chartLegend);
        countPanel.hidden = true;
        return;
      }
      const labels = series.map(labelOf);
      const isHistogram = data.type === 'histogram';
      const t = alignedTimes(series);
      const at = (s, key) => alignTo(t, s, key);
      const spec = {
        height: 260,
        t,
        series: series.flatMap((s, i) => {
          const base = [{ label: labels[i], values: at(s, 'v'), color: seriesColor(i), type: 'line', width: 1.8 }];
          if (isHistogram && s.p95) base.push({ label: labels[i] + ' p95', values: at(s, 'p95'), color: seriesColor(i), type: 'line', width: 1.2, dash: [4, 3] });
          return base;
        }),
        axes: [{ scale: 'y', label: data.unit || '' }],
      };
      fill(chartLegend, legend(series.map((s, i) => ({ label: labels[i], color: seriesColor(i) }))
        .concat(isHistogram ? [{ label: 'dashed: p95', color: 'silk' }] : [])));
      if (chart) chart.update(spec); else chart = timeSeries(chartBody, spec);

      if (isHistogram && series.some((s) => s.count)) {
        countPanel.hidden = false;
        const countSpec = {
          height: 130,
          t,
          series: series.map((s, i) => ({ label: labels[i], values: at(s, 'count'), color: seriesColor(i), type: 'bar' })),
          axes: [{ scale: 'y', label: 'Count' }],
        };
        if (countChart) countChart.update(countSpec); else countChart = timeSeries(countBody, countSpec);
      } else {
        countPanel.hidden = true;
      }
    } catch (e) {
      if (!destroyed && current()) fill(chartBody, errorBox(e, loadSeries));
    }
  }

  function labelOf(s) {
    const attrs = Object.entries(s.attributes || {});
    const text = attrs.length ? attrs.map(([k, v]) => k.replace(/^jvm\./, '') + '=' + v).join(' ') : (s.service || 'value');
    return api.state.service ? text : (s.service ? s.service + ' · ' + text : text);
  }

  async function load() {
    const current = latest();
    try {
      const res = await api.metricCatalog({});
      if (destroyed || !current()) return;
      catalog = (res.metrics || []).slice().sort((a, b) => a.name.localeCompare(b.name));
      if (!catalog.length) {
        fill(listBox, h('div', { style: { padding: '18px', textAlign: 'center' } }, h('span.muted', 'No metric has arrived yet.')));
        fill(chartBody, emptyState('No metric has arrived yet. The OpenTelemetry agent exports runtime metrics every 5 seconds by default.'));
        return;
      }
      if (!selected || !catalog.some((m) => m.name === selected)) selected = catalog[0].name;
      paintCatalog();
      await loadSeries();
    } catch (e) {
      if (!destroyed && current()) fill(listBox, errorBox(e, load));
    }
  }

  load();
  return {
    refresh: () => loadSeries(),
    destroy: () => {
      destroyed = true;
      applySearch.cancel();
      if (chart) chart.destroy();
      if (countChart) countChart.destroy();
    },
  };
}

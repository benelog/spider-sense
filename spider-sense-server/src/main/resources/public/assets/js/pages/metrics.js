// Metrics explorer: a searchable catalog on the left, the selected metric on the right.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, icon, panel, renderList, debounce, spinner, emptyState, placeholder, seriesColor } from '../ui.js';
import { pageLoader } from '../page.js';
import { chartBox, alignedTimes, alignTo } from '../charts.js';
import { count } from '../format.js';

/**
 * A series' name in the legend: its attributes less the `jvm.` prefix, else its service,
 * and led by its service when the top bar shows every service (`service` empty).
 */
export function labelOf(s, service = api.state.service) {
  const attrs = Object.entries(s.attributes || {});
  const text = attrs.length ? attrs.map(([k, v]) => k.replace(/^jvm\./, '') + '=' + v).join(' ') : (s.service || 'value');
  return service ? text : (s.service ? s.service + ' · ' + text : text);
}

export function render(root, ctx) {
  let catalog = [];
  let selected = ctx.query.metric || '';
  let rateOn = ctx.query.rate === '1';
  let filterText = ctx.query.find || '';

  const search = h('input', { type: 'search', placeholder: 'Find a metric', value: filterText, 'aria-label': 'Find a metric' });
  const listBox = h('div.catalog', spinner());
  const catalogPanel = panel({ title: 'Catalog' },
    h('div.querybar', h('div.search', icon('search'), search)),
    listBox);

  const rateBtn = h('button.btn', {
    type: 'button', 'aria-pressed': String(rateOn),
    onclick: () => {
      rateOn = !rateOn;
      rateBtn.setAttribute('aria-pressed', String(rateOn));
      router.setQuery({ rate: rateOn ? '1' : '' });
      seriesLoader.load();
    },
  }, 'Per second');
  rateBtn.hidden = true;
  const chart = chartBox({ title: 'Metric', actions: rateBtn });
  const countChart = chartBox({ title: 'Sample count', legend: false });
  countChart.node.hidden = true;

  const layout = h('div.metrics-layout', catalogPanel, h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, chart.node, countChart.node));
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
    if (!list.length) fill(listBox, placeholder('No metric matches.'));
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
    seriesLoader.load();
  }

  /** The selected metric's series; null when none is selected. */
  async function fetchSeries() {
    if (!selected) return null;
    return { name: selected, data: await api.metricSeries({ name: selected, rate: rateOn ? 'true' : '' }) };
  }

  function paintSeries(res) {
    if (!res) {
      chart.setTitle('Metric');
      rateBtn.hidden = true;
      countChart.node.hidden = true;
      chart.empty(emptyState('Pick a metric on the left.'));
      return;
    }
    const { name, data } = res;
    const series = data.series || [];
    chart.setTitle(name + (data.unit ? ' (' + data.unit + ')' : ''));
    rateBtn.hidden = data.type !== 'sum';
    if (!series.length) {
      chart.empty(emptyState('No point for this metric in the window.'));
      countChart.node.hidden = true;
      return;
    }
    const labels = series.map((s) => labelOf(s));
    const isHistogram = data.type === 'histogram';
    const t = alignedTimes(series);
    const at = (s, key) => alignTo(t, s, key);
    const spec = {
      height: 260,
      t,
      series: series.flatMap((s, i) => {
        const base = [{ label: labels[i], values: at(s, 'v'), color: seriesColor(i), type: 'line', width: 1.8 }];
        if (isHistogram && s.p95) base.push({ label: labels[i] + ' p95', legend: false, values: at(s, 'p95'), color: seriesColor(i), type: 'line', width: 1.2, dash: [4, 3] });
        return base;
      }),
      axes: [{ scale: 'y', label: data.unit || '' }],
      legendExtra: isHistogram ? [{ label: 'dashed: p95', color: 'silk' }] : [],
    };
    chart.show(spec);

    if (isHistogram && series.some((s) => s.count)) {
      countChart.node.hidden = false;
      const countSpec = {
        height: 130,
        t,
        series: series.map((s, i) => ({ label: labels[i], values: at(s, 'count'), color: seriesColor(i), type: 'bar' })),
        axes: [{ scale: 'y', label: 'Count' }],
      };
      countChart.show(countSpec);
    } else {
      countChart.node.hidden = true;
    }
  }

  const seriesLoader = pageLoader({ fetch: fetchSeries, paint: paintSeries, body: chart.body });

  const catalogLoader = pageLoader({
    fetch: () => api.metricCatalog({}),
    paint: (res) => {
      catalog = (res.metrics || []).slice().sort((a, b) => a.name.localeCompare(b.name));
      if (!catalog.length) {
        fill(listBox, placeholder('No metric has arrived yet.'));
        chart.empty(emptyState('No metric has arrived yet. The OpenTelemetry agent exports runtime metrics every 5 seconds by default.'));
        chart.setTitle('Metric');
        rateBtn.hidden = true;
        countChart.node.hidden = true;
        return;
      }
      if (!selected || !catalog.some((m) => m.name === selected)) selected = catalog[0].name;
      paintCatalog();
      seriesLoader.load();
    },
    body: listBox,
  });

  catalogLoader.load();
  return {
    // The catalog is the top bar's service's, and grows while metrics arrive: a refresh, which
    // a service change is too, reloads it before the selected metric's series.
    refresh: () => catalogLoader.load(),
    destroy: () => {
      catalogLoader.destroy();
      seriesLoader.destroy();
      applySearch.cancel();
      chart.destroy();
      countChart.destroy();
    },
  };
}

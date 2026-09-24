// JVM: the curated runtime view for one service.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, chip, spinner, errorBox, emptyState, seriesColor } from '../ui.js';
import { timeSeries, legend, alignedTimes, alignTo } from '../charts.js';
import { count } from '../format.js';

const MIB = 1024 * 1024;
const toMib = (arr) => (arr || []).map((v) => (v == null ? null : v / MIB));

export function render(root, ctx) {
  let destroyed = false;
  const latest = api.requestSequence();
  const charts = new Map();

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const grid = h('div.grid-2');
  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);

  function reset() {
    for (const box of charts.values()) if (box.chart) box.chart.destroy();
    charts.clear();
    fill(grid);
    built = false;
  }

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, headPanel, grid);
  }

  function chartPanel(id, title, spec, legendItems) {
    let box = charts.get(id);
    if (!box) {
      const bodyNode = h('div.chart');
      const legendNode = h('div');
      const node = panel({ title }, legendNode, bodyNode);
      grid.appendChild(node);
      box = { node, bodyNode, legendNode, chart: null };
      charts.set(id, box);
    }
    fill(box.legendNode, legend(legendItems));
    if (box.chart) box.chart.update(spec);
    else box.chart = timeSeries(box.bodyNode, spec);
  }

  async function pickService(current) {
    const res = await api.services();
    if (destroyed || !current()) return;
    const withJvm = (res.services || []).filter((s) => s.hasJvm);
    fill(page, panel({ title: 'JVM' }, emptyState(
      withJvm.length
        ? 'Pick a service. JVM metrics arrive when the OpenTelemetry agent reports runtime telemetry.'
        : 'No service has sent a jvm.* metric yet.',
      h('div.row', { style: { justifyContent: 'center' } },
        withJvm.map((s) => h('a.btn', { href: router.href('/jvm', { ...api.sharedQuery(), service: s.name }) }, s.name))))));
  }

  async function load() {
    const current = latest();
    if (!api.state.service) {
      reset();
      await pickService(current).catch((e) => { if (!destroyed && current()) fill(page, errorBox(e, load)); });
      return;
    }
    try {
      const data = await api.jvm({});
      if (destroyed || !current()) return;
      const heap = data.heap || {};
      if (!(heap.t || []).length && !(data.threads && (data.threads.t || []).length)) {
        reset();
        fill(page, panel({ title: 'JVM' }, emptyState(
          api.state.service + ' has sent no jvm.* metric in this window. Runtime telemetry is on by default in the OpenTelemetry Java agent.')));
        return;
      }
      build();
      const r = data.runtime || {};
      fill(head,
        h('div.row', { style: { gap: '8px' } },
          h('b', { style: { fontSize: '15px' } }, api.state.service),
          r.jvm ? chip(r.jvm) : null,
          r.pid ? chip('pid ' + r.pid) : null,
          r.host ? chip(r.host) : null,
          r.cpuCount ? chip(count(r.cpuCount) + ' cpu') : null));

      chartPanel('heap', 'Heap memory', {
        height: 170, t: heap.t || [],
        series: [
          { label: 'used', values: toMib(heap.used), color: 'accent', type: 'area', width: 2 },
          { label: 'committed', values: toMib(heap.committed), color: 'silk', type: 'line', width: 1.6 },
          { label: 'limit', values: toMib(heap.limit), color: 'warn', type: 'line', width: 1.4, dash: [4, 3] },
        ],
        axes: [{ scale: 'y', label: 'MiB' }],
      }, [{ label: 'used', color: 'accent' }, { label: 'committed', color: 'silk' }, { label: 'limit', color: 'warn' }]);

      const nonHeap = data.nonHeap || {};
      chartPanel('nonheap', 'Non-heap memory', {
        height: 170, t: nonHeap.t || [],
        series: [
          { label: 'used', values: toMib(nonHeap.used), color: 'series5', type: 'area', width: 2 },
          { label: 'committed', values: toMib(nonHeap.committed), color: 'silk', type: 'line', width: 1.6 },
        ],
        axes: [{ scale: 'y', label: 'MiB' }],
      }, [{ label: 'used', color: 'series5' }, { label: 'committed', color: 'silk' }]);

      const pools = data.pools || [];
      // Each pool has its own timestamps: one that exists only before a restart must not be
      // drawn over another pool's instants.
      const poolTimes = alignedTimes(pools);
      chartPanel('pools', 'Memory pools', {
        height: 170, t: poolTimes,
        series: pools.map((p, i) => ({ label: p.name, values: toMib(alignTo(poolTimes, p, 'used')), color: seriesColor(i), type: 'line', width: 1.6 })),
        axes: [{ scale: 'y', label: 'MiB' }],
      }, pools.map((p, i) => ({ label: p.name, color: seriesColor(i) })));

      const gc = data.gc || [];
      chartPanel('gc', 'Garbage collection', {
        height: 170, t: (gc[0] || {}).t || [],
        series: gc.flatMap((g, i) => [
          { label: g.name + ' count', values: g.count || [], color: seriesColor(i), type: 'bar', scale: 'y' },
          { label: g.name + ' time', values: g.durationMs || [], color: 'accent', type: 'line', scale: 'ms', width: 1.8 },
        ]),
        axes: [{ scale: 'y', label: 'Collections' }, { scale: 'ms', side: 1, label: 'ms', color: 'accent' }],
      }, gc.flatMap((g, i) => [{ label: g.name + ', count', color: seriesColor(i) }, { label: g.name + ', time (right axis)', color: 'accent' }]));

      const threads = data.threads || {};
      chartPanel('threads', 'Threads', {
        height: 170, t: threads.t || [],
        series: [
          { label: 'live', values: threads.count || [], color: 'series7', type: 'line', width: 2 },
          { label: 'daemon', values: threads.daemon || [], color: 'silk', type: 'line', width: 1.6 },
        ],
        axes: [{ scale: 'y', label: 'Threads' }],
      }, [{ label: 'live', color: 'series7' }, { label: 'daemon', color: 'silk' }]);

      const cpu = data.cpu || {};
      chartPanel('cpu', 'CPU', {
        height: 170, t: cpu.t || [],
        series: [
          { label: 'utilisation', values: (cpu.utilization || []).map((v) => (v == null ? null : v <= 1 ? v * 100 : v)), color: 'ok', type: 'area', scale: 'pct', width: 2 },
          { label: 'load 1m', values: cpu.systemLoad1m || [], color: 'warn', type: 'line', scale: 'load', width: 1.6 },
        ],
        axes: [{ scale: 'pct', label: '%' }, { scale: 'load', side: 1, label: 'load', color: 'warn' }],
      }, [{ label: 'process CPU utilisation', color: 'ok' }, { label: 'system load, 1 min, right axis', color: 'warn' }]);

      const classes = data.classes || {};
      chartPanel('classes', 'Loaded classes', {
        height: 170, t: classes.t || [],
        series: [{ label: 'loaded', values: classes.loaded || [], color: 'series8', type: 'line', width: 2 }],
        axes: [{ scale: 'y', label: 'Classes' }],
      }, [{ label: 'loaded classes', color: 'series8' }]);

      // One panel per JDBC pool; nothing is added when the service reports none.
      const pools2 = data.connectionPools || [];
      for (const [id, box] of charts) {
        if (!id.startsWith('pool:') || pools2.some((p) => 'pool:' + p.name === id)) continue;
        if (box.chart) box.chart.destroy();
        box.node.remove();
        charts.delete(id);
      }
      for (const pool of pools2) {
        chartPanel('pool:' + pool.name, 'Connection pool ' + pool.name, {
          height: 170, t: pool.t || [],
          series: [
            { label: 'used', values: pool.used || [], color: 'accent', type: 'area', width: 2 },
            { label: 'idle', values: pool.idle || [], color: 'silk', type: 'line', width: 1.6 },
            { label: 'max', values: pool.max || [], color: 'warn', type: 'line', width: 1.4, dash: [4, 3] },
            { label: 'pending', values: pool.pending || [], color: 'err', type: 'bar', scale: 'pending' },
          ],
          axes: [{ scale: 'y', label: 'connections' }, { scale: 'pending', side: 1, label: 'pending', color: 'err' }],
        }, [
          { label: 'used', color: 'accent' },
          { label: 'idle', color: 'silk' },
          { label: 'max', color: 'warn' },
          { label: 'pending requests, right axis', color: 'err' },
        ]);
      }
    } catch (e) {
      if (destroyed || !current()) return;
      reset();
      fill(page, errorBox(e, load));
    }
  }

  load();
  return {
    refresh: load,
    destroy: () => { destroyed = true; reset(); },
  };
}

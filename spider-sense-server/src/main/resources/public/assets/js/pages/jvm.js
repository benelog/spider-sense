// JVM: the curated runtime view for one service.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, chip, emptyState, seriesColor } from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { chartBox, alignedTimes, alignTo } from '../charts.js';
import { count } from '../format.js';

const MIB = 1024 * 1024;
const toMib = (arr) => (arr || []).map((v) => (v == null ? null : v / MIB));

export function render(root, ctx) {
  const charts = new Map();

  const head = h('div.trace-head');
  const headPanel = panel({}, head);
  const grid = h('div.grid-2');
  const layout = skeleton(root, () => [headPanel, grid]);

  /** Drops every chart; whatever replaces the panels next puts them back empty. */
  function reset() {
    for (const box of charts.values()) box.destroy();
    charts.clear();
    fill(grid);
  }

  /** The chart panel `id`, added to the grid the first time, drawn with `spec`. */
  function chartPanel(id, title, spec) {
    let box = charts.get(id);
    if (!box) {
      box = chartBox({ title });
      grid.appendChild(box.node);
      charts.set(id, box);
    }
    box.show(spec);
  }

  function pickService(res) {
    const withJvm = (res.services || []).filter((s) => s.hasJvm);
    layout.replace(panel({ title: 'JVM' }, emptyState(
      withJvm.length
        ? 'Pick a service. JVM metrics arrive when the OpenTelemetry agent reports runtime telemetry.'
        : 'No service has sent a jvm.* metric yet.',
      h('div.row', { style: { justifyContent: 'center' } },
        withJvm.map((s) => h('a.btn', { href: router.href('/jvm', { ...api.sharedQuery(), service: s.name }) }, s.name))))));
  }

  /** Without a service the page offers the ones that report a JVM; with one, it charts that one. */
  async function fetchPage() {
    if (!api.state.service) {
      reset();
      return { services: await api.services() };
    }
    return { jvm: await api.jvm({}) };
  }

  function paint(res) {
    if (res.services) { pickService(res.services); return; }
    const data = res.jvm;
    const heap = data.heap || {};
    if (!(heap.t || []).length && !(data.threads && (data.threads.t || []).length)) {
      reset();
      layout.replace(panel({ title: 'JVM' }, emptyState(
        api.state.service + ' has sent no jvm.* metric in this window. Runtime telemetry is on by default in the OpenTelemetry Java agent.')));
      return;
    }
    layout.build();
    const runtime = data.runtime || {};
    fill(head,
      h('div.row', { style: { gap: '8px' } },
        h('b', { style: { fontSize: '15px' } }, api.state.service),
        runtime.jvm ? chip(runtime.jvm) : null,
        runtime.pid ? chip('pid ' + runtime.pid) : null,
        runtime.host ? chip(runtime.host) : null,
        runtime.cpuCount ? chip(count(runtime.cpuCount) + ' cpu') : null));

    chartPanel('heap', 'Heap memory', {
      height: 170, t: heap.t || [],
      series: [
        { label: 'used', values: toMib(heap.used), color: 'accent', type: 'area', width: 2 },
        { label: 'committed', values: toMib(heap.committed), color: 'silk', type: 'line', width: 1.6 },
        { label: 'limit', values: toMib(heap.limit), color: 'warn', type: 'line', width: 1.4, dash: [4, 3] },
      ],
      axes: [{ scale: 'y', label: 'MiB' }],
    });

    const nonHeap = data.nonHeap || {};
    chartPanel('nonheap', 'Non-heap memory', {
      height: 170, t: nonHeap.t || [],
      series: [
        { label: 'used', values: toMib(nonHeap.used), color: 'series5', type: 'area', width: 2 },
        { label: 'committed', values: toMib(nonHeap.committed), color: 'silk', type: 'line', width: 1.6 },
      ],
      axes: [{ scale: 'y', label: 'MiB' }],
    });

    const memoryPools = data.pools || [];
    // Each pool has its own timestamps: one that exists only before a restart must not be
    // drawn over another pool's instants.
    const poolTimes = alignedTimes(memoryPools);
    chartPanel('pools', 'Memory pools', {
      height: 170, t: poolTimes,
      series: memoryPools.map((p, i) => ({ label: p.name, values: toMib(alignTo(poolTimes, p, 'used')), color: seriesColor(i), type: 'line', width: 1.6 })),
      axes: [{ scale: 'y', label: 'MiB' }],
    });

    const gc = data.gc || [];
    chartPanel('gc', 'Garbage collection', {
      height: 170, t: (gc[0] || {}).t || [],
      series: gc.flatMap((g, i) => [
        { label: g.name + ' count', values: g.count || [], color: seriesColor(i), type: 'bar', scale: 'y' },
        {
          label: g.name + ' time', legendLabel: g.name + ' time, right axis',
          values: g.durationMs || [], color: 'accent', type: 'line', scale: 'ms', width: 1.8,
        },
      ]),
      axes: [{ scale: 'y', label: 'Collections' }, { scale: 'ms', side: 1, label: 'ms', color: 'accent' }],
    });

    const threads = data.threads || {};
    chartPanel('threads', 'Threads', {
      height: 170, t: threads.t || [],
      series: [
        { label: 'live', values: threads.count || [], color: 'series7', type: 'line', width: 2 },
        { label: 'daemon', values: threads.daemon || [], color: 'silk', type: 'line', width: 1.6 },
      ],
      axes: [{ scale: 'y', label: 'Threads' }],
    });

    const cpu = data.cpu || {};
    chartPanel('cpu', 'CPU', {
      height: 170, t: cpu.t || [],
      series: [
        {
          label: 'utilisation', legendLabel: 'process CPU utilisation',
          values: (cpu.utilization || []).map((v) => (v == null ? null : v <= 1 ? v * 100 : v)), color: 'ok', type: 'area', scale: 'pct', width: 2,
        },
        {
          label: 'load 1m', legendLabel: 'system load, 1 min, right axis',
          values: cpu.systemLoad1m || [], color: 'warn', type: 'line', scale: 'load', width: 1.6,
        },
      ],
      axes: [{ scale: 'pct', label: '%' }, { scale: 'load', side: 1, label: 'load', color: 'warn' }],
    });

    const classes = data.classes || {};
    chartPanel('classes', 'Loaded classes', {
      height: 170, t: classes.t || [],
      series: [{ label: 'loaded', legendLabel: 'loaded classes', values: classes.loaded || [], color: 'series8', type: 'line', width: 2 }],
      axes: [{ scale: 'y', label: 'Classes' }],
    });

    // One panel per JDBC pool; nothing is added when the service reports none.
    const connectionPools = data.connectionPools || [];
    for (const [id, box] of charts) {
      if (!id.startsWith('pool:') || connectionPools.some((p) => 'pool:' + p.name === id)) continue;
      box.destroy();
      box.node.remove();
      charts.delete(id);
    }
    for (const pool of connectionPools) {
      chartPanel('pool:' + pool.name, 'Connection pool ' + pool.name, {
        height: 170, t: pool.t || [],
        series: [
          { label: 'used', values: pool.used || [], color: 'accent', type: 'area', width: 2 },
          { label: 'idle', values: pool.idle || [], color: 'silk', type: 'line', width: 1.6 },
          { label: 'max', values: pool.max || [], color: 'warn', type: 'line', width: 1.4, dash: [4, 3] },
          { label: 'pending', legendLabel: 'pending requests, right axis', values: pool.pending || [], color: 'err', type: 'bar', scale: 'pending' },
        ],
        axes: [{ scale: 'y', label: 'connections' }, { scale: 'pending', side: 1, label: 'pending', color: 'err' }],
      });
    }
  }

  const loader = pageLoader({ fetch: fetchPage, paint, body: layout, onError: reset });

  loader.load();
  return {
    refresh: loader.load,
    destroy: () => { loader.destroy(); reset(); },
  };
}

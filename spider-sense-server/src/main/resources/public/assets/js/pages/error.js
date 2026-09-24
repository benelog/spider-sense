// One error group: count over time, the sample stack trace, endpoints, recent traces.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, stat, table, serviceChip, idButton, spinner, errorBox } from '../ui.js';
import { timeSeries, legend } from '../charts.js';
import { codeFrame, foldedStack, framesMode, framesToggle } from '../frames.js';
import { copyButtons, cliLine } from '../copyas.js';
import { traceTable } from './traces.js';
import { count, rel, bothTimes, full, splitType } from '../format.js';

/** One section per cause, the root cause first, each folded as a stack trace (pages.adoc#error). */
function exceptionChain(chain, mode) {
  return chain.map((cause, i) => {
    const lines = [cause.type ? cause.type + (cause.message ? ': ' + cause.message : '') : cause.message]
      .concat(cause.frames.map((frame) => '\tat ' + frame))
      .concat(cause.more ? ['\t... ' + cause.more + ' more'] : []);
    return h('div', { style: { marginTop: i ? '12px' : '0' } },
      chain.length > 1 ? h('div.sub-head', i === 0 ? 'Root cause' : 'Wrapped by') : null,
      foldedStack(lines.filter((line) => line).join('\n'), mode));
  });
}

export function render(root, ctx) {
  const id = ctx.params.id;
  let destroyed = false;
  const latest = api.requestSequence();
  let chart = null;
  let mode = framesMode(ctx.query);
  let lastChain = null;
  let loaded = null;

  const head = h('div', { style: { padding: '14px', display: 'grid', gap: '8px' } });
  const headPanel = panel({}, head);
  const statsRow = h('div.stat-row');
  const chartBody = h('div.chart');
  const chartLegend = h('div');
  const chartPanel = panel({ title: 'Occurrences' }, chartLegend, chartBody);
  const stackBody = h('div', { style: { padding: '14px' } });
  const stackTraceBox = h('div');
  const modeBox = h('div.row', { style: { gap: '2px' } });
  const stackPanel = panel({ title: 'Sample stack trace', actions: modeBox }, stackBody);

  // App frames | All, remembered in the hash query (pages.adoc#error).
  // A Live refresh repaints only when the trace or the mode changed, so an expanded run stays open.
  let painted = null;
  function paintStack() {
    const key = mode + '|' + JSON.stringify(lastChain);
    if (key === painted) return;
    painted = key;
    fill(modeBox, framesToggle(mode, (next) => {
      mode = next;
      router.setQuery({ frames: next === 'all' ? 'all' : '' });
      paintStack();
    }));
    fill(stackTraceBox, lastChain && lastChain.length
      ? exceptionChain(lastChain, mode)
      : h('span.muted', 'This error carried no stack trace.'));
  }
  const endpointsBody = h('div');
  const tracesBody = h('div');
  const half = h('div.grid-2',
    panel({ title: 'Endpoints' }, endpointsBody),
    panel({ title: 'Recent traces' }, tracesBody));

  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);

  let built = false;
  function build() {
    if (built) return;
    built = true;
    fill(page, headPanel, statsRow, chartPanel, stackPanel, half);
  }

  async function load() {
    const current = latest();
    try {
      const win = api.windowFor();
      const data = await api.errorGroup(id, { window: win });
      if (destroyed || !current()) return;
      const e = data.error || {};
      loaded = { window: win, service: e.service };
      build();
      const { pkg, name } = splitType(e.type);
      ctx.setTitle(name || 'Error');
      fill(head,
        h('div.row', { style: { gap: '8px' } },
          h('span.mono', { style: { fontSize: '14px' } }, h('span.muted', pkg), h('b', name)),
          serviceChip(e.service)),
        h('div', { style: { color: 'var(--err)' } }, e.message || ''),
        e.sample ? h('div.row', { style: { gap: '12px' } },
          h('span.muted', { style: { fontSize: '11px' } }, 'sample'),
          idButton(e.sample.traceId, 'Copy trace id'),
          h('a.link-btn', { href: router.href('/traces/' + e.sample.traceId, api.sharedQuery()) }, 'Open trace'),
          h('span.muted', { style: { fontSize: '11px' }, title: bothTimes(e.sample.at) }, full(e.sample.at))) : null,
        copyButtons({
          markdown: () => ({ path: '/api/errors/' + encodeURIComponent(id), query: api.params({}, { window: loaded.window, service: null }) }),
          cli: () => cliLine('errors', loaded.window, loaded.service),
        }));

      fill(statsRow,
        stat(count(e.count), '', 'occurrences', { class: 'is-bad' }),
        stat(rel(e.firstSeen), '', 'first seen', { title: bothTimes(e.firstSeen) }),
        stat(rel(e.lastSeen), '', 'last seen', { title: bothTimes(e.lastSeen) }),
        stat(count((e.endpoints || []).length), '', 'endpoints'));

      const series = data.series || {};
      const spec = {
        height: 160,
        t: series.t || [],
        series: [{ label: 'Errors', values: series.count || [], color: 'err', type: 'bar' }],
        axes: [{ scale: 'y' }],
      };
      fill(chartLegend, legend([{ label: 'Occurrences per bucket', color: 'err' }]));
      if (chart) chart.update(spec); else chart = timeSeries(chartBody, spec);

      const code = data.code || [];
      lastChain = data.chain || [];
      fill(stackBody,
        code.length
          ? h('div.f-code', { style: { marginBottom: '12px' } }, h('div.sub-head', 'Code'),
            code.map((frame) => codeFrame(frame)))
          : null,
        stackTraceBox);
      paintStack();

      fill(endpointsBody, table([
        { key: 'name', label: 'Endpoint', sortable: false, cls: 'wide', render: (x) => h('span.cell-ellipsis', { title: x.name }, x.name) },
        { key: 'count', label: 'Count', align: 'right', sortable: false, width: '72px', render: (x) => count(x.count) },
      ], { rows: e.endpoints || [], rowKey: (x) => x.name, empty: 'No endpoint recorded.' }));

      fill(tracesBody, traceTable(data.traces || [], { empty: 'No trace in this window.' }));
    } catch (err) {
      if (destroyed || !current()) return;
      built = false;
      fill(page, errorBox(err, load));
    }
  }

  load();
  return { refresh: load, destroy: () => { destroyed = true; if (chart) chart.destroy(); } };
}

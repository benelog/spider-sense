// One error group: count over time, the sample stack trace, endpoints, recent traces.

import * as api from '../api.js';
import * as router from '../router.js';
import { h, fill, panel, stat, table, serviceChip, idButton, segmented } from '../ui.js';
import { pageLoader, skeleton } from '../page.js';
import { chartBox } from '../charts.js';
import { codeFrame, foldedStack, framesMode } from '../frames.js';
import { copyButtons, cliLine } from '../copyas.js';
import { countColumn } from '../columns.js';
import { traceTable } from '../widgets.js';
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
  let mode = framesMode(ctx.query());
  let lastChain = null;
  let loaded = null;

  const head = h('div', { style: { padding: '14px', display: 'grid', gap: '8px' } });
  const headPanel = panel({}, head);
  const statsRow = h('div.stat-row');
  const chart = chartBox({ title: 'Occurrences' });
  const stackBody = h('div', { style: { padding: '14px' } });
  const stackTraceBox = h('div');
  // App frames | All, remembered in the hash query (pages.adoc#error).
  const framesSwitch = segmented({
    label: 'Stack frames',
    options: [['app', 'App frames'], ['all', 'All']],
    value: mode,
    onChange: (next) => {
      mode = next;
      router.setQuery({ frames: next }, { defaults: { frames: 'app' } });
      paintStack();
    },
  });
  const stackPanel = panel({ title: 'Sample stack trace', actions: framesSwitch }, stackBody);

  // A Live refresh repaints only when the trace or the mode changed, so an expanded run stays open.
  let painted = null;
  function paintStack() {
    const key = mode + '|' + JSON.stringify(lastChain);
    if (key === painted) return;
    painted = key;
    fill(stackTraceBox, lastChain && lastChain.length
      ? exceptionChain(lastChain, mode)
      : h('span.muted', 'This error carried no stack trace.'));
  }
  // The two tables are built once, and a Live refresh gives them new rows, so a focused row
  // and a scrolled table survive it (ui.adoc#live-refresh).
  const endpointsTable = table([
    { key: 'name', label: 'Endpoint', cls: 'wide', render: (x) => h('span.cell-ellipsis', { title: x.name }, x.name) },
    countColumn('count', 'Count'),
  ], {
    rowKey: (x) => x.name,
    empty: 'No endpoint recorded.',
  });
  const tracesTable = traceTable([], { empty: 'No trace in this window.' });
  const endpointsBody = h('div', endpointsTable);
  const tracesBody = h('div', tracesTable);
  const half = h('div.grid-2',
    panel({ title: 'Endpoints' }, endpointsBody),
    panel({ title: 'Recent traces' }, tracesBody));

  const layout = skeleton(root, () => [headPanel, statsRow, chart.node, stackPanel, half]);

  function paint({ win, data }) {
    const e = data.error || {};
    loaded = { window: win, service: e.service };
    layout.build();
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
        h('a.link-btn', { href: router.detailHref('traces', e.sample.traceId) }, 'Open trace'),
        h('span.muted', { style: { fontSize: '11px' }, title: bothTimes(e.sample.at) }, full(e.sample.at))) : null,
      copyButtons({
        markdown: () => ({ path: '/api/errors/' + encodeURIComponent(id), query: api.params({}, { window: loaded.window, omitService: true }) }),
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
      series: [{ label: 'Errors', legendLabel: 'Occurrences per bucket', values: series.count || [], color: 'err', type: 'bar' }],
      axes: [{ scale: 'y' }],
    };
    chart.show(spec);

    const code = data.code || [];
    lastChain = data.chain || [];
    fill(stackBody,
      code.length
        ? h('div.f-code', { style: { marginBottom: '12px' } }, h('div.sub-head', 'Code'),
          code.map((frame) => codeFrame(frame)))
        : null,
      stackTraceBox);
    paintStack();

    endpointsTable.setRows(e.endpoints || []);
    tracesTable.setRows(data.traces || []);
  }

  const loader = pageLoader({
    fetch: async () => {
      const win = api.windowFor();
      return { win, data: await api.errorGroup(id, { window: win }) };
    },
    paint,
    body: layout,
  });

  loader.load();
  return { refresh: loader.load, destroy: () => { loader.destroy(); chart.destroy(); } };
}

// Column factories for ui.table(): the cells one API shape gets on every page that lists it
// (a query group, an error group, a time, a duration, a count, a service). The factories leave
// `sortable` out, so a column sorts in a table that sorts; pass { sortable: false } to stop it.

import { h, serviceChip } from './ui.js';
import { oneLineSql } from './sql.js';
import { dur, count, rel, bothTimes, truncate, splitType } from './format.js';

/** "12 s ago", both forms of the time in its title. */
export function timeAgo(ts) {
  return h('span', { title: bothTimes(ts) }, rel(ts));
}

/** A query group's statement on one line, cut at `max` characters, the whole of it in the title. */
export function statementColumn(max = 200, extra = {}) {
  return {
    key: 'statement', label: 'Statement', cls: 'wide',
    render: (q) => h('span.cell-ellipsis.mono', { title: q.statement }, oneLineSql(q.statement, max)),
    ...extra,
  };
}

/**
 * An error group's exception type: the package muted before the simple name, or with `short`
 * the simple name alone, for a narrow table. The full type is the title either way.
 */
export function errorTypeColumn({ short = false, ...extra } = {}) {
  return {
    key: 'type', label: 'Type', cls: extra.width ? null : 'wide',
    render: (e) => {
      const { pkg, name } = splitType(e.type);
      return short
        ? h('span.cell-ellipsis.mono', { title: e.type }, name || '-')
        : h('span.mono.cell-ellipsis', { title: e.type }, h('span.muted', pkg), name);
    },
    ...extra,
  };
}

/** An error group's message, cut at `max` characters, the whole of it in the title. */
export function messageColumn(max, extra = {}) {
  return {
    key: 'message', label: 'Message', cls: 'wide',
    render: (e) => h('span.cell-ellipsis', { title: e.message }, truncate(e.message, max)),
    ...extra,
  };
}

/** The service chip. */
export function serviceColumn(width = '150px', extra = {}) {
  return { key: 'service', label: 'Service', width, render: (x) => serviceChip(x.service), ...extra };
}

/** A time as "12 s ago", right-aligned: `lastSeen` unless another key is named. */
export function seenColumn(key = 'lastSeen', label = 'Last seen', width = '92px', extra = {}) {
  return { key, label, align: 'right', width, render: (x) => timeAgo(x[key]), ...extra };
}

/** A duration in ms, right-aligned. */
export function durationColumn(key, label, width = '74px', extra = {}) {
  return { key, label, align: 'right', width, render: (x) => dur(x[key]), ...extra };
}

/** A count, right-aligned. */
export function countColumn(key, label, width = '72px', extra = {}) {
  return { key, label, align: 'right', width, render: (x) => count(x[key]), ...extra };
}

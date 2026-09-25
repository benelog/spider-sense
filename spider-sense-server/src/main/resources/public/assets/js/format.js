// Number, duration and time formatting. Rules live in ui.adoc#numbers-and-times.

const NUM = new Intl.NumberFormat('en-US');
const NUM1 = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const NUM2 = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Counts with thousands separators. */
export function count(n) {
  if (n == null || Number.isNaN(n)) return '-';
  return NUM.format(Math.round(n));
}

/** Durations in ms: 1 decimal under 100 ms, 0 decimals above, seconds above 10 s. */
export function dur(ms) {
  if (ms == null || Number.isNaN(ms)) return '-';
  if (ms >= 10000) return NUM1.format(ms / 1000) + ' s';
  if (ms >= 100) return NUM.format(Math.round(ms)) + ' ms';
  return NUM1.format(ms) + ' ms';
}

/** Duration without the unit, for axis ticks and dense cells. */
export function durBare(ms) {
  if (ms == null || Number.isNaN(ms)) return '-';
  if (ms >= 10000) return NUM1.format(ms / 1000) + 's';
  if (ms >= 100) return NUM.format(Math.round(ms));
  return NUM1.format(ms);
}

/** Rates with 2 decimals. */
export function rate(n) {
  if (n == null || Number.isNaN(n)) return '-';
  return NUM2.format(n);
}

/** A fraction (0.0125) as a percentage with 1 decimal. */
export function pct(fraction) {
  if (fraction == null || Number.isNaN(fraction)) return '-';
  return NUM1.format(fraction * 100) + '%';
}

/** An Apdex with two decimals, one less than the API carries; '-' when there was no request. */
export function apdex(value) {
  if (value == null || Number.isNaN(value)) return '-';
  return value.toFixed(2);
}

export function bytes(n) {
  if (n == null || Number.isNaN(n)) return '-';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let v = n, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (v >= 100 || i === 0 ? NUM.format(Math.round(v)) : NUM1.format(v)) + ' ' + units[i];
}

function pad(n, width = 2) { return String(n).padStart(width, '0'); }

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function sameDay(a, b) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** HH:mm:ss within the day of `now`, MMM d HH:mm:ss otherwise. */
export function time(ts, now = Date.now()) {
  if (ts == null) return '-';
  const d = new Date(ts);
  const clock = pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  if (sameDay(d, new Date(now))) return clock;
  return MONTHS[d.getMonth()] + ' ' + d.getDate() + ' ' + clock;
}

/** Same, with milliseconds; the Logs table uses it. */
export function timeMs(ts, now = Date.now()) {
  if (ts == null) return '-';
  const d = new Date(ts);
  return time(ts, now) + '.' + pad(d.getMilliseconds(), 3);
}

/** Clock only, for chart axes. */
export function clock(ts) {
  const d = new Date(ts);
  return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

export function clockShort(ts) {
  const d = new Date(ts);
  return pad(d.getHours()) + ':' + pad(d.getMinutes());
}

/** Full date and time, for tooltips. */
export function full(ts) {
  if (ts == null) return '-';
  const d = new Date(ts);
  return MONTHS[d.getMonth()] + ' ' + d.getDate() + ' ' + d.getFullYear() + ' ' +
    pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) + '.' + pad(d.getMilliseconds(), 3);
}

/** "12 s ago". */
export function rel(ts, now = Date.now()) {
  if (ts == null) return '-';
  let s = Math.round((now - ts) / 1000);
  if (s < 0) s = 0;
  if (s < 1) return 'now';
  if (s < 60) return s + ' s ago';
  const m = Math.round(s / 60);
  if (m < 60) return m + ' min ago';
  const h = Math.round(m / 60);
  if (h < 24) return h + ' h ago';
  return Math.round(h / 24) + ' d ago';
}

/** Both forms, for a title attribute. */
export function bothTimes(ts) {
  return full(ts) + ' (' + rel(ts) + ')';
}

/** A trace or span id shortened for a dense cell; the full id goes in the title. */
export function shortId(id, keep = 8) {
  if (!id) return '-';
  return id.length <= keep ? id : id.slice(0, keep);
}

/** Split a Java class name into package and simple name. */
export function splitType(type) {
  if (!type) return { pkg: '', name: '' };
  const i = type.lastIndexOf('.');
  return i < 0 ? { pkg: '', name: type } : { pkg: type.slice(0, i + 1), name: type.slice(i + 1) };
}

export function truncate(s, max) {
  if (s == null) return '';
  return s.length <= max ? s : s.slice(0, max - 1) + '…';
}

/** A signed offset inside a trace: "+12.3 ms". */
export function offset(ms) {
  return (ms < 0 ? '-' : '+') + dur(Math.abs(ms));
}

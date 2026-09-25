// Hash router: #/traces/<id>?service=x&range=1h

import { sharedQuery, compactQuery, queryString } from './api.js';

const routes = [];
let onChange = null;
let current = { path: '/', params: {}, query: {} };

function compile(pattern) {
  const names = [];
  const source = pattern
    .split('/')
    .map((part) => {
      if (part.startsWith(':')) { names.push(part.slice(1)); return '([^/]+)'; }
      return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    })
    .join('/');
  return { re: new RegExp('^' + source + '$'), names };
}

/** register('/traces/:id'): the patterns of app.js's page table, matched in order. */
export function register(pattern) {
  routes.push({ ...compile(pattern), pattern });
}

export function parse(hash) {
  let raw = (hash || '').replace(/^#/, '');
  if (!raw.startsWith('/')) raw = '/' + raw;
  const q = raw.indexOf('?');
  const path = q < 0 ? raw : raw.slice(0, q);
  const query = {};
  if (q >= 0) {
    new URLSearchParams(raw.slice(q + 1)).forEach((v, k) => { query[k] = v; });
  }
  return { path: path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path, query };
}

/** One path segment, decoded once; a malformed escape is kept as it came rather than thrown. */
function decodeParam(raw) {
  try { return decodeURIComponent(raw); } catch (e) { return raw; }
}

/**
 * Matched against the path as the hash carries it, still percent-encoded, and each parameter
 * decoded once: `50%25%20off` is `50% off`, and an encoded `%2F` stays inside its segment.
 */
function match(path) {
  for (const route of routes) {
    const m = route.re.exec(path);
    if (m) {
      const params = {};
      route.names.forEach((n, i) => { params[n] = decodeParam(m[i + 1]); });
      return { route, params };
    }
  }
  return null;
}

export function currentRoute() { return current; }

/** Build a hash string from a path and a query object (empty values dropped, as compactQuery does). */
export function href(path, query = {}) {
  const qs = queryString(query);
  return '#' + path + (qs ? '?' + qs : '');
}

/** A hash query value that must be one of `allowed`: the value, or `fallback` when it is anything else. */
export function queryParam(query, name, allowed, fallback) {
  const value = (query || {})[name];
  return allowed.includes(value) ? value : fallback;
}

/**
 * The page of one thing: `/traces/<id>`, `/errors/<id>` and so on, the id always encoded, since an
 * endpoint or a service name can hold a slash or a percent sign (ui.adoc#urls).
 */
export function detailPath(kind, id) {
  return '/' + kind + '/' + encodeURIComponent(id);
}

/** A link to that page, carrying the top bar's query. */
export function detailHref(kind, id) {
  return href(detailPath(kind, id), sharedQuery());
}

/** Go to that page, carrying the top bar's query. */
export function openDetail(kind, id) {
  go(detailPath(kind, id), sharedQuery());
}

/** Navigate, a new history entry. */
export function go(path, query = {}) {
  location.hash = href(path, query);
}

/** Navigate in place of the current history entry, and handle the route at once. */
export function replace(path, query = {}) {
  history.replaceState(null, '', href(path, query));
  handle();
}

/**
 * Change only the query of the current route. An empty value removes its key, and so does a value
 * equal to its entry in `defaults`: a URL names only what differs from the page's defaults.
 */
export function setQuery(patch, { defaults = {} } = {}) {
  const query = { ...current.query };
  for (const [k, v] of Object.entries(patch)) query[k] = k in defaults && v === defaults[k] ? '' : v;
  replace(current.path, compactQuery(query));
}

let lastKey = null;

function handle() {
  const { path, query } = parse(location.hash);
  const found = match(path);
  if (!found) {
    if (path !== '/') { replace('/', query); return; }
    return;
  }
  const key = found.route.pattern + '|' + JSON.stringify(found.params);
  const changedRoute = key !== lastKey;
  lastKey = key;
  current = { path, params: found.params, query, route: found.route };
  if (onChange) onChange(current, changedRoute);
}

export function start(handler) {
  onChange = handler;
  addEventListener('hashchange', handle);
  if (!location.hash) history.replaceState(null, '', '#/');
  handle();
}

/** Force the active route to run its handler again. */
export function reload() { lastKey = null; handle(); }

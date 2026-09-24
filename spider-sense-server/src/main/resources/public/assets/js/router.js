// Hash router: #/traces/<id>?service=x&range=1h

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

/** register('/traces/:id', handler) */
export function register(pattern, handler) {
  routes.push({ ...compile(pattern), pattern, handler });
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

/** Build a hash string from a path and a query object (empty values dropped). */
export function href(path, query = {}) {
  const usp = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '' || v === false) continue;
    usp.set(k, String(v));
  }
  const qs = usp.toString();
  return '#' + path + (qs ? '?' + qs : '');
}

/** Navigate, keeping the shared top-bar query unless overridden. */
export function go(path, query = {}, replace = false) {
  const next = href(path, query);
  if (replace) history.replaceState(null, '', next);
  else location.hash = next;
  if (replace) handle();
}

/** Change only the query of the current route. */
export function setQuery(patch, replace = true) {
  const query = { ...current.query };
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined || v === null || v === '' || v === false) delete query[k];
    else query[k] = String(v);
  }
  go(current.path, query, replace);
}

let lastKey = null;

function handle() {
  const { path, query } = parse(location.hash);
  const found = match(path);
  if (!found) {
    if (path !== '/') { go('/', query, true); return; }
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

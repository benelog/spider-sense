// Boot: router, top-bar state, SSE, theme, keyboard shortcuts.

import * as api from './api.js';
import { RANGES, state } from './api.js';
import * as router from './router.js';
import * as ui from './ui.js';
import { h, fill, dialog, copyBlock, closeDrawer, drawerOpen } from './ui.js';
import { retheme, redrawAll, seedServiceColors } from './charts.js';
import { rate as fmtRate, count as fmtCount, bytes } from './format.js';

import * as overview from './pages/overview.js';
import * as findingsPage from './pages/findings.js';
import * as comparePage from './pages/compare.js';
import * as services from './pages/services.js';
import * as servicePage from './pages/service.js';
import * as endpointPage from './pages/endpoint.js';
import * as scatter from './pages/scatter.js';
import * as mapPage from './pages/map.js';
import * as traces from './pages/traces.js';
import * as tracePage from './pages/trace.js';
import * as queries from './pages/queries.js';
import * as queryPage from './pages/query.js';
import * as errorsPage from './pages/errors.js';
import * as errorPage from './pages/error.js';
import * as logs from './pages/logs.js';
import * as jvm from './pages/jvm.js';
import * as metrics from './pages/metrics.js';

const PAGES = [
  ['/', overview, 'Overview', '/'],
  ['/findings', findingsPage, 'Findings', '/findings'],
  ['/compare', comparePage, 'Compare', '/compare'],
  ['/map', mapPage, 'Service map', '/map'],
  ['/services', services, 'Services', '/services'],
  ['/services/:name', servicePage, 'Service', '/services'],
  ['/endpoints/:id', endpointPage, 'Endpoint', '/services'],
  ['/scatter', scatter, 'Response time scatter', '/scatter'],
  ['/traces', traces, 'Traces', '/traces'],
  ['/traces/:id', tracePage, 'Trace', '/traces'],
  ['/queries', queries, 'Queries', '/queries'],
  ['/queries/:id', queryPage, 'Query', '/queries'],
  ['/errors', errorsPage, 'Errors', '/errors'],
  ['/errors/:id', errorPage, 'Error', '/errors'],
  ['/logs', logs, 'Logs', '/logs'],
  ['/jvm', jvm, 'JVM', '/jvm'],
  ['/metrics', metrics, 'Metrics', '/metrics'],
];

const el = {};
let active = null;          // { module, instance, navKey }
let liveTimer = null;
let events = null;
let tingleCount = 0;

// --- theme ---------------------------------------------------------------

const THEME_KEY = 'spidersense.theme';

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme || '';
  const dark = theme ? theme === 'dark' : !matchMedia('(prefers-color-scheme: light)').matches;
  const btn = el.themeToggle;
  if (btn) {
    btn.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
    btn.querySelector('use').setAttribute('href', dark ? '#i-sun' : '#i-moon');
  }
  ui.readSeriesColors();
  retheme();
}

function toggleTheme() {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark'
    : !matchMedia('(prefers-color-scheme: light)').matches;
  const next = dark ? 'light' : 'dark';
  localStorage.setItem(THEME_KEY, next);
  applyTheme(next);
}

// --- top bar -------------------------------------------------------------

function syncStateFromQuery(query) {
  state.service = query.service || '';
  state.range = RANGES.some((r) => r.id === query.range) ? query.range : '15m';
  state.live = query.live === '1';
  el.serviceSelect.value = state.service;
  el.rangeSelect.value = state.range;
  el.liveToggle.setAttribute('aria-pressed', String(state.live));
  el.rateReadout.hidden = !state.live;
  document.body.classList.toggle('is-live', state.live);
  startLive();
}

function fillServiceSelect(names) {
  const current = el.serviceSelect.value;
  const wanted = ['', ...names];
  const have = Array.from(el.serviceSelect.options).map((o) => o.value);
  if (have.length === wanted.length && have.every((v, i) => v === wanted[i])) return;
  fill(el.serviceSelect, h('option', { value: '' }, 'All services'),
    names.map((n) => h('option', { value: n }, n)));
  el.serviceSelect.value = names.includes(current) ? current : '';
}

function startLive() {
  clearInterval(liveTimer);
  liveTimer = null;
  if (!state.live) return;
  liveTimer = setInterval(() => { refreshPage(); loadMarks(); }, 5000);
}

/**
 * The marks belong to the shell, not to a page (docs/ui.md): every chart draws
 * them, so they are fetched once per route change and once per Live tick and kept
 * in the shared state. A change redraws the charts without refetching their data.
 */
function loadMarks() {
  return api.marks(50).then((res) => {
    const list = res.marks || [];
    const before = state.marks.map((m) => m.id + ':' + m.at + ':' + m.name).join(',');
    state.marks = list;
    if (before !== list.map((m) => m.id + ':' + m.at + ':' + m.name).join(',')) redrawAll();
    return list;
  }).catch(() => state.marks);
}

/** The Mark button and the `M` key: mark, exercise, compare (docs/agent.md). */
function markDialog() {
  ui.markDialog({ onDone: () => loadMarks().then(() => refreshPage()) });
}

function refreshPage() {
  if (active && active.instance && active.instance.refresh) {
    try { active.instance.refresh(); } catch (e) { console.error(e); }
  }
}

// --- routing -------------------------------------------------------------

function setTitle(text) {
  el.title.textContent = text;
  document.title = text === 'Overview' ? 'Spider Sense' : text + ' — Spider Sense';
}

function markNav(navKey) {
  for (const a of el.nav.querySelectorAll('a[data-nav]')) {
    if (a.dataset.nav === navKey) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
}

function onRoute(current, changedRoute) {
  syncStateFromQuery(current.query);
  loadMarks();
  const entry = PAGES.find((p) => p[0] === current.route.pattern);
  if (!entry) return;
  const [, module, title, navKey] = entry;
  if (!changedRoute && active && active.module === module) {
    refreshPage();
    return;
  }
  if (active && active.instance && active.instance.destroy) {
    try { active.instance.destroy(); } catch (e) { console.error(e); }
  }
  closeDrawer(true);
  el.main.replaceChildren();
  el.main.scrollTop = 0;
  scrollTo(0, 0);
  setTitle(title);
  markNav(navKey);
  const ctx = {
    params: current.params,
    query: current.query,
    setTitle,
    navigate: (path, query) => router.go(path, { ...api.sharedQuery(), ...query }),
  };
  let instance = null;
  try {
    instance = module.render(el.main, ctx) || {};
  } catch (e) {
    console.error(e);
    fill(el.main, ui.errorBox(e, () => router.reload()));
    instance = {};
  }
  active = { module, instance, navKey };
}

// --- dialogs -------------------------------------------------------------

function sendDataDialog() {
  const s = state.status || {};
  const otlp = s.otlp || {};
  const base = s.endpoint || location.origin;
  const snippets = ui.snippetText(base);
  if (otlp.traces) snippets.curl = snippets.curl.replace(base + '/v1/traces', otlp.traces);
  const body = ui.tabs([
    { id: 'agent', label: '-javaagent', render: () => copyBlock(snippets.agent) },
    { id: 'env', label: 'Environment', render: () => copyBlock(snippets.env) },
    { id: 'curl', label: 'curl', render: () => copyBlock(snippets.curl) },
  ]);
  dialog({
    title: 'How to send data',
    body: [
      h('p.muted', 'Spider Sense receives OTLP/HTTP on ' + base + '. Anything that speaks the protocol can send to it.'),
      body,
    ],
    actions: h('button.btn', { type: 'button', onclick: (e) => e.target.closest('dialog').close() }, 'Close'),
  });
}

function clearDataDialog() {
  const dlg = dialog({
    title: 'Clear data',
    body: h('p', 'Every span, log and metric point in memory is dropped. Services stay registered.'),
    actions: [
      h('button.btn', { type: 'button', onclick: () => dlg.close() }, 'Cancel'),
      h('button.btn.btn-primary', {
        type: 'button',
        onclick: async () => {
          dlg.close();
          await api.clearData();
          await api.refreshStatus().then(paintFoot).catch(() => {});
          tingleCount = 0;
          updateBadge();
          router.reload();
          ui.toast('Data cleared');
        },
      }, 'Clear data'),
    ],
  });
}

// --- live events ---------------------------------------------------------

function updateBadge() {
  el.tingleBadge.hidden = tingleCount === 0;
  el.tingleBadge.textContent = tingleCount > 99 ? '99+' : String(tingleCount);
}

function pulse() {
  const mark = document.querySelector('.brand-mark');
  if (!mark) return;
  mark.classList.remove('tingle');
  void mark.offsetWidth;
  mark.classList.add('tingle');
}

function connectEvents() {
  if (events) { events.close(); events = null; }
  const Source = globalThis.EventSource;
  if (!Source) return;
  try {
    events = new Source(api.eventsUrl());
  } catch (e) {
    return;
  }
  events.addEventListener('tingle', (ev) => {
    let data = null;
    try { data = JSON.parse(ev.data); } catch (e) { return; }
    tingleCount++;
    updateBadge();
    pulse();
    if (active && active.instance && active.instance.onTingle) {
      try { active.instance.onTingle(data); } catch (e) { console.error(e); }
    }
  });
  events.addEventListener('stats', (ev) => {
    let data = null;
    try { data = JSON.parse(ev.data); } catch (e) { return; }
    const perSecond = data.perSecond || {};
    el.rateReadout.textContent = fmtRate(perSecond.spans || 0) + ' spans/s';
    el.rateReadout.title = 'Spans per second, and ' + fmtRate(perSecond.logs || 0) + ' logs/s';
  });
  events.addEventListener('service', () => {
    api.services().then((res) => {
      const names = (res.services || []).map((s) => s.name);
      seedServiceColors(names);
      fillServiceSelect(names);
    }).catch(() => {});
  });
  events.onerror = () => { /* the browser retries on its own */ };
}

// --- keyboard ------------------------------------------------------------

function isTyping(target) {
  return target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable);
}

function onKey(e) {
  if (e.key === 'Escape') {
    if (document.body.classList.contains('nav-open')) { setNav(false); return; }
    if (drawerOpen()) { closeDrawer(); return; }
    if (active && active.instance && active.instance.onEscape) active.instance.onEscape();
    return;
  }
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  if (isTyping(e.target)) return;
  if (e.key === '/') {
    const input = el.main.querySelector('.querybar input, input[type="search"], input[type="text"]');
    if (input) { e.preventDefault(); input.focus(); input.select(); }
    return;
  }
  if (e.key === 'l' || e.key === 'L') { e.preventDefault(); router.setQuery({ live: state.live ? '' : '1' }); return; }
  if (e.key === 'm' || e.key === 'M') { e.preventDefault(); markDialog(); return; }
  if (e.key === '[' || e.key === ']') {
    e.preventDefault();
    const i = api.rangeIndex(state.range);
    const next = RANGES[Math.min(RANGES.length - 1, Math.max(0, i + (e.key === ']' ? 1 : -1)))];
    router.setQuery({ range: next.id === '15m' ? '' : next.id });
  }
}

function setNav(open) {
  document.body.classList.toggle('nav-open', open);
  el.scrim.hidden = !open;
  el.menuBtn.setAttribute('aria-expanded', String(open));
}

// --- boot ----------------------------------------------------------------

async function boot() {
  if (new URLSearchParams(location.search).get('mock') === '1') {
    await import('./dev/mock.js');
  }

  Object.assign(el, {
    main: document.getElementById('main'),
    title: document.getElementById('page-title'),
    nav: document.getElementById('nav'),
    serviceSelect: document.getElementById('service-select'),
    rangeSelect: document.getElementById('range-select'),
    liveToggle: document.getElementById('live-toggle'),
    themeToggle: document.getElementById('theme-toggle'),
    rateReadout: document.getElementById('rate-readout'),
    tingleBadge: document.getElementById('tingle-badge'),
    markBtn: document.getElementById('mark-btn'),
    menuBtn: document.getElementById('menu-btn'),
    scrim: document.getElementById('scrim'),
  });

  fill(el.rangeSelect, RANGES.map((r) => h('option', { value: r.id }, r.label)));
  applyTheme(localStorage.getItem(THEME_KEY) || '');

  el.themeToggle.addEventListener('click', toggleTheme);
  el.serviceSelect.addEventListener('change', () => router.setQuery({ service: el.serviceSelect.value }));
  el.rangeSelect.addEventListener('change', () => router.setQuery({ range: el.rangeSelect.value === '15m' ? '' : el.rangeSelect.value }));
  el.liveToggle.addEventListener('click', () => router.setQuery({ live: state.live ? '' : '1' }));
  el.markBtn.addEventListener('click', markDialog);
  document.getElementById('send-data-btn').addEventListener('click', sendDataDialog);
  document.getElementById('clear-data-btn').addEventListener('click', clearDataDialog);
  document.getElementById('tingle-link').addEventListener('click', () => { tingleCount = 0; updateBadge(); });
  el.menuBtn.addEventListener('click', () => setNav(!document.body.classList.contains('nav-open')));
  el.scrim.addEventListener('click', () => setNav(false));
  el.nav.addEventListener('click', () => setNav(false));
  addEventListener('keydown', onKey);
  matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
    if (!document.documentElement.dataset.theme) applyTheme('');
  });

  for (const [pattern] of PAGES) router.register(pattern, null);

  try {
    await api.refreshStatus();
    paintFoot();
  } catch (e) {
    document.getElementById('foot-mode').textContent = 'offline';
  }

  try {
    const res = await api.services();
    const names = (res.services || []).map((s) => s.name);
    seedServiceColors(names);
    fillServiceSelect(names);
  } catch (e) { /* the page will show its own error */ }

  router.start(onRoute);
  connectEvents();
}

/** Mode, port, version, and where the data is kept. */
function paintFoot() {
  const status = state.status || {};
  document.getElementById('foot-mode').textContent = status.mode || '-';
  document.getElementById('foot-port').textContent = portOf(status.endpoint);
  document.getElementById('foot-version').textContent = 'v' + (status.version || '?');

  const store = status.storage || {};
  const row = document.getElementById('foot-store-row');
  const cell = document.getElementById('foot-store');
  const hours = status.retention && status.retention.hours;
  if (store.path || store.url) {
    row.hidden = false;
    cell.textContent = store.sizeBytes != null ? bytes(store.sizeBytes) : 'on disk';
    row.title = [
      store.path || store.url,
      store.sizeBytes != null ? bytes(store.sizeBytes) + ' on disk' : null,
      hours ? 'kept for ' + hours + ' h' : null,
    ].filter(Boolean).join('\n');
  } else {
    row.hidden = true;
  }

  const warn = document.getElementById('foot-storage-warn');
  if (store.fallback) {
    warn.hidden = false;
    warn.textContent = 'in memory only';
    warn.title = store.fallbackReason || 'The database could not be opened; data is kept in memory and is lost on restart.';
  } else if (store.droppedBatches > 0) {
    warn.hidden = false;
    warn.textContent = fmtCount(store.droppedBatches) + ' batches dropped';
    warn.title = 'The write queue overflowed' + (store.queued ? ', ' + fmtCount(store.queued) + ' batches waiting' : '') + '.';
  } else {
    warn.hidden = true;
  }
}

function portOf(endpoint) {
  try { return ':' + (new URL(endpoint).port || '80'); } catch (e) { return '-'; }
}

boot().catch((e) => {
  console.error(e);
  const main = document.getElementById('main');
  if (main) fill(main, ui.errorBox(e, () => location.reload()));
});

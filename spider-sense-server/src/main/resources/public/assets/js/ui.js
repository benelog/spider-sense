// Small DOM helpers: h(), keyed list rendering, chips, sortable tables, the drawer,
// dialogs, copy-to-clipboard and empty states.

import * as api from './api.js';

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * h('div.panel#main', { class: 'x', onclick: fn, dataset: {...} }, ...children)
 * Children may be nodes, strings, numbers, arrays, null or undefined.
 */
export function h(spec, attrs, ...children) {
  let tag = spec, cls = [], id = null;
  const m = /^([a-zA-Z0-9-]+)((?:[.#][^.#]+)*)$/.exec(spec);
  if (m) {
    tag = m[1];
    for (const part of (m[2] || '').split(/(?=[.#])/)) {
      if (!part) continue;
      if (part[0] === '.') cls.push(part.slice(1));
      else id = part.slice(1);
    }
  }
  const node = document.createElement(tag);
  if (cls.length) node.className = cls.join(' ');
  if (id) node.id = id;
  if (attrs && (attrs.nodeType || Array.isArray(attrs) || typeof attrs !== 'object')) {
    children.unshift(attrs);
    attrs = null;
  }
  if (attrs) applyAttrs(node, attrs);
  append(node, children);
  return node;
}

function applyAttrs(node, attrs) {
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') node.className = Array.isArray(v) ? v.filter(Boolean).join(' ') : v;
    else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
    else if (k === 'dataset') Object.assign(node.dataset, v);
    else if (k === 'html') node.innerHTML = v;
    else if (k === 'text') node.textContent = v;
    else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
    else if (v === true) node.setAttribute(k, '');
    else node.setAttribute(k, String(v));
  }
}

export function append(node, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    if (Array.isArray(child)) append(node, child);
    else if (child.nodeType) node.appendChild(child);
    else node.appendChild(document.createTextNode(String(child)));
  }
  return node;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

/** Replace a node's children in one go. */
export function fill(node, ...children) {
  clear(node);
  append(node, children);
  return node;
}

export function frag(...children) {
  const f = document.createDocumentFragment();
  append(f, children);
  return f;
}

/** A 16x16 icon from the sprite in index.html. */
export function icon(name, cls) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('class', 'icon' + (cls ? ' ' + cls : ''));
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  const use = document.createElementNS(SVG_NS, 'use');
  use.setAttribute('href', '#i-' + name);
  svg.appendChild(use);
  return svg;
}

export function iconButton(name, label, onclick, extra = {}) {
  return h('button.icon-btn', { type: 'button', 'aria-label': label, title: label, onclick, ...extra }, icon(name));
}

// --- service colours ----------------------------------------------------

let seriesColors = [];
const serviceSlots = new Map();

/** Read the 8 series colours from CSS custom properties; call again after a theme change. */
export function readSeriesColors() {
  const cs = getComputedStyle(document.documentElement);
  seriesColors = [];
  for (let i = 1; i <= 8; i++) {
    const v = cs.getPropertyValue('--series-' + i).trim();
    if (v) seriesColors.push(v);
  }
  if (!seriesColors.length) seriesColors = ['#e2603f', '#8a93a6', '#5ec27f', '#f0b64b', '#5aa9e6', '#ef7fa3', '#9b8cf2', '#35c0b6'];
  return seriesColors;
}

/** A stable colour per service, assigned in first-seen order. */
export function serviceColor(name) {
  if (!seriesColors.length) readSeriesColors();
  if (!name) return seriesColors[1];
  if (!serviceSlots.has(name)) serviceSlots.set(name, serviceSlots.size % seriesColors.length);
  return seriesColors[serviceSlots.get(name)];
}

/** Colour by index, for anything that is not a service. */
export function seriesColor(i) {
  if (!seriesColors.length) readSeriesColors();
  return seriesColors[i % seriesColors.length];
}

/** Seed the assignment so first-seen order follows the API, not click order. */
export function seedServices(names) {
  for (const n of names || []) serviceColor(n);
}

// --- chips --------------------------------------------------------------

export function chip(text, opts = {}) {
  const node = h('span.chip', { class: ['chip', opts.class].filter(Boolean).join(' '), title: opts.title || null }, text);
  if (opts.color) {
    node.classList.add('chip-dot');
    node.prepend(h('span.dot', { style: { background: opts.color } }));
  }
  return node;
}

export function serviceChip(name, opts = {}) {
  if (!name) return null;
  return chip(name, { ...opts, color: serviceColor(name), class: 'chip-service' });
}

export function methodChip(method) {
  if (!method) return null;
  return h('span.method', { 'data-method': method }, method);
}

export function statusChip(code) {
  if (code == null) return h('span.muted', '-');
  const klass = code >= 500 ? 'bad' : code >= 400 ? 'warn' : 'ok';
  return h('span.status-code', { class: 'status-code ' + klass }, String(code));
}

export function severityChip(severity) {
  const s = (severity || 'INFO').toUpperCase();
  return h('span.sev', { 'data-sev': s }, s);
}

export function mono(text, opts = {}) {
  return h('span.mono', { title: opts.title || null, class: ['mono', opts.class].filter(Boolean).join(' ') }, text);
}

/** A trace or span id that copies itself when clicked. */
export function idButton(id, label = 'Copy id') {
  const btn = h('button.id-copy', { type: 'button', title: id + ' — click to copy', 'aria-label': label },
    h('span.mono', id), icon('copy'));
  btn.addEventListener('click', (e) => { e.stopPropagation(); copyText(id, btn); });
  return btn;
}

export async function copyText(text, anchor) {
  let ok = true;
  try {
    if (navigator.clipboard && isSecureContext) await navigator.clipboard.writeText(text);
    else throw new Error('no clipboard');
  } catch (e) {
    const ta = h('textarea', { style: { position: 'fixed', opacity: '0' } }, text);
    document.body.appendChild(ta);
    ta.select();
    try { ok = document.execCommand('copy'); } catch (e2) { ok = false; }
    ta.remove();
  }
  toast(ok ? 'Copied' : 'Copy failed', anchor);
  return ok;
}

let toastTimer = null;
export function toast(message) {
  let node = document.getElementById('toast');
  if (!node) {
    node = h('div#toast', { role: 'status', 'aria-live': 'polite' });
    document.body.appendChild(node);
  }
  node.textContent = message;
  node.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('show'), 1600);
}

export function copyBlock(text, opts = {}) {
  const pre = h('pre.code', text);
  const btn = h('button.btn.btn-ghost.copy-block', { type: 'button' }, icon('copy'), 'Copy');
  btn.addEventListener('click', () => copyText(text, btn));
  return h('div.code-block', { class: ['code-block', opts.class].filter(Boolean).join(' ') }, pre, btn);
}

/** The "how to send data" snippets, used by the dialog and by empty states. */
export function snippetText(base) {
  return {
    agent: `java -javaagent:spider-sense.jar -jar your-app.jar\n\n# or point an app at a Spider Sense that is already running\njava -javaagent:spider-sense.jar \\\n  -Dspidersense.collector=${base} \\\n  -jar your-app.jar`,
    env: `OTEL_EXPORTER_OTLP_ENDPOINT=${base}\nOTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf\nOTEL_SERVICE_NAME=your-service`,
    curl: `curl -X POST ${base}/v1/traces \\\n  -H 'content-type: application/json' \\\n  -d '{"resourceSpans":[]}'`,
  };
}

/** The three snippets stacked, for an empty page. */
export function snippetBlocks(base) {
  const s = snippetText(base);
  return h('div.snippets', { style: { display: 'grid', gap: '10px', width: 'min(640px, 100%)' } },
    copyBlock(s.agent), copyBlock(s.env), copyBlock(s.curl));
}

// --- keyed list rendering ----------------------------------------------

/**
 * Render items into container, reusing existing nodes by key so scroll position,
 * text selection and focus survive a Live refresh. A child without a key is the
 * placeholder an empty list left behind, and goes.
 */
export function renderList(container, items, { key, create, update, enter } = {}) {
  const existing = new Map();
  for (const child of Array.from(container.children)) {
    const k = child.dataset.key;
    if (k !== undefined) existing.set(k, child);
    else child.remove();
  }
  let prev = null;
  const seen = new Set();
  items.forEach((item, index) => {
    const k = String(key ? key(item, index) : index);
    seen.add(k);
    let node = existing.get(k);
    const isNew = !node;
    if (isNew) {
      node = create(item, index);
      node.dataset.key = k;
    } else if (update) {
      update(node, item, index);
    }
    const next = prev ? prev.nextSibling : container.firstChild;
    if (node !== next) container.insertBefore(node, next);
    if (isNew && enter) enter(node, item, index);
    prev = node;
  });
  for (const [k, node] of existing) if (!seen.has(k)) node.remove();
  return container;
}

// --- tables -------------------------------------------------------------

/**
 * columns: [{ key, label, align, width, sortable, sortKey, render(row), title(row), cls }]
 * opts: { rows, sort: {key, dir}, onSort(key), onRowClick(row), rowKey(row), rowClass(row), empty }
 */
export function table(columns, opts = {}) {
  const wrap = h('div.table-wrap');
  const t = h('table.table');
  const thead = h('thead');
  const tr = h('tr');
  for (const col of columns) {
    const th = h('th', {
      class: [col.align === 'right' ? 'right' : col.align === 'center' ? 'center' : null, col.sortable !== false && opts.onSort ? 'sortable' : null].filter(Boolean).join(' ') || null,
      style: col.width ? { width: col.width } : null,
      scope: 'col',
    });
    const sortable = col.sortable !== false && opts.onSort;
    const sortKey = col.sortKey || col.key;
    if (sortable) {
      const active = opts.sort && opts.sort.key === sortKey;
      const dir = active ? opts.sort.dir : null;
      th.setAttribute('aria-sort', active ? (dir === 'asc' ? 'ascending' : 'descending') : 'none');
      const btn = h('button.th-btn', { type: 'button', onclick: () => opts.onSort(sortKey) },
        col.label, h('span.sort-mark', active ? (dir === 'asc' ? '▲' : '▼') : ''));
      th.appendChild(btn);
    } else {
      th.textContent = col.label;
    }
    tr.appendChild(th);
  }
  thead.appendChild(tr);
  t.appendChild(thead);
  const tbody = h('tbody');
  t.appendChild(tbody);
  wrap.appendChild(t);
  wrap.tbody = tbody;
  wrap.columns = columns;
  fillRows(wrap, opts.rows || [], opts);
  return wrap;
}

export function fillRows(wrap, rows, opts = {}) {
  const columns = wrap.columns;
  const tbody = wrap.tbody;
  if (!rows.length) {
    clear(tbody);
    tbody.appendChild(h('tr.empty-row', h('td', { colspan: columns.length },
      h('span.muted', opts.empty || 'Nothing in this window.'))));
    return wrap;
  }
  renderList(tbody, rows, {
    key: (row, i) => (opts.rowKey ? opts.rowKey(row, i) : i),
    create: (row, i) => buildRow(columns, row, i, opts),
    update: (node, row, i) => {
      const fresh = buildRow(columns, row, i, opts);
      node.className = fresh.className;
      node.replaceChildren(...fresh.childNodes);
      if (opts.onRowClick) { node.onclick = fresh.onclick; node.onkeydown = fresh.onkeydown; }
    },
  });
  return wrap;
}

function buildRow(columns, row, i, opts) {
  const tr = h('tr', {
    class: opts.rowClass ? opts.rowClass(row) : null,
    tabindex: opts.onRowClick ? 0 : null,
  });
  if (opts.onRowClick) {
    tr.classList.add('clickable');
    tr.onclick = (e) => { if (!e.target.closest('button, a')) opts.onRowClick(row, e); };
    // Only the row's own keys: an Enter on a link or button inside it is that control's, and
    // bubbles here too. A property, not a listener, so an update rebinds it to the new row.
    tr.onkeydown = (e) => {
      if (e.target !== e.currentTarget) return;
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); opts.onRowClick(row, e); }
    };
  }
  for (const col of columns) {
    const td = h('td', {
      class: [col.align === 'right' ? 'right' : col.align === 'center' ? 'center' : null, col.cls].filter(Boolean).join(' ') || null,
      title: col.title ? col.title(row) : null,
    });
    const v = col.render(row, i);
    if (v !== null && v !== undefined) append(td, [v]);
    tr.appendChild(td);
  }
  return tr;
}

/** Sort helper: returns a comparator for {key, dir} over numeric or string fields. */
export function comparator(sort, accessors = {}) {
  const get = accessors[sort.key] || ((row) => row[sort.key]);
  const sign = sort.dir === 'asc' ? 1 : -1;
  return (a, b) => {
    const x = get(a), y = get(b);
    if (x == null && y == null) return 0;
    if (x == null) return 1;
    if (y == null) return -1;
    if (typeof x === 'number' && typeof y === 'number') return (x - y) * sign;
    return String(x).localeCompare(String(y)) * sign;
  };
}

// --- panels -------------------------------------------------------------

export function panel(title, opts = {}, ...children) {
  if (title && typeof title === 'object' && !Array.isArray(title)) { children.unshift(opts); opts = title; title = opts.title; }
  const head = title || opts.actions
    ? h('div.panel-head', h('h2.panel-title', title || ''), opts.actions ? h('div.panel-actions', opts.actions) : null)
    : null;
  return h('section.panel', { class: ['panel', opts.class].filter(Boolean).join(' ') }, head, ...children);
}

export function stat(value, unit, caption, opts = {}) {
  return h('div.stat', { class: ['stat', opts.class].filter(Boolean).join(' '), title: opts.title || null },
    h('div.stat-value', h('span.stat-number', value), unit ? h('span.stat-unit', unit) : null),
    h('div.stat-caption', caption));
}

export function spinner() { return h('div.loading', h('span.spin'), 'Loading'); }

export function errorBox(err, retry) {
  return h('div.error-box',
    icon('bolt'),
    h('div', h('div.error-title', 'Request failed'), h('div.muted', String(err && err.message ? err.message : err))),
    retry ? h('button.btn', { type: 'button', onclick: retry }, 'Try again') : null);
}

/** The empty state: a sentence and the snippet that makes data appear. */
export function emptyState(sentence, extra) {
  return h('div.empty-state',
    h('p.empty-sentence', sentence),
    extra);
}

// --- drawer -------------------------------------------------------------

let drawerNode = null;
let drawerCloser = null;

export function drawer({ title, subtitle, body, onClose }) {
  closeDrawer(true);
  const close = () => closeDrawer();
  drawerNode = h('aside.drawer', { role: 'dialog', 'aria-modal': 'false', 'aria-label': title || 'Details' },
    h('header.drawer-head',
      h('div.drawer-titles', h('h2.drawer-title', title || ''), subtitle ? h('div.drawer-sub', subtitle) : null),
      iconButton('close', 'Close details', close)),
    h('div.drawer-body', body));
  drawerCloser = onClose;
  document.body.appendChild(drawerNode);
  document.body.classList.add('drawer-open');
  requestAnimationFrame(() => drawerNode && drawerNode.classList.add('in'));
  return drawerNode;
}

export function closeDrawer(silent = false) {
  if (!drawerNode) return false;
  const node = drawerNode, onClose = drawerCloser;
  drawerNode = null; drawerCloser = null;
  node.remove();
  document.body.classList.remove('drawer-open');
  if (!silent && onClose) onClose();
  return true;
}

export function drawerOpen() { return !!drawerNode; }

// --- dialog -------------------------------------------------------------

export function dialog({ title, body, actions, onClose }) {
  const dlg = h('dialog.dialog', { 'aria-label': title },
    h('header.dialog-head', h('h2', title), iconButton('close', 'Close dialog', () => dlg.close())),
    h('div.dialog-body', body),
    actions ? h('footer.dialog-foot', actions) : null);
  dlg.addEventListener('close', () => { if (onClose) onClose(dlg.returnValue); dlg.remove(); });
  // A click on the backdrop closes the dialog, but only when it also began there: a drag
  // from a field inside to the backdrop fires its click on the dialog too, and would lose the text.
  let pressedOnBackdrop = false;
  dlg.addEventListener('pointerdown', (e) => { pressedOnBackdrop = e.target === dlg; });
  dlg.addEventListener('click', (e) => {
    if (e.target === dlg && pressedOnBackdrop) dlg.close();
    pressedOnBackdrop = false;
  });
  document.body.appendChild(dlg);
  dlg.showModal();
  return dlg;
}

/**
 * The Mark dialog (ui.adoc#dialogs): a named moment, the person's half of the agent's
 * loop. The name is prefilled `before` until the window already has one, so the
 * usual pair costs two clicks; the top bar's service filter becomes the mark's
 * service, because a mark of one service is what `since=start` of that service means.
 */
let openMarkDialog = null;

export function markDialog(opts = {}) {
  // One at a time: a second one stacked over the first would create a second mark.
  if (openMarkDialog && openMarkDialog.open) return openMarkDialog;
  const window_ = api.windowFor();
  const named = (api.state.marks || []).some(
    (m) => m.name === 'before' && m.at >= window_.from && m.at <= window_.to);
  const service = api.state.service || '';
  const nameInput = h('input', {
    type: 'text', value: named ? 'after' : 'before', required: true,
    pattern: '[A-Za-z0-9._-]{1,64}', maxlength: '64', autocomplete: 'off',
    spellcheck: 'false', 'aria-label': 'Mark name', style: { width: '100%' },
  });
  const noteInput = h('input', {
    type: 'text', placeholder: 'optional', autocomplete: 'off',
    'aria-label': 'Note', style: { width: '100%' },
  });
  const problem = h('div.form-error', { role: 'alert' });
  problem.hidden = true;

  const ok = h('button.btn.btn-primary', { type: 'button' }, 'Mark');
  const dlg = dialog({
    title: 'Mark this moment',
    body: h('div.mark-form',
      h('label', h('span', 'Name'), nameInput),
      h('label', h('span', 'Note'), noteInput),
      h('p.muted', service
        ? 'The mark is recorded for ' + service + ', the service the top bar filters by.'
        : 'The mark is recorded for every service. Filter by a service to mark only that one.'),
      problem),
    actions: [h('button.btn', { type: 'button', onclick: () => dlg.close() }, 'Cancel'), ok],
    onClose: () => { if (openMarkDialog === dlg) openMarkDialog = null; },
  });
  openMarkDialog = dlg;

  async function submit() {
    // In flight already: a held Enter repeats, and each repeat would POST another mark.
    if (ok.disabled) return;
    const name = nameInput.value.trim();
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(name)) {
      problem.hidden = false;
      problem.textContent = 'A name is 1 to 64 of the characters A-Z, a-z, 0-9, dot, underscore and dash.';
      nameInput.focus();
      return;
    }
    ok.disabled = true;
    try {
      const mark = await api.createMark({ name, note: noteInput.value.trim(), service });
      dlg.close();
      toast('Marked ' + name);
      if (opts.onDone) opts.onDone(mark);
    } catch (e) {
      ok.disabled = false;
      problem.hidden = false;
      problem.textContent = String(e && e.message ? e.message : e);
    }
  }

  ok.addEventListener('click', submit);
  for (const input of [nameInput, noteInput]) {
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); submit(); } });
  }
  requestAnimationFrame(() => { nameInput.focus(); nameInput.select(); });
  return dlg;
}

export function tabs(items, opts = {}) {
  const list = h('div.tabs', { role: 'tablist' });
  const panelNode = h('div.tab-panel', { role: 'tabpanel' });
  let activeId = opts.active || (items[0] && items[0].id);
  // onSelect hears the viewer's choice, not the first paint: a page that writes the tab
  // to the URL would otherwise re-render itself, paint the tabs again, and loop.
  function select(id, notify = true) {
    activeId = id;
    for (const btn of list.children) btn.setAttribute('aria-selected', String(btn.dataset.tab === id));
    const item = items.find((t) => t.id === id);
    fill(panelNode, item ? item.render() : null);
    if (notify && opts.onSelect) opts.onSelect(id);
  }
  for (const item of items) {
    list.appendChild(h('button.tab', {
      type: 'button', role: 'tab', 'data-tab': item.id,
      'aria-selected': String(item.id === activeId),
      onclick: () => select(item.id),
    }, item.label, item.count != null ? h('span.tab-count', String(item.count)) : null));
  }
  select(activeId, false);
  const node = h('div.tabs-wrap', list, panelNode);
  node.select = select;
  return node;
}

/** Remember and restore the scroll position of the main scroller across a re-render. */
export function keepScroll(node, work) {
  const top = node ? node.scrollTop : 0;
  const left = node ? node.scrollLeft : 0;
  work();
  if (node) { node.scrollTop = top; node.scrollLeft = left; }
}

export function debounce(fn, ms) {
  let t = null;
  const wrapped = (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  wrapped.cancel = () => clearTimeout(t);
  wrapped.flush = (...args) => { clearTimeout(t); fn(...args); };
  return wrapped;
}

/** A proportional bar for a duration cell. */
export function durationBar(value, max, klass) {
  const w = max > 0 ? Math.max(1, Math.min(100, (value / max) * 100)) : 0;
  return h('span.dbar', { class: ['dbar', klass].filter(Boolean).join(' ') },
    h('span.dbar-fill', { style: { width: w + '%' } }));
}

/** 2xx / 4xx / 5xx mini bar. */
/**
 * Where an endpoint's or a job's time went, as one stacked bar (pages.adoc#time-breakdown):
 * `db`, `http`, `internal` and `self`, each segment sized by its share.
 *
 * <p>The order and the colours are fixed, so two bars can be compared at a
 * glance; the shares are in the title and the largest bucket is named in words
 * beside it, so the colour is never the only carrier.
 *
 * @returns null when there is no breakdown to draw
 */
export function breakdownBar(breakdown) {
  const buckets = ['db', 'http', 'internal', 'self'];
  const total = buckets.reduce((sum, b) => sum + (Number(breakdown && breakdown[b]) || 0), 0);
  if (!total) return null;
  const title = buckets
    .map((b) => b + ' ' + ((breakdown[b] || 0) * 100).toFixed(1) + '%')
    .join(' · ');
  return h('span.breakdown', { title },
    buckets.map((b) => ((breakdown[b] || 0) > 0
      ? h('span', { class: 'seg seg-' + b, style: { width: ((breakdown[b] / total) * 100) + '%' } })
      : null)));
}

/** The bucket the most time went to, so the bar is readable without its colours. */
export function breakdownLead(breakdown) {
  let lead = null;
  for (const bucket of ['db', 'http', 'internal', 'self']) {
    const share = Number(breakdown && breakdown[bucket]) || 0;
    if (!lead || share > lead[1]) lead = [bucket, share];
  }
  return lead && lead[1] > 0 ? lead : null;
}

export function statusBar(statusCodes) {
  const entries = Object.entries(statusCodes || {});
  const total = entries.reduce((s, [, n]) => s + n, 0);
  if (!total) return h('span.muted', '-');
  const groups = { ok: 0, warn: 0, bad: 0 };
  for (const [code, n] of entries) {
    const c = parseInt(code, 10);
    if (c >= 500) groups.bad += n; else if (c >= 400) groups.warn += n; else groups.ok += n;
  }
  const title = entries.sort((a, b) => b[1] - a[1]).map(([c, n]) => c + ': ' + n).join(', ');
  return h('span.statusbar', { title },
    ['ok', 'warn', 'bad'].map((g) => (groups[g] ? h('span', { class: 'seg seg-' + g, style: { width: (groups[g] / total) * 100 + '%' } }) : null)));
}

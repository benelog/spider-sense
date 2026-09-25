// A browser just big enough to render the pages in Node, on top of the fake DOM: selectors,
// layout sizes that never change, location and history, and a uPlot that draws nothing but
// runs a chart's hooks once. Timers are unref'd, so the mock's clock does not keep a test alive.
import { document, Element } from './fake-dom.js';

// --- selectors: compound (tag, #id, .class, [attr], [attr="v"]) with descendant combinators
function parseCompound(sel) {
  const out = { tag: null, id: null, classes: [], attrs: [] };
  const re = /([a-zA-Z][\w-]*)|#([\w-]+)|\.([\w-]+)|\[([\w-]+)(?:="([^"]*)")?\]/g;
  let m;
  while ((m = re.exec(sel))) {
    if (m[1]) out.tag = m[1].toUpperCase();
    else if (m[2]) out.id = m[2];
    else if (m[3]) out.classes.push(m[3]);
    else out.attrs.push([m[4], m[5]]);
  }
  return out;
}
function matchCompound(el, c) {
  if (!(el instanceof Element)) return false;
  if (c.tag && el.tagName !== c.tag) return false;
  if (c.id && el.id !== c.id) return false;
  for (const cl of c.classes) if (!el.classList.contains(cl)) return false;
  for (const [a, v] of c.attrs) {
    if (!el.hasAttribute(a)) return false;
    if (v !== undefined && el.getAttribute(a) !== v) return false;
  }
  return true;
}
function matchSelector(el, selector) {
  return selector.split(',').some((one) => {
    const parts = one.trim().split(/\s+/).map(parseCompound);
    if (!matchCompound(el, parts[parts.length - 1])) return false;
    let i = parts.length - 2;
    for (let n = el.parentNode; n && i >= 0; n = n.parentNode) if (matchCompound(n, parts[i])) i--;
    return i < 0;
  });
}
Element.prototype.matches = function (selector) { return matchSelector(this, selector); };
Element.prototype.querySelectorAll = function (selector) {
  const out = [];
  const walk = (n) => { for (const c of n.children) { if (matchSelector(c, selector)) out.push(c); walk(c); } };
  walk(this);
  return out;
};
Element.prototype.querySelector = function (selector) { return this.querySelectorAll(selector)[0] || null; };
Element.prototype.contains = function (other) { for (let n = other; n; n = n.parentNode) if (n === this) return true; return false; };
Element.prototype.replaceWith = function (node) { const p = this.parentNode; if (p) { p.insertBefore(node, this); this.remove(); } };
Element.prototype.getBoundingClientRect = () => ({ left: 0, top: 0, width: 600, height: 300 });
Element.prototype.scrollIntoView = () => {};
Element.prototype.getBBox = () => ({ x: 0, y: 0, width: 30, height: 12 });
Object.defineProperty(Element.prototype, 'clientWidth', { get: () => 600 });
Object.defineProperty(Element.prototype, 'offsetWidth', { get: () => 80 });
Object.defineProperty(Element.prototype, 'offsetHeight', { get: () => 20 });
document.querySelector = (s) => document.body.querySelector(s);
document.querySelectorAll = (s) => document.body.querySelectorAll(s);
document.getElementById = (id) => document.body.querySelector('#' + id);
document.documentElement.dataset.theme = '';

const loc = new URL('http://localhost:4000/#/');
globalThis.location = {
  get origin() { return loc.origin; }, get href() { return loc.href; }, get search() { return loc.search; },
  get hash() { return loc.hash; }, set hash(v) { loc.hash = v; },
};
globalThis.history = { replaceState: (s, t, url) => { loc.hash = url; } };
globalThis.addEventListener = () => {};
globalThis.removeEventListener = () => {};
globalThis.scrollTo = () => {};
globalThis.innerHeight = 800;
globalThis.devicePixelRatio = 1;
globalThis.scrollY = 0;
globalThis.window = globalThis;
globalThis.isSecureContext = false;
globalThis.CSS = { escape: (s) => String(s) };
globalThis.matchMedia = () => ({ matches: false, addEventListener() {} });
globalThis.getComputedStyle = () => ({ getPropertyValue: () => '', paddingLeft: '0', paddingRight: '0' });
globalThis.localStorage = { getItem: () => null, setItem: () => {} };
globalThis.ResizeObserver = class { observe() {} disconnect() {} };
globalThis.requestAnimationFrame = (fn) => { setTimeout(fn, 0); return 0; };

class FakePlot {
  constructor(opts, data, container) {
    this.opts = opts; this.data = data; this.width = opts.width; this.height = opts.height;
    this.over = document.createElement('div');
    this.bbox = { left: 0, top: 0, width: 500, height: 200 };
    this.cursor = { idx: null, left: -1, top: 0 };
    this.series = opts.series;
    container.appendChild(this.over);
    // run the draw hooks once, against a canvas that records nothing
    const ctx = new Proxy({}, { get: (t, k) => (k === 'measureText' ? () => ({ width: 10 }) : t[k] !== undefined ? t[k] : () => {}), set: (t, k, v) => { t[k] = v; return true; } });
    this.ctx = ctx;
    for (const hook of ((opts.hooks || {}).draw || [])) hook(this);
    for (const hook of ((opts.hooks || {}).setCursor || [])) hook(this);
    if (opts.axes) for (const a of opts.axes) if (a.values) a.values(this, [0, 1, 2]);
    for (const s of opts.series) if (s.value) s.value(this, 1);
  }
  destroy() {} setSize() {} redraw() {} setData() {} setSelect() {}
  valToPos(v) { return v; } posToVal(v) { return v; }
}
FakePlot.paths = { bars: () => () => null };
globalThis.uPlot = FakePlot;

const setIntervalReal = globalThis.setInterval;
globalThis.setInterval = (fn, ms) => {
  const timer = setIntervalReal(fn, ms);
  if (timer && timer.unref) timer.unref();
  return timer;
};

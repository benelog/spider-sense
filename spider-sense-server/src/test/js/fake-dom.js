// A document just big enough for ui.js's h(), renderList() and table(): elements, text nodes,
// classes, data attributes, events and the tree operations those helpers use. Not a browser.

class Node {
  constructor() { this.parentNode = null; this.childNodes = []; }
  get firstChild() { return this.childNodes[0] || null; }
  get nextSibling() {
    if (!this.parentNode) return null;
    const siblings = this.parentNode.childNodes;
    return siblings[siblings.indexOf(this) + 1] || null;
  }
  get textContent() { return this.childNodes.map((c) => c.textContent).join(''); }
  set textContent(text) { this.replaceChildren(new Text(String(text))); }
  appendChild(child) { return this.insertBefore(child, null); }
  insertBefore(child, ref) {
    if (child instanceof Fragment) {
      for (const c of child.childNodes.slice()) this.insertBefore(c, ref);
      return child;
    }
    child.remove();
    child.parentNode = this;
    const at = ref ? this.childNodes.indexOf(ref) : -1;
    if (at < 0) this.childNodes.push(child); else this.childNodes.splice(at, 0, child);
    return child;
  }
  removeChild(child) { child.remove(); return child; }
  remove() {
    if (!this.parentNode) return;
    const siblings = this.parentNode.childNodes;
    siblings.splice(siblings.indexOf(this), 1);
    this.parentNode = null;
  }
  replaceChildren(...nodes) {
    for (const c of this.childNodes.slice()) c.remove();
    for (const n of nodes) this.appendChild(typeof n === 'string' ? new Text(n) : n);
  }
  prepend(child) { this.insertBefore(child, this.firstChild); }
  get isConnected() { let n = this; while (n.parentNode) n = n.parentNode; return n === document.body || n === document; }
}

class Text extends Node {
  constructor(data) { super(); this.data = data; this.nodeType = 3; }
  get textContent() { return this.data; }
  set textContent(text) { this.data = String(text); }
}

class Fragment extends Node {
  constructor() { super(); this.nodeType = 11; }
}

class Element extends Node {
  constructor(tag) {
    super();
    this.nodeType = 1;
    this.tagName = tag.toUpperCase();
    this.attributes = new Map();
    this.listeners = {};
    this.style = {};
    const el = this;
    this.dataset = new Proxy({}, {
      get: (target, key) => el.attributes.get('data-' + dashed(key)),
      set: (target, key, value) => { el.attributes.set('data-' + dashed(key), String(value)); return true; },
      ownKeys: () => [...el.attributes.keys()].filter((k) => k.startsWith('data-')).map((k) => camel(k.slice(5))),
      getOwnPropertyDescriptor: () => ({ enumerable: true, configurable: true }),
    });
    this.classList = {
      add: (...names) => { this.className = [...new Set([...this.classes(), ...names])].join(' '); },
      remove: (...names) => { this.className = this.classes().filter((c) => !names.includes(c)).join(' '); },
      contains: (name) => this.classes().includes(name),
      toggle: (name, on = !this.classList.contains(name)) => { if (on) this.classList.add(name); else this.classList.remove(name); return on; },
    };
  }
  classes() { return (this.className || '').split(/\s+/).filter(Boolean); }
  get className() { return this.attributes.get('class') || ''; }
  set className(v) { this.attributes.set('class', String(v)); }
  get id() { return this.attributes.get('id') || ''; }
  set id(v) { this.attributes.set('id', String(v)); }
  get children() { return this.childNodes.filter((c) => c instanceof Element); }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }
  hasAttribute(name) { return this.attributes.has(name); }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  /** Fires `type` at this element and bubbles it up, as a click or a keydown would. */
  dispatch(type, init = {}) {
    const event = { type, target: this, defaultPrevented: false, stopped: false, ...init };
    event.preventDefault = () => { event.defaultPrevented = true; };
    event.stopPropagation = () => { event.stopped = true; };
    for (let n = this; n && !event.stopped; n = n.parentNode) {
      event.currentTarget = n;
      if (typeof n['on' + type] === 'function') n['on' + type](event);
      for (const fn of (n.listeners && n.listeners[type]) || []) fn(event);
    }
    return event;
  }
  click() { return this.dispatch('click'); }
  matches(selector) { return selector.split(',').some((s) => this.tagName === s.trim().toUpperCase()); }
  closest(selector) {
    for (let n = this; n instanceof Element; n = n.parentNode) if (n.matches(selector)) return n;
    return null;
  }
  /** Elements under this one with the tag, in document order. */
  all(tag) {
    const out = [];
    const walk = (n) => { for (const c of n.children) { if (c.tagName === tag.toUpperCase()) out.push(c); walk(c); } };
    walk(this);
    return out;
  }
}

function dashed(key) { return String(key).replace(/[A-Z]/g, (c) => '-' + c.toLowerCase()); }
function camel(key) { return key.replace(/-([a-z])/g, (m, c) => c.toUpperCase()); }

const document = {
  createElement: (tag) => new Element(tag),
  createElementNS: (ns, tag) => new Element(tag),
  createTextNode: (text) => new Text(text),
  createDocumentFragment: () => new Fragment(),
  body: new Element('body'),
  documentElement: new Element('html'),
  getElementById: () => null,
  querySelector: () => null,
};

globalThis.document = document;
globalThis.getComputedStyle = () => ({ getPropertyValue: () => '' });

export { document, Element, Text };

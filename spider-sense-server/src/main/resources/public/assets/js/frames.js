// Code frames as links into the editor, with the lines around them (pages.adoc#code-frames).
// Each frame asks GET /api/source once; a frame that does not resolve stays plain text.
// A whole stack trace folds its framework frames by the same rules (pages.adoc#stack-traces).

import { getJSON, state } from './api.js';
import { h } from './ui.js';

const EDITOR_KEY = 'spidersense.editor';

/** The editors a frame can open in, the first being the default. */
export const EDITORS = [
  { id: 'idea', label: 'IntelliJ IDEA' },
  { id: 'vscode', label: 'VS Code' },
];

/** The editor the top-bar setting names, remembered per browser. */
export function editor() {
  let saved = null;
  try { saved = localStorage.getItem(EDITOR_KEY); } catch (e) { saved = null; }
  return EDITORS.some((x) => x.id === saved) ? saved : EDITORS[0].id;
}

/** Remembers the choice and points every frame link on the page at the new editor. */
export function setEditor(id) {
  try { localStorage.setItem(EDITOR_KEY, id); } catch (e) { /* the choice lasts for this page only */ }
  for (const a of document.querySelectorAll('a[data-src-file]')) {
    a.href = editorHref(a.dataset.srcFile, Number(a.dataset.srcLine), id);
  }
}

/** idea://open?file=<path>&line=<n>, or vscode://file<path>:<n>. */
export function editorHref(file, line, which = editor()) {
  if (which === 'vscode') {
    const path = file.startsWith('/') ? file : '/' + file;
    return 'vscode://file' + encodeURI(path) + ':' + line;
  }
  return 'idea://open?file=' + encodeURIComponent(file) + '&line=' + line;
}

// The source is read on request and the files change under an agent's hands, so an answer
// is kept only long enough for a Live refresh not to ask again at once.
const TTL_MS = 10_000;
const cache = new Map();

function lookup(frame) {
  const now = Date.now();
  const hit = cache.get(frame);
  if (hit && now - hit.at < TTL_MS) return hit.promise;
  const promise = getJSON('/api/source', { frame }).catch(() => null);
  cache.set(frame, { at: now, promise });
  return promise;
}

/** The five lines, numbered, the frame's own line marked. */
function snippet(src) {
  const lines = src.lines || [];
  if (!lines.length) return null;
  return h('pre.src-lines', lines.map((text, i) => {
    const n = src.start + i;
    return h('span', { class: n === src.line ? 'src-line src-hit' : 'src-line' },
      h('span.src-no', String(n)), text + '\n');
  }));
}

/**
 * One frame: its text at once, then, when it resolves, a link to the editor in place of the
 * text and the lines around it underneath.
 */
export function codeFrame(frame) {
  const label = h('span', frame);
  const node = h('div.src-frame', h('div.mono.f-frame', label));
  lookup(frame).then((src) => {
    if (!src || !src.file) return;
    label.replaceWith(h('a.src-link', {
      href: editorHref(src.file, src.line),
      title: src.file + ':' + src.line,
      dataset: { srcFile: src.file, srcLine: String(src.line) },
    }, frame));
    const lines = snippet(src);
    if (lines) node.appendChild(lines);
  });
  return node;
}

// --- folding a stack trace (pages.adoc#stack-traces) -------------------------------------

/** The rules of a finding's `code` (findings.adoc#code), from /api/status.codeFrames. */
function rules() {
  const frames = (state.status || {}).codeFrames;
  if (!frames) return null;
  return { app: frames.appPackages || [], framework: frames.frameworkPrefixes || [] };
}

/**
 * `package.Class.method(File.java:41)` from a stack trace line, without the module or class
 * loader in front of it and the jar a logging framework adds behind it, as CodeFrames does.
 */
export function frameOf(line) {
  const at = /^\s*at\s+(.+)$/.exec(line);
  if (!at) return null;
  let value = at[1].trim();
  const marker = value.indexOf('~');
  if (marker > 0) value = value.slice(0, marker).trim();
  const paren = value.indexOf('(');
  const slash = value.lastIndexOf('/', paren < 0 ? value.length : paren);
  return slash >= 0 ? value.slice(slash + 1) : value;
}

/**
 * The package a framework frame is folded under: the framework prefix it matched, or, with an
 * allowlist, its first two segments. Null for an application frame.
 */
export function frameworkOf(frame, r) {
  if (r.app.length) {
    if (r.app.some((p) => frame.startsWith(p))) return null;
    return frame.split('.').slice(0, 2).join('.');
  }
  const lower = frame.toLowerCase();
  const prefix = r.framework.find((p) => lower.startsWith(p));
  return prefix ? prefix.replace(/\.$/, '') : null;
}

/** `app` (the default) or `all`, from the hash query's `frames`. */
export function framesMode(query) {
  return (query || {}).frames === 'all' ? 'all' : 'app';
}

/** The panel-head toggle, **App frames** | **All**; onChange gets 'app' or 'all'. */
export function framesToggle(mode, onChange) {
  const node = h('div.row', { style: { gap: '2px' }, role: 'group', 'aria-label': 'Stack frames' });
  const make = (id, label) => h('button.btn', {
    type: 'button', 'aria-pressed': String(mode === id),
    onclick: () => { if (mode !== id) onChange(id); },
  }, label);
  node.appendChild(make('app', 'App frames'));
  node.appendChild(make('all', 'All'));
  return node;
}

function lineSpan(cls, text) {
  const span = document.createElement('span');
  span.className = cls;
  span.textContent = text + '\n';
  return span;
}

/** `12 frames from org.springframework, org.apache`: the fold line's words. */
export function foldLabel(run) {
  const packages = [];
  for (const item of run) if (!packages.includes(item.pkg)) packages.push(item.pkg);
  const named = packages.slice(0, 3).join(', ') + (packages.length > 3 ? ' +' + (packages.length - 3) : '');
  return run.length + ' frames from ' + named;
}

/**
 * A stack trace in a <pre>, its application frames highlighted by the rules a finding's `code`
 * follows. In `app` mode every run of two or more framework frames is one dimmed line with the
 * count and the packages, which expands in place on a click or Enter; in `all` mode nothing is
 * folded. Without the rules (a server or a recording that does not send them) nothing is folded
 * or highlighted.
 */
export function foldedStack(text, mode = 'app') {
  const pre = document.createElement('pre');
  pre.className = 'stack';
  if (!text) return pre;
  const r = rules();
  let run = [];

  function flush() {
    if (!run.length) return;
    if (mode === 'all' || run.length < 2) {
      for (const item of run) pre.appendChild(lineSpan('st-frame', item.line));
    } else {
      const hidden = document.createElement('span');
      hidden.className = 'st-run';
      hidden.hidden = true;
      for (const item of run) hidden.appendChild(lineSpan('st-frame', item.line));
      const indent = /^\s*/.exec(run[0].line)[0];
      const fold = lineSpan('st-fold', indent + '⋯ ' + foldLabel(run));
      fold.setAttribute('role', 'button');
      fold.setAttribute('tabindex', '0');
      fold.setAttribute('aria-expanded', 'false');
      fold.title = 'Show these ' + run.length + ' frames';
      const open = () => { fold.remove(); hidden.hidden = false; };
      fold.addEventListener('click', open);
      fold.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
      });
      pre.appendChild(fold);
      pre.appendChild(hidden);
    }
    run = [];
  }

  for (const line of String(text).split('\n')) {
    const frame = frameOf(line);
    if (frame && r) {
      const pkg = frameworkOf(frame, r);
      if (pkg) { run.push({ line, pkg }); continue; }
      flush();
      pre.appendChild(lineSpan('st-own', line));
      continue;
    }
    flush();
    if (frame) pre.appendChild(lineSpan('st-frame', line));
    else if (/^\s*(Caused by|Suppressed):/.test(line)) pre.appendChild(lineSpan('st-cause', line));
    else if (/^\s*\.\.\. \d+ more/.test(line)) pre.appendChild(lineSpan('st-frame', line));
    else pre.appendChild(lineSpan('st-head', line));
  }
  flush();
  return pre;
}

// Code frames as links into the editor, with the lines around them (pages.adoc#code-frames).
// Each frame asks GET /api/source once; a frame that does not resolve stays plain text.
// A whole stack trace folds its framework frames by the same rules (pages.adoc#stack-traces),
// or, for a span event in the span drawer and on the Logs page, is a plain highlighted <pre>.

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

/** Where the manual says how to name the source roots (configuration.adoc#source-dirs). */
export const SOURCE_ROOTS_URL = 'https://spider-sense.benelog.net/configuration.html#source-dirs';

/** What an unresolved frame says in its title. */
const UNRESOLVED_TITLE = 'No source file for this frame under spidersense.source.dirs';

/** One frame's node, and a promise of whether it resolved to a file. */
function frameNode(frame) {
  const label = h('span', frame);
  const node = h('div.src-frame', h('div.mono.f-frame', label));
  const resolved = lookup(frame).then((src) => {
    if (!src || !src.file) {
      label.title = UNRESOLVED_TITLE;
      return false;
    }
    label.replaceWith(h('a.src-link', {
      href: editorHref(src.file, src.line),
      title: src.file + ':' + src.line,
      dataset: { srcFile: src.file, srcLine: String(src.line) },
    }, frame));
    const lines = snippet(src);
    if (lines) node.appendChild(lines);
    return true;
  });
  return { node, resolved };
}

/**
 * One frame: its text at once, then, when it resolves, a link to the editor in place of the
 * text and the lines around it underneath. A frame that does not resolve says so in its title.
 */
export function codeFrame(frame) {
  return frameNode(frame).node;
}

/** The line under a list none of whose frames resolved: how to make them resolve. */
export function sourceHint(which = editor()) {
  const name = (EDITORS.find((x) => x.id === which) || EDITORS[0]).label;
  return h('div.muted.src-hint', { style: { fontSize: '11px', marginTop: '4px' } },
    'No source found for these frames. Set ', h('code', 'spidersense.source.dirs'),
    ' to your source roots to open them in ' + name + ' (',
    h('a', { href: SOURCE_ROOTS_URL, target: '_blank', rel: 'noopener' }, 'Source roots'), ').');
}

/**
 * The frames as code frames, appended to `container`, and, once every one has been asked and
 * none resolved, the hint (pages.adoc#code-frames). Returns the container.
 */
export function codeFrames(container, frames) {
  const parts = frames.map(frameNode);
  for (const part of parts) container.appendChild(part.node);
  if (parts.length) {
    Promise.all(parts.map((part) => part.resolved)).then((resolved) => {
      if (!resolved.some(Boolean)) container.appendChild(sourceHint());
    });
  }
  return container;
}

// --- folding a stack trace (pages.adoc#stack-traces) -------------------------------------

/** The rules of a finding's `code` (findings.adoc#code), from /api/status.codeFrames. */
function rules(status) {
  const frames = (status || {}).codeFrames;
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

/** A finding keeps at most this many frames in its `code` (findings.adoc#code). */
const MAX_APP_FRAMES = 5;

/**
 * The application frames of one stack, top first, each once and at most five, by the rules a
 * finding's `code` follows: what the span drawer lists above a `code.stacktrace`. Empty without
 * the rules, as nothing can be told apart then.
 */
export function appFrames(text, status = state.status) {
  const r = rules(status);
  const out = [];
  if (!r || !text) return out;
  for (const line of String(text).split('\n')) {
    const frame = frameOf(line);
    if (!frame || frameworkOf(frame, r) !== null || out.includes(frame)) continue;
    out.push(frame);
    if (out.length >= MAX_APP_FRAMES) break;
  }
  return out;
}

/** `app` (the default) or `all`, from the hash query's `frames`. */
export function framesMode(query) {
  return (query || {}).frames === 'all' ? 'all' : 'app';
}

/**
 * What one line of a stack trace is: an `at` frame, the `Caused by:` or `Suppressed:` of a cause,
 * the `... n more` of frames a cause shares with the trace above it, or a head (the exception
 * and its message, or a continuation of the message).
 */
export function classifyLine(line) {
  if (frameOf(line) !== null) return 'frame';
  if (/^\s*(Caused by|Suppressed):/.test(line)) return 'cause';
  if (/^\s*\.\.\. \d+ more/.test(line)) return 'more';
  return 'head';
}

/** The class of a line that is not an application frame: a cause, or a dimmed frame, or a head. */
const LINE_CLASS = { frame: 'st-frame', more: 'st-frame', cause: 'st-cause', head: 'st-head' };

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
export function foldedStack(text, mode = 'app', status = state.status) {
  const pre = document.createElement('pre');
  pre.className = 'stack';
  if (!text) return pre;
  const r = rules(status);
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
    pre.appendChild(lineSpan(LINE_CLASS[classifyLine(line)], line));
  }
  flush();
  return pre;
}

// --- the plain highlighted stack trace --------------------------------------------------

/**
 * A stack trace in a <pre>, nothing folded: frames whose package shares the first non-JDK
 * frame's two top-level segments are "own" frames, and the other frames are dimmed.
 */
export function stackTrace(text, cls) {
  const pre = document.createElement('pre');
  pre.className = 'stack' + (cls ? ' ' + cls : '');
  if (!text) return pre;
  const lines = String(text).split('\n');
  const own = ownPrefix(lines);
  for (const line of lines) {
    const frame = frameOf(line);
    pre.appendChild(lineSpan(own && frame && frame.startsWith(own) ? 'st-own' : LINE_CLASS[classifyLine(line)], line));
  }
  return pre;
}

/**
 * The package prefix of the first frame outside the JDK, to two segments (com.example.), read
 * after the module or class loader in front of it (`java.base/`, `app//`).
 */
function ownPrefix(lines) {
  for (const line of lines) {
    const frame = frameOf(line);
    if (!frame) continue;
    const parts = frame.split('(')[0].split('.');
    if (parts.length < 3) continue;
    const head = parts[0];
    if (head === 'java' || head === 'javax' || head === 'jdk' || head === 'sun') continue;
    return parts.slice(0, 2).join('.') + '.';
  }
  return null;
}

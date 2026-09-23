// Code frames as links into the editor, with the lines around them (docs/ui.md, "Code frames").
// Each frame asks GET /api/source once; a frame that does not resolve stays plain text.

import { getJSON } from './api.js';
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

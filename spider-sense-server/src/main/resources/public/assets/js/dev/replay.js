// A fetch and EventSource shim that answers every GET /api/… request from a
// recorded snapshot: the JSON files scripts/demo-site.mjs captured from a
// running Spider Sense, so the UI can be published as a static page with
// real data on it and no server behind it (docs/ui.md).
//
// Loaded by app.js when <html data-snapshot="…"> names the snapshot directory
// (the assembled demo site) or when the page URL carries ?snapshot=<dir>.
// The snapshot is one window, and the clock is frozen at its end, so every
// range the top bar offers ends where the recording ended.

import { RANGES } from '../api.js';

const base = (document.documentElement.dataset.snapshot
  || new URLSearchParams(location.search).get('snapshot') || 'data').replace(/\/?$/, '/');

const manifest = await fetch(base + 'manifest.json', { headers: { accept: 'application/json' } })
  .then((res) => {
    if (!res.ok) throw new Error('snapshot manifest: ' + res.status);
    return res.json();
  });

const files = manifest.files || {};
const frozenNow = (manifest.window && manifest.window.to) || Date.now();
const frozenFrom = (manifest.window && manifest.window.from) || frozenNow - 15 * 60 * 1000;
Date.now = () => frozenNow;

// The default range becomes the recording itself, so every chart covers the
// recorded minutes and no empty ones before them; the other ranges stay, and
// all of them end where the recording ended.
const minutes = Math.max(1, Math.round((frozenNow - frozenFrom) / 60000));
const preset = RANGES.find((r) => r.id === '15m');
if (preset) {
  preset.ms = frozenNow - frozenFrom;
  preset.label = 'The recording (' + minutes + ' min)';
}

// Parameters dropped one at a time, in this order, until a recorded answer
// matches. The window is never part of a key: the snapshot has only one. The
// rest are the filters a page can add; a list without the filter is the
// nearest answer the recording has, and the miss is logged so the capture
// can learn about it.
const FALLBACK = ['before', 'q', 'minMs', 'maxMs', 'traceId', 'severity', 'status', 'endpointId',
  'step', 'rate', 'until', 'hideAcked', 'sort', 'limit', 'service'];

function keyOf(path, params) {
  const names = [...params.keys()].sort();
  const parts = [];
  for (const name of names) parts.push(name + '=' + params.get(name));
  return path + (parts.length ? '?' + parts.join('&') : '');
}

function resolve(url) {
  const u = new URL(url, location.href);
  const params = new URLSearchParams(u.search);
  params.delete('from');
  params.delete('to');
  for (const key of [...params.keys()]) if (key.startsWith('attr.')) params.delete(key);
  const asked = keyOf(u.pathname, params);
  let key = asked;
  if (files[key]) return { file: files[key], asked, key };
  for (const name of FALLBACK) {
    if (!params.has(name)) continue;
    params.delete(name);
    key = keyOf(u.pathname, params);
    if (files[key]) {
      console.info('[snapshot] ' + asked + ' answered by ' + key);
      return { file: files[key], asked, key };
    }
  }
  return { file: null, asked, key: null };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

const realFetch = globalThis.fetch.bind(globalThis);

globalThis.fetch = (input, init) => {
  const url = typeof input === 'string' ? input : input.url;
  const path = new URL(url, location.href).pathname;
  if (!path.startsWith('/api/')) return realFetch(input, init);
  const method = ((init && init.method) || (typeof input !== 'string' && input.method) || 'GET').toUpperCase();
  if (method !== 'GET') {
    return Promise.resolve(json({ error: 'This is a recording of the demo; nothing on it can be changed.' }, 405));
  }
  const found = resolve(url);
  if (!found.file) {
    console.warn('[snapshot] not recorded: ' + found.asked);
    return Promise.resolve(json({ error: 'Not in the recording: ' + found.asked }, 404));
  }
  return realFetch(base + found.file, { headers: { accept: 'application/json' } });
};

class SnapshotEventSource {
  constructor() { this.readyState = SnapshotEventSource.CLOSED; this.onerror = null; }
  addEventListener() {}
  removeEventListener() {}
  close() {}
}
SnapshotEventSource.CONNECTING = 0;
SnapshotEventSource.OPEN = 1;
SnapshotEventSource.CLOSED = 2;
globalThis.EventSource = SnapshotEventSource;

// One line at the top of the page says what this is, so nobody waits for it to move.
const note = document.createElement('div');
note.className = 'snapshot-note';
note.setAttribute('role', 'note');
const when = new Date(manifest.recordedAt || frozenNow);
note.append('A recording of the demo, captured ' + when.toISOString().slice(0, 16).replace('T', ' ')
  + ' UTC: the four example applications under one Spider Sense. Nothing here is live, and nothing can be changed.');
if (manifest.source && manifest.source.url) {
  const link = document.createElement('a');
  link.href = manifest.source.url;
  link.target = '_blank';
  link.rel = 'noopener';
  link.textContent = 'this DoltHub database';
  note.append(' The rows behind it are in ', link, '.');
}
const content = document.getElementById('content');
if (content) content.insertBefore(note, content.firstChild);
document.title = 'Spider Sense demo';

// The load cycle every page shares (ui.adoc#live-refresh): the newest request wins, nothing
// paints after the page is gone, and a failure puts the error box and its retry where the
// answer would have gone.

import { requestSequence } from './api.js';
import { h, fill, spinner, errorBox } from './ui.js';

/**
 * A page's panels, put in place when the first answer arrives. Until then the page shows a
 * spinner; `replace` puts something else there (an empty state, an error box) and the next
 * `build` puts the panels back. `parts` returns the panels.
 */
export function skeleton(root, parts) {
  const page = h('div', { style: { display: 'grid', gap: 'var(--gap)' } }, spinner());
  root.appendChild(page);
  let built = false;
  return {
    page,
    get built() { return built; },
    build() {
      if (built) return;
      built = true;
      fill(page, ...parts());
    },
    replace(...content) {
      built = false;
      fill(page, ...content);
    },
  };
}

/**
 * One stream of a page's loads. `load(...args)` calls `fetch(...args)`, and `paint(result, ...args)`
 * only while the page is alive and no newer load has started since. A failure of either calls
 * `onError(error, ...args)` first, which resets what the page cached and returns false to keep
 * what is on screen; otherwise the error box goes into `body`, a node or a skeleton. Its Try again
 * calls `retry`, by default a load without arguments.
 */
export function pageLoader({ fetch, paint, body, onError, retry }) {
  let destroyed = false;
  const startRequest = requestSequence();
  const self = {};

  async function load(...args) {
    // A Live tick leaves a load that is still out alone: asking again would only queue
    // another slow answer behind it, and the page keeps what it shows until it comes.
    if (inLiveTick && busyLoaders.has(self)) return;
    const isNewest = startRequest();
    setBusy(self, true);
    try {
      const result = await fetch(...args);
      if (destroyed || !isNewest()) return;
      paint(result, ...args);
    } catch (e) {
      if (destroyed || !isNewest()) return;
      if (onError && onError(e, ...args) === false) return;
      const box = errorBox(e, retry || (() => load()));
      if (body.replace) body.replace(box);
      else fill(body, box);
    } finally {
      if (destroyed || isNewest()) setBusy(self, false);
    }
  }

  return Object.assign(self, {
    load,
    destroy: () => { destroyed = true; setBusy(self, false); },
    isDestroyed: () => destroyed,
  });
}

// --- busy -----------------------------------------------------------------

/** The loaders of the pages on screen whose newest load has not answered yet. */
const busyLoaders = new Set();
const busyListeners = new Set();

function setBusy(loader, on) {
  const was = busyLoaders.size > 0;
  if (on) busyLoaders.add(loader);
  else busyLoaders.delete(loader);
  const now = busyLoaders.size > 0;
  if (was !== now) for (const listener of busyListeners) listener(now);
}

/** Whether a load of the page on screen is still waiting for its answer. */
export function pageBusy() {
  return busyLoaders.size > 0;
}

let inLiveTick = false;

/**
 * Runs `refresh` as a Live tick (ui.adoc#live-refresh): a loader whose previous load has not
 * answered skips the load the tick asks for. A filter or range change is no tick, and always loads.
 */
export function liveRefresh(refresh) {
  inLiveTick = true;
  try { refresh(); } finally { inLiveTick = false; }
}

/** Calls `listener(busy)` whenever pageBusy() changes. */
export function onBusyChange(listener) {
  busyListeners.add(listener);
  return () => busyListeners.delete(listener);
}

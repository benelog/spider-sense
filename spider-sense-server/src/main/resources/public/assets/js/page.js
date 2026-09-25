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

  async function load(...args) {
    const isNewest = startRequest();
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
    }
  }

  return {
    load,
    destroy: () => { destroyed = true; },
    isDestroyed: () => destroyed,
  };
}

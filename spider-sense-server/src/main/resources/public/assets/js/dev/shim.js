// What the mock (mock.js) and the recorded demo (replay.js) share: the fetch they stand in
// front of, the answers they build, and an EventSource that never talks to a server.

/** The browser's fetch, for everything outside /api/ and for the recording's own reads. */
export const realFetch = globalThis.fetch.bind(globalThis);

/** A JSON answer. */
export function reply(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8' },
  });
}

/** A text rendering (cli.adoc#text-rendering), as Copy as Markdown asks for it. */
export function replyText(text, status = 200) {
  return new Response(text, { status, headers: { 'content-type': 'text/markdown; charset=utf-8' } });
}

/** A request's JSON body, `{}` when it has none or it does not parse. */
export function bodyOf(init) {
  try { return JSON.parse((init && init.body) || '{}') || {}; } catch (e) { return {}; }
}

/**
 * Puts `answer(url, method, init)` in front of every request to /api/; anything else goes to
 * the browser's fetch.
 */
export function shimFetch(answer) {
  globalThis.fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
    if (!url.pathname.startsWith('/api/')) return realFetch(input, init);
    const method = ((init && init.method) || (typeof input !== 'string' && input.method) || 'GET').toUpperCase();
    return answer(url, method, init);
  };
}

/**
 * An EventSource with listeners and no connection: closed unless a subclass opens it, and
 * `dispatch(type, data)` delivers an event as the server's stream would.
 */
export class EventSourceStub {
  constructor(url, readyState = EventSourceStub.CLOSED) {
    this.url = url;
    this.readyState = readyState;
    this.onerror = null;
    this.handlers = new Map();
  }
  addEventListener(type, fn) {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type).add(fn);
  }
  removeEventListener(type, fn) {
    const set = this.handlers.get(type);
    if (set) set.delete(fn);
  }
  dispatch(type, data) {
    const set = this.handlers.get(type);
    if (!set) return;
    const ev = { type, data: JSON.stringify(data) };
    for (const fn of set) { try { fn(ev); } catch (e) { console.error(e); } }
  }
  close() { this.readyState = EventSourceStub.CLOSED; }
}
EventSourceStub.CONNECTING = 0;
EventSourceStub.OPEN = 1;
EventSourceStub.CLOSED = 2;

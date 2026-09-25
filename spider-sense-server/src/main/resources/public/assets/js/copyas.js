// Copy as Markdown and Copy CLI line (pages.adoc#copy-as-markdown): what a person hands to
// an agent from a page, as the bytes the agent would have read itself.

import { getText, state } from './api.js';
import { h, copyText, toast, errorText } from './ui.js';

/** The jar the CLI line names: /api/status.jar, else the name the manual uses. */
function jar() {
  const path = (state.status || {}).jar;
  if (!path) return 'spider-sense.jar';
  return /^[\w@%+=:,./-]+$/.test(path) ? path : "'" + path.replace(/'/g, "'\\''") + "'";
}

/**
 * `java -jar <jar> <command> --since=<from> --until=<to>`, with `--service` when one is named:
 * the same window as the page, as epoch milliseconds, so the CLI answers for what is on screen.
 */
export function cliLine(command, window, service) {
  return 'java -jar ' + jar() + ' ' + command
    + ' --since=' + Math.round(window.from) + ' --until=' + Math.round(window.to)
    + (service ? ' --service=' + (/^[\w.:-]+$/.test(service) ? service : "'" + service.replace(/'/g, "'\\''") + "'") : '');
}

/**
 * The two buttons. `markdown` is the path and query of a text rendering, fetched when the
 * button is pressed; `cli` is the line to copy, built when the button is pressed.
 */
export function copyButtons({ markdown, cli }) {
  const md = h('button.btn.btn-ghost', { type: 'button', title: 'The text rendering an agent reads' }, 'Copy as Markdown');
  md.addEventListener('click', async (e) => {
    e.stopPropagation();
    md.disabled = true;
    try {
      const { path, query } = markdown();
      await copyText(await getText(path, query), md);
    } catch (err) {
      toast(errorText(err));
    } finally {
      md.disabled = false;
    }
  });
  const line = h('button.btn.btn-ghost', { type: 'button', title: 'The command that answers the same window' }, 'Copy CLI line');
  line.addEventListener('click', (e) => { e.stopPropagation(); copyText(cli(), line); });
  return h('span.row.copy-as', { style: { gap: '6px' } }, md, line);
}

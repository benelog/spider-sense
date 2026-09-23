// The agent demo (docs/design.md, "The agent demo"): a recorded session of Claude Code or
// Codex CLI, read from the DoltHub database of the published demo and replayed in a
// terminal drawn after the agent's own. The page is a pure function of the play time:
// every step is shown, running or done according to where the clock is, so seeking is
// a render at another time.

const html = document.documentElement;
const params = new URLSearchParams(location.search);
const SESSION = params.get('session') || html.dataset.session;
const [DATABASE, REF = 'main'] = (params.get('dolthub') || html.dataset.dolthub).split('@');
const BASE = html.dataset.base;   // the agent demo's root, relative to this page; absent when a ?session= page is opened
const endpoint = 'https://www.dolthub.com/api/v1alpha1/' + DATABASE + '/' + encodeURIComponent(REF) + '?q=';
const REPO = 'https://github.com/benelog/spider-sense';

const TEXT = {
  en: {
    title: 'Agent demo', play: 'Play', pause: 'Pause', restart: 'Restart', end: 'Skip to the end',
    caption: (s) => `<strong>${esc(agentName(s.agent))} ${esc(s.agent_version || '')}</strong> (${esc(s.model || '')}), `
      + `run for real in a checkout of this repository on ${esc(day(s.recorded_at))} and replayed from its event stream: `
      + `every command, output and answer below is what the agent printed. Waits are shortened; the clock shows the recorded time, `
      + `${esc(duration(s.duration_ms))} in all.`,
    notes: (s) => `The prompt is the README's <a href="${REPO}#hand-it-to-an-agent">Hand it to an agent</a>, and the skill it names is `
      + `<a href="${REPO}/blob/main/skills/spider-sense/SKILL.md">skills/spider-sense/SKILL.md</a>. `
      + `The session is two tables of the <a href="${dolthubPage()}">DoltHub database</a> behind the <a href="${siteRoot()}demo/">recorded UI demo</a>, `
      + `<code>agent_session</code> and <code>agent_event</code>; <code>scripts/agent-demo.sh</code> records one.`,
    loading: 'Loading the recording from DoltHub…',
    failed: 'The recording could not be read from DoltHub: ',
  },
  ko: {
    title: '에이전트 데모', play: '재생', pause: '일시 정지', restart: '처음부터', end: '끝으로',
    caption: (s) => `<strong>${esc(agentName(s.agent))} ${esc(s.agent_version || '')}</strong>(${esc(s.model || '')})를 `
      + `이 저장소에서 실제로 실행해 ${esc(day(s.recorded_at))}에 녹화한 이벤트 스트림을 재생합니다. `
      + `아래의 명령, 출력, 답변은 모두 에이전트가 실제로 출력한 그대로입니다. 기다리는 시간은 줄였고, 시계는 녹화된 시각을 보여 줍니다. `
      + `실제로 걸린 시간은 ${esc(duration(s.duration_ms, 'ko'))}입니다.`,
    notes: (s) => `프롬프트는 README의 <a href="${REPO}#hand-it-to-an-agent">Hand it to an agent</a>를 옮긴 것이고, `
      + `프롬프트가 가리키는 skill은 <a href="${REPO}/blob/main/skills/spider-sense/SKILL.md">skills/spider-sense/SKILL.md</a>입니다. `
      + `세션은 <a href="${siteRoot()}demo/">녹화된 UI 데모</a>와 같은 <a href="${dolthubPage()}">DoltHub 데이터베이스</a>의 `
      + `<code>agent_session</code>, <code>agent_event</code> 두 테이블에 있고, <code>scripts/agent-demo.sh</code>로 녹화합니다.`,
    loading: 'DoltHub에서 녹화를 읽는 중…',
    failed: 'DoltHub에서 녹화를 읽지 못했습니다: ',
  },
};

const LANG = SESSION.split('-')[1] === 'ko' ? 'ko' : 'en';
const T = TEXT[LANG];

// --- small things ----------------------------------------------------------------

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function agentName(agent) {
  return agent === 'codex' ? 'Codex CLI' : 'Claude Code';
}

function day(ms) {
  return new Date(Number(ms)).toLocaleDateString('sv');   // YYYY-MM-DD, in the viewer's time zone
}

function duration(ms, lang = 'en') {
  const s = Math.max(0, Math.round(Number(ms) / 1000));
  const m = Math.floor(s / 60);
  if (lang === 'ko') return m ? m + '분 ' + (s % 60) + '초' : s + '초';
  return m ? m + 'm ' + (s % 60) + 's' : s + 's';
}

function clock(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

function dolthubPage() {
  return 'https://www.dolthub.com/repositories/' + DATABASE;
}

function siteRoot() {
  return BASE ? BASE + '../' : '/';
}

async function sql(query) {
  const res = await fetch(endpoint + encodeURIComponent(query), { headers: { accept: 'application/json' } });
  const body = await res.json();
  if (body.query_execution_status !== 'Success') throw new Error(body.query_execution_message || res.status);
  return body.rows || [];
}

async function loadSession() {
  if (params.get('rows')) return rowsOf(await (await fetch(params.get('rows'))).json());
  const id = SESSION.replace(/'/g, "''");
  const [session] = await sql(`SELECT * FROM agent_session WHERE id = '${id}'`);
  if (!session) throw new Error('no session ' + SESSION);
  const events = [];
  for (let from = 0; ; from += 1000) {
    const rows = await sql(`SELECT * FROM agent_event WHERE session_id = '${id}' ORDER BY seq LIMIT 1000 OFFSET ${from}`);
    events.push(...rows);
    if (rows.length < 1000) break;
  }
  return rowsOf({ session, events });
}

/** The rows with their numbers and flags read back from DoltHub's strings. */
function rowsOf({ session, events }) {
  for (const ev of events) {
    ev.at_ms = Number(ev.at_ms);
    ev.end_ms = ev.end_ms === null || ev.end_ms === '' || ev.end_ms === undefined ? ev.at_ms : Number(ev.end_ms);
    ev.error = ev.error === '1' || ev.error === 1 || ev.error === 'true' || ev.error === true;
    ev.output = ev.output || '';
    ev.title = ev.title || '';
    try { ev.args = ev.input ? JSON.parse(ev.input) : {}; } catch (e) { ev.args = {}; }
  }
  return { session, events };
}

// --- markdown, the part an agent's answer uses -----------------------------------

function inline(text) {
  let out = '';
  const re = /(`+)([\s\S]*?)\1|\*\*([^*]+)\*\*|__([^_]+)__|\*([^*\s][^*]*)\*|\[([^\]]+)\]\(([^)\s]+)\)/g;
  let at = 0;
  let m;
  while ((m = re.exec(text))) {
    out += esc(text.slice(at, m.index));
    if (m[1]) out += '<code>' + esc(m[2]) + '</code>';
    else if (m[3] || m[4]) out += '<strong>' + inline(m[3] || m[4]) + '</strong>';
    else if (m[5]) out += '<em>' + inline(m[5]) + '</em>';
    else if (m[6]) {
      const href = linkOf(m[7]);
      out += href ? '<a href="' + esc(href) + '" target="_blank" rel="noopener">' + inline(m[6]) + '</a>' : '<span class="link">' + inline(m[6]) + '</span>';
    }
    at = re.lastIndex;
  }
  return out + esc(text.slice(at));
}

/**
 * Where a link the agent printed leads on the web: a file of the checkout (with a
 * `:line`) to that line on GitHub, a page elsewhere to itself, and the demo's own
 * localhost, which was only there while it ran, nowhere.
 */
function linkOf(url) {
  if (/^https?:\/\/(127\.0\.0\.1|localhost)[:/]/.test(url)) return null;
  if (/^https?:/.test(url)) return url;
  const m = /^(?:.*?\/spider-sense\/)?\.?\/?([^:#]+)(?::(\d+))?/.exec(url);
  return m ? REPO + '/blob/main/' + m[1] + (m[2] ? '#L' + m[2] : '') : null;
}

function cells(line) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map((c) => c.trim());
}

const LIST = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;

/** List items as nested lists, by indentation; an ordered list keeps the number it starts at. */
function listHtml(entries) {
  let k = 0;
  const build = (level) => {
    const first = entries[k];
    const tag = first.ordered ? 'ol' : 'ul';
    let html = '<' + tag + (first.ordered && first.start > 1 ? ' start="' + first.start + '"' : '') + '>';
    while (k < entries.length && entries[k].indent >= level) {
      if (entries[k].indent > level) {
        html = html.replace(/<\/li>$/, '') + build(entries[k].indent) + '</li>';
        continue;
      }
      html += '<li>' + entries[k].text.split('\n').map(inline).join('<br>') + '</li>';
      k++;
    }
    return html + '</' + tag + '>';
  };
  let out = '';
  while (k < entries.length) out += build(entries[k].indent);
  return out;
}

function markdown(text) {
  const lines = text.replace(/\r/g, '').split('\n');
  const out = [];
  let i = 0;
  let para = [];
  const flush = () => {
    if (para.length) out.push('<p>' + para.map(inline).join('<br>') + '</p>');
    para = [];
  };
  while (i < lines.length) {
    const line = lines[i];
    const fence = /^\s*(```|~~~)/.exec(line);
    if (fence) {
      flush();
      const body = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith(fence[1])) body.push(lines[i++]);
      i++;
      out.push('<pre><code>' + esc(body.join('\n')) + '</code></pre>');
      continue;
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      flush();
      out.push('<h' + Math.min(4, heading[1].length) + '>' + inline(heading[2]) + '</h' + Math.min(4, heading[1].length) + '>');
      i++;
      continue;
    }
    if (/^\s*\|/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
      flush();
      const head = cells(line);
      i += 2;
      const rows = [];
      while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(cells(lines[i++]));
      out.push('<div class="table"><table><thead><tr>' + head.map((c) => '<th>' + inline(c) + '</th>').join('') + '</tr></thead><tbody>'
        + rows.map((r) => '<tr>' + r.map((c) => '<td>' + inline(c) + '</td>').join('') + '</tr>').join('') + '</tbody></table></div>');
      continue;
    }
    if (LIST.test(line)) {
      flush();
      const entries = [];
      while (i < lines.length) {
        const m = LIST.exec(lines[i]);
        if (m) {
          entries.push({ indent: m[1].length, ordered: /\d/.test(m[2]), start: parseInt(m[2], 10), text: m[3] });
          i++;
        } else if (lines[i].trim() && /^\s{2,}/.test(lines[i]) && entries.length) {
          entries[entries.length - 1].text += '\n' + lines[i].trim();
          i++;
        } else break;
      }
      out.push(listHtml(entries));
      continue;
    }
    if (/^>\s?/.test(line)) {
      flush();
      const body = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) body.push(lines[i++].replace(/^>\s?/, ''));
      out.push('<blockquote>' + markdown(body.join('\n')) + '</blockquote>');
      continue;
    }
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
      flush();
      out.push('<p class="faint">' + '─'.repeat(20) + '</p>');
      i++;
      continue;
    }
    if (!line.trim()) flush();
    else para.push(line);
    i++;
  }
  flush();
  return out.join('');
}

// --- the steps, as each agent draws them -----------------------------------------

const PREVIEW = 4;   // output lines shown before "… +N lines"

function relative(path, cwd) {
  const p = String(path || '');
  return cwd && p.startsWith(cwd + '/') ? p.slice(cwd.length + 1) : p;
}

function outputLines(text) {
  const lines = String(text).replace(/\s+$/, '').split('\n');
  return lines.length === 1 && lines[0] === '' ? [] : lines;
}

/** The output under a step: its first lines, and the rest behind a click. */
function subBlock(prefix, lines, cls = '', expanded = false) {
  const shown = expanded ? lines : lines.slice(0, PREVIEW);
  const rest = lines.length - shown.length;
  const more = rest > 0 ? '\n<span class="more" data-expand>… +' + rest + ' lines (click to expand)</span>' : '';
  return '<div class="sub"><span>' + prefix + '</span><pre class="' + cls + '">' + esc(shown.join('\n')) + more + '</pre></div>';
}

function claudeLabel(ev, cwd) {
  const a = ev.args || {};
  switch (ev.tool) {
    case 'Bash': {
      const lines = String(a.command || ev.title).split('\n');
      return 'Bash(' + lines.slice(0, 2).join('\n') + (lines.length > 2 ? ' …' : '') + ')';
    }
    case 'Read': return 'Read(' + relative(a.file_path || ev.title, cwd) + ')';
    case 'Grep': return 'Search(pattern: "' + (a.pattern || '') + '"' + (a.path ? ', path: "' + relative(a.path, cwd) + '"' : '') + ')';
    case 'Glob': return 'Search(pattern: "' + (a.pattern || '') + '"' + (a.path ? ', path: "' + relative(a.path, cwd) + '"' : '') + ')';
    case 'Monitor': return 'Monitor(' + (a.description || ev.title) + ')';
    case 'Task':
    case 'Agent': return 'Task(' + (a.description || ev.title) + ')';
    default: return ev.tool + '(' + String(ev.title).split('\n')[0] + ')';
  }
}

function claudeResult(ev, expanded) {
  const lines = outputLines(ev.output);
  if (ev.error) return subBlock('  ⎿  ', lines.length ? lines : ['Error'], 'err', expanded);
  if (/^Command running in background with ID/.test(ev.output)) return subBlock('  ⎿  ', ['Running in the background (↓ to manage)']);
  if (ev.tool === 'Read') return subBlock('  ⎿  ', ['Read ' + lines.length + ' lines']);
  if (ev.tool === 'Grep' || ev.tool === 'Glob') {
    const n = lines.filter((l) => l.trim() && !/^Found \d+/.test(l)).length;
    return subBlock('  ⎿  ', ['Found ' + n + ' ' + (ev.tool === 'Glob' || (ev.args && ev.args.output_mode !== 'content') ? 'files' : 'lines')]);
  }
  return subBlock('  ⎿  ', lines.length ? lines : ['(No content)'], '', expanded);
}

/** Codex draws a read, a search or a listing as a line of "Explored"; anything else is "Ran". */
function explored(command) {
  const c = command.trim();
  if (/[;&]|\|\||>/.test(c)) return null;
  const words = c.match(/'[^']*'|"[^"]*"|\S+/g) || [];
  const unq = (w) => w.replace(/^['"]|['"]$/g, '');
  const base = (w) => unq(w).split('/').pop();
  const pipe = c.split('|').map((s) => s.trim());
  const first = pipe[0].split(/\s+/)[0];
  const last = (s) => {
    const ws = s.match(/'[^']*'|"[^"]*"|\S+/g) || [];
    return ws.filter((w, i) => i > 0 && !w.startsWith('-') && !/^'?\d+(,\d+)?p'?$/.test(w)).pop();
  };
  if (pipe.length > 2) return null;
  if (['cat', 'nl', 'head', 'tail', 'sed', 'wc'].includes(first)) {
    const file = last(pipe[0]) || (pipe[1] && last(pipe[1]));
    return file ? 'Read ' + base(file) : null;
  }
  if (first === 'rg' && words.includes('--files')) return 'List ' + (unq(last(pipe[0]) || '.'));
  if (first === 'rg' || first === 'grep') {
    const args = (pipe[0].match(/'[^']*'|"[^"]*"|\S+/g) || []).slice(1).filter((w) => !w.startsWith('-'));
    if (!args.length) return null;
    return 'Search ' + unq(args[0]) + (args[1] ? ' in ' + unq(args[args.length - 1]) : '');
  }
  if (first === 'ls' || first === 'find' || first === 'tree') return 'List ' + unq(last(pipe[0]) || '.');
  return null;
}

// --- the timeline ------------------------------------------------------------------

const CAP = 1600;          // no pause between two steps is longer than this, in play time
const STEP = 350;          // nor shorter
const REVEAL = 220;        // characters of an answer shown per second of play time
const REVEAL_MAX = 7000;   // however long it is

class Timeline {
  constructor(session, events) {
    this.session = session;
    this.agent = session.agent;
    this.cwd = session.cwd;
    this.prompt = events.find((e) => e.kind === 'user')?.output || session.prompt;
    this.typing = Math.min(2600, this.prompt.length * 12);
    this.offset = this.typing + 500;
    this.items = this.group(events.filter((e) => e.kind !== 'user'));
    this.map = this.mapping();
    const last = Math.max(Number(session.duration_ms), ...this.items.map((it) => it.finish ?? it.start));
    this.last = last;
    this.end = this.play(last) + 1200;
  }

  group(events) {
    const items = [];
    for (const ev of events) {
      if (ev.kind === 'thinking' && !ev.output.trim()) {
        items.push({ type: 'think', ev, start: ev.at_ms, finish: ev.end_ms });
        continue;
      }
      if (this.agent === 'codex' && ev.kind === 'tool' && ev.tool === 'shell') {
        const line = explored(ev.title);
        const prev = items[items.length - 1];
        if (line) {
          if (prev && prev.type === 'explore') prev.members.push({ ev, line });
          else items.push({ type: 'explore', members: [{ ev, line }], start: ev.at_ms });
          continue;
        }
      }
      items.push({ type: ev.kind, ev, start: ev.at_ms, finish: ev.end_ms });
    }
    for (const it of items) {
      if (it.type === 'explore') it.finish = Math.max(...it.members.map((m) => m.ev.end_ms));
    }
    return items;
  }

  /** Real time to play time: every gap between two moments something happened, capped, and long enough to read an answer. */
  mapping() {
    const points = new Map();
    const need = (t, ms) => points.set(t, Math.max(points.get(t) || 0, ms));
    need(0, 0);
    for (const it of this.items) {
      need(it.start, it.type === 'text' ? Math.min(REVEAL_MAX, (it.ev.output.length / REVEAL) * 1000) : 0);
      if (it.type === 'explore') for (const m of it.members) { need(m.ev.at_ms, 0); need(m.ev.end_ms, 0); }
      else need(it.finish ?? it.start, 0);
    }
    need(Math.max(Number(this.session.duration_ms), ...this.items.map((it) => it.finish ?? it.start)), 0);
    const real = [...points.keys()].sort((a, b) => a - b);
    const play = [this.offset];
    for (let i = 1; i < real.length; i++) {
      const gap = real[i] - real[i - 1];
      play.push(play[i - 1] + Math.max(Math.min(gap, CAP), Math.min(gap, STEP), points.get(real[i - 1])));
    }
    return { real, play };
  }

  play(r) {
    const { real, play } = this.map;
    if (r <= real[0]) return play[0];
    for (let i = 1; i < real.length; i++) {
      if (r <= real[i]) return play[i - 1] + (play[i] - play[i - 1]) * ((r - real[i - 1]) / ((real[i] - real[i - 1]) || 1));
    }
    return play[play.length - 1];
  }

  real(p) {
    const { real, play } = this.map;
    if (p <= play[0]) return 0;
    for (let i = 1; i < play.length; i++) {
      if (p <= play[i]) return real[i - 1] + (real[i] - real[i - 1]) * ((p - play[i - 1]) / ((play[i] - play[i - 1]) || 1));
    }
    return real[real.length - 1];
  }
}

// --- drawing -----------------------------------------------------------------------

const CLAUDE_SPIN = ['·', '✢', '✳', '✶', '✻', '✽', '✻', '✶', '✳', '✢'];
const CLAUDE_VERBS = ['Sensing', 'Tingling', 'Weaving', 'Spinning', 'Untangling', 'Sleuthing', 'Pondering'];

function modelName(model) {
  const m = /^claude-(opus|sonnet|haiku|fable)-(\d+)(?:-(\d+))?/.exec(model || '');
  if (!m) return model || '';
  return m[1][0].toUpperCase() + m[1].slice(1) + ' ' + m[2] + (m[3] && m[3].length < 3 ? '.' + m[3] : '');
}

function welcome(s) {
  const cwd = String(s.cwd || '').replace(/^\/home\/me/, '~');
  if (s.agent === 'codex') {
    return '<div class="welcome"><div><span class="dim">&gt;_</span> <span class="bold">OpenAI Codex</span> <span class="dim">(v' + esc(s.agent_version) + ')</span></div><br>'
      + '<div><span class="dim">model:    </span> ' + esc(s.model) + '   <span class="dim">/model to change</span></div>'
      + '<div><span class="dim">directory:</span> ' + esc(cwd) + '</div></div>';
  }
  return '<div class="welcome"><div class="brand-c">✻ <span class="bold">Claude Code</span> v' + esc(s.agent_version) + '</div>'
    + '<br><div>' + esc(modelName(s.model)) + '</div><div class="dim">' + esc(cwd) + '</div></div>';
}

class Player {
  constructor(term, timeline) {
    this.term = term;
    this.tl = timeline;
    this.p = 0;
    this.speed = 1;
    this.playing = false;
    this.stick = true;
    this.expanded = new Set();
    this.nodes = [];
    term.className = 'term ' + timeline.agent;
    term.innerHTML = welcome(timeline.session) + '<div class="log"></div><div class="status"></div><div class="composer"></div><div class="hint"></div>';
    this.log = term.querySelector('.log');
    this.status = term.querySelector('.status');
    this.composer = term.querySelector('.composer');
    this.hint = term.querySelector('.hint');
    this.hint.innerHTML = timeline.agent === 'codex'
      ? '<span>⏎ send   ⌃J newline   ⌃T transcript   ⌃C quit</span>'
      : '<span>? for shortcuts</span>';
    term.addEventListener('scroll', () => {
      this.stick = term.scrollTop + term.clientHeight >= term.scrollHeight - 40;
    });
    term.addEventListener('click', (e) => {
      const more = e.target.closest('[data-expand]');
      if (!more) return;
      const node = more.closest('[data-item]');
      const key = node.dataset.item + ':' + (more.closest('[data-member]')?.dataset.member ?? '');
      this.expanded.add(key);
      this.nodes[Number(node.dataset.item)].sig = null;
      this.render();
    });
  }

  itemHtml(it, index, p) {
    const tl = this.tl;
    const codex = tl.agent === 'codex';
    const start = tl.play(it.start);
    const done = p >= tl.play(it.finish ?? it.start);
    const ev = it.ev;
    if (it.type === 'think') {
      return { sig: 'think', html: '' };
    }
    if (it.type === 'text') {
      const reveal = Math.min(REVEAL_MAX, (ev.output.length / REVEAL) * 1000);
      const shown = reveal ? Math.min(ev.output.length, Math.floor(((p - start) / reveal) * ev.output.length)) : ev.output.length;
      let n = shown;
      if (n < ev.output.length) {
        const cut = ev.output.lastIndexOf(' ', n);
        n = cut > n - 40 && cut > 0 ? cut : n;
      }
      const partial = n < ev.output.length;
      const body = markdown(ev.output.slice(0, n)) + (partial ? '<span class="cursor">&nbsp;</span>' : '');
      return { sig: 'text:' + n, html: '<div class="step"><span class="bullet">' + (codex ? '•' : '⏺') + '</span><div class="body md">' + body + '</div></div>' };
    }
    if (it.type === 'thinking') {
      return { sig: 'thinking', html: '<div class="step"><span class="bullet dim">•</span><div class="body md dim italic">' + markdown(ev.output) + '</div></div>' };
    }
    if (it.type === 'plan') {
      let items = [];
      try { items = JSON.parse(ev.output); } catch (e) { items = []; }
      const lines = items.map((x) => (x.completed ? '✔ ' : '□ ') + x.text);
      return { sig: 'plan', html: '<div class="step"><span class="bullet">•</span><div class="body"><span class="bold">Updated Plan</span>' + subBlock('└ ', lines) + '</div></div>' };
    }
    if (it.type === 'end') {
      const text = 'Worked for ' + duration(tl.session.duration_ms);
      return { sig: 'end', html: codex ? '<div class="worked">─ ' + text + ' </div>' : '<div class="worked"><span class="dim">✻ ' + text + '</span></div>' };
    }
    if (it.type === 'explore') {
      const members = it.members.filter((m) => p >= tl.play(m.ev.at_ms));
      const running = members.some((m) => p < tl.play(m.ev.end_ms));
      const lines = members.map((m, i) => (i ? '  ' : '└ ') + m.line);
      return {
        sig: 'explore:' + members.length + ':' + running,
        html: '<div class="step' + (running ? ' running' : '') + '"><span class="bullet">•</span><div class="body"><span class="bold">' + (running ? 'Exploring' : 'Explored') + '</span>'
          + '<div class="sub"><span></span><pre>' + esc(lines.join('\n')) + '</pre></div></div></div>',
      };
    }
    // a tool call
    const expanded = this.expanded.has(index + ':');
    const bulletCls = !done ? '' : ev.error ? 'err' : codex ? '' : 'ok';
    let head;
    let result = '';
    if (codex) {
      const verb = done ? 'Ran' : 'Running';
      const cmd = ev.tool === 'shell' ? ev.title : ev.tool + ' ' + ev.title;
      head = '<span class="cmd"><span class="verb">' + verb + '</span> ' + esc(cmd) + '</span>';
      if (done) {
        const lines = outputLines(ev.output);
        result = subBlock('└ ', lines.length ? lines : ['(no output)'], ev.error ? 'err' : '', expanded);
      }
    } else {
      const label = claudeLabel(ev, tl.cwd);
      const paren = label.indexOf('(');
      head = '<span class="bold">' + esc(label.slice(0, paren)) + '</span>' + esc(label.slice(paren));
      result = done ? claudeResult(ev, expanded) : '';
    }
    return {
      sig: 'tool:' + done + ':' + expanded,
      html: '<div class="step' + (done ? '' : ' running') + '"><span class="bullet ' + bulletCls + '">' + (codex ? '•' : '⏺') + '</span><div class="body">' + head + result + '</div></div>',
    };
  }

  render() {
    const tl = this.tl;
    const p = this.p;
    const codex = tl.agent === 'codex';
    // The prompt: typed into the box, then submitted into the log.
    const submitted = p >= tl.typing + 250;
    const promptNode = this.log.querySelector('.user');
    if (submitted && !promptNode) {
      this.log.insertAdjacentHTML('afterbegin', '<div class="user"><span class="caret">' + (codex ? '› ' : '&gt; ') + '</span>' + esc(tl.prompt) + '</div>');
    } else if (!submitted && promptNode) promptNode.remove();

    let visible = 0;
    tl.items.forEach((it, i) => {
      if (p < tl.play(it.start)) return;
      visible = i + 1;
      const { sig, html } = this.itemHtml(it, i, p);
      let node = this.nodes[i];
      if (!node) {
        const el = document.createElement('div');
        el.dataset.item = i;
        this.log.appendChild(el);
        node = this.nodes[i] = { el, sig: null };
      }
      if (node.sig !== sig) {
        node.el.innerHTML = html;
        node.sig = sig;
      }
    });
    for (let i = this.nodes.length - 1; i >= visible; i--) {
      if (this.nodes[i]) this.nodes[i].el.remove();
      this.nodes.length = i;
    }

    // The status line while the agent works.
    const endItem = tl.items.find((it) => it.type === 'end');
    const finished = endItem ? p >= tl.play(endItem.start) : p >= tl.end - 1200;
    const r = tl.real(p);
    if (submitted && !finished) {
      const elapsed = duration(r);
      if (codex) {
        const thought = [...tl.items].reverse().find((it) => it.type === 'thinking' && p >= tl.play(it.start));
        const title = thought && /\*\*([^*]+)\*\*/.exec(thought.ev.output);
        this.status.innerHTML = '<span class="spin">•</span> <span class="shimmer">' + esc(title ? title[1] : 'Working') + '</span> <span class="dim">(' + elapsed + ' • esc to interrupt)</span>';
      } else {
        const glyph = CLAUDE_SPIN[Math.floor(performance.now() / 120) % CLAUDE_SPIN.length];
        const verb = CLAUDE_VERBS[Math.floor(r / 9000) % CLAUDE_VERBS.length];
        this.status.innerHTML = '<span class="spin">' + glyph + ' ' + verb + '…</span> <span class="dim">(' + elapsed + ' · esc to interrupt)</span>';
      }
    } else this.status.innerHTML = '';

    // The box the prompt goes in.
    const caret = codex ? '› ' : '&gt; ';
    if (!submitted) {
      const n = Math.floor(Math.min(1, p / tl.typing) * tl.prompt.length);
      this.composer.innerHTML = caret + '<span class="typed">' + esc(tl.prompt.slice(0, n)) + '</span><span class="md"><span class="cursor">&nbsp;</span></span>';
    } else {
      this.composer.innerHTML = caret + '<span class="faint">' + (codex ? 'Ask Codex to do anything' : '') + '</span>';
    }

    if (this.stick) this.term.scrollTop = this.term.scrollHeight;
    this.onTick && this.onTick(p, r);
  }

  seek(p) {
    this.p = Math.max(0, Math.min(this.tl.end, p));
    this.stick = true;
    this.render();
  }

  start() {
    if (this.p >= this.tl.end) this.seek(0);
    this.playing = true;
    let last = performance.now();
    const frame = (now) => {
      if (!this.playing) return;
      this.p = Math.min(this.tl.end, this.p + (now - last) * this.speed);
      last = now;
      this.render();
      if (this.p >= this.tl.end) {
        this.playing = false;
        this.onState && this.onState();
        return;
      }
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
    this.onState && this.onState();
  }

  pause() {
    this.playing = false;
    this.onState && this.onState();
  }
}

// --- the page ------------------------------------------------------------------------

function links() {
  const [agent, lang] = SESSION.split('-');
  const href = (a, l) => BASE
    ? BASE + (a === 'claude' ? 'claude-code/' : 'codex/') + (l === 'ko' ? 'ko/' : '')
    : '?session=' + a + '-' + l + (params.get('dolthub') ? '&dolthub=' + encodeURIComponent(params.get('dolthub')) : '');
  document.querySelectorAll('[data-agent]').forEach((a) => {
    a.href = href(a.dataset.agent, lang);
    if (a.dataset.agent === agent) a.setAttribute('aria-current', 'page');
  });
  document.querySelectorAll('[data-lang]').forEach((a) => {
    a.href = href(agent, a.dataset.lang);
    if (a.dataset.lang === lang) a.setAttribute('aria-current', 'page');
  });
}

async function main() {
  html.lang = LANG;
  document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = T[el.dataset.i18n]; });
  document.querySelectorAll('[data-i18n-title]').forEach((el) => {
    el.title = T[el.dataset.i18nTitle];
    el.setAttribute('aria-label', T[el.dataset.i18nTitle]);
  });
  document.title = (SESSION.startsWith('codex') ? 'Codex CLI' : 'Claude Code') + ' · Spider Sense ' + T.title.toLowerCase();
  links();

  const term = document.getElementById('term');
  term.innerHTML = '<div class="dim">' + esc(T.loading) + '</div>';
  let data;
  try {
    data = await loadSession();
  } catch (e) {
    term.innerHTML = '<div class="error-box">' + esc(T.failed + (e && e.message ? e.message : e)) + '</div>';
    return;
  }
  const { session, events } = data;
  document.getElementById('caption').innerHTML = T.caption(session);
  document.getElementById('notes').innerHTML = T.notes(session);
  document.getElementById('window-title').textContent = (session.agent === 'codex' ? 'codex' : 'claude') + ' — ' + String(session.cwd || '').split('/').pop();

  const tl = new Timeline(session, events);
  const player = new Player(term, tl);
  const playBtn = document.getElementById('play');
  const fill = document.getElementById('progress-fill');
  const progress = document.getElementById('progress');
  const clockEl = document.getElementById('clock');
  player.onTick = (p, r) => {
    fill.style.width = ((p / tl.end) * 100).toFixed(2) + '%';
    progress.setAttribute('aria-valuenow', Math.round((p / tl.end) * 100));
    clockEl.textContent = clock(r) + ' / ' + clock(tl.last);
  };
  player.onState = () => {
    playBtn.textContent = player.playing ? '❚❚' : '▶';
    playBtn.title = player.playing ? T.pause : T.play;
    playBtn.setAttribute('aria-label', playBtn.title);
  };
  playBtn.addEventListener('click', () => (player.playing ? player.pause() : player.start()));
  document.getElementById('restart').addEventListener('click', () => { player.seek(0); player.start(); });
  document.getElementById('end').addEventListener('click', () => { player.pause(); player.seek(tl.end); });
  const seekTo = (x) => {
    const box = progress.getBoundingClientRect();
    player.seek(((x - box.left) / box.width) * tl.end);
  };
  progress.addEventListener('pointerdown', (e) => {
    seekTo(e.clientX);
    const move = (m) => seekTo(m.clientX);
    const up = () => { removeEventListener('pointermove', move); removeEventListener('pointerup', up); };
    addEventListener('pointermove', move);
    addEventListener('pointerup', up);
  });
  progress.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowRight') player.seek(player.p + 5000);
    if (e.key === 'ArrowLeft') player.seek(player.p - 5000);
  });
  document.querySelectorAll('[data-speed]').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.speed === '1'));
    b.addEventListener('click', () => {
      player.speed = Number(b.dataset.speed);
      document.querySelectorAll('[data-speed]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    });
  });
  addEventListener('keydown', (e) => {
    if (e.key === ' ' && !e.target.closest('button, input, a')) {
      e.preventDefault();
      player.playing ? player.pause() : player.start();
    }
  });
  player.render();
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) player.seek(tl.end);
  else player.start();
  player.onState();
}

main();

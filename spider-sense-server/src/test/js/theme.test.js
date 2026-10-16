// app.css: the theme sets the colour scheme the browser draws its own controls in (ui.adoc#palette).
import './browser-env.js';
import { document } from './fake-dom.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { timeSeries } from '../../main/resources/public/assets/js/charts.js';
import { state } from '../../main/resources/public/assets/js/api.js';

const css = readFileSync(new URL('../../main/resources/public/assets/app.css', import.meta.url), 'utf8');
const JS_DIR = new URL('../../main/resources/public/assets/js/', import.meta.url);

/** The declarations of the first rule whose selector is exactly `selector`. */
function rule(selector) {
  const at = css.indexOf('\n' + selector + ' {');
  assert.ok(at >= 0, 'no rule for ' + selector);
  return css.slice(at, css.indexOf('}', at));
}

test('every palette names the colour scheme of the native controls drawn in it', () => {
  assert.match(rule(':root'), /color-scheme: dark;/);
  assert.match(rule('  :root:not([data-theme="dark"])'), /color-scheme: light;/);
  assert.match(rule(':root[data-theme="light"]'), /color-scheme: light;/);
});

/** A palette's tokens: the dark ones of :root, and the light rule's over them. */
function palette(light) {
  const tokens = {};
  const read = (selector) => {
    for (const m of rule(selector).matchAll(/(--[\w-]+):\s*([^;]+);/g)) tokens[m[1]] = m[2].trim();
  };
  read(':root');
  if (light) read(':root[data-theme="light"]');
  const resolve = (v) => { const m = /^var\((--[\w-]+)\)$/.exec(v); return m ? resolve(tokens[m[1]]) : v; };
  return (name) => resolve(tokens[name]);
}

/** The WCAG contrast ratio of two #rrggbb colours. */
function contrast(a, b) {
  const lum = (hex) => {
    const n = parseInt(hex.slice(1), 16);
    const [r, g, bl] = [16, 8, 0].map((s) => { const c = ((n >> s) & 255) / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

test('the text colours reach 4.5:1 on every background of both palettes', () => {
  for (const light of [false, true]) {
    const token = palette(light);
    for (const fg of ['--text', '--text-muted', '--ok-text', '--warn-text', '--err-text', '--accent-text', '--info-text']) {
      for (const bg of ['--bg', '--bg-panel', '--bg-raised']) {
        const ratio = contrast(token(fg), token(bg));
        assert.ok(ratio >= 4.5, `${light ? 'light' : 'dark'} ${fg} on ${bg} is ${ratio.toFixed(2)}:1`);
      }
    }
    const onAccent = contrast('#ffffff', token('--accent-strong'));
    assert.ok(onAccent >= 4.5, `${light ? 'light' : 'dark'} white on --accent-strong is ${onAccent.toFixed(2)}:1`);
  }
});

/** app.css's rules as [selector list, declarations], without the comments and the @keyframes. */
function rules() {
  const plain = css.replace(/\/\*[\s\S]*?\*\//g, '').replace(/@keyframes[^{]*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}/g, '');
  return [...plain.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => [m[1].trim(), m[2]]);
}

/** What may fade, as it holds no text: an icon, a swatch, a dot, the logo, a map node's box, icon and histogram, an edge's curve. */
const NO_TEXT = /(?:^|[\s>])(?:\.icon|i|\.dot|\.sev-dot|\.empty-hero|\.map-node-box|\.map-node-icon|\.map-hist|\.map-edge\.dim)$/;

test('no text is dimmed with an opacity, which would take it under 4.5:1; only what holds no text fades', () => {
  const faded = [];
  for (const [selectors, body] of rules()) {
    for (const m of body.matchAll(/(?<![-\w])opacity:\s*([\d.]+)/g)) {
      if (+m[1] <= 0 || +m[1] >= 1) continue;
      for (const s of selectors.split(',').map((x) => x.trim())) if (!NO_TEXT.test(s)) faded.push(s + ' at ' + m[1]);
    }
  }
  for (const file of readdirSync(JS_DIR, { recursive: true }).filter((f) => f.endsWith('.js'))) {
    const source = readFileSync(new URL(file, JS_DIR), 'utf8');
    for (const m of source.matchAll(/(?<![-\w])opacity:\s*['"]?([\d.]+)/g)) if (+m[1] > 0 && +m[1] < 1) faded.push(file + ': ' + m[0]);
  }
  assert.deepEqual(faded, []);
});

test('a chart writes its axes\' values and labels, and its marks\' names, in colours that reach 4.5:1 on the panel', () => {
  const browserStyle = globalThis.getComputedStyle;
  const t = [Date.now() - 60000, Date.now()];
  try {
    for (const light of [false, true]) {
      const token = palette(light);
      const name = light ? 'light' : 'dark';
      globalThis.getComputedStyle = () => ({ getPropertyValue: (n) => token(n) || '', paddingLeft: '0', paddingRight: '0' });
      // uPlot writes an axis' values and its label in the axis' stroke, whatever colour a spec gives the axis.
      for (const color of ['accent', 'ok', 'warn', 'err', 'silk', 'text', 'muted', 'bucket2', 'series1', 'series5', undefined]) {
        const chart = timeSeries(document.createElement('div'), {
          t, series: [{ label: 'v', values: [1, 2] }], axes: [{ scale: 'y', label: 'n' }, { scale: 'y', side: 1, label: 'right', color }],
        });
        for (const axis of chart.plot.opts.axes) {
          const ratio = contrast(axis.stroke, token('--bg-panel'));
          assert.ok(ratio >= 4.5, `${name} axis in ${color}: ${axis.stroke} on --bg-panel is ${ratio.toFixed(2)}:1`);
        }
        chart.destroy();
      }
      // A mark's name is written on the canvas beside its line.
      state.marks = [{ name: 'start', at: 100000 }, { name: 'before', at: 300000 }];
      const chart = timeSeries(document.createElement('div'), { t: [0, 500000], series: [{ label: 'v', values: [1, 2] }] });
      const written = [];
      chart.plot.ctx = new Proxy({}, {
        get: (c, k) => (k === 'fillText' ? (text) => written.push([text, c.fillStyle]) : k === 'measureText' ? () => ({ width: 10 }) : k in c ? c[k] : () => {}),
        set: (c, k, v) => { c[k] = v; return true; },
      });
      for (const hook of chart.plot.opts.hooks.draw) hook(chart.plot);
      assert.deepEqual(written.map(([text]) => text), ['start', 'before']);
      for (const [text, color] of written) {
        const ratio = contrast(color, token('--bg-panel'));
        assert.ok(ratio >= 4.5, `${name} mark ${text}: ${color} on --bg-panel is ${ratio.toFixed(2)}:1`);
      }
      chart.destroy();
    }
  } finally {
    globalThis.getComputedStyle = browserStyle;
    state.marks = [];
  }
});

/** `#rrggbb` laid at `alpha` over the #rrggbb `under`, as the browser composites a tint. */
function over(hex, alpha, under) {
  const ch = (h, s) => (parseInt(h.slice(1), 16) >> s) & 255;
  return '#' + [16, 8, 0].map((s) => Math.round(ch(hex, s) * alpha + ch(under, s) * (1 - alpha))
    .toString(16).padStart(2, '0')).join('');
}

test('the accent and INFO text reach 4.5:1 on the tints they sit on as well', () => {
  for (const light of [false, true]) {
    const token = palette(light);
    const softAlpha = Number(/rgba\([^)]*,\s*([\d.]+)\)/.exec(token('--accent-soft'))[1]);
    const severityAlpha = Number(/var\(--series-5\) (\d+)%/.exec(rule('.sev[data-sev="INFO"]'))[1]) / 100;
    for (const bg of ['--bg', '--bg-panel', '--bg-raised']) {
      // A pressed button, a chip or the active page in the sidebar: --accent-soft over the background.
      const soft = contrast(token('--accent-text'), over(token('--accent'), softAlpha, token(bg)));
      assert.ok(soft >= 4.5, `${light ? 'light' : 'dark'} --accent-text on --accent-soft over ${bg} is ${soft.toFixed(2)}:1`);
      // The INFO severity: --series-5 tinted over the background, a hovered row's included.
      const info = contrast(token('--info-text'), over(token('--series-5'), severityAlpha, token(bg)));
      assert.ok(info >= 4.5, `${light ? 'light' : 'dark'} --info-text on the INFO tint over ${bg} is ${info.toFixed(2)}:1`);
    }
  }
});

test('a status colour set as text takes its text token, and white text sits on the strong accent', () => {
  for (const [selector, token] of [['.warned', '--warn-text'], ['.good', '--ok-text'], ['.bad', '--err-text'],
    ['.status-code.warn', '--warn-text'], ['.stat.is-warn .stat-number', '--warn-text'], ['.st-head', '--err-text'],
    ['.accent', '--accent-text'], ['a:hover', '--accent-text'], ['.tab[aria-selected="true"]', '--accent-text'],
    ['.sev[data-sev="INFO"]', '--info-text'], ['.method[data-method="GET"]', '--info-text']]) {
    assert.match(rule(selector), new RegExp('(?:^|[{;\\s])color: var\\(' + token + '\\)'), selector);
  }
  assert.match(rule('.btn-primary'), /background: var\(--accent-strong\)/);
});

test('neither the accent nor a series colour is set as text, only through a text token', () => {
  const offenders = [...css.matchAll(/(?<![-\w])color: var\((--accent|--series-\d)\)/g)].map((m) => m[0]);
  assert.deepEqual(offenders, []);
});

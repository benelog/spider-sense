// app.css: the theme sets the colour scheme the browser draws its own controls in (ui.adoc#palette).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const css = readFileSync(new URL('../../main/resources/public/assets/app.css', import.meta.url), 'utf8');

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
    for (const fg of ['--text', '--text-muted', '--ok-text', '--warn-text', '--err-text']) {
      for (const bg of ['--bg', '--bg-panel', '--bg-raised']) {
        const ratio = contrast(token(fg), token(bg));
        assert.ok(ratio >= 4.5, `${light ? 'light' : 'dark'} ${fg} on ${bg} is ${ratio.toFixed(2)}:1`);
      }
    }
    const onAccent = contrast('#ffffff', token('--accent-strong'));
    assert.ok(onAccent >= 4.5, `${light ? 'light' : 'dark'} white on --accent-strong is ${onAccent.toFixed(2)}:1`);
  }
});

test('a status colour set as text takes its text token, and white text sits on the strong accent', () => {
  for (const [selector, token] of [['.warned', '--warn-text'], ['.good', '--ok-text'], ['.bad', '--err-text'],
    ['.status-code.warn', '--warn-text'], ['.stat.is-warn .stat-number', '--warn-text'], ['.st-head', '--err-text']]) {
    assert.match(rule(selector), new RegExp('color: var\\(' + token + '\\)'), selector);
  }
  assert.match(rule('.btn-primary'), /background: var\(--accent-strong\)/);
});

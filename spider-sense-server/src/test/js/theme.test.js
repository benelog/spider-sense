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

// ui.formDialog and the Mark dialog (ui.adoc#dialogs).
import './fake-dom.js';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formDialog, markDialog, errorText, snippetText } from '../../main/resources/public/assets/js/ui.js';
import { state } from '../../main/resources/public/assets/js/api.js';

const settle = () => new Promise((r) => setImmediate(r));
const inputsOf = (dlg) => dlg.all('input');
const primary = (dlg) => dlg.all('button').find((b) => b.classList.contains('btn-primary'));
const problemOf = (dlg) => dlg.all('div').find((d) => d.classList.contains('form-error'));

test('errorText reads an Error or the thrown value', () => {
  assert.equal(errorText(new Error('boom')), 'boom');
  assert.equal(errorText('plain'), 'plain');
});

test('a form submits its trimmed values once, closes, then reports done', async () => {
  const submitted = [];
  let release;
  const dlg = formDialog({
    title: 'T', fields: [{ name: 'note', label: 'Note' }], submitLabel: 'Go',
    submit: (values) => { submitted.push(values); return new Promise((r) => { release = r; }); },
    done: (result, values) => submitted.push(['done', result, values]),
  });
  const [note] = inputsOf(dlg);
  note.value = '  why  ';
  note.dispatch('keydown', { key: 'Enter' });
  note.dispatch('keydown', { key: 'Enter' });
  primary(dlg).click();
  assert.deepEqual(submitted, [{ note: 'why' }]);
  release('ok');
  await settle();
  assert.equal(dlg.open, false);
  assert.deepEqual(submitted[1], ['done', 'ok', { note: 'why' }]);
});

test('a refusal is shown under the fields and the dialog stays open for another try', async () => {
  let calls = 0;
  const dlg = formDialog({
    title: 'T', fields: [{ name: 'note', label: 'Note' }], submitLabel: 'Go',
    submit: async () => { calls++; throw new Error('No such finding'); },
  });
  primary(dlg).click();
  await settle();
  assert.equal(dlg.open, true);
  assert.equal(problemOf(dlg).hidden, false);
  assert.equal(problemOf(dlg).textContent, 'No such finding');
  primary(dlg).click();
  await settle();
  assert.equal(calls, 2);
});

test('the Mark dialog refuses a name outside the mark-name characters before any request', async () => {
  state.marks = [];
  const dlg = markDialog();
  const [name] = inputsOf(dlg);
  assert.equal(name.getAttribute('value'), 'before');
  assert.equal(name.getAttribute('pattern'), '[A-Za-z0-9._-]{1,64}');
  name.value = 'has space';
  primary(dlg).click();
  await settle();
  assert.equal(problemOf(dlg).textContent, 'A name is 1 to 64 of the characters A-Z, a-z, 0-9, dot, underscore and dash.');
  assert.equal(markDialog(), dlg, 'one Mark dialog at a time');
  dlg.close();
});

test('the send-data snippets name the real jar, and lead with the collector line unless this is the default embedded one (ui.adoc#dialogs)', () => {
  const embedded = snippetText({ mode: 'agent', endpoint: 'http://127.0.0.1:4000', jar: '/opt/spider-sense-0.1.0.jar' });
  assert.match(embedded.agent, /^java -javaagent:\/opt\/spider-sense-0\.1\.0\.jar -jar your-app\.jar\n/);
  const standalone = snippetText({
    mode: 'standalone', endpoint: 'http://127.0.0.1:4000', jar: '/opt/spider-sense-0.1.0.jar',
    otlp: { traces: 'http://127.0.0.1:4000/v1/traces' },
  });
  assert.equal(standalone.agent.split('\n').slice(0, 2).join('\n'), '# send to this Spider Sense\njava -javaagent:/opt/spider-sense-0.1.0.jar \\');
  assert.match(standalone.agent, /-Dspidersense\.collector=http:\/\/127\.0\.0\.1:4000/);
  assert.match(standalone.agent, /# or start a Spider Sense of its own, embedded in the app\njava -javaagent:\/opt\/spider-sense-0\.1\.0\.jar -jar your-app\.jar$/);
  const otherPort = snippetText({ mode: 'agent', endpoint: 'http://127.0.0.1:4001' });
  assert.match(otherPort.agent, /^# send to this Spider Sense\njava -javaagent:spider-sense\.jar \\\n  -Dspidersense\.collector=http:\/\/127\.0\.0\.1:4001/);
  assert.match(otherPort.curl, /^curl -X POST http:\/\/127\.0\.0\.1:4001\/v1\/traces/);
});

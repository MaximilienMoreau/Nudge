import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const source = readFileSync('extension/content.js', 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function compose() {
  const listeners = {};
  const checkbox = { checked: true, addEventListener() {} };
  const status = {};
  const manual = { hidden: true, value: '', addEventListener() {} };
  const hint = { addEventListener(type, listener) { state.openHint = listener; } };
  const panel = { querySelector(selector) { return selector.endsWith('-chk') ? checkbox : selector.endsWith('-hint') ? hint : selector.endsWith('-manual') ? manual : status; }, remove() {}, setAttribute() {} };
  const state = { recipients: ['client@example.com'], body: 'Hello', injected: false, sent: 0, requests: 0, hints: 0 };
  const container = { isConnected: true, closest: selector => (selector === '.AO' && state.reply ? {} : null), querySelectorAll() { return []; }, contains() { return true; } };
  const notices = [];
  const document = {
    createElement(tag) { return tag === 'div' ? panel : { style: {}, appendChild() {}, setAttribute() {} }; },
    head: { appendChild() {} }, body: { appendChild(el) { notices.push(el.textContent); } }, readyState: 'loading', querySelectorAll() { return []; },
    addEventListener(type, listener) { (listeners[type] ||= []).push(listener); }
  };
  const sendBtn = { isConnected: true, contains() { return false; }, click() {
    const event = click();
    if (!event.blocked) { assert.equal(state.injected, !state.reply); state.sent++; }
  }};
  const context = vm.createContext({ window: { location: { hostname: 'mail.google.com' }, addEventListener(type, listener) { (listeners[type] ||= []).push(listener); } }, document,
    console: { log() {} }, setTimeout() {}, MutationObserver: class { observe() {} }, requestAnimationFrame() {}, AbortController,
    chrome: { runtime: { sendMessage(message) {
      if (message.type !== 'REGISTER_EMAIL') { state.hints++; return Promise.resolve({ suggestion: 'Later' }); }
      state.requests++;
      state.payload = message.payload;
      return new Promise((resolve, reject) => { state.resolve = resolve; state.reject = reject; });
    }}}, state, container, sendBtn });
  vm.runInContext(source, context);
  vm.runInContext(`Object.assign(ADAPTERS.gmail, {
    findComposeWindows: () => [{ container, sendBtn }],
    extractSubject: () => 'Proposal', extractRecipients: () => state.recipients,
    extractBody: () => state.body, getSidebarRoot: () => ({appendChild() {}}),
    injectPixel: () => { state.injected = true; return true; }
  }); startTracking();`, context);
  function click() {
    const event = { type: 'click', target: sendBtn, blocked: false,
      preventDefault() { this.blocked = true; }, stopImmediatePropagation() {} };
    for (const listener of listeners.click) listener(event);
    return event;
  }
  return { state, click, checkbox, status, notices, context, manual };
}

const success = { success: true, results: [{ trackingPixelUrl: 'https://api.example/track/open/id' }] };

test('send is held until registration and pixel injection complete, and duplicate clicks are coalesced', async () => {
  const { state, click } = compose();
  assert.equal(click().blocked, true);
  assert.equal(click().blocked, true);
  assert.equal(state.requests, 1);
  assert.equal(state.sent, 0);
  state.resolve(success); await flush();
  assert.equal(state.sent, 1);
  assert.equal(state.injected, true);
});

test('backend failure keeps the draft unsent', async () => {
  const { state, click, status } = compose(); click();
  state.resolve({ success: false, error: 'Server unavailable' }); await flush();
  assert.equal(state.sent, 0);
  assert.equal(status.textContent, 'Server unavailable');
});

test('multiple recipients are blocked before registering misleading tracking records', () => {
  const { state, click, status } = compose();
  state.recipients.push('second@example.com');
  assert.equal(click().blocked, true);
  assert.equal(state.requests, 0);
  assert.match(status.textContent, /one recipient/);
});

test('turning tracking off allows normal sending without backend registration', () => {
  const { state, click, checkbox } = compose(); checkbox.checked = false;
  assert.equal(click().blocked, false);
  assert.equal(state.requests, 0);
});

test('edits made while registering require another explicit Send', async () => {
  const { state, click, status } = compose(); click();
  state.body = 'Changed'; state.resolve(success); await flush();
  assert.equal(state.sent, 0);
  assert.match(status.textContent, /Draft changed/);
});


test('popup scripts obey Manifest V3 CSP without inline handlers or inline scripts', () => {
  const html = readFileSync('extension/popup.html', 'utf8');
  assert.doesNotMatch(html, /\son[a-z]+\s*=/i);
  for (const script of html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/gi)) {
    assert.match(script[1], /\bsrc=/);
    assert.equal(script[2].trim(), '');
  }
});

test('the send-time suggestion is only requested when opened', () => {
  const { state } = compose();
  assert.equal(state.hints, 0);
  state.openHint();
  assert.equal(state.hints, 1);
});

test('a reply to several people is sent, untracked, with a notice', () => {
  const { state, click, notices } = compose();
  state.reply = true;
  state.recipients.push('second@example.com');
  click();
  assert.equal(state.sent, 1);
  assert.equal(state.requests, 0);
  assert.match(notices[0], /without tracking: several recipients/);
});

test('an unrecognised recipient can be typed in the panel', async () => {
  const { state, click, status, manual } = compose();
  state.recipients.length = 0;
  click();
  assert.equal(state.sent, 0);
  assert.match(status.textContent, /not recognised/);
  assert.equal(manual.hidden, false);
  manual.value = 'not an address';
  click();
  assert.equal(state.requests, 0);
  manual.value = ' Client@Example.com ';
  click();
  state.resolve(success); await flush();
  assert.equal(state.sent, 1);
  assert.deepEqual([...state.payload.recipientEmails], ['client@example.com']);
});

test('the typed address never hides recipients that the webmail shows', () => {
  const { state, click, manual } = compose();
  manual.hidden = false;
  manual.value = 'typed@example.com';
  state.recipients.push('second@example.com');
  click();
  assert.equal(state.requests, 0);
});

test('send buttons are recognised in English and French, and only them', () => {
  const { context } = compose();
  const send = label => context.isSendLabel(label);
  for (const label of ['send', 'envoyer', 'send (ctrl+enter)', 'envoyer (ctrl-entrée)', 'send email', 'envoyer le message', "envoyer l'e-mail"]) assert.equal(send(label), true, label);
  for (const label of ['', 'send later', 'send feedback', 'envoyer plus tard', 'envoyer un commentaire', 'sent', 'more send options', 'envoyer et archiver']) assert.equal(send(label), false, label);
});

test('recipient fields are recognised in English and French', () => {
  const { context } = compose();
  for (const label of ['to', 'cc', 'bcc', 'à', 'cci', 'to recipients', 'destinataires']) assert.equal(context.isRecipientLabel(label), true, label);
  for (const label of ['today', 'tout supprimer', 'corps du message', 'add a subject', 'accept']) assert.equal(context.isRecipientLabel(label), false, label);
});

test('"send and archive" buttons and scheduling windows are recognised', () => {
  const { context } = compose();
  for (const label of ['send & archive', 'send + archive', 'send and archive', 'envoyer et archiver']) assert.equal(context.isSendVariantLabel(label), true, label);
  for (const label of ['send', 'envoyer', 'send feedback', 'archive']) assert.equal(context.isSendVariantLabel(label), false, label);
  for (const label of ['Schedule send', "Programmer l'envoi", 'Planifier l’envoi Demain matin']) assert.equal(context.isScheduleLabel(label), true, label);
  for (const label of ['Send', 'Schedule a meeting', 'New message']) assert.equal(context.isScheduleLabel(label), false, label);
});

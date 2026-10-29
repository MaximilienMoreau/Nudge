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
  const panel = { querySelector(selector) { return selector.endsWith('-chk') ? checkbox : status; }, remove() {} };
  const state = { recipients: ['client@example.com'], body: 'Hello', injected: false, sent: 0, requests: 0 };
  const container = { isConnected: true, querySelectorAll() { return []; }, contains() { return true; } };
  const document = {
    createElement(tag) { return tag === 'div' ? panel : { style: {}, appendChild() {} }; },
    head: { appendChild() {} }, body: {}, readyState: 'loading', querySelectorAll() { return []; },
    addEventListener(type, listener) { (listeners[type] ||= []).push(listener); }
  };
  const sendBtn = { isConnected: true, contains() { return false; }, click() {
    const event = click();
    if (!event.blocked) { assert.equal(state.injected, true); state.sent++; }
  }};
  const context = vm.createContext({ window: { location: { hostname: 'mail.google.com' }, addEventListener(type, listener) { (listeners[type] ||= []).push(listener); } }, document,
    console: { log() {} }, MutationObserver: class { observe() {} }, requestAnimationFrame() {}, AbortController,
    chrome: { runtime: { sendMessage(message) {
      if (message.type !== 'REGISTER_EMAIL') return Promise.resolve({ suggestion: 'Later' });
      state.requests++;
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
  return { state, click, checkbox, status };
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

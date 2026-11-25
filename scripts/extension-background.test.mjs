import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const source = readFileSync('extension/background.js', 'utf8');
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

function worker({ responses = {}, rules = [], failRules = false, local = {}, token = 't', status = 200 } = {}) {
  let listener, access;
  const notified = [];
  const bodies = [];
  const badge = [];
  const events = {};
  const on = name => ({ addListener(fn) { events[name] = fn; } });
  local = { nudge_token: token, nudge_email: 'me@example.com', ...local };
  // chrome.storage accepts both a callback and a promise
  const reader = store => (_, done) => done ? done({ ...store }) : Promise.resolve({ ...store });
  const order = [];
  const context = vm.createContext({
    console: { warn() {} }, Intl, AbortSignal, URL,
    fetch: async (url, options = {}) => {
      order.push(`${options.method ?? 'GET'} ${new URL(url).pathname}`);
      if (options.body) bodies.push(JSON.parse(options.body));
      return { ok: status === 200, status, json: async () => responses[new URL(url).pathname] };
    },
    chrome: {
      runtime: { onMessage: { addListener(fn) { listener = fn; } }, onStartup: on('startup'), onInstalled: on('installed'), getURL: path => path },
      alarms: { get: async () => ({}), create() {}, onAlarm: on('alarm') },
      notifications: { create: async (id, options) => { notified.push({ id, ...options }); }, clear() {}, onClicked: on('clicked') },
      action: { setBadgeText: ({ text }) => badge.push(text), setBadgeBackgroundColor() {} },
      storage: {
        onChanged: on('storage'),
        local: { get: reader(local), set: async values => { Object.assign(local, values); },
          remove: async keys => { for (const key of keys) delete local[key]; }, setAccessLevel: ({ accessLevel }) => { access = accessLevel; } }
      },
      declarativeNetRequest: {
        getDynamicRules: async () => [...rules],
        updateDynamicRules: async ({ removeRuleIds, addRules }) => {
          if (failRules) throw new Error('no host access');
          order.push('rules');
          rules = rules.filter(rule => !removeRuleIds.includes(rule.id));
          for (const rule of addRules) assert.ok(!rules.some(other => other.id === rule.id), 'duplicate rule id');
          rules.push(...addRules);
        }
      }
    }
  });
  vm.runInContext(source, context);
  return { order, bodies, responses, notified, badge, local, access: () => access, events, rules: () => rules, call: code => vm.runInContext(code, context), send: message => new Promise(resolve => listener(message, {}, resolve)) };
}

const created = n => ({ '/api/emails': [{ trackingId: id(n), trackingPixelUrl: `https://n.example/track/open/${id(n)}` }] });

test('own pixel is ignored before the compose window receives it', async () => {
  const w = worker({ responses: created(1) });
  const result = await w.send({ type: 'REGISTER_EMAIL', payload: {} });
  assert.equal(result.success, true);
  assert.deepEqual(w.order, ['POST /api/emails', 'rules']);
  assert.equal(w.rules()[0].condition.urlFilter, id(1));
  assert.equal(w.rules()[0].action.type, 'redirect');
});

test('concurrent updates get distinct rule numbers and install each ID once', async () => {
  const w = worker({ responses: {} });
  await Promise.all([
    w.call(`ignoreOwnPixels(['${id(1)}', '${id(2)}'])`),
    w.call(`ignoreOwnPixels(['${id(2)}', '${id(3)}'])`)
  ]);
  assert.deepEqual(w.rules().map(rule => rule.condition.urlFilter), [id(1), id(2), id(3)]);
});

test('a rule failure does not prevent sending', async () => {
  const w = worker({ responses: created(1), failRules: true });
  assert.equal((await w.send({ type: 'REGISTER_EMAIL', payload: {} })).success, true);
});

test('oldest rules are evicted at the limit', async () => {
  const rules = Array.from({ length: 4000 }, (_, i) => ({ id: i + 1, condition: { urlFilter: id(i + 1) } }));
  const w = worker({ responses: created(9999), rules });
  await w.send({ type: 'REGISTER_EMAIL', payload: {} });
  assert.equal(w.rules().length, 4000);
  assert.ok(!w.rules().some(rule => rule.condition.urlFilter === id(1)));
  assert.ok(w.rules().some(rule => rule.condition.urlFilter === id(9999)));
});

test('signing in protects existing emails and rejects values that are not tracking IDs', async () => {
  const w = worker({ responses: { '/api/emails': { content: [{ trackingId: id(3) }, { trackingId: '*' }, { trackingId: id(2) }] } } });
  assert.equal((await w.send({ type: 'SYNC_OWN_PIXELS' })).ok, true);
  assert.deepEqual(w.rules().map(rule => rule.condition.urlFilter), [id(2), id(3)]);
});

// ── Notifications ─────────────────────────────────────────────

const mail = (n, extra) => ({ id: n, subject: `Subject ${n}`, recipientEmail: 'c@example.com', openCount: 1, lastOpenedAt: null, lastClickedAt: null, ...extra });
const list = (...content) => ({ '/api/emails': { content } });
const account = 'http://localhost:8080|me@example.com';
const seenAt = at => ({ nudge_seen: { account, at } });

test('first check records past activity without notifying', async () => {
  const w = worker({ responses: list(mail(1, { lastOpenedAt: '2026-01-02T10:00:00' })) });
  await w.call('checkActivity()');
  assert.equal(w.notified.length, 0);
  assert.equal(w.local.nudge_seen.at, '2026-01-02T10:00:00');
});

test('first ever open is notified once, even when nothing had been opened before', async () => {
  const w = worker({ responses: list(mail(1)) });
  await w.call('checkActivity()');
  w.responses['/api/emails'] = { content: [mail(1, { lastOpenedAt: '2026-01-02T10:00:00.123' })] };
  await w.call('checkActivity()');
  await w.call('checkActivity()');
  assert.deepEqual(w.notified.map(n => [n.title, n.message]), [['Email opened', 'c@example.com — Subject 1']]);
});

test('only new activity is notified, with the right wording', async () => {
  const w = worker({ local: seenAt('2026-01-02T10:00:00'), responses: list(
    mail(1, { lastOpenedAt: '2026-01-02T10:00:00.5', openCount: 3 }),
    mail(2, { lastOpenedAt: '2026-01-01T00:00:00', lastClickedAt: '2026-01-02T11:00:00' }),
    mail(3, { lastOpenedAt: '2026-01-02T10:00:00' })) });
  await w.call('checkActivity()');
  assert.deepEqual(w.notified.map(n => [n.id, n.title]), [['nudge-1', 'Email opened again'], ['nudge-2', 'Link clicked']]);
  assert.equal(w.local.nudge_seen.at, '2026-01-02T11:00:00');
});

test('a burst is grouped into one notification', async () => {
  const w = worker({ local: seenAt(''), responses: list(...[1, 2, 3, 4].map(n => mail(n, { lastOpenedAt: '2026-01-02T10:00:00' }))) });
  await w.call('checkActivity()');
  assert.deepEqual(w.notified.map(n => n.id), ['nudge-summary']);
});

test('turning notifications off stays silent and does not replay them later', async () => {
  const w = worker({ local: { ...seenAt(''), nudge_notifications: false }, responses: list(mail(1, { lastOpenedAt: '2026-01-02T10:00:00' })) });
  await w.call('checkActivity()');
  w.local.nudge_notifications = true;
  await w.call('checkActivity()');
  assert.equal(w.notified.length, 0);
});

test('archiving the latest email does not replay older activity', async () => {
  const w = worker({ local: seenAt('2026-01-05T00:00:00'), responses: list(mail(1, { lastOpenedAt: '2026-01-02T10:00:00' })) });
  await w.call('checkActivity()');
  assert.equal(w.notified.length, 0);
  assert.equal(w.local.nudge_seen.at, '2026-01-05T00:00:00');
});

test('another account starts from its own baseline', async () => {
  const w = worker({ local: { nudge_seen: { account: 'http://localhost:8080|other@example.com', at: '' } }, responses: list(mail(1, { lastOpenedAt: '2026-01-02T10:00:00' })) });
  await w.call('checkActivity()');
  assert.equal(w.notified.length, 0);
  assert.equal(w.local.nudge_seen.account, account);
});

test('an expired session signs out, and the icon shows it', async () => {
  const w = worker({ status: 401 });
  const result = await w.send({ type: 'GET_RECENT_EMAILS' });
  assert.equal(result.signedOut, true);
  assert.equal(w.local.nudge_token, undefined);
  await w.events.storage({ nudge_token: { oldValue: 't' } }, 'local');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(w.badge, ['!']);
});

test('a server error keeps the session', async () => {
  const w = worker({ status: 500 });
  const result = await w.send({ type: 'GET_RECENT_EMAILS' });
  assert.equal(result.ok, false);
  assert.equal(result.signedOut, false);
  assert.equal(w.local.nudge_token, 't');
});

test('the stored session is not readable from webmail pages', () => {
  assert.equal(worker().access(), 'TRUSTED_CONTEXTS');
});

test('ticks receive statuses but no tracking ID or content', async () => {
  const w = worker({ responses: list({ ...mail(1), trackingId: id(1), trackingPixelUrl: 'x', content: 'secret', createdAt: 'd', status: 'Opened' }) });
  const { emails } = await w.send({ type: 'GET_SENT_STATUS' });
  assert.deepEqual(Object.keys(emails[0]).sort(), ['createdAt', 'lastClickedAt', 'lastOpenedAt', 'openCount', 'recipientEmail', 'status', 'subject']);
});

test('the message text is not sent unless the user asked to store it', async () => {
  const payload = { subject: 's', recipientEmails: ['a@example.com'], content: 'private text' };
  const off = worker({ responses: created(1) });
  await off.send({ type: 'REGISTER_EMAIL', payload });
  assert.deepEqual(off.bodies, [{ subject: 's', recipientEmails: ['a@example.com'] }]);
  const on = worker({ responses: created(1), local: { nudge_store_content: true } });
  await on.send({ type: 'REGISTER_EMAIL', payload });
  assert.equal(on.bodies[0].content, 'private text');
});

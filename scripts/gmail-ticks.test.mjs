import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const context = vm.createContext({
  window: { location: { hash: '' }, addEventListener() {} },
  document: { body: {}, addEventListener() {}, querySelectorAll: () => [] },
  MutationObserver: class { observe() {} }, setInterval() {}, Date
});
vm.runInContext(readFileSync('extension/gmail-ticks.js', 'utf8'), context);
const { findTrackedEmail, showsTime, normalizeSubject } = context;

// Local time, as Gmail shows it; the server sends UTC without a zone.
const at = (h, m, s = 0, day = 25) => new Date(2026, 10, day, h, m, s);
const utc = date => date.toISOString().slice(0, -1);
const mail = (createdAt, extra = {}) => ({ subject: 'Devis cuisine', recipientEmail: 'Client@Example.com', createdAt: utc(createdAt), status: 'Opened', ...extra });
const row = (title, extra = {}) => ({ subject: 'Devis cuisine', addresses: ['client@example.com'], titles: [title], ...extra });

for (const title of ['Wed, Nov 25, 2026, 3:48 PM', 'Wed, Nov 25, 2026, 3:48 PM', 'mer. 25 nov. 2026, 15:48', 'Mi., 25. Nov. 2026, 15:48', '25 nov 2026 15.48', 'mer. 25 nov. 2026 à 15 h 48', '2026年11月25日(水) 15:48']) {
  test(`reads "${title}"`, () => {
    assert.equal(showsTime(title, at(15, 48)), true);
    assert.equal(showsTime(title, at(3, 48)), false);
    assert.equal(showsTime(title, at(15, 49)), false);
    assert.equal(showsTime(title, at(15, 48, 0, 24)), false);
  });
}

test('midnight and noon in 12-hour format', () => {
  assert.equal(showsTime('Wed, Nov 25, 2026, 12:05 AM', at(0, 5)), true);
  assert.equal(showsTime('Wed, Nov 25, 2026, 12:05 PM', at(12, 5)), true);
  assert.equal(showsTime('Wed, Nov 25, 2026, 12:05 AM', at(12, 5)), false);
});

test('a list date without time is not enough', () => {
  assert.equal(showsTime('Nov 25, 2026', at(15, 48)), false);
});

test('matches when Gmail records the sending slightly after registration', () => {
  const tracked = mail(at(15, 47, 58));
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 15:48'), [tracked]), tracked);
});

test('an untracked email with the same subject and recipient gets no tick', () => {
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 18:10'), [mail(at(15, 48))]), null);
});

test('another recipient or subject gets no tick', () => {
  const tracked = [mail(at(15, 48))];
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 15:48', { addresses: ['x@example.com'] }), tracked), null);
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 15:48', { subject: 'Autre' }), tracked), null);
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 15:48', { subject: '' }), [mail(at(15, 48), { subject: '' })]), null);
});

test('the same email sent twice keeps each status', () => {
  const first = mail(at(15, 48)), second = mail(at(16, 30), { status: 'Not Opened' });
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 15:48'), [second, first]), first);
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 16:30'), [second, first]), second);
});

test('a draft registered twice uses the registration that was sent', () => {
  const abandoned = mail(at(15, 48, 5)), sent = mail(at(15, 48, 40));
  assert.equal(findTrackedEmail(row('mer. 25 nov. 2026, 15:48'), [abandoned, sent]), sent);
});

test('reply and forward prefixes are ignored', () => {
  assert.equal(normalizeSubject('RE: Fwd: TR :  Devis   cuisine'), 'devis cuisine');
  assert.equal(normalizeSubject('Retour client'), 'retour client');
});

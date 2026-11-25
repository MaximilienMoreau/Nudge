/**
 * gmail-ticks.js — read ticks in Gmail's Sent list.
 *
 *   ✓   tracked, no open detected      ✓?  loaded automatically: impossible to tell
 *   ✓✓  opened
 *
 * Gmail exposes no link between a row and a tracked email, so a row gets a tick only
 * if its subject, recipient and time of sending all match. A missing tick is better
 * than the tick of another email.
 */

const TICK_REFRESH_MS = 60_000;
const ROW_SCAN_DELAY_MS = 300;
// Registration happens just before Gmail records the sending, and clocks may differ.
const SENT_OFFSETS_MIN = [0, 1, -1, 2, 3];

const TICKS = {
  'Not Opened':            { text: '✓',  color: '#80868b', label: 'Not opened: no open detected' },
  'Unconfirmed':           { text: '✓?', color: '#8ab4f8', label: 'Unconfirmed: Apple Mail and Proton Mail load images automatically' },
  'Opened':                { text: '✓✓', color: '#1e8e3e', label: 'Opened' },
  'Opened Multiple Times': { text: '✓✓', color: '#1e8e3e', label: 'Opened' }
};

// ── Matching ──────────────────────────────────────────────────

function normalizeSubject(subject) {
  return (subject ?? '').replace(/\s+/g, ' ').replace(/^(\s*(re|fwd?|tr|aw|wg|rv)\s*:\s*)+/i, '').trim().toLowerCase();
}

/**
 * Whether a date written in the user's language, such as "Wed, Nov 25, 2026, 3:48 PM"
 * or "mer. 25 nov. 2026, 15:48", is the given local time. Only numbers are compared,
 * so month names need no translation; the year, day, hour and minute must be present.
 */
function showsTime(title, date) {
  const time = title.match(/(\d{1,2})\s*[:.h]\s*(\d{2})(?!\d)/);
  if (!time) return false;
  const hour = Number(time[1]);
  const pm = /p\.?\s?m\.?/i.test(title), am = /a\.?\s?m\.?(?![a-z])/i.test(title);
  const hours = pm ? hour % 12 + 12 : am ? hour % 12 : hour;
  if (hours !== date.getHours() || Number(time[2]) !== date.getMinutes()) return false;
  const numbers = title.replace(time[0], ' ').match(/\d+/g)?.map(Number) ?? [];
  return numbers.includes(date.getFullYear()) && numbers.includes(date.getDate());
}

/** row: { subject, addresses, titles } */
function findTrackedEmail(row, emails) {
  const subject = normalizeSubject(row.subject);
  if (!subject) return null;
  const candidates = emails.filter(email =>
    normalizeSubject(email.subject) === subject &&
    row.addresses.includes((email.recipientEmail ?? '').toLowerCase()));
  // Closest time first. At equal distance the latest registration wins: an earlier
  // one is a draft that was registered, then edited before being sent.
  for (const offset of SENT_OFFSETS_MIN) {
    const match = candidates
      .filter(email => {
        const sent = new Date(new Date(`${email.createdAt}Z`).getTime() + offset * 60_000);
        return row.titles.some(title => showsTime(title, sent));
      })
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))[0];
    if (match) return match;
  }
  return null;
}

// ── Page ──────────────────────────────────────────────────────

let trackedEmails = [];

const inSentList = () => /^#sent(\/|$)/.test(window.location.hash);

function readRow(row) {
  return {
    subject: row.querySelector('.bog')?.textContent ?? '',
    addresses: [...row.querySelectorAll('[email]')].map(el => el.getAttribute('email').toLowerCase()),
    titles: [...row.querySelectorAll('[title]:not(.nudge-tick)')].map(el => el.title).filter(title => /\d{4}/.test(title))
  };
}

function tickLabel(email) {
  const count = email.openCount > 1 ? ` ${email.openCount} times` : '';
  const seen = [email.lastOpenedAt, email.lastClickedAt].filter(Boolean).sort().at(-1);
  return TICKS[email.status].label + count + (seen ? `, last on ${new Date(`${seen}Z`).toLocaleString()}` : '');
}

/** Gmail recycles rows: each pass re-evaluates all of them, and only writes changes. */
function refreshTicks() {
  const show = inSentList();
  document.querySelectorAll('tr.zA').forEach(row => {
    const email = show ? findTrackedEmail(readRow(row), trackedEmails) : null;
    const existing = row.querySelector('.nudge-tick');
    const tick = email && TICKS[email.status];
    if (!tick) { existing?.remove(); return; }
    const label = `Nudge — ${tickLabel(email)}`;
    if (existing?.title === label && existing.textContent === tick.text) return;
    const el = existing ?? document.createElement('span');
    el.className = 'nudge-tick';
    el.textContent = tick.text;
    el.title = label;
    el.setAttribute('aria-label', label);
    el.style.cssText = `color:${tick.color};font-weight:700;margin-right:6px;letter-spacing:-2px;padding-right:2px;`;
    if (!existing) row.querySelector('.bog').before(el);
  });
}

async function loadTrackedEmails() {
  if (!inSentList() || document.hidden) return;
  let result;
  try { result = await chrome.runtime.sendMessage({ type: 'GET_SENT_STATUS' }); }
  catch { stopTicks(); return; } // the extension was reloaded or removed
  if (result?.ok) trackedEmails = result.emails;
  else if (result?.signedOut) trackedEmails = [];
  refreshTicks();
}

let scanTimer = null;
const rowObserver = new MutationObserver(() => {
  scanTimer ??= setTimeout(() => { scanTimer = null; refreshTicks(); }, ROW_SCAN_DELAY_MS);
});
const refreshTimer = setInterval(loadTrackedEmails, TICK_REFRESH_MS);

function stopTicks() {
  rowObserver.disconnect();
  clearInterval(refreshTimer);
  clearTimeout(scanTimer);
  window.removeEventListener('hashchange', loadTrackedEmails);
  document.removeEventListener('visibilitychange', loadTrackedEmails);
  document.querySelectorAll('.nudge-tick').forEach(el => el.remove());
}

window.addEventListener('hashchange', loadTrackedEmails);
document.addEventListener('visibilitychange', loadTrackedEmails);
rowObserver.observe(document.body, { childList: true, subtree: true });
loadTrackedEmails();

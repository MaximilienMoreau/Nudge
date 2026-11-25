/**
 * background.js — Nudge Browser Extension Service Worker (Manifest V3 — Chrome & Edge)
 *
 * Responsibilities:
 *  - Register a new email with the Nudge backend (REGISTER_EMAIL)
 *  - Return an AI send-time suggestion (GET_SEND_TIME)
 *  - List recent emails for the popup (GET_RECENT_EMAILS)
 *  - Give the Gmail ticks the status of sent emails (GET_SENT_STATUS)
 *  - Notify when an email is opened or a link clicked
 *  - Report the active tab's platform support (GET_PLATFORM_STATUS)
 *  - Keep the sender's own browser from loading its own tracking pixels
 *  - Keep the session (token/email) and settings in chrome.storage.local
 */

const DEFAULT_API_BASE = 'http://localhost:8080';

// The session token is stored here: keep it out of reach of content scripts, which
// share a process with the webmail page.
chrome.storage.local.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' });

async function getApiBase() {
  return new Promise(resolve => {
    chrome.storage.local.get(['nudge_api_base'], r =>
      resolve(r.nudge_api_base || DEFAULT_API_BASE)
    );
  });
}

/** The message text is only stored if the user asked for it (AI follow-ups). */
async function withoutContent(payload) {
  const { nudge_store_content: store } = await chrome.storage.local.get(['nudge_store_content']);
  if (store === true) return payload;
  const { content, ...rest } = payload;
  return rest;
}

// ── Own-pixel protection ──────────────────────────────────────
//
// The compose editor, the Sent folder and quoted replies all load the pixel, which
// would mark the email as read by its own sender. Each of the user's tracking IDs
// gets a network rule that answers with a local GIF instead.
//
// Matching the ID alone, wherever it appears in the URL, also covers webmails that
// load images through a proxy (Gmail keeps the original URL after '#', Yahoo and
// Proton in a query parameter). Rules are per ID so that emails received from other
// Nudge users are still reported to them.

const BLANK_GIF = 'data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACwAAAAAAQABAAACAkQBADs=';
const MAX_OWN_PIXELS = 4000; // Chrome allows 5000 dynamic redirect rules
const TRACKING_ID = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;

let ruleUpdates = Promise.resolve();

/** Updates are queued: rule numbers are derived from the rules already installed. */
function ignoreOwnPixels(trackingIds) {
  ruleUpdates = ruleUpdates.catch(() => {}).then(() => addOwnPixelRules(trackingIds));
  return ruleUpdates;
}

async function addOwnPixelRules(trackingIds) {
  const dnr = chrome.declarativeNetRequest;
  const existing = (await dnr.getDynamicRules()).sort((a, b) => a.id - b.id);
  const known = new Set(existing.map(rule => rule.condition.urlFilter));
  const added = [...new Set(trackingIds)].filter(id => TRACKING_ID.test(id) && !known.has(id)).slice(-MAX_OWN_PIXELS);
  if (!added.length) return;
  let nextId = (existing.at(-1)?.id ?? 0) + 1;
  const overflow = Math.max(0, existing.length + added.length - MAX_OWN_PIXELS);
  await dnr.updateDynamicRules({
    removeRuleIds: existing.slice(0, overflow).map(rule => rule.id),
    addRules: added.map(id => ({
      id: nextId++,
      action: { type: 'redirect', redirect: { url: BLANK_GIF } },
      condition: { urlFilter: id, resourceTypes: ['image', 'xmlhttprequest'] }
    }))
  });
}

/** Covers emails registered from the dashboard or from another browser. */
async function syncOwnPixels() {
  const { content = [] } = await apiGet('/api/emails?size=200');
  await ignoreOwnPixels(content.map(email => email.trackingId).reverse()); // oldest first
}

// ── Message router ────────────────────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  switch (message.type) {

    case 'REGISTER_EMAIL':
      handleRegisterEmail(message.payload)
        .then(sendResponse)
        .catch(err => sendResponse({ success: false, error: err.message }));
      return true;

    case 'GET_AUTH_STATUS':
      getAuthStatus().then(sendResponse);
      return true;

    case 'SYNC_OWN_PIXELS':
      syncOwnPixels()
        .then(() => sendResponse({ ok: true }))
        .catch(error => {
          console.warn('[Nudge] own pixels not synced:', error.message);
          sendResponse({ ok: false });
        });
      return true;

    case 'GET_RECENT_EMAILS':
      apiGet('/api/emails?size=10')
        .then(page => sendResponse({ ok: true, emails: page.content ?? [] }))
        .catch(error => sendResponse({ ok: false, signedOut: error.signedOut === true, error: error.message }));
      return true;

    case 'GET_SENT_STATUS':
      // Only what the ticks need: this goes to a content script.
      apiGet('/api/emails?size=200')
        .then(({ content = [] }) => sendResponse({ ok: true, emails: content.map(
          ({ subject, recipientEmail, createdAt, status, openCount, lastOpenedAt, lastClickedAt }) =>
            ({ subject, recipientEmail, createdAt, status, openCount, lastOpenedAt, lastClickedAt })) }))
        .catch(error => sendResponse({ ok: false, signedOut: error.signedOut === true }));
      return true;

    case 'GET_SEND_TIME':
      handleGetSendTime()
        .then(sendResponse)
        .catch(() => sendResponse({ suggestion: 'No data yet' }));
      return true;

    case 'GET_PLATFORM_STATUS':
      // Called by popup.js to know what the active tab supports
      handleGetPlatformStatus(sender).then(sendResponse);
      return true;

    case 'PLATFORM_UNSUPPORTED':
      // content.js fires this when it loads on an unsupported host

      return false;

    case 'LOGOUT':
      handleLogout().then(sendResponse).catch(error => sendResponse({ ok: false, error: error.message }));
      return true;
  }
});

// ── Register email ────────────────────────────────────────────

async function handleRegisterEmail(payload) {
  const [apiBase, { token }] = await Promise.all([getApiBase(), getStoredCredentials()]);
  if (!token) {
    return { success: false, error: 'Not authenticated. Please sign in via the Nudge popup.' };
  }

  const response = await fetch(`${apiBase}/api/emails`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    signal: AbortSignal.timeout(15000),
    body: JSON.stringify(await withoutContent(payload))
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    return { success: false, error: err.error || 'Backend error' };
  }

  // API returns List<EmailDTO> — one per recipient
  const data = await response.json();
  const arr  = Array.isArray(data) ? data : [data];
  // Must be in place before the pixel reaches the editor. A failure does not block
  // sending: the server also ignores the sender's browser for a few minutes.
  await ignoreOwnPixels(arr.map(d => d.trackingId))
    .catch(error => console.warn('[Nudge] own pixel not ignored:', error.message));
  return {
    success: true,
    results: arr.map(d => ({ trackingId: d.trackingId, trackingPixelUrl: d.trackingPixelUrl }))
  };
}

// ── Open notifications ────────────────────────────────────────
//
// A Manifest V3 worker is stopped when idle, so it cannot hold a WebSocket: an
// alarm polls the server. The time of the latest activity already seen is kept on
// disk, so what happened while the browser was closed is notified at next sign-in.

const ACTIVITY_ALARM = 'check-activity';
const MAX_SEPARATE_NOTIFICATIONS = 3;

// Re-creating an existing alarm would postpone it each time the worker wakes up.
chrome.alarms.get(ACTIVITY_ALARM).then(alarm => {
  if (!alarm) chrome.alarms.create(ACTIVITY_ALARM, { delayInMinutes: 1, periodInMinutes: 1 });
});
chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === ACTIVITY_ALARM) checkActivity();
});

let activityCheck = null;
function checkActivity() {
  activityCheck ??= notifyNewActivity()
    .catch(error => { if (!error.signedOut) console.warn('[Nudge] activity check failed:', error.message); })
    .finally(() => { activityCheck = null; });
  return activityCheck;
}

/** Server dates are UTC without a zone: their text order is their time order. */
function lastActivity(email) {
  const opened = email.lastOpenedAt ?? '', clicked = email.lastClickedAt ?? '';
  return opened > clicked ? opened : clicked;
}

async function notifyNewActivity() {
  const [apiBase, { token, email: owner }] = await Promise.all([getApiBase(), getStoredCredentials()]);
  if (!token) return;
  const { content = [] } = await apiGet('/api/emails?sort=lastActivityAt&size=20');
  const account = `${apiBase}|${owner}`;
  const { nudge_seen: seen, nudge_notifications: enabled } =
    await chrome.storage.local.get(['nudge_seen', 'nudge_notifications']);
  const latest = content.map(lastActivity).reduce((a, b) => (a > b ? a : b), '');

  // First check for this account: past activity is not news.
  const known = seen?.account === account;
  if (known && enabled !== false) {
    await showActivity(content.filter(email => lastActivity(email) > seen.at));
  }
  const at = known && seen.at > latest ? seen.at : latest;
  if (!known || at !== seen.at) await chrome.storage.local.set({ nudge_seen: { account, at } });
}

async function showActivity(emails) {
  const show = (id, title, message) => chrome.notifications.create(id, {
    type: 'basic', iconUrl: chrome.runtime.getURL('icons/icon128.png'), title, message
  });
  if (emails.length > MAX_SEPARATE_NOTIFICATIONS) {
    await show('nudge-summary', `New activity on ${emails.length} emails`, emails.map(email => email.subject).join(', '));
    return;
  }
  for (const email of emails) {
    const clicked = (email.lastClickedAt ?? '') > (email.lastOpenedAt ?? '');
    const title = clicked ? 'Link clicked' : email.openCount > 1 ? 'Email opened again' : 'Email opened';
    await show(`nudge-${email.id}`, title, `${email.recipientEmail || 'Recipient'} — ${email.subject}`);
  }
}

chrome.notifications.onClicked.addListener(async id => {
  chrome.notifications.clear(id);
  chrome.tabs.create({ url: `${await getApiBase()}/dashboard.html` });
});

// Without a session nothing is tracked or notified: make it visible on the icon.
async function updateSignInBadge() {
  const { token } = await getStoredCredentials();
  chrome.action.setBadgeBackgroundColor({ color: '#ef4444' });
  chrome.action.setBadgeText({ text: token ? '' : '!' });
}
chrome.runtime.onStartup.addListener(updateSignInBadge);
chrome.runtime.onInstalled.addListener(updateSignInBadge);
chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== 'local' || !changes.nudge_token) return;
  updateSignInBadge();
  if (changes.nudge_token.newValue) checkActivity();
});

// ── Authenticated requests ────────────────────────────────────

async function apiGet(path) {
  const [apiBase, { token }] = await Promise.all([getApiBase(), getStoredCredentials()]);
  const signedOut = () => Object.assign(new Error('Please sign in.'), { signedOut: true });
  if (!token) throw signedOut();
  const response = await fetch(apiBase + path, {
    headers: { 'Authorization': `Bearer ${token}` },
    signal: AbortSignal.timeout(15000)
  });
  if (response.status === 401) {
    // Expired session, unless the user signed in again in the meantime.
    if ((await getStoredCredentials()).token === token) {
      await chrome.storage.local.remove(['nudge_token', 'nudge_email']);
    }
    throw signedOut();
  }
  if (!response.ok) throw new Error(`Server error (${response.status})`);
  return response.json();
}

// ── Send-time suggestion ──────────────────────────────────────

async function handleGetSendTime() {
  const [apiBase, { token }] = await Promise.all([getApiBase(), getStoredCredentials()]);
  if (!token) return { suggestion: 'Sign in to get insights' };

  const response = await fetch(`${apiBase}/api/ai/send-time`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({ timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone })
  });

  if (!response.ok) return { suggestion: 'No data yet' };

  const data = await response.json();
  return { suggestion: data.suggestion ?? 'No data yet' };
}

// ── Platform status (for popup) ───────────────────────────────

async function handleGetPlatformStatus() {
  // Ask the active tab which platform it's on
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) return { platform: 'unknown', supported: false };

    const url  = new URL(tab.url || '');
    const host = url.hostname;

    if (host === 'mail.google.com')             return { platform: 'Gmail',        supported: true };
    if (host.endsWith('outlook.live.com'))      return { platform: 'Outlook',      supported: true };
    if (host.endsWith('outlook.office.com'))    return { platform: 'Outlook',      supported: true };
    if (host.endsWith('outlook.office365.com')) return { platform: 'Outlook 365',  supported: true };
    if (host === 'mail.proton.me')              return { platform: 'Proton Mail',  supported: true };
    if (host === 'mail.protonmail.com')         return { platform: 'Proton Mail',  supported: true };
    if (host === 'app.tuta.com' || host === 'mail.tutanota.com') return { platform: 'Tuta', supported: true };
    if (host === 'mail.infomaniak.com')         return { platform: 'Infomaniak',   supported: true };
    if (host === 'mail.yahoo.com')              return { platform: 'Yahoo Mail',   supported: true };

    // Any web email not in our injection list — offer dashboard fallback
    return { platform: host || 'Unknown', supported: false };
  } catch {
    return { platform: 'Unknown', supported: false };
  }
}

// ── Auth helpers ──────────────────────────────────────────────

async function getAuthStatus() {
  const { token, email } = await getStoredCredentials();
  return { loggedIn: !!token, email };
}

function getStoredCredentials() {
  return new Promise(resolve => {
    chrome.storage.local.get(['nudge_token', 'nudge_email'], result => {
      resolve({ token: result.nudge_token, email: result.nudge_email });
    });
  });
}

async function handleLogout() {
  const [apiBase, { token }] = await Promise.all([getApiBase(), getStoredCredentials()]);
  if (token) {
    const response = await fetch(`${apiBase}/api/auth/logout`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15000)
    });
    if (!response.ok && response.status !== 401) throw new Error('Sign out failed. Please retry.');
  }
  await chrome.storage.local.remove(['nudge_token', 'nudge_email']);
  return { ok: true };
}

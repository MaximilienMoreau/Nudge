/**
 * popup.js — Nudge Extension Popup
 *
 * On open:
 *  1. Checks auth status
 *  2. Detects the platform of the active tab (via background.js)
 *  3. Lists recent emails with their read status
 *  4. Shows the right tip: inline-injection tip vs. dashboard fallback
 */

const DEFAULT_API = 'http://localhost:8080';

function getApiBase() {
  return new Promise(resolve => {
    chrome.storage.local.get(['nudge_api_base'], r =>
      resolve(r.nudge_api_base || DEFAULT_API)
    );
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  document.querySelectorAll('[data-tab]').forEach(button => {
    button.addEventListener('click', () => showTab(button.dataset.tab, button));
  });
  document.querySelectorAll('[data-action="logout"]').forEach(button => button.addEventListener('click', logout));
  document.getElementById('login-btn').addEventListener('click', login);
  document.getElementById('password').addEventListener('keydown', event => { if (event.key === 'Enter') login(); });
  document.getElementById('save-server-btn').addEventListener('click', () => {
    saveServerUrl().catch(error => showServerError(error.message));
  });
  await setUpToggles();
  document.getElementById('describe-btn').addEventListener('click', copyPageStructure);
  const apiBase = await getApiBase();

  // Populate server URL field
  document.getElementById('server-url').value = apiBase;

  // Wire up dynamic dashboard links
  const dashUrl = `${apiBase}/dashboard.html`;
  document.getElementById('dashboard-link').href = dashUrl;
  document.getElementById('dashboard-link-track').href = dashUrl;

  await Promise.all([
    checkAuthStatus(),
    detectPlatform()
  ]);
});

async function saveServerUrl() {
  const val = document.getElementById('server-url').value.trim().replace(/\/$/, '');
  let server;
  try {
    server = new URL(val);
    if (server.protocol !== 'https:' && !(server.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(server.hostname))) throw new Error();
    if (server.username || server.password || server.pathname !== '/' || server.search || server.hash) throw new Error();
  } catch { showServerError('Use an HTTPS server URL, or HTTP localhost for development.'); return; }
  const granted = await chrome.permissions.request({ origins: [`${server.origin}/*`] });
  if (!granted) { showServerError('Server access was not granted.'); return; }
  const previous = await getApiBase();
  if (previous !== server.origin) {
    await chrome.storage.local.remove(['nudge_token', 'nudge_email']);
    showLoginView();
  }
  await chrome.storage.local.set({ nudge_api_base: val });
  const dashUrl = `${val}/dashboard.html`;
  document.getElementById('dashboard-link').href = dashUrl;
  document.getElementById('dashboard-link-track').href = dashUrl;
  document.getElementById('server-status').style.display = 'none';
  const btn = document.getElementById('save-server-btn');
  btn.textContent = '✓';
  setTimeout(() => { btn.textContent = 'Save'; }, 1500);
}

// ── Auth ──────────────────────────────────────────────────────

async function checkAuthStatus() {
  const response = await chrome.runtime.sendMessage({ type: 'GET_AUTH_STATUS' });
  if (response.loggedIn) {
    showLoggedInView(response.email);
  } else {
    showLoginView();
  }
}

async function login() {
  const email    = document.getElementById('email').value.trim();
  const password = document.getElementById('password').value;
  const errEl    = document.getElementById('login-error');
  const btn      = document.getElementById('login-btn');

  errEl.style.display = 'none';
  if (!email || !password) { showError('Please enter your email and password.'); return; }

  btn.textContent = 'Signing in…';
  btn.disabled    = true;

  try {
    const apiBase = await getApiBase();
    const res  = await fetch(`${apiBase}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, rememberMe: true })
    });
    const data = await res.json();

    if (!res.ok) { showError(data.error || 'Login failed'); return; }

    await chrome.storage.local.set({ nudge_token: data.token, nudge_email: data.email });
    document.getElementById('password').value = '';
    chrome.runtime.sendMessage({ type: 'SYNC_OWN_PIXELS' }).catch(() => {});
    showLoggedInView(data.email);
  } catch {
    showError('Cannot reach Nudge backend. Is it running?');
  } finally {
    btn.textContent = 'Sign In';
    btn.disabled    = false;
  }
}

async function logout() {
  const result = await chrome.runtime.sendMessage({ type: 'LOGOUT' });
  if (result?.ok) showLoginView();
  else document.getElementById('status-text').textContent = result?.error || 'Sign out failed';
}

// ── Recent emails ─────────────────────────────────────────────

const READ_BADGES = {
  'Not Opened':            { label: '✓ Not opened', style: '', hint: 'No open detected. The email may still have been read with images blocked.' },
  'Unconfirmed':           { label: '✓? Unconfirmed', style: 'unconfirmed', hint: 'Apple Mail and Proton Mail load images automatically: impossible to tell.' },
  'Opened':                { label: '✓✓ Opened', style: 'opened', hint: '' },
  'Opened Multiple Times': { label: '✓✓ Opened', style: 'opened', hint: '' }
};

async function loadRecentEmails() {
  const message = document.getElementById('recent-message');
  let result;
  try { result = await chrome.runtime.sendMessage({ type: 'GET_RECENT_EMAILS' }); }
  catch (error) { result = { ok: false, error: error.message }; }

  if (result?.signedOut) { showLoginView(); return; }
  const dashUrl = `${await getApiBase()}/dashboard.html`;
  const emails  = result?.ok ? result.emails : [];
  document.getElementById('recent-list').replaceChildren(...emails.map(email => emailCard(email, dashUrl)));
  message.textContent = !result?.ok ? `Emails unavailable: ${result?.error ?? 'no response'}`
    : emails.length ? '' : 'No tracked email yet.';
  message.classList.toggle('hidden', !message.textContent);
}

function emailCard(email, dashUrl) {
  const el = (tag, className, text = '') => Object.assign(document.createElement(tag), { className, textContent: text });
  const badge = READ_BADGES[email.status] ?? { label: email.status, style: '', hint: '' };
  const badgeEl = el('span', `p-read-badge ${badge.style}`, badge.label);
  badgeEl.title = badge.hint;

  const seenAt = [email.lastOpenedAt, email.lastClickedAt].filter(Boolean).sort().at(-1);
  const count  = email.openCount > 1 ? `${email.openCount}× · ` : '';
  const time   = el('span', 'p-email-time', count + (seenAt ? timeAgo(seenAt) : `sent ${timeAgo(email.createdAt)}`));

  const top = el('div', 'p-email-top');
  top.append(el('span', 'p-email-subject', email.subject), badgeEl);
  const bottom = el('div', 'p-email-bottom');
  bottom.append(el('span', 'p-email-recipient', email.recipientEmail ?? ''), time);

  const card = el('a', 'p-email-card');
  card.href = dashUrl;
  card.target = '_blank';
  card.append(top, bottom);
  return card;
}

/** Server dates are UTC, sent without a zone. */
function timeAgo(serverDate) {
  const date = new Date(`${serverDate}Z`);
  const minutes = Math.floor((Date.now() - date) / 60000);
  if (minutes < 1)    return 'just now';
  if (minutes < 60)   return `${minutes} min ago`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)} h ago`;
  if (minutes < 10080) return `${Math.floor(minutes / 1440)} d ago`;
  return date.toLocaleDateString();
}

async function setUpToggles() {
  const toggle = document.getElementById('notifications-toggle');
  const { nudge_notifications: enabled } = await chrome.storage.local.get(['nudge_notifications']);
  toggle.checked = enabled !== false;
  toggle.addEventListener('change', () => chrome.storage.local.set({ nudge_notifications: toggle.checked }));

  const content = document.getElementById('content-toggle');
  content.checked = (await chrome.storage.local.get(['nudge_store_content'])).nudge_store_content === true;
  content.addEventListener('change', () => chrome.storage.local.set({ nudge_store_content: content.checked }));
}

// ── Problem report ────────────────────────────────────────────

async function copyPageStructure() {
  const button = document.getElementById('describe-btn');
  const area   = document.getElementById('describe-result');
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    const [{ result }] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['describe.js'] });
    if (!result) throw new Error('Open a new message first');
    area.value = result;
    area.classList.remove('hidden');
    area.select();
    await navigator.clipboard.writeText(result);
    button.textContent = '✓ Copied';
  } catch (error) {
    button.textContent = area.value ? 'Copy the text below' : /new message/.test(error.message) ? error.message : 'Not available on this page';
  }
}

// ── Platform detection ────────────────────────────────────────

async function detectPlatform() {
  try {
    const { platform, supported } = await chrome.runtime.sendMessage({ type: 'GET_PLATFORM_STATUS' });
    const badge  = document.getElementById('platform-badge');
    const tipOk  = document.getElementById('tip-supported');
    const tipFb  = document.getElementById('tip-fallback');

    badge.textContent = platform || 'Unknown';
    badge.className   = `platform-badge ${supported ? 'platform-supported' : 'platform-unsupported'}`;

    if (supported) {
      tipOk.classList.remove('hidden');
      tipFb.classList.add('hidden');
    } else {
      tipOk.classList.add('hidden');
      tipFb.classList.remove('hidden');
    }
  } catch {
    // Non-critical — leave defaults
  }
}

// ── UI helpers ────────────────────────────────────────────────

function showLoggedInView(email) {
  document.getElementById('login-view').classList.add('hidden');
  document.getElementById('logged-view').classList.remove('hidden');
  document.getElementById('user-email-display').textContent = email;
  document.getElementById('sidebar-logout-btn').style.display = '';
  setStatus(true, 'Connected');
  loadRecentEmails();
}

function showLoginView() {
  document.getElementById('login-view').classList.remove('hidden');
  document.getElementById('logged-view').classList.add('hidden');
  document.getElementById('sidebar-logout-btn').style.display = 'none';
  setStatus(false, 'Not signed in');
}

function setStatus(connected, text) {
  document.getElementById('status-dot').classList.toggle('connected', connected);
  document.getElementById('status-text').textContent = text;
}

function showError(msg) {
  const el = document.getElementById('login-error');
  el.textContent = msg;
  el.style.display = 'block';
}

function showServerError(message) {
  const element = document.getElementById('server-status');
  element.textContent = message;
  element.style.display = 'block';
}

function showTab(name, button) {
  document.querySelectorAll('.p-tab-panel').forEach(element => element.classList.add('hidden'));
  document.querySelectorAll('[data-tab]').forEach(element => element.classList.remove('active'));
  document.getElementById(`tab-${name}`).classList.remove('hidden');
  button?.classList.add('active');
}

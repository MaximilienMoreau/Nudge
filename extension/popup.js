/**
 * popup.js — Nudge Extension Popup
 *
 * On open:
 *  1. Checks auth status
 *  2. Detects the platform of the active tab (via background.js)
 *  3. Shows the right tip: inline-injection tip vs. dashboard fallback
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
    await chrome.storage.session.remove(['nudge_token', 'nudge_email']);
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
      body: JSON.stringify({ email, password })
    });
    const data = await res.json();

    if (!res.ok) { showError(data.error || 'Login failed'); return; }

    await chrome.storage.session.set({ nudge_token: data.token, nudge_email: data.email });
    document.getElementById('password').value = '';
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

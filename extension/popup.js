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
  const apiBase = await getApiBase();

  // Populate server URL field
  document.getElementById('server-url').value = apiBase;

  // Wire up dynamic dashboard link
  document.getElementById('dashboard-link').href = `${apiBase}/dashboard.html`;

  await Promise.all([
    checkAuthStatus(),
    detectPlatform()
  ]);
});

async function saveServerUrl() {
  const val = document.getElementById('server-url').value.trim().replace(/\/$/, '');
  if (!val.startsWith('http')) return;
  await chrome.storage.local.set({ nudge_api_base: val });
  document.getElementById('dashboard-link').href = `${val}/dashboard.html`;
  const btn = document.querySelector('[onclick="saveServerUrl()"]');
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

  errEl.classList.add('hidden');
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

    await chrome.storage.local.set({ nudge_token: data.token, nudge_email: data.email });
    showLoggedInView(data.email);
  } catch {
    showError('Cannot reach Nudge backend. Is it running?');
  } finally {
    btn.textContent = 'Sign In';
    btn.disabled    = false;
  }
}

async function logout() {
  await chrome.runtime.sendMessage({ type: 'LOGOUT' });
  showLoginView();
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
  setStatus(true, 'Connected — tracking active');
}

function showLoginView() {
  document.getElementById('login-view').classList.remove('hidden');
  document.getElementById('logged-view').classList.add('hidden');
  setStatus(false, 'Not signed in');
}

function setStatus(connected, text) {
  document.getElementById('status-dot').classList.toggle('connected', connected);
  document.getElementById('status-text').textContent = text;
}

function showError(msg) {
  const el = document.getElementById('login-error');
  el.textContent = msg;
  el.classList.remove('hidden');
}

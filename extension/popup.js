/**
 * popup.js — Nudge Extension Popup
 *
 * Handles login/logout within the extension.
 * Credentials are stored in chrome.storage.local so background.js can use them.
 */

const API = 'http://localhost:8080';

// ── Init ──────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  checkAuthStatus();
});

async function checkAuthStatus() {
  const response = await chrome.runtime.sendMessage({ type: 'GET_AUTH_STATUS' });

  if (response.loggedIn) {
    showLoggedInView(response.email);
  } else {
    showLoginView();
  }
}

// ── Login ─────────────────────────────────────────────────────

async function login() {
  const email    = document.getElementById('email').value.trim();
  const password = document.getElementById('password').value;
  const errEl    = document.getElementById('login-error');
  const btn      = document.getElementById('login-btn');

  errEl.classList.add('hidden');

  if (!email || !password) {
    showError('Please enter your email and password.');
    return;
  }

  btn.textContent = 'Signing in…';
  btn.disabled = true;

  try {
    const res = await fetch(`${API}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password })
    });

    const data = await res.json();

    if (!res.ok) {
      showError(data.error || 'Login failed');
      return;
    }

    // Store credentials in extension storage
    await chrome.storage.local.set({
      nudge_token: data.token,
      nudge_email: data.email
    });

    showLoggedInView(data.email);

  } catch (e) {
    showError('Cannot reach Nudge backend. Is it running?');
  } finally {
    btn.textContent = 'Sign In';
    btn.disabled = false;
  }
}

// ── Logout ────────────────────────────────────────────────────

async function logout() {
  await chrome.runtime.sendMessage({ type: 'LOGOUT' });
  showLoginView();
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

/**
 * auth.js — Login / Register page logic
 * Communicates with POST /api/auth/login and /api/auth/register
 */

const API = 'http://localhost:8080';

// ── Tab switching ─────────────────────────────────────────────

function showTab(tab) {
  const isLogin = tab === 'login';
  document.getElementById('login-form').style.display    = isLogin ? '' : 'none';
  document.getElementById('register-form').style.display = isLogin ? 'none' : '';
  document.getElementById('tab-login').classList.toggle('active', isLogin);
  document.getElementById('tab-register').classList.toggle('active', !isLogin);
}

// ── Login ─────────────────────────────────────────────────────

async function handleLogin(e) {
  e.preventDefault();
  const btn = document.getElementById('login-btn');
  const errEl = document.getElementById('login-error');
  errEl.style.display = 'none';

  setLoading(btn, true);

  try {
    const res = await fetch(`${API}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email:    document.getElementById('login-email').value.trim(),
        password: document.getElementById('login-password').value
      })
    });

    const data = await res.json();

    if (!res.ok) {
      showError(errEl, data.error || 'Login failed');
      return;
    }

    // Persist JWT and user info for dashboard use
    localStorage.setItem('nudge_token',  data.token);
    localStorage.setItem('nudge_email',  data.email);
    localStorage.setItem('nudge_userId', data.userId);

    window.location.href = 'dashboard.html';

  } catch (err) {
    showError(errEl, 'Cannot reach server. Is the backend running?');
  } finally {
    setLoading(btn, false);
  }
}

// ── Register ──────────────────────────────────────────────────

async function handleRegister(e) {
  e.preventDefault();
  const btn = document.getElementById('reg-btn');
  const errEl = document.getElementById('reg-error');
  errEl.style.display = 'none';

  setLoading(btn, true);

  try {
    const res = await fetch(`${API}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email:    document.getElementById('reg-email').value.trim(),
        password: document.getElementById('reg-password').value
      })
    });

    const data = await res.json();

    if (!res.ok) {
      showError(errEl, data.error || 'Registration failed');
      return;
    }

    localStorage.setItem('nudge_token',  data.token);
    localStorage.setItem('nudge_email',  data.email);
    localStorage.setItem('nudge_userId', data.userId);

    window.location.href = 'dashboard.html';

  } catch (err) {
    showError(errEl, 'Cannot reach server. Is the backend running?');
  } finally {
    setLoading(btn, false);
  }
}

// ── Helpers ───────────────────────────────────────────────────

function setLoading(btn, loading) {
  btn.disabled = loading;
  btn.innerHTML = loading
    ? '<span class="spinner"></span> Please wait...'
    : btn.id === 'login-btn' ? 'Sign In' : 'Create Account';
}

function showError(el, msg) {
  el.textContent = msg;
  el.style.display = 'block';
}

// Redirect if already logged in
if (localStorage.getItem('nudge_token')) {
  window.location.href = 'dashboard.html';
}

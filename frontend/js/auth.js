/**
 * auth.js — Login / Register page logic
 *
 * A2: Uses NUDGE_CONFIG.API_BASE from config.js (no hardcoded URL).
 * U9: Client-side password strength validation with inline feedback.
 */

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
  const btn   = document.getElementById('login-btn');
  const errEl = document.getElementById('login-error');
  errEl.style.display = 'none';
  setLoading(btn, true);

  try {
    const res = await fetch(`${NUDGE_CONFIG.API_BASE}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email:    document.getElementById('login-email').value.trim(),
        password: document.getElementById('login-password').value
      })
    });

    const data = await res.json();
    if (!res.ok) { showError(errEl, data.error || 'Login failed'); return; }

    localStorage.setItem('nudge_token',  data.token);
    localStorage.setItem('nudge_email',  data.email);
    localStorage.setItem('nudge_userId', data.userId);
    window.location.href = 'dashboard.html';

  } catch {
    showError(errEl, 'Cannot reach server. Is the backend running?');
  } finally {
    setLoading(btn, false);
  }
}

// ── Register ──────────────────────────────────────────────────

async function handleRegister(e) {
  e.preventDefault();
  const btn    = document.getElementById('reg-btn');
  const errEl  = document.getElementById('reg-error');
  errEl.style.display = 'none';

  // U9: Password strength check before sending to server
  const password = document.getElementById('reg-password').value;
  const strength = checkPasswordStrength(password);
  if (!strength.ok) {
    showError(errEl, strength.message);
    return;
  }

  setLoading(btn, true);

  try {
    const res = await fetch(`${NUDGE_CONFIG.API_BASE}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email:    document.getElementById('reg-email').value.trim(),
        password
      })
    });

    const data = await res.json();
    if (!res.ok) { showError(errEl, data.error || 'Registration failed'); return; }

    localStorage.setItem('nudge_token',  data.token);
    localStorage.setItem('nudge_email',  data.email);
    localStorage.setItem('nudge_userId', data.userId);
    window.location.href = 'dashboard.html';

  } catch {
    showError(errEl, 'Cannot reach server. Is the backend running?');
  } finally {
    setLoading(btn, false);
  }
}

// ── U9: Password strength ─────────────────────────────────────

/**
 * Returns { ok: boolean, message: string, score: 0-4 }.
 * Rules: min 8 chars, uppercase, digit, special char.
 */
function checkPasswordStrength(password) {
  if (password.length < 8) {
    return { ok: false, score: 0, message: 'Password must be at least 8 characters.' };
  }
  const hasUpper   = /[A-Z]/.test(password);
  const hasDigit   = /\d/.test(password);
  const hasSpecial = /[^A-Za-z0-9]/.test(password);

  const score = (hasUpper ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);

  if (score < 2) {
    return {
      ok: false, score,
      message: 'Password must contain at least one uppercase letter and one number or special character.'
    };
  }
  return { ok: true, score: score + 1, message: '' };
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

// ── Password strength indicator (live feedback) ───────────────

document.addEventListener('DOMContentLoaded', () => {
  const pwInput    = document.getElementById('reg-password');
  const strengthEl = document.getElementById('pw-strength');
  if (!pwInput || !strengthEl) return;

  pwInput.addEventListener('input', () => {
    const result = checkPasswordStrength(pwInput.value);
    const labels = ['', 'Weak', 'Fair', 'Good', 'Strong'];
    const colors = ['', '#ef4444', '#f59e0b', '#6366f1', '#22c55e'];
    strengthEl.textContent = pwInput.value ? labels[result.score] || 'Weak' : '';
    strengthEl.style.color = colors[result.score] || '#ef4444';
  });
});

// Redirect if already logged in
if (localStorage.getItem('nudge_token')) {
  window.location.href = 'dashboard.html';
}

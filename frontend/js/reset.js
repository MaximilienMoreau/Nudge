/** reset.js — Choose a new password from an emailed link. */

const token = new URLSearchParams(window.location.hash.slice(1)).get('token');
history.replaceState(null, '', window.location.pathname); // keep the token out of the history
// A new link opened in this tab only changes the fragment
window.addEventListener('hashchange', () => window.location.reload());

document.addEventListener('DOMContentLoaded', () => {
  const form   = document.getElementById('reset-form');
  const button = document.getElementById('reset-btn');
  const result = document.getElementById('reset-result');
  const show = (text, ok = false) => {
    result.textContent = text;
    result.style.color = ok ? 'var(--success)' : 'var(--danger)';
  };
  if (!token) {
    show('This link is incomplete. Ask for a new one from the sign-in page.');
    button.disabled = true;
  }

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const newPassword = document.getElementById('reset-password').value;
    if (newPassword !== document.getElementById('reset-confirm').value) { show('The passwords do not match.'); return; }
    button.disabled = true;
    try {
      const res = await fetch(`${NUDGE_CONFIG.API_BASE}/api/auth/reset-password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, newPassword })
      });
      const data = await res.json();
      if (!res.ok) { show(data.error ?? 'Request failed'); button.disabled = false; return; }
      for (const key of ['nudge_email', 'nudge_userId', 'nudge_createdAt']) localStorage.removeItem(key);
      form.reset();
      show('Password changed. You can sign in.', true);
    } catch {
      show('Cannot reach server.');
      button.disabled = false;
    }
  });
});

/**
 * dashboard.js — Main dashboard logic
 * Handles email listing, tracking creation, and AI follow-up generation.
 */

const API = 'http://localhost:8080';

// ── Auth guard ────────────────────────────────────────────────

const token  = localStorage.getItem('nudge_token');
const userEmail = localStorage.getItem('nudge_email');

if (!token) {
  window.location.href = 'index.html';
}

// ── State ─────────────────────────────────────────────────────

let allEmails = [];          // cached email list
let followUpEmail = null;    // email context for AI modal

// ── Init ──────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  // Display user info in sidebar
  const emailEl = document.getElementById('user-email-display');
  const avatarEl = document.getElementById('user-avatar');
  if (userEmail) {
    emailEl.textContent = userEmail;
    avatarEl.textContent = userEmail[0].toUpperCase();
  }

  loadEmails();
});

// ── View switching ────────────────────────────────────────────

function showView(name) {
  document.getElementById('view-emails').style.display = name === 'emails' ? '' : 'none';
  document.getElementById('view-track').style.display  = name === 'track' ? '' : 'none';
  document.getElementById('view-title').textContent =
    name === 'emails' ? 'Email Dashboard' : 'Track New Email';

  document.querySelectorAll('.nav-item').forEach((el, i) => {
    el.classList.toggle('active', (i === 0 && name === 'emails') || (i === 1 && name === 'track'));
  });
}

// ── Load emails ───────────────────────────────────────────────

async function loadEmails() {
  const tbody = document.getElementById('emails-tbody');
  tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state"><div class="spinner"></div></div></td></tr>`;

  try {
    const res = await authFetch('/api/emails');
    if (!res.ok) throw new Error('Failed to load emails');

    allEmails = await res.json();
    renderEmailTable(allEmails);
    updateStats(allEmails);
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="7"><div class="empty-state">
      <div class="empty-icon">⚠️</div>
      <h3>Could not load emails</h3>
      <p>${err.message}</p>
    </div></td></tr>`;
  }
}

function renderEmailTable(emails) {
  const tbody = document.getElementById('emails-tbody');

  if (emails.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7">
      <div class="empty-state">
        <div class="empty-icon">📭</div>
        <h3>No tracked emails yet</h3>
        <p style="color:var(--muted)">Click <strong>+ Track Email</strong> to get started.</p>
      </div>
    </td></tr>`;
    return;
  }

  tbody.innerHTML = emails.map(email => {
    const isHot = email.leadScore >= 70;
    const rowClass = isHot ? 'hot-lead' : '';
    const hotBadge = isHot ? ' 🔥' : '';

    return `
    <tr class="${rowClass}">
      <td>
        <div style="font-weight:600;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">
          ${escHtml(email.subject)}${hotBadge}
        </div>
      </td>
      <td style="color:var(--muted)">${escHtml(email.recipientEmail)}</td>
      <td style="color:var(--muted);white-space:nowrap">${formatDate(email.createdAt)}</td>
      <td>${statusBadge(email.status)}</td>
      <td>${scoreBar(email.leadScore)}</td>
      <td style="color:var(--muted);white-space:nowrap">${email.lastOpenedAt ? formatDate(email.lastOpenedAt) : '—'}</td>
      <td>
        <button class="btn btn-sm btn-secondary" onclick="openFollowUpModal(${email.id})">
          🤖 Follow Up
        </button>
      </td>
    </tr>`;
  }).join('');
}

// ── Stats ─────────────────────────────────────────────────────

function updateStats(emails) {
  const total  = emails.length;
  const opened = emails.filter(e => e.openCount > 0).length;
  const hot    = emails.filter(e => e.leadScore >= 70).length;
  const avg    = total > 0 ? Math.round(emails.reduce((s, e) => s + e.leadScore, 0) / total) : 0;

  document.getElementById('stat-total').textContent  = total;
  document.getElementById('stat-opened').textContent = opened;
  document.getElementById('stat-hot').textContent    = hot;
  document.getElementById('stat-avg-score').textContent = avg;
}

// ── Track Email (standalone page) ────────────────────────────

async function handleTrackEmail(e) {
  e.preventDefault();
  const btn = document.getElementById('track-btn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Creating…';

  const body = {
    subject: document.getElementById('t-subject').value.trim(),
    recipientEmail: document.getElementById('t-recipient').value.trim(),
    content: document.getElementById('t-content').value.trim()
  };

  try {
    const res = await authFetch('/api/emails', { method: 'POST', body: JSON.stringify(body) });
    const data = await res.json();

    if (!res.ok) throw new Error(data.error || 'Failed to create');

    showTrackResult(data, 'track-result', 'pixel-url', 'pixel-html');
    document.getElementById('track-form').reset();
  } catch (err) {
    alert('Error: ' + err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Generate Tracking Pixel';
  }
}

// ── Track Email (modal) ───────────────────────────────────────

async function handleModalTrack(e) {
  e.preventDefault();
  const btn = document.getElementById('modal-track-btn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Creating…';

  const body = {
    subject: document.getElementById('m-subject').value.trim(),
    recipientEmail: document.getElementById('m-recipient').value.trim(),
    content: document.getElementById('m-content').value.trim()
  };

  try {
    const res = await authFetch('/api/emails', { method: 'POST', body: JSON.stringify(body) });
    const data = await res.json();

    if (!res.ok) throw new Error(data.error || 'Failed to create');

    showTrackResult(data, 'modal-result', 'modal-pixel-url', 'modal-pixel-html');
    document.getElementById('modal-track-form').reset();
    loadEmails(); // Refresh table
  } catch (err) {
    alert('Error: ' + err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Create Pixel';
  }
}

function showTrackResult(data, resultId, urlId, htmlId) {
  document.getElementById(resultId).style.display = '';
  document.getElementById(urlId).textContent = data.trackingPixelUrl;
  document.getElementById(htmlId).textContent =
    `<img src="${data.trackingPixelUrl}" width="1" height="1" style="display:none" alt=""/>`;
}

// ── Follow-Up Modal ───────────────────────────────────────────

function openFollowUpModal(emailId) {
  followUpEmail = allEmails.find(e => e.id === emailId);
  if (!followUpEmail) return;

  const daysSinceSent = Math.floor(
    (Date.now() - new Date(followUpEmail.createdAt)) / (1000 * 60 * 60 * 24)
  );

  document.getElementById('followup-context').innerHTML = `
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:.5rem">
      <div><span style="color:var(--muted)">Subject:</span> <strong>${escHtml(followUpEmail.subject)}</strong></div>
      <div><span style="color:var(--muted)">Recipient:</span> ${escHtml(followUpEmail.recipientEmail)}</div>
      <div><span style="color:var(--muted)">Days since sent:</span> ${daysSinceSent}</div>
      <div><span style="color:var(--muted)">Opens:</span> ${followUpEmail.openCount}</div>
      <div><span style="color:var(--muted)">Engagement score:</span> ${followUpEmail.leadScore}/100</div>
      <div><span style="color:var(--muted)">Status:</span> ${statusBadge(followUpEmail.status)}</div>
    </div>
  `;

  document.getElementById('followup-result').style.display = 'none';
  document.getElementById('followup-modal').style.display = 'flex';
}

function closeFollowUpModal() {
  document.getElementById('followup-modal').style.display = 'none';
  followUpEmail = null;
}

async function generateFollowUp() {
  if (!followUpEmail) return;

  const btn = document.getElementById('gen-btn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Generating…';

  const daysSinceSent = Math.floor(
    (Date.now() - new Date(followUpEmail.createdAt)) / (1000 * 60 * 60 * 24)
  );

  const payload = {
    emailId:         followUpEmail.id,
    subject:         followUpEmail.subject,
    originalContent: followUpEmail.content || '',
    recipientEmail:  followUpEmail.recipientEmail,
    engagementScore: followUpEmail.leadScore,
    openCount:       followUpEmail.openCount,
    daysSinceSent:   daysSinceSent
  };

  try {
    const res = await authFetch('/api/ai/followup', {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    const data = await res.json();

    if (!res.ok) throw new Error(data.error || 'AI generation failed');

    document.getElementById('followup-subject').textContent = data.suggestedSubject;
    document.getElementById('followup-text').textContent    = data.followUpText;
    document.getElementById('followup-result').style.display = '';
  } catch (err) {
    alert('Error: ' + err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Generate Follow-Up';
  }
}

function copyFollowUp() {
  const text = `Subject: ${document.getElementById('followup-subject').textContent}\n\n` +
               document.getElementById('followup-text').textContent;
  navigator.clipboard.writeText(text).then(() => showToast('Copied!', 'Follow-up copied to clipboard', 'success'));
}

// ── Modal helpers ─────────────────────────────────────────────

function openNewEmailModal() {
  document.getElementById('modal-result').style.display = 'none';
  document.getElementById('modal-track-form').reset();
  document.getElementById('new-email-modal').style.display = 'flex';
}
function closeNewEmailModal() {
  document.getElementById('new-email-modal').style.display = 'none';
  loadEmails();
}
function closeModalOnBg(e) {
  if (e.target === e.currentTarget) e.currentTarget.style.display = 'none';
}

// ── Auth + fetch wrapper ──────────────────────────────────────

function authFetch(path, options = {}) {
  return fetch(API + path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
      ...(options.headers || {})
    }
  });
}

function logout() {
  localStorage.clear();
  window.location.href = 'index.html';
}

// ── Toast system ──────────────────────────────────────────────

/**
 * Show a toast notification.
 * Called from websocket.js when emails are opened in real time.
 */
function showToast(title, message, type = 'info') {
  const icons = { success: '✅', info: '📬', error: '❌' };
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.innerHTML = `
    <span class="toast-icon">${icons[type] || '📨'}</span>
    <div class="toast-content">
      <div class="toast-title">${escHtml(title)}</div>
      <div class="toast-msg">${escHtml(message)}</div>
    </div>
  `;
  document.getElementById('toast-container').appendChild(el);
  setTimeout(() => el.remove(), 5000);
}

// ── Rendering helpers ─────────────────────────────────────────

function statusBadge(status) {
  const map = {
    'Not Opened':           'badge-gray',
    'Opened':               'badge-green',
    'Opened Multiple Times':'badge-orange'
  };
  return `<span class="badge ${map[status] || 'badge-gray'}">${escHtml(status)}</span>`;
}

function scoreBar(score) {
  const color = score >= 70 ? '#f59e0b' : score >= 40 ? '#6366f1' : '#64748b';
  return `
    <div class="score-wrap">
      <div class="score-bar-track">
        <div class="score-bar-fill" style="width:${score}%;background:${color}"></div>
      </div>
      <span class="score-num" style="color:${color}">${score}</span>
    </div>`;
}

function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  const now = new Date();
  const diff = now - d;
  const mins  = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days  = Math.floor(diff / 86400000);
  if (mins < 60)  return `${mins}m ago`;
  if (hours < 24) return `${hours}h ago`;
  if (days < 7)   return `${days}d ago`;
  return d.toLocaleDateString();
}

function escHtml(str) {
  if (!str) return '';
  return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

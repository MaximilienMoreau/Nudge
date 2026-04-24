/**
 * dashboard.js — Main dashboard logic
 *
 * A2:  Uses NUDGE_CONFIG.API_BASE from config.js (no hardcoded URL).
 * Q10: handleTrackEmail and handleModalTrack share submitTrackEmail().
 * U1:  "Copy HTML" button with one-click clipboard copy.
 * U2:  alert() replaced everywhere with showToast().
 * U3:  Client-side search + sortable columns on the email table.
 * U4:  Score bar has a tooltip explaining the scoring factors.
 * U5:  "Pro Plan" sidebar text shows the account creation date instead.
 * U7:  Follow-up modal cannot be closed while generation is in progress.
 * U8:  Warning banner shown when the AI response is the fallback placeholder.
 * P1:  WebSocket notifications patch only the affected table row, no full reload.
 */

// ── Auth guard ────────────────────────────────────────────────

const userEmail = localStorage.getItem('nudge_email');

// Token is in an httpOnly cookie — redirect if email not present (not logged in)
if (!userEmail) window.location.href = 'index.html';

// ── State ─────────────────────────────────────────────────────

let allEmails    = [];       // all emails from the last load
let filteredEmails = [];     // after search/filter
let followUpEmail = null;    // context for AI modal
let sortState     = { col: null, asc: true };
let isGenerating  = false;   // U7: guard modal close during generation

// ── Init ──────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  const emailEl  = document.getElementById('user-email-display');
  const avatarEl = document.getElementById('user-avatar');
  const roleEl   = document.getElementById('user-role');
  if (userEmail) {
    emailEl.textContent  = userEmail;
    avatarEl.textContent = userEmail[0].toUpperCase();
  }

  // U5: Show account creation date from localStorage (set after login)
  const createdAt = localStorage.getItem('nudge_createdAt');
  if (roleEl) {
    roleEl.textContent = createdAt
      ? 'Since ' + new Date(createdAt).toLocaleDateString()
      : 'Free Plan';
  }

  setupTableSorting();
  loadEmails();
});

// ── View switching ────────────────────────────────────────────

const VIEW_TITLES = { emails: 'Email Dashboard', track: 'Track New Email', insights: 'AI Insights', archived: 'Archived Emails' };
const ALL_VIEWS   = ['emails', 'track', 'insights', 'archived'];

function showView(name) {
  ALL_VIEWS.forEach(v => {
    document.getElementById(`view-${v}`).style.display = v === name ? '' : 'none';
  });
  document.getElementById('view-title').textContent = VIEW_TITLES[name] ?? name;
  document.querySelectorAll('.nav-item').forEach((el, i) => {
    el.classList.toggle('active', ALL_VIEWS[i] === name);
  });
  if (name === 'insights') loadSendTime();
  if (name === 'archived') loadArchivedEmails();
}

// ── Load emails (paginated) ───────────────────────────────────

async function loadEmails(page = 0) {
  const tbody = document.getElementById('emails-tbody');
  tbody.innerHTML = `<tr><td colspan="9"><div class="empty-state"><div class="spinner"></div></div></td></tr>`;

  try {
    const res = await authFetch(`/api/emails?page=${page}&size=50`);
    if (!res.ok) throw new Error('Failed to load emails');

    const pageData = await res.json();
    // Handle both paginated response {content:[]} and plain array
    allEmails      = pageData.content ?? pageData;
    filteredEmails = [...allEmails];
    renderEmailTable(filteredEmails);
    updateStats(allEmails);

    // Render pagination if applicable
    if (pageData.totalPages > 1) {
      renderPagination(pageData.number, pageData.totalPages);
    }
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="9"><div class="empty-state">
      <div class="empty-icon">⚠️</div>
      <h3>Could not load emails</h3>
      <p>${escHtml(err.message)}</p>
    </div></td></tr>`;
  }
}

function renderPagination(current, total) {
  let html = '<div style="display:flex;gap:.5rem;justify-content:center;margin-top:1rem">';
  if (current > 0) html += `<button class="btn btn-ghost btn-sm" onclick="loadEmails(${current-1})">← Prev</button>`;
  html += `<span style="color:var(--muted);line-height:2">Page ${current+1} / ${total}</span>`;
  if (current < total-1) html += `<button class="btn btn-ghost btn-sm" onclick="loadEmails(${current+1})">Next →</button>`;
  html += '</div>';
  document.getElementById('emails-tbody').insertAdjacentHTML('afterend', html);
}

// ── U3: Search & filter ───────────────────────────────────────

function handleSearch(query) {
  const q = query.toLowerCase();
  filteredEmails = q
    ? allEmails.filter(e =>
        e.subject.toLowerCase().includes(q) ||
        (e.recipientEmail || '').toLowerCase().includes(q))
    : [...allEmails];
  renderEmailTable(filteredEmails);
}

// ── U3: Sortable columns ──────────────────────────────────────

function setupTableSorting() {
  document.querySelectorAll('th[data-sort]').forEach(th => {
    th.style.cursor = 'pointer';
    th.addEventListener('click', () => {
      const col = th.dataset.sort;
      if (sortState.col === col) {
        sortState.asc = !sortState.asc;
      } else {
        sortState.col = col;
        sortState.asc = true;
      }
      document.querySelectorAll('th[data-sort]').forEach(h =>
        h.textContent = h.textContent.replace(/ [▲▼]$/, ''));
      th.textContent += sortState.asc ? ' ▲' : ' ▼';
      sortEmails();
    });
  });
}

function sortEmails() {
  const { col, asc } = sortState;
  if (!col) return;
  filteredEmails.sort((a, b) => {
    let va = a[col], vb = b[col];
    if (va == null) va = col.includes('At') ? '' : -Infinity;
    if (vb == null) vb = col.includes('At') ? '' : -Infinity;
    if (typeof va === 'string') return asc ? va.localeCompare(vb) : vb.localeCompare(va);
    return asc ? va - vb : vb - va;
  });
  renderEmailTable(filteredEmails);
}

// ── Render email table ────────────────────────────────────────

function renderEmailTable(emails) {
  const tbody = document.getElementById('emails-tbody');

  if (emails.length === 0) {
    tbody.innerHTML = `<tr><td colspan="9">
      <div class="empty-state">
        <div class="empty-icon">📭</div>
        <h3>No tracked emails yet</h3>
        <p style="color:var(--muted)">Click <strong>+ Track Email</strong> to get started.</p>
      </div>
    </td></tr>`;
    return;
  }

  tbody.innerHTML = emails.map(email => {
    const isHot     = email.leadScore >= 70;
    const hotBadge  = isHot ? ' 🔥' : '';

    return `
    <tr class="${isHot ? 'hot-lead' : ''}" data-email-id="${email.id}">
      <td>
        <div style="font-weight:600;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
             title="${escHtml(email.subject)}">
          ${escHtml(email.subject)}${hotBadge}
        </div>
      </td>
      <td style="color:var(--muted)">${escHtml(email.recipientEmail)}</td>
      <td style="color:var(--muted);white-space:nowrap">${formatDate(email.createdAt)}</td>
      <td>${statusBadge(email.status)}</td>
      <td>${scoreBar(email.leadScore)}</td>
      <td style="color:var(--muted);white-space:nowrap">${email.lastOpenedAt ? formatDate(email.lastOpenedAt) : '—'}</td>
      <td style="color:var(--muted);text-align:center">
        ${email.clickCount > 0
          ? `<span title="Last clicked: ${email.lastClickedAt ? formatDate(email.lastClickedAt) : '—'}">🔗 ${email.clickCount}</span>`
          : '—'}
      </td>
      <td>
        <button class="btn btn-sm btn-secondary" onclick="openFollowUpModal(${email.id})">
          🤖 Follow Up
        </button>
      </td>
      <td>
        <button class="btn btn-sm btn-ghost" title="Archive email"
                onclick="archiveEmail(${email.id})">🗑</button>
      </td>
    </tr>`;
  }).join('');
}

// ── P1: Targeted row update from WebSocket ────────────────────

function updateEmailRow(updatedEmail) {
  // Update in-memory cache
  const idx = allEmails.findIndex(e => e.id === updatedEmail.id);
  if (idx !== -1) allEmails[idx] = updatedEmail;
  const fidx = filteredEmails.findIndex(e => e.id === updatedEmail.id);
  if (fidx !== -1) filteredEmails[fidx] = updatedEmail;

  // Update the DOM row without a full re-render
  const row = document.querySelector(`tr[data-email-id="${updatedEmail.id}"]`);
  if (!row) { renderEmailTable(filteredEmails); return; }

  const isHot = updatedEmail.leadScore >= 70;
  row.className = isHot ? 'hot-lead' : '';
  const cells = row.querySelectorAll('td');
  cells[3].innerHTML = statusBadge(updatedEmail.status);
  cells[4].innerHTML = scoreBar(updatedEmail.leadScore);
  cells[5].textContent = updatedEmail.lastOpenedAt ? formatDate(updatedEmail.lastOpenedAt) : '—';

  updateStats(allEmails);
}

// ── Stats ─────────────────────────────────────────────────────

function updateStats(emails) {
  const total  = emails.length;
  const opened = emails.filter(e => e.openCount > 0).length;
  const hot    = emails.filter(e => e.leadScore >= 70).length;
  const avg    = total > 0
    ? Math.round(emails.reduce((s, e) => s + e.leadScore, 0) / total) : 0;

  document.getElementById('stat-total').textContent      = total;
  document.getElementById('stat-opened').textContent     = opened;
  document.getElementById('stat-hot').textContent        = hot;
  document.getElementById('stat-avg-score').textContent  = avg;
}

// ── AI Insights: Send-time ────────────────────────────────────

async function loadSendTime() {
  const body = document.getElementById('send-time-body');
  body.innerHTML = `<div class="empty-state"><div class="spinner"></div></div>`;

  try {
    const res  = await authFetch('/api/ai/send-time', { method: 'POST' });
    if (!res.ok) throw new Error(`Backend error ${res.status}`);
    const data = await res.json();

    if (!data.hasData) {
      body.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">📭</div>
          <h3>No data yet</h3>
          <p style="color:var(--muted)">${escHtml(data.suggestion)}</p>
        </div>`;
      return;
    }

    body.innerHTML = `
      <div style="display:flex;align-items:center;gap:1.5rem;flex-wrap:wrap">
        <div style="text-align:center">
          <div style="font-size:2.5rem;font-weight:800;color:var(--primary)">
            ${escHtml(data.bestDay)}
          </div>
          <div style="color:var(--muted);font-size:.8rem;margin-top:.25rem">Best day</div>
        </div>
        <div style="text-align:center">
          <div style="font-size:2.5rem;font-weight:800;color:var(--primary)">
            ${escHtml(data.bestHour)}
          </div>
          <div style="color:var(--muted);font-size:.8rem;margin-top:.25rem">Best time</div>
        </div>
        <div style="flex:1;min-width:160px">
          <div style="font-size:1.05rem;font-weight:600;margin-bottom:.3rem">
            ${escHtml(data.suggestion)}
          </div>
          <div style="font-size:.8rem;color:var(--muted)">${escHtml(data.rationale)}</div>
        </div>
      </div>`;
  } catch (err) {
    body.innerHTML = `<div class="empty-state">
      <div class="empty-icon">⚠️</div>
      <p style="color:var(--muted)">${escHtml(err.message)}</p>
    </div>`;
  }
}

// ── Q10: Shared track-email helper ────────────────────────────

/**
 * Submits a track-email form. Used by both the standalone page and the modal.
 * Returns the first EmailDTO on success, null on failure.
 */
async function submitTrackEmail({ subject, recipientEmail, content }) {
  const body = { subject, recipientEmail, content };
  const res  = await authFetch('/api/emails', {
    method: 'POST',
    body:   JSON.stringify(body)
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Failed to create');
  // API returns List<EmailDTO> (F3); take the first
  return Array.isArray(data) ? data[0] : data;
}

// ── Track Email (standalone page) ────────────────────────────

async function handleTrackEmail(e) {
  e.preventDefault();
  const btn = document.getElementById('track-btn');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Creating…';

  try {
    const emailDto = await submitTrackEmail({
      subject:        document.getElementById('t-subject').value.trim(),
      recipientEmail: document.getElementById('t-recipient').value.trim(),
      content:        document.getElementById('t-content').value.trim()
    });
    showTrackResult(emailDto, 'track-result', 'pixel-url', 'pixel-html');
    document.getElementById('track-form').reset();
  } catch (err) {
    showToast('Error', err.message, 'error');  // U2
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

  try {
    const emailDto = await submitTrackEmail({
      subject:        document.getElementById('m-subject').value.trim(),
      recipientEmail: document.getElementById('m-recipient').value.trim(),
      content:        document.getElementById('m-content').value.trim()
    });
    showTrackResult(emailDto, 'modal-result', 'modal-pixel-url', 'modal-pixel-html');
    document.getElementById('modal-track-form').reset();
    await loadEmails();
  } catch (err) {
    showToast('Error', err.message, 'error');  // U2
  } finally {
    btn.disabled = false;
    btn.textContent = 'Create Pixel';
  }
}

// ── U1: Show tracking result with Copy buttons ────────────────

function showTrackResult(data, resultId, urlId, htmlId) {
  const pixelUrl  = data.trackingPixelUrl;
  const htmlSnip  = `<img src="${pixelUrl}" width="1" height="1" style="display:none" alt=""/>`;

  document.getElementById(resultId).style.display = '';
  document.getElementById(urlId).textContent   = pixelUrl;
  document.getElementById(htmlId).textContent  = htmlSnip;

  // U1: Wire up copy buttons
  const copyUrlBtn  = document.getElementById(urlId + '-copy');
  const copyHtmlBtn = document.getElementById(htmlId + '-copy');
  if (copyUrlBtn)  copyUrlBtn.onclick  = () => copyText(pixelUrl, 'Pixel URL copied!', copyUrlBtn);
  if (copyHtmlBtn) copyHtmlBtn.onclick = () => copyText(htmlSnip, 'HTML snippet copied!', copyHtmlBtn);
}

// ── F1: Archive email ─────────────────────────────────────────

async function archiveEmail(emailId) {
  try {
    const res = await authFetch(`/api/emails/${emailId}`, { method: 'DELETE' });
    if (!res.ok) throw new Error('Failed to archive');
    allEmails      = allEmails.filter(e => e.id !== emailId);
    filteredEmails = filteredEmails.filter(e => e.id !== emailId);
    renderEmailTable(filteredEmails);
    updateStats(allEmails);
    showToast('Archived', 'Email removed from your dashboard.', 'success');
  } catch (err) {
    showToast('Error', err.message, 'error');
  }
}

// ── Archived emails ───────────────────────────────────────────

async function loadArchivedEmails() {
  const tbody = document.getElementById('archived-tbody');
  tbody.innerHTML = `<tr><td colspan="6"><div class="empty-state"><div class="spinner"></div></div></td></tr>`;

  try {
    const res = await authFetch('/api/emails/archived');
    if (!res.ok) throw new Error('Failed to load archived emails');
    const emails = await res.json();

    if (emails.length === 0) {
      tbody.innerHTML = `<tr><td colspan="6">
        <div class="empty-state">
          <div class="empty-icon">🗂</div>
          <h3>No archived emails</h3>
          <p style="color:var(--muted)">Emails you archive will appear here.</p>
        </div>
      </td></tr>`;
      return;
    }

    tbody.innerHTML = emails.map(e => `
      <tr>
        <td style="font-weight:600;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
            title="${escHtml(e.subject)}">${escHtml(e.subject)}</td>
        <td style="color:var(--muted)">${escHtml(e.recipientEmail)}</td>
        <td style="color:var(--muted);white-space:nowrap">${formatDate(e.createdAt)}</td>
        <td style="color:var(--muted);white-space:nowrap">${formatDate(e.archivedAt)}</td>
        <td style="color:var(--muted)">${e.openCount}</td>
        <td style="display:flex;gap:.4rem">
          <button class="btn btn-sm btn-secondary" onclick="restoreEmail(${e.id})">↩ Restore</button>
          <button class="btn btn-sm btn-ghost" style="color:#ef4444"
                  onclick="permanentDeleteEmail(${e.id})">🗑 Delete</button>
        </td>
      </tr>`).join('');
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6"><div class="empty-state">
      <div class="empty-icon">⚠️</div><p style="color:var(--muted)">${escHtml(err.message)}</p>
    </div></td></tr>`;
  }
}

async function restoreEmail(emailId) {
  try {
    const res = await authFetch(`/api/emails/${emailId}/restore`, { method: 'POST' });
    if (!res.ok) throw new Error('Failed to restore');
    showToast('Restored', 'Email moved back to your dashboard.', 'success');
    loadArchivedEmails();
  } catch (err) {
    showToast('Error', err.message, 'error');
  }
}

async function permanentDeleteEmail(emailId) {
  if (!confirm('Permanently delete this email and all its tracking data? This cannot be undone.')) return;
  try {
    const res = await authFetch(`/api/emails/${emailId}/permanent`, { method: 'DELETE' });
    if (!res.ok) throw new Error('Failed to delete');
    showToast('Deleted', 'Email permanently deleted.', 'success');
    loadArchivedEmails();
  } catch (err) {
    showToast('Error', err.message, 'error');
  }
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
      <div><span style="color:var(--muted)">Score:</span> ${followUpEmail.leadScore}/100</div>
      <div><span style="color:var(--muted)">Status:</span> ${statusBadge(followUpEmail.status)}</div>
    </div>`;

  document.getElementById('followup-result').style.display = 'none';
  document.getElementById('ai-fallback-warning').style.display = 'none';  // U8
  document.getElementById('followup-modal').style.display = 'flex';
}

function closeFollowUpModal() {
  if (isGenerating) return; // U7: prevent close during generation
  document.getElementById('followup-modal').style.display = 'none';
  followUpEmail = null;
}

async function generateFollowUp() {
  if (!followUpEmail || isGenerating) return;

  const btn = document.getElementById('gen-btn');
  isGenerating = true;    // U7
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span> Generating…';

  const daysSinceSent = Math.floor(
    (Date.now() - new Date(followUpEmail.createdAt)) / (1000 * 60 * 60 * 24)
  );

  // S9: Only send emailId and daysSinceSent — server computes score and openCount
  const payload = {
    emailId:       followUpEmail.id,
    daysSinceSent
  };

  try {
    const res  = await authFetch('/api/ai/followup', { method: 'POST', body: JSON.stringify(payload) });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'AI generation failed');

    document.getElementById('followup-subject').textContent = data.suggestedSubject;
    document.getElementById('followup-text').textContent    = data.followUpText;
    document.getElementById('followup-result').style.display = '';

    // U8: Detect fallback placeholder text
    const isFallback = data.followUpText.includes("I wanted to follow up on my previous email");
    document.getElementById('ai-fallback-warning').style.display = isFallback ? '' : 'none';

  } catch (err) {
    showToast('Error', err.message, 'error');  // U2
  } finally {
    isGenerating = false;  // U7
    btn.disabled = false;
    btn.textContent = 'Generate Follow-Up';
  }
}

function copyFollowUp() {
  const text = `Subject: ${document.getElementById('followup-subject').textContent}\n\n` +
               document.getElementById('followup-text').textContent;
  const btn = document.querySelector('[onclick="copyFollowUp()"]');
  copyText(text, 'Follow-up copied to clipboard', btn);
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
  if (e.target !== e.currentTarget) return;
  const modalId = e.currentTarget.id;
  if (modalId === 'followup-modal') {
    closeFollowUpModal();
  } else {
    e.currentTarget.style.display = 'none';
  }
}

// ── Auth + fetch wrapper ──────────────────────────────────────

async function authFetch(path, options = {}) {
  const res = await fetch(NUDGE_CONFIG.API_BASE + path, {
    ...options,
    credentials: 'include',   // sends the httpOnly cookie automatically
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  });
  if (res.status === 401) { window.location.href = 'index.html'; }
  return res;
}

function logout() {
  // S6: Invalidate token server-side
  authFetch('/api/auth/logout', { method: 'POST' }).catch(() => {});
  localStorage.clear();
  window.location.href = 'index.html';
}

// ── Toast system ──────────────────────────────────────────────

function showToast(title, message, type = 'info') {
  const icons = { success: '✅', info: '📬', error: '❌' };
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.innerHTML = `
    <span class="toast-icon">${icons[type] || '📨'}</span>
    <div class="toast-content">
      <div class="toast-title">${escHtml(title)}</div>
      <div class="toast-msg">${escHtml(message)}</div>
    </div>`;
  document.getElementById('toast-container').appendChild(el);
  setTimeout(() => el.remove(), 8000);
}

// ── Rendering helpers ─────────────────────────────────────────

function statusBadge(status) {
  const map = {
    'Not Opened':            'badge-gray',
    'Opened':                'badge-green',
    'Opened Multiple Times': 'badge-orange'
  };
  return `<span class="badge ${map[status] || 'badge-gray'}">${escHtml(status)}</span>`;
}

/**
 * U4: Score bar now includes a tooltip explaining the scoring breakdown.
 */
function scoreBar(score) {
  const color   = score >= 70 ? '#f59e0b' : score >= 40 ? '#6366f1' : '#64748b';
  const tooltip = 'Reply Probability Score (0–100). ' +
                  'Based on: open count (up to 40 pts), ' +
                  'recency of last open (up to 40 pts), ' +
                  'and frequency bonus for repeated opens (up to 20 pts).';
  return `
    <div class="score-wrap" title="${tooltip}">
      <div class="score-bar-track">
        <div class="score-bar-fill" style="width:${score}%;background:${color}"></div>
      </div>
      <span class="score-num" style="color:${color}">${score}</span>
      <span class="score-help" title="${tooltip}">?</span>
    </div>`;
}

function formatDate(iso) {
  if (!iso) return '—';
  const d    = new Date(iso);
  const now  = new Date();
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
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function copyText(text, successMsg, btn) {
  navigator.clipboard.writeText(text)
    .then(() => {
      showToast('Copied', successMsg, 'success');
      if (btn) {
        const orig = btn.textContent;
        btn.textContent = '✓ Copied!';
        setTimeout(() => { btn.textContent = orig; }, 2000);
      }
    })
    .catch(() => showToast('Error', 'Could not copy to clipboard', 'error'));
}

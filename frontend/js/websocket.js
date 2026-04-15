/**
 * websocket.js — Real-time notification client
 *
 * A2:  Uses NUDGE_CONFIG.WS_URL from config.js.
 * S3:  Passes ?token= in the SockJS URL so the server validates at HTTP level.
 * U6:  Exponential backoff on reconnect (5s → 10s → 20s → 40s, capped at 60s).
 * P1:  On EMAIL_OPENED notification, patches only the affected table row
 *      by fetching the updated email DTO — no full table reload.
 * P4:  SockJS and STOMP are loaded via <script defer> in dashboard.html,
 *      not dynamically injected here.
 */

let stompClient   = null;
let retryDelay    = 5000;   // U6: starts at 5s
const MAX_DELAY   = 60000;

function connectWebSocket() {
  const wsToken = localStorage.getItem('nudge_token');
  if (!wsToken) return;

  // S3: Pass JWT as query param for HTTP-level handshake validation
  const socket = new SockJS(NUDGE_CONFIG.WS_URL + '?token=' + encodeURIComponent(wsToken));
  stompClient  = Stomp.over(socket);
  stompClient.debug = () => {};   // Suppress noisy debug logs

  stompClient.connect(
    { Authorization: `Bearer ${wsToken}` },
    onConnected,
    onError
  );
}

function onConnected() {
  setWsDot(true);
  retryDelay = 5000;  // U6: reset backoff on successful connection
  console.log('[Nudge WS] Connected');

  stompClient.subscribe('/user/queue/notifications', frame => {
    try {
      handleNotification(JSON.parse(frame.body));
    } catch (e) {
      console.error('[Nudge WS] Failed to parse notification', e);
    }
  });
}

function onError(err) {
  setWsDot(false);
  console.warn('[Nudge WS] Connection error — retrying in', retryDelay, 'ms', err);
  setTimeout(() => {
    retryDelay = Math.min(retryDelay * 2, MAX_DELAY); // U6: exponential backoff
    connectWebSocket();
  }, retryDelay);
}

// ── Notification handler ──────────────────────────────────────

async function handleNotification(notification) {
  const { type, emailId, subject, recipientEmail, openCount, leadScore } = notification;

  if (type === 'EMAIL_OPENED') {
    const isHot = leadScore >= 70;
    const title = isHot ? '🔥 Hot Lead!' : '📬 Email Opened';
    const msg   = `"${subject}" opened by ${recipientEmail} (${openCount}x, score: ${leadScore})`;

    if (typeof showToast === 'function') {
      showToast(title, msg, isHot ? 'success' : 'info');
    }

    // P1: Fetch only the updated email DTO and patch the table row
    try {
      const res = await fetch(`${NUDGE_CONFIG.API_BASE}/api/emails/${emailId}`, {
        headers: { 'Authorization': `Bearer ${localStorage.getItem('nudge_token')}` }
      });
      if (res.ok && typeof updateEmailRow === 'function') {
        updateEmailRow(await res.json());
      }
    } catch {
      // Fallback to full reload if the targeted update fails
      if (typeof loadEmails === 'function') loadEmails();
    }
  }

  if (type === 'FOLLOW_UP_REMINDER') {
    if (typeof showToast === 'function') {
      showToast(
        '⏰ Follow-up reminder',
        `Time to follow up on "${subject}" sent to ${recipientEmail}`,
        'info'
      );
    }
  }
}

// ── WS indicator ─────────────────────────────────────────────

function setWsDot(connected) {
  const dot   = document.getElementById('ws-dot');
  const label = document.getElementById('ws-label');
  if (!dot) return;
  dot.classList.toggle('connected', connected);
  label.textContent = connected ? 'Live' : 'Reconnecting…';
}

// Boot when page loads
document.addEventListener('DOMContentLoaded', connectWebSocket);

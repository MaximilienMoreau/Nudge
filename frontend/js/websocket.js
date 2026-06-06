/**
 * websocket.js — Real-time notification client
 *
 *  Uses NUDGE_CONFIG.WS_URL from config.js.
 *  Authenticates via the httpOnly cookie sent automatically on the SockJS handshake.
 *  Exponential backoff on reconnect (5s → 10s → 20s → 40s, capped at 60s).
 *  On EMAIL_OPENED notification, patches only the affected table row
 *      by fetching the updated email DTO — no full table reload.
 *  SockJS and STOMP are loaded via <script defer> in dashboard.html,
 *      not dynamically injected here.
 */

let stompClient   = null;
let retryDelay    = 5000;   // starts at 5s
const MAX_DELAY   = 60000;
const HOT_LEAD_THRESHOLD = 70;

function connectWebSocket() {
  // Token is in an httpOnly cookie — no need to pass it explicitly.
  // SockJS sends cookies automatically for same-origin requests.
  if (!localStorage.getItem('nudge_email')) return;

  const socket = new SockJS(NUDGE_CONFIG.WS_URL);
  stompClient  = Stomp.over(socket);
  stompClient.debug = () => {};

  stompClient.connect({}, onConnected, onError);
}

function onConnected() {
  setWsDot(true);
  retryDelay = 5000;  // reset backoff on successful connection
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
  const delaySec = Math.round(retryDelay / 1000);
  setWsDot(false, `Reconnecting in ${delaySec}s…`);
  console.warn('[Nudge WS] Connection error — retrying in', retryDelay, 'ms', err);
  setTimeout(() => {
    retryDelay = Math.min(retryDelay * 2, MAX_DELAY);
    connectWebSocket();
  }, retryDelay);
}

// ── Notification handler ──────────────────────────────────────

async function handleNotification(notification) {
  const { type, emailId, subject, recipientEmail, openCount, leadScore } = notification;

  if (type === 'EMAIL_OPENED') {
    const isHot = leadScore >= HOT_LEAD_THRESHOLD;
    const title = isHot ? '🔥 Hot Lead!' : '📬 Email Opened';
    const msg   = `"${subject}" opened by ${recipientEmail} (${openCount}x, score: ${leadScore})`;

    if (typeof showToast === 'function') {
      showToast(title, msg, isHot ? 'success' : 'info');
    }

    // Fetch only the updated email DTO and patch the table row
    try {
      const res = await fetch(`${NUDGE_CONFIG.API_BASE}/api/emails/${emailId}`, {
        credentials: 'include'
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

function setWsDot(connected, label = connected ? 'Live' : 'Reconnecting…') {
  const dot   = document.getElementById('ws-dot');
  const labelEl = document.getElementById('ws-label');
  if (!dot) return;
  dot.classList.toggle('connected', connected);
  labelEl.textContent = label;
}

// Boot when page loads
document.addEventListener('DOMContentLoaded', connectWebSocket);

/**
 * websocket.js — Real-time notification client
 *
 *  Uses NUDGE_CONFIG.WS_URL from config.js.
 *  Authenticates via the httpOnly cookie sent automatically on the SockJS handshake.
 *  Exponential backoff on reconnect (5s → 10s → 20s → 40s, capped at 60s).
 *  Notifications refresh the current server-sorted page and global statistics.
 *  SockJS and STOMP are loaded via <script defer> in dashboard.html,
 *      not dynamically injected here.
 */

let stompClient   = null;
let retryTimer = null;
let stopped = false;
let retryDelay    = 5000;   // starts at 5s
const MAX_DELAY   = 60000;

function connectWebSocket() {
  // Token is in an httpOnly cookie — no need to pass it explicitly.
  // SockJS sends cookies automatically for same-origin requests.
  if (stopped || !localStorage.getItem('nudge_email')) return;

  const client = new StompJs.Client({
    webSocketFactory: () => new SockJS(NUDGE_CONFIG.WS_URL),
    reconnectDelay: 0, // reconnect below with exponential backoff
    debug: () => {},
    onConnect: () => { if (stompClient === client) onConnected(); },
    onWebSocketClose: event => { if (stompClient === client) onError(event); },
    onStompError: frame => console.warn('[Nudge WS]', frame.headers.message)
  });
  stompClient = client;
  client.activate();
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
  clearTimeout(retryTimer);
  if (stopped) return;
  retryTimer = setTimeout(() => {
    retryDelay = Math.min(retryDelay * 2, MAX_DELAY);
    connectWebSocket();
  }, retryDelay);
}

// ── Notification handler ──────────────────────────────────────

async function handleNotification(notification) {
  const { type, emailId, subject, recipientEmail, openCount, leadScore } = notification;

  if (type === 'EMAIL_OPENED' || type === 'EMAIL_CLICKED') {
    const isHot = leadScore >= HOT_LEAD_THRESHOLD;
    const title = type === 'EMAIL_CLICKED'
      ? '🔗 Link Clicked'
      : isHot ? '🔥 Hot Lead!' : '📬 Email Opened';
    const msg = type === 'EMAIL_CLICKED'
      ? `Link in "${subject}" clicked by ${recipientEmail} (score: ${leadScore})`
      : `"${subject}" opened by ${recipientEmail} (${openCount}x, score: ${leadScore})`;

    if (typeof showToast === 'function') {
      showToast(title, msg, isHot ? 'success' : 'info');
    }

    if (typeof updateEmailRow === 'function') updateEmailRow();
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

function disconnectWebSocket() {
  stopped = true;
  clearTimeout(retryTimer);
  const client = stompClient;
  stompClient = null;
  if (client) client.deactivate();
}

function reconnectWebSocket() {
  disconnectWebSocket();
  stopped = false;
  connectWebSocket();
}
window.addEventListener('pagehide', disconnectWebSocket);

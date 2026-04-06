/**
 * websocket.js — Real-time notification client
 *
 * Connects to the backend via STOMP over SockJS.
 * Subscribes to /user/queue/notifications.
 * When an email is opened, a toast is shown and the table refreshes.
 *
 * Requires: SockJS + STOMP client loaded from CDN (see <script> tags below).
 * These are injected dynamically so they don't block page load.
 */

const WS_URL = 'http://localhost:8080/ws';

let stompClient = null;

// Dynamically load SockJS and STOMP from CDN, then connect
function loadWebSocketLibs(callback) {
  if (window.SockJS && window.Stomp) {
    callback();
    return;
  }

  const sockjsScript = document.createElement('script');
  sockjsScript.src = 'https://cdn.jsdelivr.net/npm/sockjs-client@1.6.1/dist/sockjs.min.js';
  sockjsScript.onload = () => {
    const stompScript = document.createElement('script');
    stompScript.src = 'https://cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js';
    stompScript.onload = callback;
    document.head.appendChild(stompScript);
  };
  document.head.appendChild(sockjsScript);
}

function connectWebSocket() {
  const token = localStorage.getItem('nudge_token');
  if (!token) return;

  loadWebSocketLibs(() => {
    const socket = new SockJS(WS_URL);
    stompClient = Stomp.over(socket);

    // Suppress noisy STOMP debug logs in production
    stompClient.debug = () => {};

    stompClient.connect(
      { Authorization: `Bearer ${token}` },  // Sent in CONNECT frame headers
      onConnected,
      onError
    );
  });
}

function onConnected() {
  setWsDot(true);
  console.log('[Nudge WS] Connected');

  // Subscribe to personal notification queue
  stompClient.subscribe('/user/queue/notifications', (frame) => {
    try {
      const notification = JSON.parse(frame.body);
      handleEmailOpenedNotification(notification);
    } catch (e) {
      console.error('[Nudge WS] Failed to parse notification', e);
    }
  });
}

function onError(err) {
  setWsDot(false);
  console.warn('[Nudge WS] Connection error', err);
  // Retry after 5 seconds
  setTimeout(connectWebSocket, 5000);
}

/**
 * Called when a tracked email is opened.
 * Shows a toast, refreshes the email table.
 */
function handleEmailOpenedNotification(notification) {
  const { subject, recipientEmail, openCount, leadScore } = notification;

  const isHot = leadScore >= 70;
  const title = isHot ? '🔥 Hot Lead!' : '📬 Email Opened';
  const msg = `"${subject}" was opened by ${recipientEmail} (${openCount}x, score: ${leadScore})`;

  // Show toast — defined in dashboard.js
  if (typeof showToast === 'function') {
    showToast(title, msg, isHot ? 'success' : 'info');
  }

  // Refresh table to show updated open count & score
  if (typeof loadEmails === 'function') {
    loadEmails();
  }
}

function setWsDot(connected) {
  const dot   = document.getElementById('ws-dot');
  const label = document.getElementById('ws-label');
  if (!dot) return;

  dot.classList.toggle('connected', connected);
  label.textContent = connected ? 'Live' : 'Reconnecting…';
}

// Boot the connection when page loads
document.addEventListener('DOMContentLoaded', connectWebSocket);

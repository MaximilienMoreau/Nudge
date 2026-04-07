/**
 * background.js — Nudge Chrome Extension Service Worker (Manifest V3)
 *
 * Responsibilities:
 *  - Register a new email with the Nudge backend (REGISTER_EMAIL)
 *  - Return an AI send-time suggestion (GET_SEND_TIME)
 *  - Report the active tab's platform support (GET_PLATFORM_STATUS)
 *  - Persist auth token via chrome.storage.local
 */

const API_BASE = 'http://localhost:8080';

// ── Track unsupported-platform tabs reported by content.js ────

const unsupportedTabs = new Set();

// ── Message router ────────────────────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  switch (message.type) {

    case 'REGISTER_EMAIL':
      handleRegisterEmail(message.payload)
        .then(sendResponse)
        .catch(err => sendResponse({ success: false, error: err.message }));
      return true;

    case 'GET_AUTH_STATUS':
      getAuthStatus().then(sendResponse);
      return true;

    case 'GET_SEND_TIME':
      handleGetSendTime()
        .then(sendResponse)
        .catch(() => sendResponse({ suggestion: 'No data yet' }));
      return true;

    case 'GET_PLATFORM_STATUS':
      // Called by popup.js to know what the active tab supports
      handleGetPlatformStatus(sender).then(sendResponse);
      return true;

    case 'PLATFORM_UNSUPPORTED':
      // content.js fires this when it loads on an unsupported host
      if (sender.tab?.id) unsupportedTabs.add(sender.tab.id);
      return false;

    case 'LOGOUT':
      chrome.storage.local.remove(['nudge_token', 'nudge_email'], () =>
        sendResponse({ ok: true })
      );
      return true;
  }
});

// ── Register email ────────────────────────────────────────────

async function handleRegisterEmail(payload) {
  const { token } = await getStoredCredentials();
  if (!token) {
    return { success: false, error: 'Not authenticated. Please sign in via the Nudge popup.' };
  }

  const response = await fetch(`${API_BASE}/api/emails`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify(payload)
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    return { success: false, error: err.error || 'Backend error' };
  }

  const data = await response.json();
  return {
    success: true,
    trackingId:       data.trackingId,
    trackingPixelUrl: data.trackingPixelUrl
  };
}

// ── Send-time suggestion ──────────────────────────────────────

async function handleGetSendTime() {
  const { token } = await getStoredCredentials();
  if (!token) return { suggestion: 'Sign in to get insights' };

  const response = await fetch(`${API_BASE}/api/ai/send-time`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({})
  });

  if (!response.ok) return { suggestion: 'No data yet' };

  const data = await response.json();
  return { suggestion: data.suggestion ?? 'No data yet' };
}

// ── Platform status (for popup) ───────────────────────────────

async function handleGetPlatformStatus() {
  // Ask the active tab which platform it's on
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) return { platform: 'unknown', supported: false };

    const url  = new URL(tab.url || '');
    const host = url.hostname;

    if (host === 'mail.google.com')           return { platform: 'Gmail',         supported: true };
    if (host.endsWith('outlook.live.com'))    return { platform: 'Outlook',       supported: true };
    if (host.endsWith('outlook.office.com'))  return { platform: 'Outlook',       supported: true };
    if (host.endsWith('outlook.office365.com')) return { platform: 'Outlook 365', supported: true };

    // Any web email not in our injection list — offer dashboard fallback
    return { platform: host || 'Unknown', supported: false };
  } catch {
    return { platform: 'Unknown', supported: false };
  }
}

// ── Auth helpers ──────────────────────────────────────────────

async function getAuthStatus() {
  const { token, email } = await getStoredCredentials();
  return { loggedIn: !!token, email };
}

function getStoredCredentials() {
  return new Promise(resolve => {
    chrome.storage.local.get(['nudge_token', 'nudge_email'], result => {
      resolve({ token: result.nudge_token, email: result.nudge_email });
    });
  });
}

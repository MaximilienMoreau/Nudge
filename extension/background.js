/**
 * background.js — Nudge Chrome Extension Service Worker (Manifest V3)
 *
 * Responsibilities:
 *  - Register a new email with the Nudge backend when content.js sends a message
 *  - Persist auth token via chrome.storage.local
 *  - Respond to popup queries about tracking status
 */

const API_BASE = 'http://localhost:8080';

// ── Message router ────────────────────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  switch (message.type) {

    case 'REGISTER_EMAIL':
      // content.js sends this before Gmail sends the email
      handleRegisterEmail(message.payload)
        .then(sendResponse)
        .catch(err => sendResponse({ success: false, error: err.message }));
      return true; // keep channel open for async response

    case 'GET_AUTH_STATUS':
      getAuthStatus().then(sendResponse);
      return true;

    case 'LOGOUT':
      chrome.storage.local.remove(['nudge_token', 'nudge_email'], () => sendResponse({ ok: true }));
      return true;
  }
});

// ── Core logic ────────────────────────────────────────────────

/**
 * Register a new tracked email with the backend.
 * Returns the tracking pixel URL to embed in the email body.
 *
 * @param {{ subject: string, recipientEmail: string, content: string }} payload
 */
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
    trackingId:      data.trackingId,
    trackingPixelUrl: data.trackingPixelUrl
  };
}

/**
 * Check if the user is logged in.
 */
async function getAuthStatus() {
  const { token, email } = await getStoredCredentials();
  return { loggedIn: !!token, email };
}

function getStoredCredentials() {
  return new Promise(resolve => {
    chrome.storage.local.get(['nudge_token', 'nudge_email'], (result) => {
      resolve({ token: result.nudge_token, email: result.nudge_email });
    });
  });
}

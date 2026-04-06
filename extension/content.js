/**
 * content.js — Gmail Compose Window Integration
 *
 * This script runs in the context of https://mail.google.com.
 *
 * What it does:
 *  1. Watches for Gmail compose windows to open (via MutationObserver)
 *  2. Injects a "Track with Nudge" button into each compose toolbar
 *  3. When the user clicks Send while tracking is active:
 *     - Captures subject, recipient, and body
 *     - Calls the Nudge backend to register the email
 *     - Injects the tracking pixel <img> tag into the email body
 *
 * Gmail uses a complex dynamic DOM — we watch for compose containers
 * by looking for the [role="dialog"] with a send button inside.
 */

console.log('[Nudge] Content script loaded on Gmail');

// Track which compose windows we've already injected into
const injectedComposeWindows = new WeakSet();

// ── MutationObserver: watch for compose windows ───────────────

const observer = new MutationObserver(() => {
  detectComposeWindows();
});

observer.observe(document.body, {
  childList: true,
  subtree: true
});

// Run immediately in case a compose window is already open
detectComposeWindows();

/**
 * Find all Gmail compose dialogs and inject Nudge tracking button.
 * Gmail renders compose as [role="dialog"] elements.
 */
function detectComposeWindows() {
  // Gmail compose windows contain a send button with data-tooltip="Send"
  const sendButtons = document.querySelectorAll('[data-tooltip="Send"]');

  sendButtons.forEach(sendBtn => {
    // Find the parent compose container
    const composeContainer = sendBtn.closest('div[role="dialog"], .nH.Hd');
    if (!composeContainer) return;
    if (injectedComposeWindows.has(composeContainer)) return;

    injectedComposeWindows.add(composeContainer);
    injectNudgeButton(composeContainer, sendBtn);
  });
}

/**
 * Inject the Nudge "Track" toggle button into the compose toolbar.
 *
 * @param {Element} composeContainer  The compose dialog element
 * @param {Element} sendBtn           The Gmail "Send" button
 */
function injectNudgeButton(composeContainer, sendBtn) {
  // State for this compose window
  let trackingEnabled = true;
  let trackingId = null;

  // Create button
  const nudgeBtn = document.createElement('button');
  nudgeBtn.className = 'nudge-track-btn';
  nudgeBtn.title = 'Toggle Nudge email tracking';
  nudgeBtn.setAttribute('type', 'button');
  nudgeBtn.style.cssText = `
    background: #6366f1;
    color: white;
    border: none;
    border-radius: 6px;
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    margin-left: 8px;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    vertical-align: middle;
  `;
  nudgeBtn.innerHTML = '📨 Nudge: ON';

  nudgeBtn.addEventListener('click', () => {
    trackingEnabled = !trackingEnabled;
    nudgeBtn.innerHTML = trackingEnabled ? '📨 Nudge: ON' : '📨 Nudge: OFF';
    nudgeBtn.style.background = trackingEnabled ? '#6366f1' : '#64748b';
  });

  // Insert next to the send button
  sendBtn.parentElement.insertBefore(nudgeBtn, sendBtn.nextSibling);

  // Intercept the send button click to inject pixel before sending
  sendBtn.addEventListener('click', async (e) => {
    if (!trackingEnabled) return;

    const subject   = extractSubject(composeContainer);
    const recipient = extractRecipient(composeContainer);
    const content   = extractBody(composeContainer);

    if (!subject && !recipient) return; // Nothing to track

    try {
      nudgeBtn.innerHTML = '📨 Registering…';
      nudgeBtn.disabled = true;

      // Ask background service worker to register the email
      const response = await chrome.runtime.sendMessage({
        type: 'REGISTER_EMAIL',
        payload: { subject, recipientEmail: recipient, content }
      });

      if (response.success) {
        trackingId = response.trackingId;
        // Inject the invisible tracking pixel into the email body
        injectTrackingPixel(composeContainer, response.trackingPixelUrl);
        nudgeBtn.innerHTML = '✅ Tracked!';
        console.log('[Nudge] Email registered, trackingId:', trackingId);
      } else {
        console.warn('[Nudge] Registration failed:', response.error);
        nudgeBtn.innerHTML = '⚠️ ' + (response.error || 'Error');
        nudgeBtn.style.background = '#ef4444';
      }
    } catch (err) {
      console.error('[Nudge] Error registering email:', err);
      nudgeBtn.innerHTML = '⚠️ Error';
    }
  }, { once: false }); // NOTE: listen every time (user may cancel and retry)
}

// ── Gmail DOM extraction helpers ──────────────────────────────

/**
 * Extract the email subject from the compose window.
 * Gmail's subject input has the name "subjectbox".
 */
function extractSubject(composeContainer) {
  const subjectInput = composeContainer.querySelector('input[name="subjectbox"]');
  return subjectInput ? subjectInput.value.trim() : '';
}

/**
 * Extract the recipient email from the compose window.
 * Gmail renders recipients in [email] data attributes or visible spans.
 */
function extractRecipient(composeContainer) {
  // Try the "To" field chip (Gmail renders recipients as chips with data-hovercard-id)
  const chip = composeContainer.querySelector('[data-hovercard-id]');
  if (chip) {
    const hoverId = chip.getAttribute('data-hovercard-id');
    if (hoverId && hoverId.includes('@')) return hoverId;
  }

  // Fallback: look for email in textarea[name="to"] or input with aria-label="To"
  const toInput = composeContainer.querySelector('[aria-label="To"], [name="to"]');
  if (toInput) return toInput.value.trim();

  // Last resort: find any text that looks like an email in the To area
  const toArea = composeContainer.querySelector('.vO'); // Gmail's To field class
  if (toArea) {
    const emailMatch = toArea.innerText.match(/[^\s@]+@[^\s@]+\.[^\s@]+/);
    if (emailMatch) return emailMatch[0];
  }

  return '';
}

/**
 * Extract the email body text.
 * Gmail's compose body is a contenteditable div.
 */
function extractBody(composeContainer) {
  const bodyEl = composeContainer.querySelector('[contenteditable="true"][aria-label]');
  return bodyEl ? bodyEl.innerText.trim() : '';
}

/**
 * Inject a 1x1 invisible tracking pixel into the email body.
 * The pixel is placed at the very end of the message.
 */
function injectTrackingPixel(composeContainer, pixelUrl) {
  const bodyEl = composeContainer.querySelector('[contenteditable="true"][aria-label]');
  if (!bodyEl) {
    console.warn('[Nudge] Could not find compose body to inject pixel');
    return;
  }

  // Remove any previously injected pixel in case of re-send
  const existing = bodyEl.querySelector('.nudge-pixel');
  if (existing) existing.remove();

  // Create invisible tracking pixel
  const pixelImg = document.createElement('img');
  pixelImg.src = pixelUrl;
  pixelImg.width = 1;
  pixelImg.height = 1;
  pixelImg.style.cssText = 'display:block;border:none;width:1px;height:1px;opacity:0;position:absolute;';
  pixelImg.className = 'nudge-pixel';
  pixelImg.alt = '';

  bodyEl.appendChild(pixelImg);
  console.log('[Nudge] Tracking pixel injected:', pixelUrl);
}

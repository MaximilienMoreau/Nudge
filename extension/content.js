/**
 * content.js — Nudge Multi-Platform Email Tracker
 *
 * Supported platforms: Gmail, Outlook Web, Proton Mail, Infomaniak Mail, Yahoo Mail
 *
 * Architecture: platform-adapter pattern
 *   - Each email client has its own adapter with DOM selectors.
 *   - The Nudge sidebar panel (UI) is shared across all adapters.
 *   - A MutationObserver continuously watches for new compose windows.
 *
 * Fallback: when the platform is not supported for injection, the
 *           extension popup guides users to the web dashboard.
 */

// Register at document_start, before the webmail installs its Send handlers.
const sendInterceptors = new Set();
function dispatchSend(event) {
  for (const intercept of sendInterceptors) if (intercept(event)) break;
}
window.addEventListener('click', dispatchSend, true);
window.addEventListener('keydown', dispatchSend, true);

// ── Platform detection ─────────────────────────────────────────

const PLATFORM = (() => {
  const h = window.location.hostname.toLowerCase();
  if (h === 'mail.google.com')             return 'gmail';
  if (h.endsWith('outlook.live.com'))      return 'outlook';
  if (h.endsWith('outlook.office.com'))    return 'outlook';
  if (h.endsWith('outlook.office365.com')) return 'outlook';
  if (h === 'mail.proton.me')              return 'protonmail';
  if (h === 'mail.protonmail.com')         return 'protonmail';
  if (h === 'mail.infomaniak.com')         return 'infomaniak';
  if (h === 'mail.yahoo.com')              return 'yahoo';
  return null;
})();

console.log(`[Nudge] content script — platform: ${PLATFORM ?? 'unsupported'}`);

// ── Platform adapters ──────────────────────────────────────────
//
// Each adapter must implement:
//   findComposeWindows() → Array<{ container: Element, sendBtn: Element }>
//   extractSubject(container)   → string
//   extractRecipient(container) → string
//   extractBody(container)      → string
//   injectPixel(container, url) → void
//   getSidebarRoot(container)   → Element  (where the panel is appended)

const ADAPTERS = {

  // ── Gmail ────────────────────────────────────────────────────
  gmail: {
    findComposeWindows() {
      const found = [];
      document.querySelectorAll('.aoO[role="button"], [data-tooltip^="Send"], [data-tooltip^="Envoyer"]').forEach(sendBtn => {
        const container = sendBtn.closest('div[role="dialog"], .nH.Hd');
        if (container) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      return c.querySelector('input[name="subjectbox"]')?.value.trim() ?? '';
    },
    extractRecipients(c) {
      const emails = new Set();
      c.querySelectorAll('[data-hovercard-id]').forEach(chip => {
        const h = chip.getAttribute('data-hovercard-id') ?? '';
        if (h.includes('@')) emails.add(h);
      });
      if (emails.size) return [...emails];

      const toInput = c.querySelector('[aria-label="To"], [name="to"]');
      if (toInput?.value) {
        toInput.value.split(',').forEach(e => {
          const t = e.trim();
          if (t.includes('@')) emails.add(t);
        });
        if (emails.size) return [...emails];
      }

      const toArea = c.querySelector('.vO');
      if (toArea) {
        const matches = toArea.innerText.match(/[^\s@,]+@[^\s@,]+\.[^\s@,]+/g) ?? [];
        matches.forEach(m => emails.add(m));
      }
      return [...emails];
    },
    extractBody(c) {
      return c.querySelector('[contenteditable="true"][role="textbox"]')?.innerText.trim() ?? '';
    },
    injectPixel(c, url) {
      const body = c.querySelector('[contenteditable="true"][role="textbox"]');
      if (!body) return false;
      body.querySelector('.nudge-pixel')?.remove();
      body.appendChild(buildPixelImg(url));
      body.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    },
    getSidebarRoot(c) {
      // Append inside the compose bottom bar (send button row)
      return c.querySelector('.gU.Up, .aDh, .btC') ?? c;
    }
  },

  // ── Outlook Web (live / office / office365) ──────────────────
  outlook: {
    findComposeWindows() {
      const found = [];
      // Outlook renders a Send button with aria-label "Send" in the compose toolbar
      document.querySelectorAll(
        'button[aria-label="Send"], button[title="Send"], button[data-testid="compose-send-button"]'
      ).forEach(sendBtn => {
        const container = sendBtn.closest(
          '[role="dialog"], [class*="compose"], [class*="Compose"], [class*="draftArea"]'
        );
        if (container) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      return (
        c.querySelector('input[aria-label*="ubject"], input[placeholder*="ubject"]')?.value.trim() ?? ''
      );
    },
    extractRecipients(c) {
      const emails = new Set();
      c.querySelectorAll('[class*="recipientWell"] [title], [class*="Persona"] [data-lpc-hover-target]')
        .forEach(chip => {
          const txt = chip.getAttribute('title') || chip.textContent;
          const m = txt?.match(/[^\s@;]+@[^\s@;]+\.[^\s@;]+/);
          if (m) emails.add(m[0]);
        });
      if (emails.size) return [...emails];

      const toInput = c.querySelector('[aria-label="To"], input[placeholder*="To"]');
      if (toInput) {
        const text = toInput.value || toInput.innerText || '';
        (text.match(/[^\s@;,]+@[^\s@;,]+\.[^\s@;,]+/g) ?? []).forEach(e => emails.add(e));
      }
      return [...emails];
    },
    extractBody(c) {
      return (
        c.querySelector('[contenteditable="true"], [role="textbox"]')?.innerText.trim() ?? ''
      );
    },
    injectPixel(c, url) {
      const body = c.querySelector('[contenteditable="true"], [role="textbox"]');
      if (!body) return false;
      body.querySelector('.nudge-pixel')?.remove();
      body.appendChild(buildPixelImg(url));
      body.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    },
    getSidebarRoot(c) {
      // Insert into the command/toolbar bar at the bottom of the compose area
      return (
        c.querySelector('[class*="commandBar"], [class*="CommandBar"], [class*="toolbar"]') ?? c
      );
    }
  },

  // ── Proton Mail (mail.proton.me / mail.protonmail.com) ────────
  // Note: Proton Mail recipients who have "block remote images" enabled
  // will not trigger the pixel. External recipients are unaffected.
  protonmail: {
    findComposeWindows() {
      const found = [];
      document.querySelectorAll('button[data-testid="composer:send-button"]').forEach(sendBtn => {
        const container = sendBtn.closest(
          '[data-testid="composer-container"], [class*="composer"], [class*="Composer"]'
        );
        if (container) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      return c.querySelector('input[data-testid="composer:subject"]')?.value.trim() ?? '';
    },
    extractRecipients(c) {
      const emails = new Set();
      // Address chips expose the email in their title or a nested element
      c.querySelectorAll(
        '[data-testid="composer:to"] .composer-addresses-item, ' +
        '[data-testid="composer:to"] [title], [data-testid="composer:cc"] [title], [data-testid="composer:bcc"] [title]'
      ).forEach(el => {
        const txt = el.getAttribute('title') || el.textContent || '';
        const m = txt.match(/[^\s@;<>]+@[^\s@;<>]+\.[^\s@;<>]+/);
        if (m) emails.add(m[0]);
      });
      if (emails.size) return [...emails];
      const input = c.querySelector('input[placeholder*="Email address"]');
      if (input?.value) {
        (input.value.match(/[^\s@;,]+@[^\s@;,]+\.[^\s@;,]+/g) ?? []).forEach(e => emails.add(e));
      }
      return [...emails];
    },
    extractBody(c) {
      // Proton Mail uses a same-origin iframe for the editor (Rooster)
      const iframe = c.querySelector('iframe.rooster-editor-iframe, iframe[title*="Email"]');
      if (iframe) {
        try {
          return iframe.contentDocument?.querySelector('[contenteditable="true"]')?.innerText.trim() ?? '';
        } catch { /* cross-origin guard */ }
      }
      return c.querySelector('[contenteditable="true"]')?.innerText.trim() ?? '';
    },
    injectPixel(c, url) {
      const iframe = c.querySelector('iframe.rooster-editor-iframe, iframe[title*="Email"]');
      if (iframe) {
        try {
          const body = iframe.contentDocument?.querySelector('[contenteditable="true"]');
          if (body) {
            body.querySelector('.nudge-pixel')?.remove();
            body.appendChild(buildPixelImg(url));
            body.dispatchEvent(new Event('input', { bubbles: true }));
            return true;
          }
        } catch { /* cross-origin guard */ }
      }
      // Fallback: top-level contenteditable (may not embed in sent email)
      const body = c.querySelector('[contenteditable="true"]');
      if (body) {
        body.querySelector('.nudge-pixel')?.remove();
        body.appendChild(buildPixelImg(url));
        body.dispatchEvent(new Event('input', { bubbles: true }));
        return true;
      }
      return false;
    },
    getSidebarRoot(c) {
      return (
        c.querySelector(
          '[data-testid="composer:actions"], [class*="ComposerActions"], [class*="composer-actions"]'
        ) ?? c
      );
    }
  },

  // ── Infomaniak Mail (mail.infomaniak.com) ─────────────────────
  infomaniak: {
    findComposeWindows() {
      const found = [];
      document.querySelectorAll(
        'button[title*="Send"], button[aria-label*="Send"], ' +
        'button[data-action="send"], button.k-btn-compose-send'
      ).forEach(sendBtn => {
        const container = sendBtn.closest(
          '[class*="compose"], [class*="Compose"], [role="dialog"], form[class*="mail"]'
        );
        if (container) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      return (
        c.querySelector(
          'input[name="subject"], input[placeholder*="ubject"], input[aria-label*="ubject"]'
        )?.value.trim() ?? ''
      );
    },
    extractRecipients(c) {
      const emails = new Set();
      c.querySelectorAll('[class*="recipient"] [title], [class*="to-field"] [title]').forEach(el => {
        const txt = el.getAttribute('title') || el.textContent || '';
        const m = txt.match(/[^\s@;,<>]+@[^\s@;,<>]+\.[^\s@;,<>]+/);
        if (m) emails.add(m[0]);
      });
      if (emails.size) return [...emails];
      const input = c.querySelector('input[aria-label*="To"], input[name="to"], input[placeholder*="To"]');
      if (input?.value) {
        (input.value.match(/[^\s@;,]+@[^\s@;,]+\.[^\s@;,]+/g) ?? []).forEach(e => emails.add(e));
      }
      return [...emails];
    },
    extractBody(c) {
      return (
        c.querySelector('[contenteditable="true"], [role="textbox"], .note-editable')?.innerText.trim() ?? ''
      );
    },
    injectPixel(c, url) {
      const body = c.querySelector('[contenteditable="true"], [role="textbox"], .note-editable');
      if (!body) return false;
      body.querySelector('.nudge-pixel')?.remove();
      body.appendChild(buildPixelImg(url));
      body.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    },
    getSidebarRoot(c) {
      return (
        c.querySelector('[class*="toolbar"], [class*="actions"], [class*="compose-footer"]') ?? c
      );
    }
  },

  // ── Yahoo Mail (mail.yahoo.com) ───────────────────────────────
  yahoo: {
    findComposeWindows() {
      const found = [];
      document.querySelectorAll(
        'button[data-test-id="btn-send"], button[aria-label="Send Email"], button[aria-label="Send"]'
      ).forEach(sendBtn => {
        const container = sendBtn.closest(
          '[data-test-id="compose-view"], [class*="compose"], [role="dialog"]'
        );
        if (container) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      return (
        c.querySelector(
          'input[data-test-id="compose-subject"], input[placeholder*="ubject"]'
        )?.value.trim() ?? ''
      );
    },
    extractRecipients(c) {
      const emails = new Set();
      c.querySelectorAll(
        '[data-test-id="to"] .recipient-text, [data-test-id="to"] [title], ' +
        '[class*="addressList"] [title], [data-test-id="cc"] [title], [data-test-id="bcc"] [title]'
      ).forEach(el => {
        const txt = el.getAttribute('title') || el.textContent || '';
        const m = txt.match(/[^\s@;<>]+@[^\s@;<>]+\.[^\s@;<>]+/);
        if (m) emails.add(m[0]);
      });
      if (emails.size) return [...emails];
      const input = c.querySelector('[data-test-id="yui-to"] input, input[aria-label*="To"]');
      if (input?.value) {
        (input.value.match(/[^\s@;,]+@[^\s@;,]+\.[^\s@;,]+/g) ?? []).forEach(e => emails.add(e));
      }
      return [...emails];
    },
    extractBody(c) {
      return (
        c.querySelector(
          '[data-test-id="rte"] [contenteditable="true"], ' +
          '[aria-label*="Body"][contenteditable="true"], ' +
          '[class*="editor"] [contenteditable="true"]'
        )?.innerText.trim() ?? ''
      );
    },
    injectPixel(c, url) {
      const body = c.querySelector(
        '[data-test-id="rte"] [contenteditable="true"], ' +
        '[aria-label*="Body"][contenteditable="true"], ' +
        '[class*="editor"] [contenteditable="true"]'
      );
      if (!body) return false;
      body.querySelector('.nudge-pixel')?.remove();
      body.appendChild(buildPixelImg(url));
      body.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    },
    getSidebarRoot(c) {
      return (
        c.querySelector('[data-test-id="compose-header"], [class*="compose-toolbar"]') ?? c
      );
    }
  }
};

// ── Bootstrap ──────────────────────────────────────────────────

const adapter = PLATFORM ? ADAPTERS[PLATFORM] : null;

function startTracking() {
if (!adapter) {
  // Signal to background/popup that this tab needs dashboard fallback
  chrome.runtime.sendMessage({ type: 'PLATFORM_UNSUPPORTED', host: window.location.hostname });
} else {
  const mounted = new Map();
  let scanPending = false;
  function scan() {
    scanPending = false;
    for (const [container, state] of mounted) {
      if (!container.isConnected || !state.sendBtn.isConnected) {
        state.cleanup();
        mounted.delete(container);
      }
    }
    adapter.findComposeWindows().forEach(({ container, sendBtn }) => {
      if (mounted.has(container)) return;
      mounted.set(container, { sendBtn, cleanup: mountSidebar(container, sendBtn) });
    });
  }
  new MutationObserver(() => {
    if (!scanPending) {
      scanPending = true;
      requestAnimationFrame(scan);
    }
  }).observe(document.body, { childList: true, subtree: true });
  scan();
}

}

// ── Nudge Sidebar (shared UI) ──────────────────────────────────

function mountSidebar(container, sendBtn) {
  ensureStyles();

  // ── Build panel ──
  const uid = `nudge-${Date.now()}`;
  const panel = document.createElement('div');
  panel.className = 'nudge-panel';
  panel.innerHTML = `
    <div class="nudge-panel-head">
      <span class="nudge-brand">📨 Nudge</span>
      <label class="nudge-switch" title="Toggle tracking">
        <input type="checkbox" id="${uid}-chk" checked>
        <span class="nudge-track"></span>
      </label>
    </div>
    <div class="nudge-panel-body">
      <div class="nudge-pill nudge-on" id="${uid}-status">Tracking ON</div>
      <div class="nudge-hint">
        <div class="nudge-hint-label">⏰ Best send time</div>
        <div class="nudge-hint-val" id="${uid}-sendtime">Loading…</div>
      </div>
    </div>
  `;

  const checkbox  = panel.querySelector(`#${uid}-chk`);
  const statusPill = panel.querySelector(`#${uid}-status`);
  const sendtimeEl = panel.querySelector(`#${uid}-sendtime`);

  // Toggle tracking on/off
  checkbox.addEventListener('change', () => {
    const on = checkbox.checked;
    if (!on) removeTrackingPixels(container);
    statusPill.textContent = on ? 'Tracking ON' : 'Tracking OFF';
    statusPill.className = `nudge-pill ${on ? 'nudge-on' : 'nudge-off'}`;
  });

  // Mount panel
  const root = adapter.getSidebarRoot(container);
  root.appendChild(panel);

  // Fetch send-time suggestion in background
  chrome.runtime.sendMessage({ type: 'GET_SEND_TIME' })
    .then(r => { if (sendtimeEl) sendtimeEl.textContent = r?.suggestion ?? 'No data yet'; })
    .catch(() => { if (sendtimeEl) sendtimeEl.textContent = 'No data yet'; });

  // Capture before the webmail's normal send handler; replay only after injection.
  let pending = false;
  let replaying = false;
  function snapshot() {
    return {
      subject: adapter.extractSubject(container),
      recipientEmails: adapter.extractRecipients(container),
      content: adapter.extractBody(container)
    };
  }
  async function interceptSend(event) {
    if (replaying) return;
    if (!checkbox.checked) {
      removeTrackingPixels(container);
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    if (pending) return;
    removeTrackingPixels(container);
    const payload = snapshot();
    if (payload.recipientEmails.length !== 1) {
      statusPill.textContent = 'Use one recipient, or turn tracking OFF to send.';
      statusPill.className = 'nudge-pill nudge-off';
      return;
    }
    if (!payload.subject) payload.subject = '(no subject)';
    const before = JSON.stringify(snapshot());
    pending = true;
    statusPill.textContent = 'Preparing tracking…';
    statusPill.className = 'nudge-pill nudge-pending';
    try {
      const res = await chrome.runtime.sendMessage({ type: 'REGISTER_EMAIL', payload });
      if (!container.isConnected) return;
      if (!res?.success || res.results?.length !== 1) {
        throw new Error(res?.error || 'Could not prepare tracking. Retry or turn tracking OFF.');
      }
      if (before !== JSON.stringify(snapshot()) || !checkbox.checked) {
        throw new Error('Draft changed. Review it and press Send again.');
      }
      if (!adapter.injectPixel(container, res.results[0].trackingPixelUrl)) {
        throw new Error('Editor unavailable. Turn tracking OFF to send.');
      }
      statusPill.textContent = 'Tracking prepared';
      statusPill.className = 'nudge-pill nudge-on';
      replaying = true;
      try { sendBtn.click(); } finally { replaying = false; }
    } catch (error) {
      removeTrackingPixels(container);
      statusPill.textContent = error.message;
      statusPill.className = 'nudge-pill nudge-off';
    } finally {
      pending = false;
    }
  }
  const dispatch = event => {
    if ((event.type === 'click' && (sendBtn === event.target || sendBtn.contains(event.target))) ||
        (event.type === 'keydown' && event.key === 'Enter' && (event.ctrlKey || event.metaKey) && container.contains(event.target))) {
      interceptSend(event);
      return true;
    }
    return false;
  };
  sendInterceptors.add(dispatch);
  return () => { sendInterceptors.delete(dispatch); panel.remove(); };
}

// ── Helpers ────────────────────────────────────────────────────

function removeTrackingPixels(container) {
  container.querySelectorAll('.nudge-pixel').forEach(pixel => pixel.remove());
  for (const iframe of container.querySelectorAll('iframe')) {
    try { iframe.contentDocument?.querySelectorAll('.nudge-pixel').forEach(pixel => pixel.remove()); }
    catch { /* another origin cannot be edited */ }
  }
}

function buildPixelImg(url) {
  const img = document.createElement('img');
  img.src       = url;
  img.width     = 1;
  img.height    = 1;
  img.style.cssText = 'display:block;border:none;width:1px;height:1px;opacity:0;position:absolute;';
  img.className = 'nudge-pixel';
  img.alt       = '';
  return img;
}

// ── Injected CSS (once per page) ───────────────────────────────

let _stylesInjected = false;
function ensureStyles() {
  if (_stylesInjected) return;
  _stylesInjected = true;

  const style = document.createElement('style');
  style.textContent = `
    /* ── Nudge panel ── */
    .nudge-panel {
      display: inline-flex;
      flex-direction: column;
      background: #1e293b;
      border: 1px solid #334155;
      border-radius: 10px;
      padding: 7px 10px;
      margin: 4px 6px;
      min-width: 155px;
      font-family: system-ui, sans-serif;
      font-size: 12px;
      color: #f1f5f9;
      box-shadow: 0 2px 10px rgba(0,0,0,.4);
      vertical-align: middle;
      z-index: 9999;
      line-height: 1.4;
    }
    .nudge-panel-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;
    }
    .nudge-brand {
      font-weight: 700;
      font-size: 12px;
      color: #818cf8;
      letter-spacing: -.01em;
    }

    /* Status pill */
    .nudge-pill {
      display: inline-block;
      font-size: 11px;
      font-weight: 600;
      padding: 2px 7px;
      border-radius: 20px;
      margin-bottom: 6px;
    }
    .nudge-on      { background: rgba(34,197,94,.15);  color: #4ade80; }
    .nudge-off     { background: rgba(239,68,68,.15);  color: #f87171; }
    .nudge-pending { background: rgba(99,102,241,.15); color: #a5b4fc; }

    /* Send-time hint */
    .nudge-hint-label {
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: .05em;
      color: #64748b;
      margin-bottom: 2px;
    }
    .nudge-hint-val {
      font-size: 11px;
      font-weight: 600;
      color: #cbd5e1;
    }

    /* Toggle switch */
    .nudge-switch {
      position: relative;
      display: inline-block;
      width: 30px;
      height: 17px;
      cursor: pointer;
    }
    .nudge-switch input { opacity: 0; width: 0; height: 0; }
    .nudge-track {
      position: absolute;
      inset: 0;
      background: #475569;
      border-radius: 17px;
      transition: background .2s;
    }
    .nudge-track::before {
      content: '';
      position: absolute;
      width: 13px;
      height: 13px;
      left: 2px;
      bottom: 2px;
      background: #fff;
      border-radius: 50%;
      transition: transform .2s;
    }
    .nudge-switch input:checked + .nudge-track { background: #6366f1; }
    .nudge-switch input:checked + .nudge-track::before { transform: translateX(13px); }
  `;
  document.head.appendChild(style);
}

// Start only after all state (including styles) has been initialized.
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', startTracking, { once: true });
} else {
  startTracking();
}

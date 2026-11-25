/**
 * content.js — Nudge Multi-Platform Email Tracker
 *
 * Supported platforms: Gmail, Outlook Web, Proton Mail, Tuta, Infomaniak Mail, Yahoo Mail
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
  if (h === 'app.tuta.com')                return 'tuta';
  if (h === 'mail.tutanota.com')           return 'tuta';
  if (h === 'mail.infomaniak.com')         return 'infomaniak';
  if (h === 'mail.yahoo.com')              return 'yahoo';
  return null;
})();

console.log(`[Nudge] content script — platform: ${PLATFORM ?? 'unsupported'}`);

// ── Shared detection (English and French interfaces) ──────────

const EMAIL = /[^\s@;,<>()"]+@[^\s@;,<>()"]+\.[^\s@;,<>()"]+/g;
const SUBJECT_FIELD = 'input[name="subject"], input[aria-label*="subject" i], input[aria-label*="objet" i], ' +
  'input[placeholder*="subject" i], input[placeholder*="objet" i]';

function labelsOf(el) {
  return ['aria-label', 'title', 'data-tooltip'].map(name => el.getAttribute(name)).concat(el.textContent)
    // Gmail wraps shortcuts in invisible direction marks
    .map(label => (label ?? '').replace(/[\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '').replace(/\s+/g, ' ').trim().toLowerCase());
}

/** "Send", "Envoyer (Ctrl+Entrée)"… but not "Send later" or "Envoyer un commentaire". */
function isSendLabel(label) {
  return /^(send|envoyer)( (the |le |l['’])?(e-?mail|mail|message))?( ?\(.*\))?$/.test(label);
}

/** "Send & archive", "Envoyer et archiver"… */
function isSendVariantLabel(label) {
  return /^(send|envoyer)( and | et | ?[&+] ?)(archive|archiver)\b/.test(label);
}

/** Title of the window or menu that schedules a message. */
function isScheduleLabel(label) {
  return /^(schedule send|programmer l['’]envoi|planifier l['’]envoi)/i.test(label.trim());
}

/** "To", "À", "Cc recipients", "Destinataires"… */
function isRecipientLabel(label) {
  return /^(to|cc|bcc|à|a|pour|cci|destinataires?)( recipients?| \(?obligatoire\)?)?\s*:?$/.test(label);
}

/** The message editor. Recipient fields can be editable too (Outlook). */
function findEditor(root) {
  const editors = [...root.querySelectorAll('[contenteditable="true"]')]
    .filter(el => !isRecipientLabel((el.getAttribute('aria-label') ?? '').trim().toLowerCase()))
    .filter(el => !/^(subject|objet)/i.test(el.getAttribute('aria-label') ?? ''));
  return editors.find(el => /message|body|corps/i.test(el.getAttribute('aria-label') ?? '')) ?? editors.at(-1) ?? null;
}

/** Smallest element that holds the button, a subject field and an editor. */
function composeRootOf(sendBtn) {
  for (let el = sendBtn.parentElement; el && el !== document.body; el = el.parentElement) {
    if (el.querySelector(SUBJECT_FIELD) && findEditor(el)) return el;
  }
  return null;
}

/** Compose windows found from their Send button, whatever the layout. */
function findByStructure(buttonSelector, fallbackSelector) {
  const found = [];
  document.querySelectorAll(buttonSelector).forEach(sendBtn => {
    if (!labelsOf(sendBtn).some(isSendLabel)) return;
    const container = composeRootOf(sendBtn) ?? sendBtn.closest(fallbackSelector);
    if (container) found.push({ container, sendBtn });
  });
  // A Send button outside any compose window reaches a root that holds a real one.
  return found.filter(({ container }) =>
    !found.some(other => other.container !== container && container.contains(other.container)));
}

/** Addresses shown in the recipient fields: text, title, aria-label or value. */
function recipientsFromFields(root) {
  const emails = new Set();
  const fields = [...root.querySelectorAll('[aria-label]')]
    .filter(field => isRecipientLabel(field.getAttribute('aria-label').trim().toLowerCase()))
    .concat(...root.querySelectorAll('[class*="RECIPIENT_ENTITY"], [class*="wellItem" i]'));
  fields.forEach(field => {
    for (const el of [field, ...field.querySelectorAll('*')]) {
      const text = [el.value, el.getAttribute('title'), el.getAttribute('aria-label'), el.children.length ? '' : el.textContent].join(' ');
      (text.match(EMAIL) ?? []).forEach(email => emails.add(email.toLowerCase()));
    }
  });
  return [...emails];
}

function insertPixel(editor, url) {
  if (!editor) return false;
  editor.querySelectorAll('.nudge-pixel').forEach(pixel => pixel.remove());
  editor.appendChild(buildPixelImg(url));
  editor.dispatchEvent(new Event('input', { bubbles: true }));
  return true;
}

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
        // .M9 is the root of both new messages and replies written in a conversation
        const container = sendBtn.closest('div.M9') ?? sendBtn.closest('div[role="dialog"], .nH.Hd');
        if (container && labelsOf(sendBtn).some(isSendLabel)) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      const subject = c.querySelector('input[name="subjectbox"]')?.value.trim();
      // A reply may have no subject field: use the conversation's title
      return subject || (this.isReply(c) ? document.querySelector('h2.hP')?.textContent.trim() : '') || '';
    },
    isReply(c) {
      return c.closest('.AO') !== null; // the reading area, as opposed to a compose window
    },
    extractRecipients(c) {
      const emails = new Set();
      const inEditor = el => el.closest('[contenteditable="true"][role="textbox"]'); // mentions are not recipients
      const collect = (selector, read) => c.querySelectorAll(selector).forEach(el => {
        if (!inEditor(el)) (read(el)?.match(EMAIL) ?? []).forEach(email => emails.add(email.toLowerCase()));
      });
      const chip = el => el.getAttribute('data-hovercard-id');
      for (const field of ['to', 'cc', 'bcc']) {
        collect(`[name="${field}"] [data-hovercard-id]`, chip);
        collect(`input[name="${field}"], textarea[name="${field}"], [name="${field}"] input`, el => el.value);
      }
      if (!emails.size) collect('[email]', el => el.getAttribute('email')); // collapsed fields of a reply
      if (!emails.size) collect('[data-hovercard-id]', chip);
      if (!emails.size) collect('.vO', el => el.innerText);
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
      return findByStructure(
        'button[aria-label^="Send" i], button[title^="Send" i], button[aria-label^="Envoyer" i], ' +
        'button[title^="Envoyer" i], button[data-testid="compose-send-button"]',
        '[role="dialog"], [class*="compose"], [class*="Compose"], [class*="draftArea"]'
      );
    },
    extractSubject(c) {
      return (
        c.querySelector(SUBJECT_FIELD)?.value.trim() ?? ''
      );
    },
    extractRecipients(c) {
      const emails = new Set();
      c.querySelectorAll('[class*="recipientWell"] [title], [class*="Persona"] [data-lpc-hover-target]')
        .forEach(chip => {
          const txt = chip.getAttribute('title') || chip.textContent;
          const m = txt?.match(/[^\s@;]+@[^\s@;]+\.[^\s@;]+/);
          if (m) emails.add(m[0].toLowerCase());
        });
      recipientsFromFields(c).forEach(email => emails.add(email));
      return [...emails];
    },
    extractBody(c) {
      return findEditor(c)?.innerText.trim() ?? '';
    },
    injectPixel(c, url) {
      return insertPixel(findEditor(c), url);
    },
    getSidebarRoot(c) {
      // Insert into the command/toolbar bar at the bottom of the compose area
      return (
        c.querySelector('[class*="commandBar"], [class*="CommandBar"], [class*="toolbar"]') ?? c
      );
    }
  },

  // ── Proton Mail (mail.proton.me / mail.protonmail.com) ────────
  // Selectors taken from Proton's published source (ProtonMail/WebClients, mail-composer).
  protonmail: {
    settleMs: 200,
    findComposeWindows() {
      const found = [];
      document.querySelectorAll('button[data-testid="composer:send-button"]').forEach(sendBtn => {
        const container = sendBtn.closest('section.composer');
        if (container) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      return c.querySelector('input[data-testid="composer:subject"]')?.value.trim() ?? '';
    },
    /** Outside the address fields, Proton only shows names: open them to read the address. */
    async prepare(c) {
      const summary = c.querySelector('[data-testid="composer:address"] [role="button"]');
      if (!summary || this.summaryLabels(c).length !== 1) return;
      summary.click();
      for (let tries = 0; tries < 20 && !c.querySelector('[data-testid="composer:to-field"] .composer-addresses-item'); tries++) {
        await new Promise(resolve => setTimeout(resolve, 50));
      }
    },
    summaryLabels(c) {
      return [...c.querySelectorAll('[data-testid="composer:address"] [role="button"] > span.align-top')]
        .map(label => label.textContent.replace(/,$/, '').trim());
    },
    extractRecipients(c) {
      const recipients = new Set(this.summaryLabels(c).map(label => label.match(EMAIL)?.[0].toLowerCase() ?? label));
      const items = [...c.querySelectorAll('.composer-addresses-item')];
      items.forEach((item, index) => {
        const address = item.querySelector('[data-testid^="remove-address-button-"]')
          ?.getAttribute('data-testid').slice('remove-address-button-'.length).toLowerCase();
        if (address) recipients.add(address);
        // A contact group holds several people and shows none of their addresses
        else recipients.add(`group ${index} a`).add(`group ${index} b`);
      });
      // Typed but not yet turned into a recipient
      c.querySelectorAll('[data-testid="composer:to"], [data-testid="composer:to-cc"], [data-testid="composer:to-bcc"]')
        .forEach(input => (input.value?.match(EMAIL) ?? []).forEach(email => recipients.add(email.toLowerCase())));
      return [...recipients];
    },
    editor(c) {
      const iframe = c.querySelector('iframe[data-testid="rooster-iframe"]');
      try { return iframe?.contentDocument?.querySelector('#rooster-editor') ?? null; }
      catch { return null; }
    },
    extractBody(c) {
      return this.editor(c)?.innerText.trim() ?? '';
    },
    injectPixel(c, url) {
      return insertPixel(this.editor(c), url);
    },
    getSidebarRoot(c) {
      return c.querySelector('footer.composer-actions') ?? c;
    }
  },

  // ── Tuta (app.tuta.com) ───────────────────────────────────────
  // Selectors taken from Tuta's published source (tutao/tutanota, MailEditor).
  tuta: {
    settleMs: 200,
    findComposeWindows() {
      const found = [];
      // The header button becomes "send later" when a date is set
      document.querySelectorAll('[data-testid="btn:send_action"], .dialog-header [data-testid="btn:sendLater_action"]').forEach(sendBtn => {
        const container = sendBtn.closest('[role="dialog"]');
        if (container?.querySelector('#mail-editor')) found.push({ container, sendBtn });
      });
      return found;
    },
    extractSubject(c) {
      return c.querySelector('[data-testid="tfi:subject_label"]')?.value.trim() ?? '';
    },
    extractRecipients(c) {
      const emails = new Set();
      for (const field of ['to', 'cc', 'bcc']) {
        // Each recipient is a button identified by its address
        c.querySelectorAll(`[data-testid="tf:${field}_label"] [data-testid^="btn:"]`).forEach(bubble =>
          (bubble.getAttribute('data-testid').match(EMAIL) ?? [])
            .forEach(email => emails.add(email.replace(/^btn:/, '').toLowerCase())));
        (c.querySelector(`[data-testid="tfi:${field}_label"]`)?.value.match(EMAIL) ?? [])
          .forEach(email => emails.add(email.toLowerCase()));
      }
      return [...emails];
    },
    editor(c) {
      return c.querySelector('#mail-editor [data-testid="text_editor"]');
    },
    extractBody(c) {
      return this.editor(c)?.innerText.trim() ?? '';
    },
    injectPixel(c, url) {
      return insertPixel(this.editor(c), url);
    },
    getSidebarRoot(c) {
      return c.querySelector('#mail-editor');
    },
    panelFirst: true // the end of the editor can be far below the screen
  },

  // ── Infomaniak Mail (mail.infomaniak.com) ─────────────────────
  infomaniak: {
    findComposeWindows() {
      return findByStructure(
        'button',
        '[class*="compose"], [class*="Compose"], [role="dialog"], form[class*="mail"]'
      );
    },
    extractSubject(c) {
      return (
        c.querySelector(SUBJECT_FIELD)?.value.trim() ?? ''
      );
    },
    extractRecipients(c) {
      const emails = new Set();
      c.querySelectorAll('[class*="recipient"] [title], [class*="to-field"] [title]').forEach(el => {
        const txt = el.getAttribute('title') || el.textContent || '';
        const m = txt.match(/[^\s@;,<>]+@[^\s@;,<>]+\.[^\s@;,<>]+/);
        if (m) emails.add(m[0].toLowerCase());
      });
      recipientsFromFields(c).forEach(email => emails.add(email));
      if (emails.size) return [...emails];
      const input = c.querySelector('input[name="to"], input[placeholder="To" i], input[placeholder="À" i], input[placeholder*="destinataire" i]');
      (input?.value.match(EMAIL) ?? []).forEach(email => emails.add(email.toLowerCase()));
      return [...emails];
    },
    extractBody(c) {
      return (findEditor(c) ?? c.querySelector('.note-editable'))?.innerText.trim() ?? '';
    },
    injectPixel(c, url) {
      return insertPixel(findEditor(c) ?? c.querySelector('.note-editable'), url);
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
        'button[data-test-id="btn-send"], button[aria-label="Send Email"], button[aria-label="Send"], button[aria-label^="Envoyer"]'
      ).forEach(sendBtn => {
        if (!sendBtn.matches('[data-test-id="btn-send"]') && !labelsOf(sendBtn).some(isSendLabel)) return;
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
          'input[data-test-id="compose-subject"], ' + SUBJECT_FIELD
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
      const input = c.querySelector('[data-test-id="yui-to"] input, input[aria-label="To"], input[aria-label="À"]');
      if (input?.value) {
        (input.value.match(/[^\s@;,]+@[^\s@;,]+\.[^\s@;,]+/g) ?? []).forEach(e => emails.add(e));
      }
      return [...emails];
    },
    extractBody(c) {
      return (
        c.querySelector(
          '[data-test-id="rte"] [contenteditable="true"], ' +
          '[aria-label*="Body"][contenteditable="true"], [aria-label*="Corps"][contenteditable="true"], ' +
          '[class*="editor"] [contenteditable="true"]'
        )?.innerText.trim() ?? ''
      );
    },
    injectPixel(c, url) {
      const body = c.querySelector(
        '[data-test-id="rte"] [contenteditable="true"], ' +
        '[aria-label*="Body"][contenteditable="true"], [aria-label*="Corps"][contenteditable="true"], ' +
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

  // The scheduling window is outside the compose window: remember which one was used last.
  let lastUsed = null;
  window.addEventListener('mousedown', event => {
    for (const container of mounted.keys()) if (container.contains(event.target)) lastUsed = container;
  }, true);
  let scheduling = false;
  function watchScheduling() {
    const open = [...document.querySelectorAll('[role="dialog"], [data-testid="composer:schedule-send:dropdown-title"]')]
      .some(el => ![...mounted.keys()].some(container => el.contains(container)) && el.getClientRects().length > 0 &&
        isScheduleLabel(el.getAttribute('aria-label') || el.textContent.slice(0, 80)));
    if (open && !scheduling) {
      const state = mounted.get(lastUsed) ?? (mounted.size === 1 ? [...mounted.values()][0] : null);
      state?.prepareSchedule();
    }
    scheduling = open;
  }

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
      mounted.set(container, { sendBtn, ...mountSidebar(container, sendBtn) });
    });
    watchScheduling();
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

let panelCount = 0;
function mountSidebar(container, sendBtn) {
  ensureStyles();

  // ── Build panel ──
  const uid = `nudge-${++panelCount}`;
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
      <input class="nudge-manual" id="${uid}-manual" type="email" placeholder="Recipient's email address" hidden>
      <details class="nudge-hint" id="${uid}-hint">
        <summary class="nudge-hint-label">Best send time</summary>
        <div class="nudge-hint-val" id="${uid}-sendtime">Loading…</div>
      </details>
    </div>
  `;

  const checkbox  = panel.querySelector(`#${uid}-chk`);
  const statusPill = panel.querySelector(`#${uid}-status`);
  const sendtimeEl = panel.querySelector(`#${uid}-sendtime`);
  const manual     = panel.querySelector(`#${uid}-manual`);
  // Keep the webmail's keyboard shortcuts from reacting to what is typed here
  for (const type of ['keydown', 'keypress', 'keyup', 'paste', 'input']) manual.addEventListener(type, event => event.stopPropagation());

  // Toggle tracking on/off
  checkbox.addEventListener('change', () => {
    const on = checkbox.checked;
    if (!on) removeTrackingPixels(container);
    statusPill.textContent = on ? 'Tracking ON' : 'Tracking OFF';
    statusPill.className = `nudge-pill ${on ? 'nudge-on' : 'nudge-off'}`;
  });

  // Mount panel
  const root = adapter.getSidebarRoot(container);
  if (adapter.panelFirst) root.prepend(panel);
  else root.appendChild(panel);

  // Asked only when the user opens the section, once per compose window.
  panel.querySelector(`#${uid}-hint`).addEventListener('toggle', () => {
    chrome.runtime.sendMessage({ type: 'GET_SEND_TIME' })
      .then(r => { sendtimeEl.textContent = r?.suggestion ?? 'No data yet'; })
      .catch(() => { sendtimeEl.textContent = 'No data yet'; });
  }, { once: true });

  // Capture before the webmail's normal send handler; replay only after injection.
  let pending = false;
  let replaying = false;
  function snapshot() {
    const found = adapter.extractRecipients(container);
    // The address typed in the panel only fills in for one that the webmail does not show
    const typed = found.length || manual.hidden ? [] : manual.value.match(EMAIL) ?? [];
    return {
      subject: adapter.extractSubject(container),
      recipientEmails: found.length ? found : typed.map(email => email.toLowerCase()),
      content: adapter.extractBody(container)
    };
  }
  function setStatus(text, kind) {
    statusPill.textContent = text;
    statusPill.className = `nudge-pill nudge-${kind}`;
  }
  function replay(button) {
    replaying = true;
    try { button.click(); } finally { replaying = false; }
  }
  /** Registers the email and adds the pixel to the draft; throws with a readable message. */
  async function track(payload) {
    if (!payload.subject) payload.subject = '(no subject)';
    const before = JSON.stringify(snapshot());
    const res = await chrome.runtime.sendMessage({ type: 'REGISTER_EMAIL', payload });
    if (!container.isConnected) return false;
    if (!res?.success || res.results?.length !== 1) {
      throw new Error(res?.error || 'Could not prepare tracking. Retry or turn tracking OFF.');
    }
    if (before !== JSON.stringify(snapshot()) || !checkbox.checked) {
      throw new Error('Draft changed. Review it and press Send again.');
    }
    if (!adapter.injectPixel(container, res.results[0].trackingPixelUrl)) {
      throw new Error('Editor unavailable. Turn tracking OFF to send.');
    }
    return true;
  }
  /** Scheduling cannot be held back: the pixel is added while the user picks a date. */
  async function prepareSchedule() {
    if (!checkbox.checked || pending) return;
    pending = true;
    try {
      removeTrackingPixels(container);
      await adapter.prepare?.(container);
      const payload = snapshot();
      if (payload.recipientEmails.length !== 1) {
        throw new Error(payload.recipientEmails.length ? 'several recipients' : 'recipient not recognised');
      }
      setStatus('Preparing tracking…', 'pending');
      if (await track(payload)) setStatus('Tracking prepared', 'on');
    } catch (error) {
      removeTrackingPixels(container);
      setStatus('Not tracked if scheduled', 'off');
      showNotice(`Scheduled message not tracked: ${error.message}`);
    } finally {
      pending = false;
    }
  }
  async function interceptSend(event, button) {
    if (replaying) return;
    if (!checkbox.checked) {
      removeTrackingPixels(container);
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    if (pending) return;
    removeTrackingPixels(container);
    if (adapter.prepare) {
      pending = true;
      try { await adapter.prepare(container); } finally { pending = false; }
    }
    const payload = snapshot();
    if (payload.recipientEmails.length !== 1) {
      const found = payload.recipientEmails.length;
      if (adapter.isReply?.(container)) {
        // Never hold back a reply to several people: send it, and say it is untracked.
        replay(button);
        showNotice(found ? 'Reply sent without tracking: several recipients.' : 'Reply sent without tracking: recipient not recognised.');
        return;
      }
      statusPill.textContent = found
        ? 'Use one recipient, or turn tracking OFF to send.'
        : 'Recipient not recognised: type the address below, then Send. One recipient only.';
      statusPill.className = 'nudge-pill nudge-off';
      if (!found) manual.hidden = false;
      return;
    }
    pending = true;
    setStatus('Preparing tracking…', 'pending');
    try {
      if (!await track(payload)) return;
      setStatus('Tracking prepared', 'on');
      // Editors that keep their own copy of the message need time to take the pixel in
      if (adapter.settleMs) await new Promise(resolve => setTimeout(resolve, adapter.settleMs));
      replay(button);
    } catch (error) {
      removeTrackingPixels(container);
      statusPill.textContent = error.message;
      statusPill.className = 'nudge-pill nudge-off';
    } finally {
      pending = false;
    }
  }
  function clickedSendButton(event) {
    if (event.type !== 'click') return null;
    if (sendBtn === event.target || sendBtn.contains(event.target)) return sendBtn;
    const other = event.target.closest?.('button, [role="button"]');
    return other && container.contains(other) && labelsOf(other).some(isSendVariantLabel) ? other : null;
  }
  const dispatch = event => {
    const byKeyboard = event.type === 'keydown' && event.key === 'Enter' && (event.ctrlKey || event.metaKey) && container.contains(event.target);
    const button = byKeyboard ? sendBtn : clickedSendButton(event);
    if (!button) return false;
    interceptSend(event, button);
    return true;
  };
  sendInterceptors.add(dispatch);
  return { prepareSchedule, cleanup() { sendInterceptors.delete(dispatch); panel.remove(); } };
}

// ── Helpers ────────────────────────────────────────────────────

const TRACKING_URL = /\/track\/open\/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/i;

/**
 * Also removes the pixels of quoted emails: reading a reply must not mark the emails
 * it quotes as opened.
 */
function removeTrackingPixels(container) {
  const clean = root => root?.querySelectorAll('img').forEach(img => {
    if (img.classList.contains('nudge-pixel') || TRACKING_URL.test(safeDecode(img.getAttribute('src') ?? ''))) img.remove();
  });
  clean(container);
  for (const iframe of container.querySelectorAll('iframe')) {
    try { clean(iframe.contentDocument); }
    catch { /* another origin cannot be edited */ }
  }
}

function safeDecode(url) {
  try { return decodeURIComponent(url); } catch { return url; }
}

function showNotice(text) {
  const notice = document.createElement('div');
  notice.className = 'nudge-notice';
  notice.setAttribute('role', 'status');
  notice.textContent = text;
  document.body.appendChild(notice);
  setTimeout(() => notice.remove(), 6000);
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

    .nudge-manual {
      display: block;
      width: 100%;
      box-sizing: border-box;
      margin-bottom: 6px;
      padding: 4px 8px;
      border: 1px solid #475569;
      border-radius: 6px;
      background: #0f172a;
      color: #f1f5f9;
      font: 12px system-ui, sans-serif;
    }
    .nudge-manual[hidden] { display: none; }

    /* Send-time hint */
    .nudge-hint-label {
      font-size: 10px;
      text-transform: uppercase;
      letter-spacing: .05em;
      color: #64748b;
      margin-bottom: 2px;
      cursor: pointer;
    }
    .nudge-hint-val {
      font-size: 11px;
      font-weight: 600;
      color: #cbd5e1;
    }

    .nudge-notice {
      position: fixed;
      left: 50%;
      bottom: 24px;
      transform: translateX(-50%);
      padding: 10px 16px;
      border-radius: 8px;
      background: #1e293b;
      color: #f1f5f9;
      font: 13px system-ui, sans-serif;
      box-shadow: 0 2px 10px rgba(0,0,0,.4);
      z-index: 2147483647;
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

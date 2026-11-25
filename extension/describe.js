/**
 * Describes the compose window of the current page, to add or repair a webmail.
 * Run by the popup's "Copy page structure" button; the result is shown to the user.
 *
 * Only the structure is kept: every word that is not part of the interface vocabulary
 * below is replaced, so texts, names, addresses, values and links never leave the page.
 */
(() => {
  const WORDS = new Set(('to cc bcc from subject message body send send: sent add a an the your recipient recipients remove ' +
    'delete email mail address field text editor press alt f10 exit compose new reply forward draft toolbar contact contacts people picker ' +
    'à a de objet sujet corps envoyer ajouter destinataire destinataires supprimer adresse champ cci pour du le la un une').split(' '));
  const TEXT_ATTRIBUTES = ['aria-label', 'title', 'placeholder', 'data-tooltip'];
  const CODE_ATTRIBUTES = ['id', 'class', 'role', 'name', 'type', 'contenteditable', 'data-testid', 'data-test-id', 'draggable'];
  const EMAIL = /[^\s@"'<>;,]+@[^\s@"'<>;,]+/g;

  const maskText = text => text.replace(EMAIL, ' EMAIL ').trim().split(/\s+/)
    .map(word => (word === 'EMAIL' || WORDS.has(word.toLowerCase()) ? word : 'x')).join(' ').slice(0, 80);
  const maskCode = text => text.replace(EMAIL, 'EMAIL').replace(/\d{4,}/g, 'N')
    .split(/\s+/).map(token => (token.length > 40 ? `${token.slice(0, 20)}…` : token)).join(' ').slice(0, 120);

  const subject = document.querySelector('input[aria-label*="subject" i], input[placeholder*="subject" i], input[aria-label*="objet" i], input[placeholder*="objet" i], input[name*="subject" i]');
  const editors = [...document.querySelectorAll('[contenteditable="true"], trix-editor, lexxy-editor, iframe, textarea')];
  const start = subject ?? editors.at(-1);
  if (!start) return null;
  // Up to the first ancestor that also holds an editor and a button: the compose window
  let root = start;
  while (root.parentElement && root.parentElement !== document.body) {
    root = root.parentElement;
    if (editors.some(editor => editor !== subject && root.contains(editor)) &&
        [...root.querySelectorAll('button, [role="button"], input[type="submit"]')]
          .some(button => /^\s*(send|envoyer)/i.test(`${button.getAttribute('aria-label') ?? ''} ${button.textContent}`.trim()))) break;
  }

  const bodyLabel = el => /message|body|corps/i.test(el.getAttribute('aria-label') ?? '');
  const lines = [`host: ${location.hostname}`];
  const visit = (el, depth) => {
    if (lines.length > 700 || /^(script|style|svg|path|head)$/i.test(el.tagName) || el.classList.contains('nudge-panel')) return;
    const attributes = [
      ...CODE_ATTRIBUTES.filter(name => el.hasAttribute(name)).map(name => `${name}="${maskCode(el.getAttribute(name))}"`),
      ...TEXT_ATTRIBUTES.filter(name => el.hasAttribute(name)).map(name => `${name}="${maskText(el.getAttribute(name))}"`)
    ];
    const own = [...el.childNodes].filter(node => node.nodeType === 3).map(node => node.data.trim()).filter(Boolean).join(' ');
    const body = el.isContentEditable && bodyLabel(el);
    lines.push(`${' '.repeat(depth)}<${[el.tagName.toLowerCase(), ...attributes].join(' ')}>${own && !body ? ` ${maskText(own)}` : ''}`);
    if (!body) for (const child of el.children) visit(child, depth + 1); // never the message itself
  };
  visit(root, 0);
  return lines.join('\n');
})();

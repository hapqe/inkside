/*
 * HTML from rendered markdown, made safe to insert.
 *
 * Chat text comes from the agent, and the agent reads documents anyone could have
 * written; text boxes can come from a synced workspace. The pages that render them
 * have file access and the app's bridge, so nothing in that text may run: markdown's
 * raw HTML and KaTeX's output pass through an allowlist, and anything else (scripts,
 * iframes, event handlers, javascript: links, CSS that loads things) is dropped.
 * Used by index.html (chat; artifact frames are added afterwards by hydrateCards) and
 * latex_bitmap.html (text boxes).
 */
const SAFE_TAGS = new Set(('a abbr b blockquote br code del div em h1 h2 h3 h4 h5 h6 hr i img ' +
  'input kbd li mark ol p pre s small span strong sub sup table tbody td tfoot th thead tr u ul ' +
  'svg path line rect g').split(' '));
const DROP_WITH_CONTENT = new Set(('script style iframe frame frameset object embed applet ' +
  'link meta base form textarea select button template noscript math').split(' '));
const SAFE_ATTRS = new Set(('class title alt aria-hidden colspan rowspan align start ' +
  'checked disabled width height viewbox preserveaspectratio d x y x1 x2 y1 y2 ' +
  'stroke stroke-width fill fill-rule data-tex data-artifact style href src type').split(' '));

function safeUrl(value, forImage) {
  const v = String(value).replace(/[\u0000- \u007f]/g, '').toLowerCase();
  if (/^https?:/.test(v)) return true;
  if (!forImage && (/^mailto:/.test(v) || v.charAt(0) === '#')) return true;
  return forImage && /^data:image\/(png|jpe?g|gif|webp);/.test(v);
}

function cleanAttributes(el) {
  const tag = el.localName;
  for (const attr of Array.from(el.attributes)) {
    const name = attr.name.toLowerCase();
    const value = attr.value;
    let keep = SAFE_ATTRS.has(name);
    if (keep && name === 'href') keep = tag === 'a' && safeUrl(value, false);
    if (keep && name === 'src') keep = tag === 'img' && safeUrl(value, true);
    if (keep && name === 'type') keep = tag === 'input' && value.toLowerCase() === 'checkbox';
    if (keep && name === 'style') keep = !/url\s*\(|expression|@import|javascript:|behavior/i.test(value);
    if (!keep) el.removeAttribute(attr.name);
  }
  if (tag === 'input' && el.getAttribute('type') !== 'checkbox') el.remove();
  if (tag === 'a' && el.hasAttribute('href')) el.setAttribute('rel', 'noopener noreferrer');
}

function sanitizeTree(node) {
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType === Node.COMMENT_NODE) { child.remove(); continue; }
    if (child.nodeType !== Node.ELEMENT_NODE) continue;
    const tag = child.localName;
    if (DROP_WITH_CONTENT.has(tag)) { child.remove(); continue; }
    sanitizeTree(child);
    if (!SAFE_TAGS.has(tag)) {
      // Unknown but harmless markup (font, center, …): keep the text, drop the tag.
      child.replaceWith(...Array.from(child.childNodes));
      continue;
    }
    cleanAttributes(child);
  }
}

function sanitizeHtml(html) {
  const tpl = document.createElement('template');
  tpl.innerHTML = html;
  sanitizeTree(tpl.content);
  const out = document.createElement('div');
  out.appendChild(tpl.content);
  return out.innerHTML;
}

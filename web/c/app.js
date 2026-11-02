import { layout, canRender, ROWS } from './cellfont.js';

// Same order as Grounds.all in the app (CellFontSyncTest checks this too).
export const GROUNDS = [
  ['#1F3BD1', '#FF5A36'],
  ['#C0301E', '#FFD23F'],
  ['#0B7A55', '#FFD23F'],
  ['#161616', '#FF5A36'],
  ['#B0460F', '#FFD23F'],
  ['#00707F', '#FFD23F'],
];
export const RELAY = 'https://ntfy.sh/';
export const TOPIC_PREFIX = 'daptap-';
const AAD = new TextEncoder().encode('daptap1');
const RELAY_MAX = 4000;

// ------------------------------------------------------------------ encoding

export function b64urlDecode(s) {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export function b64urlEncode(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const clean = (v, max) => String(v ?? '').replace(/[\u0000-\u001F\u007F]/g, ' ').trim().slice(0, max);

/** Decodes the card in the URL fragment. Returns null when it isn't a usable DapTap card. */
export function parsePayload(hash) {
  try {
    const raw = hash.replace(/^#/, '');
    if (!raw) return null;
    const p = JSON.parse(new TextDecoder().decode(b64urlDecode(raw)));
    if (typeof p !== 'object' || p === null) return null;
    const card = {
      name: clean(p.n, 80), phone: clean(p.p, 40), email: clean(p.e, 120), company: clean(p.c, 80),
      title: clean(p.t, 80), website: clean(p.w, 200), instagram: clean(p.i, 60).replace(/^@/, ''),
      label: Math.min(Math.max(Number.isInteger(p.l) ? p.l : 0, 0), GROUNDS.length - 1),
    };
    if (!card.name && !card.phone && !card.email) return null;
    const session = typeof p.s === 'string' && /^[A-Za-z0-9_-]{16,64}$/.test(p.s) ? p.s : null;
    const key = typeof p.k === 'string' ? p.k : null;
    return { card, session, key };
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ crypto (matches SecretBox.kt)

export async function importKey(b64) {
  const raw = b64urlDecode(b64);
  if (raw.length !== 32) throw new Error('bad key');
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export async function seal(key, bytes) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: AAD, tagLength: 128 }, key, bytes));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv); out.set(ct, 12);
  return b64urlEncode(out);
}

export async function openBox(key, box) {
  try {
    const raw = b64urlDecode(box);
    if (raw.length < 28) return null;
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: raw.subarray(0, 12), additionalData: AAD, tagLength: 128 }, key, raw.subarray(12));
    return new Uint8Array(pt);
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ vCard (matches VCard.kt writer)

const esc = (s) => s.replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/,/g, '\\,').replace(/;/g, '\\;');
const normUrl = (u) => (u.includes('://') ? u : 'https://' + u);

function fold(line) {
  const enc = new TextEncoder();
  if (enc.encode(line).length <= 75) return line;
  let out = '', count = 0, first = true;
  for (const ch of line) {
    const len = enc.encode(ch).length;
    if (count + len > (first ? 75 : 74)) { out += '\r\n '; count = 0; first = false; }
    out += ch; count += len;
  }
  return out;
}

export function vcard(card, photo) {
  const lines = ['BEGIN:VCARD', 'VERSION:3.0'];
  const parts = card.name.trim().split(/\s+/).filter(Boolean);
  const given = parts.length > 1 ? parts.slice(0, -1).join(' ') : (parts[0] || '');
  const family = parts.length > 1 ? parts[parts.length - 1] : '';
  lines.push(`N:${esc(family)};${esc(given)};;;`);
  lines.push(`FN:${esc(card.name || card.email || card.phone)}`);
  if (card.company) lines.push(`ORG:${esc(card.company)};`);
  if (card.title) lines.push(`TITLE:${esc(card.title)}`);
  if (card.phone) lines.push(`TEL;TYPE=CELL,VOICE:${esc(card.phone)}`);
  if (card.email) lines.push(`EMAIL;TYPE=INTERNET:${esc(card.email)}`);
  if (card.website) lines.push(`URL:${esc(normUrl(card.website))}`);
  if (card.instagram) lines.push(`X-SOCIALPROFILE;TYPE=instagram:${esc('https://instagram.com/' + card.instagram)}`);
  if (photo && photo.length) lines.push('PHOTO;ENCODING=b;TYPE=JPEG:' + btoa(String.fromCharCode(...photo)));
  lines.push('END:VCARD');
  return lines.map(fold).join('\r\n') + '\r\n';
}

// ------------------------------------------------------------------ relay

export async function fetchPhoto(session, key, fetchImpl = fetch) {
  const res = await fetchImpl(`${RELAY}${TOPIC_PREFIX}${session}/json?poll=1&since=all`, { cache: 'no-store' });
  if (!res.ok) return null;
  const text = await res.text();
  for (const line of text.split('\n')) {
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    if (msg.event !== 'message' || typeof msg.message !== 'string') continue;
    const plain = await openBox(key, msg.message);
    if (!plain) continue;
    try {
      const m = JSON.parse(new TextDecoder().decode(plain));
      if (m.t === 'photo' && m.photo) return b64urlDecode(m.photo);
    } catch { /* not ours */ }
  }
  return null;
}

/** Card the iPhone user sends back, in the app's WirePayload shape. */
export function replyMessage(form) {
  const card = {
    v: 1, n: clean(form.name, 80), p: clean(form.phone, 40), e: clean(form.email, 120),
    c: clean(form.company, 80), t: clean(form.title, 80), i: clean(form.instagram, 60).replace(/^@/, ''),
    l: Math.floor(Math.random() * GROUNDS.length),
  };
  for (const k of Object.keys(card)) if (card[k] === '') delete card[k];
  return { t: 'card', card, from: 'web' };
}

export async function sendBack(session, key, form, fetchImpl = fetch) {
  const body = await seal(key, new TextEncoder().encode(JSON.stringify(replyMessage(form))));
  if (body.length > RELAY_MAX) throw new Error('too large');
  const res = await fetchImpl(`${RELAY}${TOPIC_PREFIX}${session}`, { method: 'POST', body });
  if (!res.ok) throw new Error('relay ' + res.status);
}

// ------------------------------------------------------------------ rendering

const ICONS = {
  phone: '<path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z"/>',
  mail: '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="m22 7-10 6L2 7"/>',
  at: '<circle cx="12" cy="12" r="4"/><path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8"/>',
  web: '<circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15 15 0 0 1 4 10 15 15 0 0 1-4 10 15 15 0 0 1-4-10 15 15 0 0 1 4-10z"/>',
};

function drawName(svg, word, animate) {
  const { columns, cells } = layout(word);
  svg.setAttribute('viewBox', `0 0 ${columns} ${ROWS}`);
  svg.setAttribute('aria-label', word);
  const far = Math.hypot(columns, ROWS) || 1;
  svg.innerHTML = cells.map(([c, r]) => {
    const d = Math.hypot(c + 0.5 - columns / 2, r + 0.5 - ROWS / 2) / far;
    return `<rect class="${animate ? 'cell' : ''}" style="--d:${d.toFixed(3)}" x="${c + 0.07}" y="${r + 0.07}" width=".86" height=".86" rx=".2"/>`;
  }).join('');
}

/** One-line fit: pitch = width / (columns + 2), capped; the grid background uses the same pitch. */
function fitPitch(word) {
  const cols = canRender(word) ? layout(word).columns : 12;
  const width = Math.min(document.documentElement.clientWidth, 720);
  return Math.max(6, Math.min(30, width / (cols + 2)));
}

/** Surname cell: the poster pitch divided by the smallest whole number that fits it on one line. */
function sizeRest(svg, word) {
  const box = svg.parentElement;
  const cs = getComputedStyle(box);
  const avail = box.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
  const pitch = parseFloat(document.documentElement.style.getPropertyValue('--pitch')) || 24;
  const cols = layout(word).columns;
  const k = Math.max(1, Math.ceil((cols * pitch) / Math.max(avail, 1)));
  svg.style.width = `${(cols * pitch) / k}px`;
}

function setPitch(word) {
  const p = fitPitch(word);
  const root = document.documentElement.style;
  root.setProperty('--pitch', `${p}px`);
  root.setProperty('--photo-cells', String(Math.max(3, Math.ceil(88 / p))));
}

function detail(icon, text, href) {
  const li = document.createElement('li');
  li.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[icon]}</svg>`;
  const a = document.createElement('a');
  a.textContent = text;
  if (href) a.href = href;
  if (href && href.startsWith('http')) { a.target = '_blank'; a.rel = 'noopener noreferrer'; }
  li.append(a);
  return li;
}

const isApple = () => /iPhone|iPad|iPod|Macintosh/.test(navigator.userAgent) && 'ontouchend' in document;

function saveContact(card, photo) {
  const text = vcard(card, photo);
  const file = (card.name || 'contact').replace(/[^\p{L}\p{N} ._-]/gu, '').trim().slice(0, 60) || 'contact';
  if (isApple()) {
    // iOS Safari opens a text/vcard document as its native contact sheet ("Create New Contact").
    location.href = 'data:text/vcard;charset=utf-8,' + encodeURIComponent(text);
    return;
  }
  const url = URL.createObjectURL(new Blob([text], { type: 'text/vcard' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: `${file}.vcf` });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

function main() {
  const $ = (id) => document.getElementById(id);
  const data = parsePayload(location.hash);
  if (!data) {
    setPitch('OOPS');
    drawName($('broken-name'), 'OOPS', !matchMedia('(prefers-reduced-motion: reduce)').matches);
    $('broken').hidden = false;
    return;
  }
  const { card, session, key } = data;
  const [ground, accent] = GROUNDS[card.label];
  document.documentElement.style.setProperty('--ground', ground);
  document.documentElement.style.setProperty('--accent', accent);
  document.querySelector('meta[name=theme-color]').content = ground;
  const first = card.name.split(/\s+/)[0] || card.name || 'Hello';
  const rest = card.name.includes(' ') ? card.name.slice(card.name.indexOf(' ') + 1).trim() : '';
  document.title = `${card.name || 'Contact'} · DapTap`;
  $('from').textContent = 'Card from ' + first;
  const restCells = rest !== '' && canRender(rest);
  const fit = () => { setPitch(first); if (restCells) sizeRest($('rest-cells'), rest); };
  setPitch(first);
  addEventListener('resize', fit, { passive: true });

  const animate = !matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (canRender(first)) drawName($('name'), first, animate);
  else { $('name').hidden = true; $('name-text').hidden = false; $('name-text').textContent = first; }
  if (restCells) {
    drawName($('rest-cells'), rest, animate);
    $('rest-cells').hidden = false;
  } else $('rest').textContent = rest;
  $('role').textContent = [card.title, card.company].filter(Boolean).join(' · ');
  const list = $('details');
  if (card.phone) list.append(detail('phone', card.phone, 'tel:' + card.phone.replace(/[^\d+]/g, '')));
  if (card.email) list.append(detail('mail', card.email, 'mailto:' + card.email));
  if (card.instagram) list.append(detail('at', card.instagram, 'https://instagram.com/' + encodeURIComponent(card.instagram)));
  if (card.website) list.append(detail('web', card.website.replace(/^https?:\/\//, ''), normUrl(card.website)));
  $('card').hidden = false;
  fit(); // the surname is sized against the poster's width, which only exists once it is shown
  $('actions').hidden = false;
  $('save').textContent = `Save ${first}`;
  $('sheet-name').textContent = first;

  let photo = null;
  let cryptoKey = null;
  const canReply = !!(session && key && window.crypto?.subtle);
  if (canReply) {
    importKey(key).then(async (k) => {
      cryptoKey = k;
      photo = await fetchPhoto(session, k).catch(() => null);
      if (photo) {
        $('photo-img').src = URL.createObjectURL(new Blob([photo], { type: 'image/jpeg' }));
        $('photo-img').alt = `Photo of ${card.name}`;
        $('photo').hidden = false;
      }
    }).catch(() => { $('back').hidden = true; });
  } else {
    $('back').hidden = true;
  }

  $('save').addEventListener('click', () => saveContact(card, photo));

  const sheet = $('sheet');
  const form = $('form');
  $('back').addEventListener('click', () => { $('form-error').textContent = ''; sheet.showModal(); form.elements.name.focus(); });
  $('cancel').addEventListener('click', () => sheet.close());
  sheet.addEventListener('click', (e) => { if (e.target === sheet) sheet.close(); });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const values = Object.fromEntries(new FormData(form));
    if (!values.name.trim()) { $('form-error').textContent = 'Add your name so they know who it is.'; form.elements.name.focus(); return; }
    if (!values.phone.trim() && !values.email.trim()) { $('form-error').textContent = 'Add a phone number or an email so they can reach you.'; return; }
    const send = $('send');
    send.disabled = true; send.textContent = 'Sending…';
    try {
      if (!cryptoKey) cryptoKey = await importKey(key);
      await sendBack(session, cryptoKey, values);
      sheet.close();
      $('back').disabled = true;
      $('back').textContent = 'Your card was sent';
      $('status').textContent = `Sent. It'll pop up on ${first}'s phone.`;
    } catch {
      $('form-error').textContent = "Couldn't send. Check your connection and try again.";
    } finally {
      send.disabled = false; send.textContent = 'Send';
    }
  });
}

if (typeof document !== 'undefined' && document.getElementById('card')) {
  // Opening another DapTap link in the same tab only changes the fragment: start over with the new card.
  addEventListener('hashchange', () => location.reload());
  main();
}

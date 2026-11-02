import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
if (!globalThis.crypto) globalThis.crypto = webcrypto;
import { parsePayload, b64urlEncode, b64urlDecode, importKey, seal, openBox, vcard, replyMessage, sendBack, fetchPhoto, GROUNDS } from '../c/app.js';
import { layout, canRender, normalize, GLYPHS } from '../c/cellfont.js';

const enc = (o) => '#' + b64urlEncode(new TextEncoder().encode(JSON.stringify(o)));
const KEY = b64urlEncode(new Uint8Array(32).fill(7));

test('parsePayload accepts a card and rejects junk', () => {
  const p = parsePayload(enc({ v: 1, n: 'Keshav Pillarisetti', p: '+1 951', l: 2, s: 'abcdefghijklmnop', k: KEY }));
  assert.equal(p.card.name, 'Keshav Pillarisetti');
  assert.equal(p.card.label, 2);
  assert.equal(p.session, 'abcdefghijklmnop');
  assert.equal(parsePayload(''), null);
  assert.equal(parsePayload('#%%%'), null);
  assert.equal(parsePayload(enc({ v: 1 })), null);
  assert.equal(parsePayload(enc([1, 2])), null);
  assert.equal(parsePayload(enc({ n: 'x', s: '../../etc' })).session, null);
  assert.equal(parsePayload(enc({ n: 'x', l: 999 })).card.label, GROUNDS.length - 1);
  assert.equal(parsePayload(enc({ n: 'A'.repeat(500) })).card.name.length, 80);
  assert.equal(parsePayload(enc({ n: 'x\u0000y' })).card.name, 'x y');
});

test('crypto round trip and tamper detection', async () => {
  const key = await importKey(KEY);
  const box = await seal(key, new TextEncoder().encode('hi'));
  assert.equal(new TextDecoder().decode(await openBox(key, box)), 'hi');
  const raw = b64urlDecode(box); raw[raw.length - 1] ^= 1;
  assert.equal(await openBox(key, b64urlEncode(raw)), null);
  assert.equal(await openBox(key, 'short'), null);
  await assert.rejects(importKey('AAAA'));
});

test('vcard escapes and folds', () => {
  const v = vcard({ name: 'A, B; C', phone: '1', email: '', company: 'X;Y', title: '', website: 'a.b', instagram: 'h', label: 0 }, null);
  assert.match(v, /^BEGIN:VCARD\r\nVERSION:3\.0\r\n/);
  assert.match(v, /FN:A\\, B\\; C\r\n/);
  assert.match(v, /URL:https:\/\/a\.b\r\n/);
  const long = vcard({ name: 'Ω'.repeat(80), phone: '', email: 'e@x', company: '', title: '', website: '', instagram: '', label: 0 }, new Uint8Array(3000));
  for (const line of long.split('\r\n')) assert.ok(new TextEncoder().encode(line).length <= 75);
});

test('reply message drops empties and strips @', () => {
  const m = replyMessage({ name: ' Jo ', phone: '', email: 'jo@x', company: '', title: '', instagram: '@jo' });
  assert.deepEqual(Object.keys(m.card).sort(), ['e', 'i', 'l', 'n', 'v']);
  assert.equal(m.card.i, 'jo');
  assert.equal(m.card.n, 'Jo');
});

test('sendBack posts ciphertext to the session topic; fetchPhoto finds the photo', async () => {
  const key = await importKey(KEY);
  let posted;
  await sendBack('abcdefghijklmnop', key, { name: 'Jo', phone: '1', email: '', company: '', title: '', instagram: '' },
    async (url, init) => { posted = { url, init }; return { ok: true }; });
  assert.equal(posted.url, 'https://ntfy.sh/daptap-abcdefghijklmnop');
  assert.ok(!posted.init.body.includes('Jo'));
  const photo = new Uint8Array([255, 216, 255, 1, 2, 3]);
  const msg = await seal(key, new TextEncoder().encode(JSON.stringify({ t: 'photo', photo: b64urlEncode(photo), from: 'x' })));
  const other = await seal(await importKey(b64urlEncode(new Uint8Array(32).fill(9))), new TextEncoder().encode('{}'));
  const body = [JSON.stringify({ event: 'open' }), 'junk', JSON.stringify({ event: 'message', message: other }), JSON.stringify({ event: 'message', message: msg })].join('\n');
  const got = await fetchPhoto('abcdefghijklmnop', key, async () => ({ ok: true, text: async () => body }));
  assert.deepEqual([...got], [...photo]);
  assert.equal(await fetchPhoto('abcdefghijklmnop', key, async () => ({ ok: false })), null);
});

test('cell font', () => {
  assert.equal(layout('Keshav').columns, 35);
  assert.equal(normalize('José'), 'JOSE');
  assert.ok(canRender('Zoë'));
  assert.ok(!canRender('李'));
  for (const [ch, rows] of Object.entries(GLYPHS)) assert.equal(rows.length, 7, ch);
});

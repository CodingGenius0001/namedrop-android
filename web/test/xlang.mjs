// Cross-language check driven by app/src/test/.../CrossLanguageTest.kt.
// Reads Kotlin-produced vectors on stdin, verifies the web code agrees byte for byte,
// then seals messages for Kotlin to open. Prints one JSON line.
import { webcrypto } from 'node:crypto';
if (!globalThis.crypto) globalThis.crypto = webcrypto;
import { parsePayload, importKey, openBox, seal, vcard, b64urlDecode, b64urlEncode, replyMessage } from '../c/app.js';

const input = JSON.parse(await new Promise((res) => { let s = ''; process.stdin.on('data', (d) => (s += d)); process.stdin.on('end', () => res(s)); }));
const fail = (msg) => { console.log(JSON.stringify({ ok: false, error: msg })); process.exit(0); };

const key = await importKey(input.key);
const opened = await openBox(key, input.kotlinBox);
if (!opened || b64urlEncode(opened) !== input.plaintext) fail('could not open Kotlin box');
if (await openBox(key, input.kotlinBox.slice(0, -2) + 'AA')) fail('tampered box opened');

const parsed = parsePayload(new URL(input.url).hash);
if (!parsed) fail('payload did not parse');
for (const [k, v] of Object.entries(input.card)) if (parsed.card[k] !== v) fail(`card.${k}: ${parsed.card[k]} != ${v}`);
if (parsed.session !== input.session || parsed.key !== input.key) fail('session/key mismatch');

const v1 = vcard(parsed.card, null);
if (v1 !== input.vcard) fail('vcard differs:\n' + JSON.stringify(v1) + '\n' + JSON.stringify(input.vcard));
const v2 = vcard(parsed.card, b64urlDecode(input.photo));
if (v2 !== input.vcardPhoto) fail('vcard with photo differs');

const jsBox = await seal(key, b64urlDecode(input.plaintext));
const reply = replyMessage({ name: ' Jane  Doe ', phone: '+1 555 0199', email: '', company: 'Café Ünïcode', title: '', instagram: '@jane' });
const replyBox = await seal(key, new TextEncoder().encode(JSON.stringify(reply)));
console.log(JSON.stringify({ ok: true, jsBox, replyBox }));

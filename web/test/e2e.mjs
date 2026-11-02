// Browser end-to-end check of the web card, as an iPhone would open it from the NFC banner.
//   node test/e2e.mjs "<card url>" [--browser webkit|chromium] [--out dir] [--reply]
// Serves web/ at /namedrop-android/ on localhost (a secure context, so WebCrypto works), opens the
// card with an iPhone viewport, checks the render, and with --reply sends a card back through ntfy.sh.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, webkit, devices } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const cardUrl = args.find((a) => a.startsWith('http'));
const flag = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
const engine = flag('--browser', 'webkit');
const out = flag('--out', '/tmp');
const reply = args.includes('--reply');

const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/namedrop-android/, '');
  if (p.endsWith('/')) p += 'index.html';
  const file = path.join(root, p);
  if (!file.startsWith(root) || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://localhost:${server.address().port}/namedrop-android/`;
const url = cardUrl.replace(/^https:\/\/codinggenius0001\.github\.io\/namedrop-android\//, base);

const browser = await (engine === 'chromium' ? chromium : webkit).launch();
const ctx = await browser.newContext({ ...devices['iPhone 15'] });
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const check = (cond, msg) => { if (!cond) { console.error('FAIL', msg); process.exitCode = 1; } else console.log('ok  ', msg); };

await page.goto(url);
await page.waitForSelector('#card:not([hidden])');
await page.waitForTimeout(1600); // let the ripple + build animation finish
const cells = await page.locator('#name rect').count();
check(cells > 20, `name drawn in cells (${cells})`);
const nameBox = await page.locator('#name').boundingBox();
const vw = page.viewportSize().width;
check(nameBox && nameBox.x >= 0 && nameBox.x + nameBox.width <= vw + 0.5, 'first name fits on one line inside the screen');
check((await page.locator('#details li').count()) >= 1, 'details listed');
check((await page.locator('#save').textContent()).startsWith('Save '), 'save button names the person');
const scrollW = await page.evaluate(() => document.documentElement.scrollWidth);
check(scrollW <= vw, 'no horizontal scroll');
await page.screenshot({ path: `${out}/web-card-${engine}.png` });

if (reply) {
  await page.click('#back');
  await page.waitForSelector('dialog[open]');
  await page.click('#send');
  check((await page.locator('#form-error').textContent()).includes('name'), 'empty form explains what is missing');
  await page.fill('input[name=name]', 'Jordan Lee');
  await page.fill('input[name=phone]', '+1 (909) 555-0177');
  await page.fill('input[name=email]', 'jordan@example.com');
  await page.fill('input[name=company]', 'Pomona College');
  await page.fill('input[name=instagram]', '@jordan.lee');
  await page.screenshot({ path: `${out}/web-sheet-${engine}.png` });
  await page.click('#send');
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Sent'), null, { timeout: 15000 });
  check(true, 'reply sent through the relay');
  await page.screenshot({ path: `${out}/web-sent-${engine}.png` });
}

// A truncated link must explain itself, not crash.
await page.goto(base + 'c/#bm90LWEtY2FyZA');
await page.waitForSelector('#broken:not([hidden])');
check(true, 'broken link shows the recovery message');
await page.screenshot({ path: `${out}/web-broken-${engine}.png` });

check(errors.length === 0, 'no console errors' + (errors.length ? ': ' + errors.join(' | ') : ''));
await browser.close();
server.close();

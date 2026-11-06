// The service worker in a real Chrome (ADR 0041): offline load, then a new version waits for "Update now".
// Serves a temporary copy of web/build/dist/web with a tiny static server, so it needs no Lendger server.
import { createServer } from 'node:http';
import { cpSync, mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startChrome, sleep } from './lib.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, '../../web/build/dist/web');
if (!existsSync(join(dist, 'sw.js'))) throw new Error('run ./gradlew :web:stampWorker first');
const copy = join(mkdtempSync(join(tmpdir(), 'circles-sw-')), 'web');
cpSync(dist, copy, { recursive: true });
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
const PORT = 18082;
let hits = 0;
const handler = (req, res) => {
  const path = req.url.split('?')[0];
  const file = join(copy, path === '/' ? 'index.html' : path);
  if (path.startsWith('/api/') || !existsSync(file)) { res.writeHead(404); res.end(); return; }
  hits++;
  res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-cache' });
  res.end(readFileSync(file));
};
const open = () => new Promise((r) => { const s = createServer(handler); s.listen(PORT, '127.0.0.1', () => r(s)); });
const close = (s) => new Promise((r) => { s.closeAllConnections?.(); s.close(r); });

let step = 0; const ok = (m) => console.log(`ok ${++step}: ${m}`);
async function until(c, expr, what, ms = 15000) { const t0 = Date.now(); while (Date.now() - t0 < ms) { if ((await c.evaluate(expr)) === true) return; await sleep(200); } throw new Error('timeout: ' + what + '\n' + await c.evaluate('document.body.innerText')); }
const welcome = 'document.body.innerText.includes("Welcome to Lendger")';

let server = await open();
const chrome = await startChrome(9335);
try {
  await chrome.goto(`http://127.0.0.1:${PORT}/`); await until(chrome, welcome, 'welcome page');
  await until(chrome, 'navigator.serviceWorker.ready.then(() => true)', 'worker active'); ok('service worker registered and active');
  await chrome.goto(`http://127.0.0.1:${PORT}/`); await until(chrome, welcome + ' && navigator.serviceWorker.controller !== null', 'page controlled');
  const caches1 = await chrome.evaluate('caches.keys().then(k => JSON.stringify(k))'); ok('shell cached as ' + caches1);
  if (await chrome.evaluate('document.body.innerText.includes("A new version is ready")')) throw new Error('update prompt on first install');
  ok('no update prompt after the first install');

  await close(server);
  const before = hits;
  await chrome.goto(`http://127.0.0.1:${PORT}/`); await sleep(1500);
  await until(chrome, welcome, 'welcome page offline'); ok('with the server gone the page still opens, from the cache');

  // A new version: change the styles and the worker's version, as a rebuild would.
  writeFileSync(join(copy, 'styles.css'), readFileSync(join(copy, 'styles.css'), 'utf8') + '\n/* version two */\n');
  writeFileSync(join(copy, 'sw.js'), readFileSync(join(copy, 'sw.js'), 'utf8').replace(/const VERSION = '[^']*'/, "const VERSION = 'version-two'"));
  server = await open();
  await chrome.evaluate('navigator.serviceWorker.getRegistration().then(r => r.update())');
  await until(chrome, 'document.body.innerText.includes("A new version is ready")', 'update prompt'); ok('a new version waits behind an "Update now" prompt');
  const oldStyles = await chrome.evaluate('fetch("/styles.css").then(r => r.text())');
  if (oldStyles.includes('version two')) throw new Error('the new styles were served before the update'); ok('until the member agrees, the old version keeps running');
  await chrome.evaluate('document.querySelector(\'[data-action="update-now"]\').click()');
  await until(chrome, welcome + ' && !document.body.innerText.includes("A new version is ready")', 'reloaded after update', 20000);
  await until(chrome, 'caches.keys().then(k => k.length === 1 && k[0] === "circles-shell-version-two")', 'old cache deleted'); ok('after the update only the new cache remains');
  const newStyles = await chrome.evaluate('fetch("/styles.css").then(r => r.text())');
  if (!newStyles.includes('version two')) throw new Error('new styles not served after the update'); ok('the new version is running');
  console.log('\nBROWSER LOGS:\n' + chrome.logs.filter((l) => !/Password forms|favicon/.test(l)).join('\n'));
} catch (e) { console.log('FAILED:', e.message); console.log(chrome.logs.join('\n')); process.exitCode = 1; }
finally { chrome.stop(); await close(server).catch(() => {}); }

import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
export const sleep = ms => new Promise(r => setTimeout(r, ms));
const jar = process.env.CIRCLES_JAR ?? join(dirname(fileURLToPath(import.meta.url)), '../../server/build/libs/circles-server.jar');

export async function startServer(port = 18081) {
  const data = mkdtempSync(join(tmpdir(), 'circles-smoke-'));
  const proc = spawn('java', ['-jar', jar, 'serve'], { env: { ...process.env, CIRCLES_DATA_DIR: data, CIRCLES_BIND: '127.0.0.1', CIRCLES_PORT: String(port) }, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = ''; proc.stdout.on('data', d => log += d); proc.stderr.on('data', d => log += d);
  for (let i = 0; i < 60; i++) { try { if ((await fetch(`http://127.0.0.1:${port}/healthz`)).ok) break; } catch {} await sleep(250); }
  return { url: `http://127.0.0.1:${port}`, stop: () => proc.kill(), log: () => log };
}

export async function startChrome(port = 9333) {
  const profile = mkdtempSync(join(tmpdir(), 'chrome-prof-'));
  const proc = spawn('google-chrome', ['--headless=new', '--disable-gpu', '--no-sandbox', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
  let targets;
  for (let i = 0; i < 60; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); break; } catch { await sleep(250); } }
  const page = targets.find(t => t.type === 'page');
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise(r => ws.addEventListener('open', r));
  let id = 0; const pending = new Map(); const events = []; const listeners = [];
  ws.addEventListener('message', m => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } else if (d.method) { events.push(d); listeners.forEach(l => l(d)); } });
  const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  await send('Runtime.enable'); await send('Page.enable'); await send('Log.enable');
  const logs = [];
  listeners.push(d => {
    if (d.method === 'Runtime.consoleAPICalled') logs.push(`console.${d.params.type}: ${d.params.args.map(a => a.value ?? a.description ?? '').join(' ')}`);
    if (d.method === 'Runtime.exceptionThrown') logs.push(`EXCEPTION: ${d.params.exceptionDetails.exception?.description ?? d.params.exceptionDetails.text}`);
    if (d.method === 'Log.entryAdded') logs.push(`log.${d.params.entry.level}: ${d.params.entry.text} ${d.params.entry.url ?? ''}`);
  });
  const evaluate = async expression => { const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); return r.result.exceptionDetails ? `ERR ${r.result.exceptionDetails.exception?.description}` : r.result.result.value; };
  return { send, evaluate, logs, goto: async url => { await send('Page.navigate', { url }); await sleep(1500); }, stop: () => { try { ws.close(); } catch {} proc.kill(); } };
}

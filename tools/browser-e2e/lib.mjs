import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
export const sleep = ms => new Promise(r => setTimeout(r, ms));
const jar = process.env.LENDGER_JAR ?? join(dirname(fileURLToPath(import.meta.url)), '../../server/build/libs/lendger-server.jar');

export async function startServer(port = 18081) {
  const data = mkdtempSync(join(tmpdir(), 'circles-smoke-'));
  const proc = spawn('java', ['-jar', jar, 'serve'], { env: { ...process.env, LENDGER_DATA_DIR: data, LENDGER_BIND: '127.0.0.1', LENDGER_RATE_LIMIT: 'false', LENDGER_PORT: String(port) }, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = ''; proc.stdout.on('data', d => log += d); proc.stderr.on('data', d => log += d);
  for (let i = 0; i < 60; i++) { try { if ((await fetch(`http://127.0.0.1:${port}/healthz`)).ok) break; } catch {} await sleep(250); }
  proc.once('exit', () => rmSync(data, { recursive: true, force: true }));
  return { url: `http://localhost:${port}`, stop: () => proc.kill(), log: () => log };
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
  return { send, evaluate, logs, on: listener => listeners.push(listener), goto: async url => { await send('Page.navigate', { url }); await sleep(1500); }, stop: () => { try { ws.close(); } catch {} proc.once('exit', () => setTimeout(() => rmSync(profile, { recursive: true, force: true }), 500)); proc.kill(); } };
}

// ---- accessibility (axe-core) ----
import { readFileSync } from 'node:fs';
const axeSource = () => readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'node_modules/axe-core/axe.min.js'), 'utf8');
export const A11Y = process.env.A11Y === '1';
export const a11yFindings = [];
export const readingLevels = [];

/**
 * Runs axe-core against the page as it is now (WCAG 2.0, 2.1 and 2.2 level A and AA rules) and records every violation under [name].
 * Only does anything when A11Y=1, so the ordinary run stays quick. Violations are collected, not thrown, so one run reports all pages.
 */
export async function checkAccessibility(chrome, name) {
  await screenshot(chrome, name);
  if (!A11Y) return;
  await chrome.evaluate(axeSource());
  const result = await chrome.evaluate(`axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] } }).then(r => JSON.stringify({ violations: r.violations.map(v => ({ id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.slice(0, 3).map(n => n.target.join(' ') + ' :: ' + (n.failureSummary || '').split('\\n').slice(0, 2).join(' | ')) })), passes: r.passes.length }))`);
  const parsed = JSON.parse(result);
  for (const v of parsed.violations) a11yFindings.push({ page: name, ...v });
  console.log(`axe ${name}: ${parsed.violations.length} violation(s), ${parsed.passes} rules passed`);
  await keyboardAndStructure(chrome, name);
}

const press = async (chrome, key, code, vk) => {
  for (const type of ['keyDown', 'keyUp']) await chrome.send('Input.dispatchKeyEvent', { type, key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
};

/**
 * The checks axe cannot make (ADR-free, WCAG 2.1.1, 2.1.2, 2.4.1, 2.4.2, 2.4.7): the page has a language, a title, one main landmark and
 * one h1 with no skipped heading levels; real Tab presses reach every visible control once, in order, without getting stuck; and each
 * control shows a focus indicator. Also measures reading level of the page's text (Flesch-Kincaid grade) for the M6 review.
 */
async function keyboardAndStructure(chrome, name) {
  await sleep(400); // let any re-render after the last action settle, or focus is measured on a page that is about to change
  const structure = JSON.parse(await chrome.evaluate(`(() => {
    const problems = [];
    if (!document.documentElement.lang) problems.push('the page has no lang attribute');
    if (!document.title.trim()) problems.push('the page has no title');
    if (document.querySelectorAll('main').length !== 1) problems.push('expected exactly one main landmark, found ' + document.querySelectorAll('main').length);
    const headings = [...document.querySelectorAll('h1,h2,h3,h4')].map(h => Number(h.tagName[1]));
    if (headings.filter(h => h === 1).length !== 1) problems.push('expected exactly one h1, found ' + headings.filter(h => h === 1).length);
    headings.forEach((h, i) => { if (i > 0 && h > headings[i - 1] + 1) problems.push('heading level jumps from h' + headings[i - 1] + ' to h' + h); });
    // Tick boxes and radio buttons are drawn 24 px; their label is the tap area and must be at least 44 px high (WCAG 2.5.8 comfort size).
    for (const box of document.querySelectorAll('input[type=checkbox],input[type=radio]')) {
      if (!box.checkVisibility()) continue;
      const label = box.labels && box.labels[0]; const r = label && label.getBoundingClientRect();
      if (!label || r.height < 43.5 || r.width < 43.5) problems.push('tick box "' + (label ? label.textContent.trim().slice(0, 30) : box.name) + '" has a tap area of ' + (r ? Math.round(r.width) + 'x' + Math.round(r.height) : 'none') + ' px, under 44');
    }
    // A member's name is a link; its tap area is at least 44 px high (ADR 0076).
    for (const link of document.querySelectorAll('a.person-name')) {
      if (!link.checkVisibility()) continue;
      const r = link.getBoundingClientRect();
      if (r.height < 43.5) problems.push('name link "' + link.textContent.trim().slice(0, 30) + '" has a tap area of ' + Math.round(r.width) + 'x' + Math.round(r.height) + ' px, under 44');
    }
    const focusable = [...document.querySelectorAll('a[href],button,input,select,textarea,summary,[tabindex]')]
      .filter(e => !e.disabled && e.tabIndex >= 0 && e.checkVisibility() && e.type !== 'hidden' && !e.hidden);
    return JSON.stringify({ problems, count: focusable.length });
  })()`));
  const problems = [...structure.problems];
  // Real Tab presses from the first control on the page (after a screen change focus sits on main, which is later in the page).
  const probe = `(() => {
    const e = document.activeElement; if (!e || e === document.body) return JSON.stringify({ body: true });
    const cs = getComputedStyle(e);
    const visibleFocus = (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0) || cs.boxShadow !== 'none';
    const label = (e.getAttribute('aria-label') || e.innerText || e.getAttribute('name') || e.id || e.tagName).trim().slice(0, 30);
    return JSON.stringify({ key: e.tagName + '#' + [...document.querySelectorAll('*')].indexOf(e), label, visibleFocus });
  })()`;
  // A redraw keeps focus where it was (FocusKeeper). The page can still change under the walk on its own (a notice clears itself after
  // 8 s and takes its Dismiss button with it), so the list of controls is read before and after, and a walk over a changing page is repeated.
  const controlList = () => chrome.evaluate(`JSON.stringify([...document.querySelectorAll('a[href],button,input,select,textarea,summary,[tabindex]')]
    .filter(e => !e.disabled && e.tabIndex >= 0 && e.checkVisibility() && e.type !== 'hidden' && !e.hidden).map(e => (e.getAttribute('aria-label') || e.innerText || e.id || e.tagName).trim().slice(0, 30)))`);
  let seen = [], stopped = '';
  for (let attempt = 1; attempt <= 3; attempt++) {
    const before = await controlList();
    structure.count = JSON.parse(before).length;
    seen = []; stopped = '';
    {
    await press(chrome, 'Shift', 'ShiftLeft', 16); // tells the browser the last input was the keyboard, so :focus-visible applies to the first control too
    await chrome.evaluate(`(() => { window.scrollTo(0, 0); const first = [...document.querySelectorAll('a[href],button,input,select,textarea,summary,[tabindex]')].find(e => !e.disabled && e.tabIndex >= 0 && e.checkVisibility() && e.type !== 'hidden' && !e.hidden); if (first) first.focus(); return true; })()`);
    seen = [JSON.parse(await chrome.evaluate(probe))];
    for (let i = 1; i < structure.count * 4 + 6; i++) {
      await press(chrome, 'Tab', 'Tab', 9);
      const info = JSON.parse(await chrome.evaluate(probe));
      if (info.body) { stopped = 'focus fell to the page body after ' + seen.length + ' stops (last: ' + (seen.at(-1)?.label ?? 'none') + ')'; break; }
      if (info.key === seen.at(-1).key) continue; // a date field has several tab stops inside one control
      if (seen.some(s => s.key === info.key)) { if (seen[0].key === info.key) seen[0].visibleFocus = info.visibleFocus; else seen.push(info); break; }
      seen.push(info);
    }
  }
    if ((await controlList()) === before) break; // the page held still during the walk
  }
  if (seen.length < structure.count) problems.push('Tab reached only ' + seen.length + ' of ' + structure.count + ' visible controls' + (stopped ? ': ' + stopped : '') + ' [' + seen.map(s => s.label).join(' | ') + ']');
  const keys = seen.map(s => s.key);
  if (new Set(keys).size !== keys.length) problems.push('a control was reached twice before the end (a keyboard trap or repeated focus)');
  for (const s of seen) if (!s.visibleFocus) problems.push('no visible focus indicator on ' + s.label);
  if (problems.length) a11yFindings.push({ page: name, id: 'keyboard-structure', impact: 'serious', help: 'Keyboard and page structure', nodes: problems });
  console.log(`keys ${name}: ${seen.length} of ${structure.count} controls reached, ${problems.length} problem(s)`);
  const level = JSON.parse(await chrome.evaluate(`(() => {
    const text = [...document.querySelectorAll('main p, main li, main summary')].map(e => e.innerText.trim()).filter(t => t.length > 0).map(t => /[.!?]$/.test(t) ? t : t + '.').join(' ');
    const sentences = text.split(/(?<=[.!?])\\s+/).filter(x => x.split(/\\s+/).length > 2);
    const words = text.split(/\\s+/).filter(w => /[A-Za-z]/.test(w));
    const syl = w => { const m = w.toLowerCase().replace(/[^a-z]/g, '').replace(/(?:[^laeiouy]es|ed|[^laeiouy]e)$/, '').replace(/^y/, '').match(/[aeiouy]{1,2}/g); return Math.max(1, m ? m.length : 1); };
    const syllables = words.reduce((n, w) => n + syl(w), 0);
    const grade = sentences.length && words.length ? 0.39 * (words.length / sentences.length) + 11.8 * (syllables / words.length) - 15.59 : 0;
    const longest = sentences.reduce((a, b) => (b.split(/\\s+/).length > a.split(/\\s+/).length ? b : a), '');
    return JSON.stringify({ grade: Math.round(grade * 10) / 10, words: words.length, longest: longest.slice(0, 140), longestWords: longest.split(/\\s+/).length });
  })()`));
  readingLevels.push({ page: name, ...level });
}

export function reportAccessibility() {
  if (!A11Y) return 0;
  const prose = readingLevels.filter(r => r.words >= 40);
  if (prose.length) {
    console.log('\nReading level (Flesch-Kincaid grade of the page text; the aim is grade 8 or below, and no sentence over 30 words):');
    for (const r of prose.sort((a, b) => b.grade - a.grade)) console.log(`  grade ${String(r.grade).padStart(4)}  ${String(r.words).padStart(4)} words  longest sentence ${r.longestWords} words  [${r.page}]`);
  }
  if (a11yFindings.length === 0) { console.log('\nAccessibility: no WCAG A/AA violations found by axe.'); return 0; }
  console.log(`\nAccessibility: ${a11yFindings.length} violation(s):`);
  for (const f of a11yFindings) console.log(`- [${f.page}] ${f.id} (${f.impact}): ${f.help}\n    ${f.nodes.join('\n    ')}`);
  return a11yFindings.length;
}

/** With SHOTS=<folder> every page state the script checks is also saved as a PNG (DARK=1 renders them in dark mode), for looking at. */
async function screenshot(chrome, name) {
  const dir = process.env.SHOTS;
  if (!dir) return;
  mkdirSync(dir, { recursive: true });
  await chrome.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: process.env.DARK === '1' ? 'dark' : 'light' }] });
  await chrome.send('Emulation.setDeviceMetricsOverride', { width: Number(process.env.SHOT_WIDTH || 1000), height: 800, deviceScaleFactor: 1, mobile: false });
  await sleep(300);
  if (process.env.SHOT_WIDTH) { // narrow check: sideways overflow, and controls smaller than the 44 px touch target (the invisible touch area counts)
    const m = JSON.parse(await chrome.evaluate(`JSON.stringify({ overflow: document.documentElement.scrollWidth - window.innerWidth, client: document.documentElement.clientWidth, edges: ['#notice', 'main section > div', 'main ul.cards > li', 'main'].map(q => { const e = document.querySelector(q); if (!e) return q + ' none'; const r = e.getBoundingClientRect(); return q + ' ' + Math.round(r.left) + '..' + Math.round(r.right); }),
      small: [...document.querySelectorAll('main button, main input:not([type=checkbox]):not([type=radio]), main select, main a.button')].filter(e => e.checkVisibility()).map(e => { const r = e.getBoundingClientRect(); return { t: (e.innerText || e.id || e.tagName).trim().slice(0, 24), w: Math.round(r.width), h: Math.round(r.height) }; }).filter(x => x.h < 32 || x.w < 32) })`));
    console.log('narrow ' + name + ': client ' + m.client + ' ' + m.edges.join(', ') + '; overflow ' + m.overflow + ' px, small controls ' + JSON.stringify(m.small));
  }
  const shot = await chrome.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  writeFileSync(`${dir}/${name.replace(/[^a-z0-9]+/gi, '-')}.png`, Buffer.from(shot.result.data, 'base64'));
}

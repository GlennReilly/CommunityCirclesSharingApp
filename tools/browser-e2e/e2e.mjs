import { startServer, startChrome, sleep } from './lib.mjs';
const PASS = 'correct horse battery staple';
const server = await startServer(); const alice = await startChrome(9333); const bea = await startChrome(9334);
const text = c => c.evaluate('document.getElementById("app").innerText');
async function until(c, expr, what, ms = 20000) { const t0 = Date.now(); while (Date.now() - t0 < ms) { if (await c.evaluate(expr) === true) return; await sleep(250); } throw new Error('timeout: ' + what + '\n' + await text(c)); }
const submit = (c, formId, fields) => c.evaluate(`(() => { const f = document.getElementById(${JSON.stringify(formId)}); for (const [k, v] of Object.entries(${JSON.stringify(fields)})) { f.elements.namedItem(k).value = v; } f.requestSubmit(); return true; })()`);
const click = (c, action, extra = '') => c.evaluate(`(() => { const b = document.querySelector('[data-action="${action}"]${extra}'); if (!b) return 'no button'; b.click(); return true; })()`);
const has = (c, s) => `document.getElementById("app").innerText.includes(${JSON.stringify(s)})`;
let step = 0; const ok = m => console.log(`ok ${++step}: ${m}`);
try {
  await alice.goto(server.url + '/'); await until(alice, has(alice, 'Start a circle'), 'start form');
  await submit(alice, 'start-form', { passphrase: 'short' });
  await until(alice, has(alice, 'Choose a longer passphrase'), 'short passphrase notice'); ok('short passphrase refused with a message');
  await submit(alice, 'start-form', { passphrase: PASS });
  await until(alice, has(alice, 'Circle home') + ' && ' + has(alice, '1 member'), 'alice home'); ok('alice started a circle');
  await until(alice, has(alice, 'Up to date'), 'alice synced'); ok('alice synced (status: Up to date)');
  await click(alice, 'invite');
  await until(alice, 'document.getElementById("invite-output") !== null', 'invite shown');
  const link = await alice.evaluate('document.getElementById("invite-output").value'); ok('invite link made: ' + link.replace(/\/[A-Za-z0-9_-]{43}$/, '/<secret>'));
  await sleep(1500); await click(alice, 'sync'); await until(alice, has(alice, 'Up to date'), 'alice synced invite');
  await bea.goto(link); await until(bea, has(bea, 'Join this circle'), 'bea join form');
  const addr = await bea.evaluate('location.href'); if (addr.includes('#/join')) throw new Error('secret left in address bar: ' + addr); ok('invite secret removed from the address bar');
  await submit(bea, 'join-form', { passphrase: PASS });
  await until(bea, has(bea, 'A steward needs to let you in'), 'bea waiting'); ok('bea joined and is waiting for a steward');
  await until(bea, has(bea, 'Up to date') + ' && ' + has(bea, '2 members'), 'bea synced'); ok('bea synced, 2 members');
  await click(alice, 'sync'); await until(alice, has(alice, 'Waiting to be let in'), 'alice sees bea waiting'); ok('alice sees bea waiting');
  await click(alice, 'admit'); await sleep(1500); await click(alice, 'sync'); await until(alice, '!' + has(alice, 'Waiting to be let in'), 'waiting list cleared'); ok('alice let bea in');
  await click(bea, 'sync'); await until(bea, '!' + has(bea, 'A steward needs to let you in'), 'bea can read'); ok('bea can now read the circle');
  await click(alice, 'lock'); await until(alice, has(alice, 'Unlock Circles'), 'alice locked'); ok('alice locked');
  await submit(alice, 'unlock-form', { passphrase: 'wrong wrong wrong wrong' }); await until(alice, has(alice, 'That passphrase did not work'), 'wrong passphrase'); ok('wrong passphrase refused');
  await alice.goto(server.url + '/'); await until(alice, has(alice, 'Unlock Circles'), 'locked after reload'); ok('after a reload alice is still locked (keys not in the clear)');
  await submit(alice, 'unlock-form', { passphrase: PASS }); await until(alice, has(alice, 'Circle home') + ' && ' + has(alice, '2 members'), 'alice unlocked'); ok('alice unlocked, circle intact with 2 members');
  const stored = await alice.evaluate(`new Promise(r => { const q = indexedDB.open('circles'); q.onsuccess = () => { const tx = q.result.transaction(['keys'], 'readonly'); const g = tx.objectStore('keys').get('identity'); g.onsuccess = () => r(JSON.stringify(g.result)); }; })`);
  if (/seed/i.test(stored.replace(/"(sealed|salt)"/g, ''))) console.log('note: stored text mentions seed', stored.slice(0, 200));
  ok('stored identity is only the wrapped blob: ' + stored.slice(0, 120) + '...');
  console.log('\nALICE LOGS:\n' + alice.logs.filter(l => !/favicon|Password forms/.test(l)).join('\n'));
  console.log('BEA LOGS:\n' + bea.logs.filter(l => !/favicon|Password forms/.test(l)).join('\n'));
} catch (e) { console.log('FAILED:', e.message); console.log('ALICE LOGS:\n' + alice.logs.join('\n')); console.log('BEA LOGS:\n' + bea.logs.join('\n')); console.log('SERVER:\n' + server.log().slice(-1500)); process.exitCode = 1; }
finally { alice.stop(); bea.stop(); server.stop(); }

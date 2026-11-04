// How long a first visit takes on a slow phone and network (the point of the 250 KB budget, spec section 6).
// Usage: node load-e2e.mjs   (DIRECT=1 skips the stand-in proxy and talks to the jar itself, which compresses by itself since ADR 0058)
// Usage: node load-e2e.mjs   (needs the shadow jar and Chrome). Prints seconds until the welcome page is on screen, with the
// cache cold, for two DevTools network profiles and a 4x slower CPU. No pass/fail: it informs the budget decision.
import { createServer, request } from 'node:http';
import { createGzip } from 'node:zlib';
import { startServer, startChrome, sleep } from './lib.mjs';

const PROFILES = [
  { name: 'Wi-Fi (no throttling)', latency: 0, down: -1, cpu: 1 },
  { name: 'Fast 3G (1.6 Mbit/s, 150 ms) and a 4x slower CPU', latency: 150, down: (1.6 * 1024 * 1024) / 8, cpu: 4 },
  { name: 'Slow 3G (400 kbit/s, 400 ms) and a 4x slower CPU', latency: 400, down: (400 * 1024) / 8, cpu: 4 },
];

const server = await startServer(18083);
// The jar itself does not compress; the install guides put Caddy in front (deploy/Caddyfile: encode zstd gzip). This stand-in does the same
// with gzip so the measurement is of what a member would really download.
const proxy = createServer((req, res) => {
  const upstream = request({ host: '127.0.0.1', port: 18083, path: req.url, method: req.method, headers: req.headers }, up => {
    const type = up.headers['content-type'] ?? '';
    if (/(javascript|css|html|json|text)/.test(type) && !/event-stream/.test(type) && (req.headers['accept-encoding'] ?? '').includes('gzip')) {
      const headers = { ...up.headers, 'content-encoding': 'gzip' };
      delete headers['content-length'];
      res.writeHead(up.statusCode, headers);
      up.pipe(createGzip({ level: 6 })).pipe(res);
    } else { res.writeHead(up.statusCode, up.headers); up.pipe(res); }
  });
  req.pipe(upstream);
});
await new Promise(r => proxy.listen(18084, '127.0.0.1', r));
const chrome = await startChrome(9335);
try {
  await chrome.send('Network.enable');
  await chrome.send('Network.setBypassServiceWorker', { bypass: true }); // measure the network, not the service worker's cache
  for (const p of PROFILES) {
    await chrome.send('Network.clearBrowserCache');
    await chrome.send('Network.setCacheDisabled', { cacheDisabled: true });
    await chrome.send('Network.emulateNetworkConditions', { offline: false, latency: p.latency, downloadThroughput: p.down, uploadThroughput: p.down, connectionType: 'cellular3g' });
    await chrome.send('Emulation.setCPUThrottlingRate', { rate: p.cpu });
    await chrome.send('Page.navigate', { url: 'about:blank' });
    await sleep(500);
    const started = Date.now();
    await chrome.send('Page.navigate', { url: process.env.DIRECT === '1' ? 'http://127.0.0.1:18083/' : 'http://127.0.0.1:18084/' });
    let ms = null;
    while (Date.now() - started < 120000) {
      if (await chrome.evaluate(`(document.querySelector('main h1') || {}).textContent === 'Welcome to Circles'`) === true) { ms = Date.now() - started; break; }
      await sleep(100);
    }
    const resources = JSON.parse(await chrome.evaluate(`JSON.stringify(performance.getEntriesByType('resource').filter(r => /circles\\.js$/.test(r.name)).map(r => ({ transfer: r.transferSize, decoded: r.decodedBodySize, encoding: r.contentEncoding })))`));
    console.log(`${p.name}: ${ms === null ? 'did not load in 120 s' : (ms / 1000).toFixed(1) + ' s'}; circles.js ${resources.map(r => `${Math.round(r.transfer / 1024)} KB sent for ${Math.round(r.decoded / 1024)} KB${r.encoding ? ' (' + r.encoding + ')' : ''}`).join(', ')}`);
  }
} finally {
  chrome.stop();
  proxy.close();
  server.stop();
}

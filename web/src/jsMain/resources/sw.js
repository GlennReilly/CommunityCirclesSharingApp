'use strict';
// The service worker (spec section 6, ADR 0041): keeps the app shell so the page opens offline, never touches the API,
// and lets the page decide when to switch to a new version. Written by hand because it must be its own file; the build
// replaces the token below with a hash of the shell files, so a changed shell is a changed worker.
const VERSION = '__BUILD_ID__';
const CACHE_PREFIX = 'circles-shell-';
const CACHE = CACHE_PREFIX + VERSION;
const SHELL = ['/', '/circles.js', '/styles.css', '/wordlist.txt'];

self.addEventListener('install', (event) => {
  // Fetch past the HTTP cache so the stored copies really are this version's files. No skipWaiting here: the page asks.
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL.map((url) => new Request(url, { cache: 'reload' })))));
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key.startsWith(CACHE_PREFIX) && key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  // API answers are never stored here: the local replica is the only copy of the data (spec 6).
  if (url.pathname.startsWith('/api/')) return;
  if (request.mode === 'navigate' && url.pathname === '/') {
    event.respondWith(caches.match('/', { ignoreSearch: true }).then((hit) => hit || fetch(request)));
    return;
  }
  if (SHELL.includes(url.pathname)) {
    event.respondWith(caches.match(request, { ignoreSearch: true }).then((hit) => hit || fetch(request)));
  }
});

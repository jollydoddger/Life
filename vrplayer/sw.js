// sw.js — service worker so Orbit installs to the Android home screen and runs
// offline (the whole app lives on the phone after the first load).
//
// Strategy: precache the app shell + vendored libraries; serve same-origin GETs
// cache-first with a runtime cache; never intercept cross-origin requests (video
// streams, deeplink feeds) so range requests and CORS behave normally.

const CACHE = 'orbit-v3';

const ASSETS = [
  './index.html',
  './styles.css',
  './app.js',
  './player.js',
  './feed.js',
  './media.js',
  './library.js',
  './testpattern.js',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-maskable-512.png',
  './lib/three.module.js',
  './lib/jsm/webxr/VRButton.js',
  './lib/jsm/effects/StereoEffect.js',
  './lib/hls.js',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  if (new URL(req.url).origin !== self.location.origin) return; // let video/feeds pass through

  e.respondWith(
    caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match('./index.html')))
  );
});

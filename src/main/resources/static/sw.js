/* SmartAttend service worker: caches the app shell only. API responses are never cached,
   so attendance always requires a live connection to the server. */
const CACHE = "smartattend-v1";
const SHELL = ["/", "/index.html", "/login.html", "/register.html", "/student.html", "/offline.html", "/css/app.css",
  "/js/core.js", "/js/webauthn.js", "/js/face.js", "/js/auth.js", "/js/student.js", "/img/logo.svg",
  "/fonts/schibsted-grotesk-latin-wght-normal.woff2", "/manifest.webmanifest"];

self.addEventListener("install", (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api/")) return;
  // Network first so updates arrive immediately; fall back to cache when offline.
  e.respondWith(fetch(e.request).then((res) => {
    if (res.ok) { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
    return res;
  }).catch(() => caches.match(e.request).then((r) => r || (e.request.mode === "navigate" ? caches.match("/offline.html") : undefined))));
});

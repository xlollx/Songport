package com.xlollx.songport.server

/**
 * The pages as an installable web app (PWA): from a phone, "Add to Home screen" (or Chrome's
 * "Install app", which wants https) opens Trackhop full screen, like an app. A manifest, the icons
 * (the app's own, full bleed, so also good as "maskable") and a service worker that keeps nothing:
 * every page comes from the server, as in the browser; only when the server cannot be reached does
 * it show a short page saying so. Served without the password (they hold nothing private): the
 * browser fetches them on its own.
 */
internal object Pwa {
    /** The files, by the path they are served at. */
    val files = setOf("manifest.webmanifest", "sw.js", "icon-192.png", "icon-180.png", "icon-512.png")

    /** The tags for the page's head. */
    const val HEAD = """<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" type="image/png" sizes="192x192" href="/icon-192.png">
<link rel="apple-touch-icon" href="/icon-180.png">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Trackhop">
<meta name="apple-mobile-web-app-status-bar-style" content="black">
<script>if('serviceWorker' in navigator)navigator.serviceWorker.register('/sw.js').catch(function(){})</script>"""

    private const val MANIFEST = """{
  "id": "/",
  "name": "Trackhop",
  "short_name": "Trackhop",
  "description": "Keeps your playlists in sync between music services.",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "background_color": "#141218",
  "theme_color": "#141218",
  "icons": [
    { "src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any maskable" },
    { "src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable" }
  ]
}"""

    // Network only: Trackhop's pages are live (runs, results), so nothing is cached. A page that
    // cannot load because the server is off or out of reach gets a short page instead of the
    // browser's own error.
    private const val WORKER = """self.addEventListener('install', function () { self.skipWaiting() });
self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()) });
self.addEventListener('fetch', function (e) {
  if (e.request.mode !== 'navigate') return;
  e.respondWith(fetch(e.request).catch(function () {
    return new Response('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Trackhop</title>' +
      '<body style="font-family:system-ui,sans-serif;background:#141218;color:#e6e0e9;padding:24px">' +
      '<h1>Trackhop is out of reach</h1><p>The server does not answer: check that it is running and that this device is on its network, then try again.</p>' +
      '<p><a href="/" style="color:#d0bcff">Try again</a></p></body>', { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  }));
});
"""

    /** The file at [name] (one of [files]): its bytes and its type. */
    fun file(name: String): Pair<ByteArray, String>? = when (name) {
        "manifest.webmanifest" -> MANIFEST.toByteArray() to "application/manifest+json"
        "sw.js" -> WORKER.toByteArray() to "text/javascript; charset=utf-8"
        in files -> Pwa::class.java.getResourceAsStream("/pwa/$name")?.use { it.readBytes() }?.let { it to "image/png" }
        else -> null
    }
}

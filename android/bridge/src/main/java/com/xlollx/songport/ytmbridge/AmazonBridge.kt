package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The credentials of the Amazon Music web player, taken from the player itself.
 *
 * Amazon hands out an access token only to the running player: `config.json` is served anonymously
 * and the page carries an empty `accessToken`. So the Bridge mirrors the `x-amzn-*` headers the
 * player puts in its own API calls (they travel inside the request body, under `headers`). What
 * Songport then sends is exactly what the player sends: no guessing of device types, feature flags
 * or CSRF values, and it keeps working when Amazon changes them.
 *
 * Two chances to see them, because a player that nobody is looking at does not always start:
 * the sign-in and traffic-capture screens install [HOOK] in their own visible WebView and store
 * whatever it sees, and [player] can also run the player in an offscreen WebView when the stored
 * headers are too old. Nothing leaves the phone: the headers stay in this app, encrypted with the
 * session, and are used only for its own requests.
 */
object AmazonBridge {

    /** What the player uses: its API headers (preferred) or, failing that, its configuration object. */
    class Player(
        val headers: Map<String, String>, val config: JsonObject?, val userAgent: String?, val at: Long,
        /** Calls the offscreen player made without a token: it ran, but as a signed-out visitor. */
        val anonymousCalls: Int = 0,
    ) {
        /** The access token lives inside the authentication header the player builds. */
        val signedIn: Boolean
            get() = (parseJson(headers["x-amzn-authentication"] ?: "{}") as? JsonObject)["accessToken"].str?.isNotBlank() == true
    }

    /** An access token lasts about an hour; well before that the headers are captured again. */
    private const val TTL_MS = 30 * 60_000L

    @Volatile private var cached: Player? = null

    fun forget() { cached = null }

    /** True if usable credentials are already at hand, without starting anything. */
    fun ready(ctx: Context): Boolean = stored(ctx) != null

    /**
     * Stores headers seen in a visible WebView (sign-in, traffic capture). Called from JavaScript,
     * so it accepts whatever arrives and simply ignores anything without a token.
     */
    fun remember(ctx: Context, headersJson: String, userAgent: String?, host: String?) {
        val map = headersOf(headersJson) ?: return
        AmazonSession.saveHeaders(ctx.applicationContext, headersJson, userAgent, host)
        cached = Player(map, null, userAgent, System.currentTimeMillis())
    }

    /**
     * Installs the hook into a WebView the user can see (sign-in, traffic capture): a player someone
     * is actually looking at always starts, so this is the surest place to see its headers. Returns
     * true when the script runs by itself at every document start; otherwise the caller must also
     * evaluate [HOOK] in its page callbacks.
     */
    @SuppressLint("AddJavascriptInterface")
    fun install(ctx: Context, web: WebView): Boolean {
        val app = ctx.applicationContext
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun headers(json: String, ua: String?, host: String?) { remember(app, json, ua, host) }
            /** The configuration is only used here for the display name shown on the status screen. */
            @JavascriptInterface
            fun config(json: String, ua: String?) {
                val j = parseJson(json) as? JsonObject ?: return
                AmazonClient.Config(j).customerName?.takeIf { it.isNotBlank() }?.let { AmazonSession.saveAccount(app, it) }
            }
        }, "AmzBridge")
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(web, HOOK, setOf("*"))
        return early
    }

    /** The player's credentials, from cache or freshly captured. Blocking; never on the main thread. */
    @Synchronized
    fun player(ctx: Context, domain: String): Player {
        cached?.takeIf { fresh(it.at) && it.signedIn }?.let { return it }
        stored(ctx)?.let { cached = it; return it }
        val p = capture(ctx.applicationContext, domain)
        cached = p
        return p
    }

    private fun fresh(at: Long) = System.currentTimeMillis() - at < TTL_MS

    /** The headers kept with the session, while they are young enough to still carry a live token. */
    private fun stored(ctx: Context): Player? {
        val app = ctx.applicationContext
        val at = AmazonSession.headersAt(app)
        if (!fresh(at)) return null
        val map = headersOf(AmazonSession.headers(app) ?: return null) ?: return null
        return Player(map, null, AmazonSession.headersUserAgent(app), at)
    }

    /** Parses a captured `headers` object, keeping it only when it really carries a token. */
    private fun headersOf(json: String): Map<String, String>? {
        val j = parseJson(json) as? JsonObject ?: return null
        val map = j.mapNotNull { (k, v) -> v.str?.let { k to it } }.toMap()
        val token = (parseJson(map["x-amzn-authentication"] ?: "{}") as? JsonObject)["accessToken"].str
        return if (token.isNullOrBlank()) null else map
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun capture(app: Context, domain: String): Player {
        val latch = CountDownLatch(1)
        // Written from the WebView thread, read here: atomic holders, not plain locals.
        val headers = AtomicReference<Map<String, String>?>(null)
        val config = AtomicReference<JsonObject?>(null)
        val userAgent = AtomicReference<String?>(null)
        val lastUrl = AtomicReference("")
        val problem = AtomicReference<String?>(null)
        val calls = AtomicInteger(0)
        val anonymous = AtomicInteger(0)
        var web: WebView? = null
        val main = Handler(Looper.getMainLooper())

        val sink = object {
            @JavascriptInterface
            fun headers(json: String, ua: String?, host: String?) {
                calls.incrementAndGet()
                val map = headersOf(json) ?: run { anonymous.incrementAndGet(); return }
                AmazonSession.saveHeaders(app, json, ua, host)
                headers.set(map)
                userAgent.set(ua)
                latch.countDown()
            }

            /** `window.amznMusic.appConfig`, used only when no call of the player was seen. */
            @JavascriptInterface
            fun config(json: String, ua: String?) {
                config.compareAndSet(null, parseJson(json) as? JsonObject ?: return)
                userAgent.compareAndSet(null, ua)
            }

            /** The player showed a page instead of starting ("update your browser", "get the app"). */
            @JavascriptInterface
            fun page(text: String) { problem.compareAndSet(null, "page says: " + text.take(120)) }
        }

        main.post {
            try {
                CookieManager.getInstance().apply { setAcceptCookie(true); flush() }
                val w = WebView(app)
                web = w
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; loadWithOverviewMode = true; useWideViewPort = true }
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                // A window this player is never shown in still needs a size: without a viewport the
                // single page application lays nothing out and makes no calls at all.
                w.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY))
                w.layout(0, 0, VIEW_W, VIEW_H)
                // The desktop player, as in the traffic capture: the phone-sized one stopped calling its API.
                BridgeUa.apply(app, w)
                w.addJavascriptInterface(sink, "AmzBridge")
                val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (early) WebViewCompat.addDocumentStartJavaScript(w, HOOK, setOf("*"))
                w.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        lastUrl.set(url ?: "")
                        if (!early) view.evaluateJavascript(HOOK, null)
                        // Sent to the sign-in page: the cookies no longer open the player. No point waiting.
                        if (url != null && url.contains("/ap/signin")) { problem.compareAndSet(null, SIGN_IN_AGAIN); latch.countDown() }
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        lastUrl.set(url)
                        if (!early) view.evaluateJavascript(HOOK, null)
                        view.evaluateJavascript(CONFIG, null)
                    }
                    override fun onReceivedError(view: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                        if (req.isForMainFrame) problem.compareAndSet(null, "page error ${err.errorCode}")
                    }
                }
                w.loadUrl("https://${AmazonSession.headersHost(app) ?: domain}/")
                // The configuration is worth asking for even if the page never reports itself finished.
                listOf(5_000L, 12_000L, 25_000L, 40_000L).forEach { at ->
                    main.postDelayed({ runCatching { web?.evaluateJavascript(CONFIG, null) } }, at)
                }
            } catch (e: Exception) {
                problem.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                latch.countDown()
            }
        }
        latch.await(50, TimeUnit.SECONDS)
        main.post { runCatching { web?.stopLoading(); web?.destroy() } }

        val h = headers.get()
        val c = config.get()
        if (h == null && problem.get() == SIGN_IN_AGAIN) throw BridgeException(
            "the Amazon session no longer opens the player (it asked to sign in again). Open Songport Bridge, sign out of Amazon and sign in again.",
        )
        if (h == null && problem.get()?.startsWith("page says") == true) throw BridgeException(
            "the Amazon Music player did not start [${problem.get()}, url: ${lastUrl.get().take(80)}].",
        )
        if (h == null && c == null) throw BridgeException(
            "the Amazon Music player did not hand over its credentials in time" +
                " [url: ${lastUrl.get().take(80).ifBlank { "none" }}" +
                ", calls seen: ${calls.get()}" +
                (problem.get()?.let { ", $it" } ?: "") + "]." +
                " Open Songport Bridge, tap Capture traffic and let the player load once, then try again.",
        )
        return Player(h ?: emptyMap(), c, userAgent.get(), System.currentTimeMillis(), anonymous.get())
    }

    private const val VIEW_W = 1280
    private const val VIEW_H = 2000
    private const val SIGN_IN_AGAIN = "sign-in page"

    /**
     * Mirrors the `x-amzn-*` headers of the player's own API calls. They are not HTTP headers: the
     * player serialises them as a JSON string inside the request body, under `headers`.
     */
    val HOOK = """
        (function(){
          if (window.__amzHdr) return; window.__amzHdr = 1;
          // What the desktop player checks before it starts (taken from its own code, see the traffic
          // capture): not Linux, no touch, FLAC in MP4 through MSE and Widevine, a window.chrome.
          // Nothing is ever played here, so the codec answers are given.
          try { Object.defineProperty(Navigator.prototype, 'platform', { get: function(){ return 'Win32'; }, configurable: true }); } catch(e){}
          try { Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: function(){ return 0; }, configurable: true }); } catch(e){}
          try {
            var isType = MediaSource.isTypeSupported.bind(MediaSource);
            MediaSource.isTypeSupported = function(t){ return /audio\/mp4/.test(String(t)) ? true : isType(t); };
          } catch(e){}
          try {
            var realEme = navigator.requestMediaKeySystemAccess.bind(navigator);
            var stub = function(ks, cfgs){
              var cfg = (cfgs && cfgs[0]) || {};
              return { keySystem: ks, getConfiguration: function(){ return cfg; },
                createMediaKeys: function(){ return Promise.resolve({
                  createSession: function(){ return { addEventListener: function(){}, removeEventListener: function(){}, generateRequest: function(){ return Promise.resolve(); }, update: function(){ return Promise.resolve(); }, close: function(){ return Promise.resolve(); }, closed: Promise.resolve() }; },
                  setServerCertificate: function(){ return Promise.resolve(true); } }); } };
            };
            navigator.requestMediaKeySystemAccess = function(ks, cfgs){ return realEme(ks, cfgs).catch(function(){ return stub(ks, cfgs); }); };
          } catch(e){}
          try { if (!window.chrome) window.chrome = { runtime: {}, app: { isInstalled: false }, loadTimes: function(){ return {}; }, csi: function(){ return {}; } }; } catch(e){}
          // A page in place of the player: said, so the error names it instead of a silent timeout.
          var probe = function(){
            try {
              var t = String(document.body && document.body.innerText || '');
              var m = t.match(/[^\n]*(Update Chrome|Aggiornare Chrome|no longer supported|non è più supportat|Get the app|Scarica l.app|Open the app|Apri l.app)[^\n]*/i);
              if (m && AmzBridge.page) AmzBridge.page(m[0]);
            } catch(e){}
          };
          setTimeout(probe, 8000); setTimeout(probe, 20000);
          var match = function(u){ return /skill\.music\.a2z\.com\/api\/|music\.amazon\.[a-z.]+\/(?:[A-Z]{2}\/)?api\//.test(String(u||'')); };
          var grab = function(b){
            if (b == null) return;
            try {
              if (typeof b !== 'string') b = String(b);
              var j = JSON.parse(b);
              if (!j || !j.headers) return;
              var h = (typeof j.headers === 'string') ? j.headers : JSON.stringify(j.headers);
              AmzBridge.headers(h, navigator.userAgent, location.host);
            } catch(e){}
          };
          var of = window.fetch;
          if (of) window.fetch = function(input, init){
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            if (match(url) && init && init.body) grab(init.body);
            return of.apply(this, arguments);
          };
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(b){ if (match(this.__u)) grab(b); return os.apply(this, arguments); };
        })();
    """.trimIndent()

    /** The configuration object, retried while the single page application boots. */
    val CONFIG = """
        (function(){
          if (window.__amzCfg) return; window.__amzCfg = 1;
          var tries = 0;
          var look = function(){
            try {
              var c = window.amznMusic && window.amznMusic.appConfig;
              if (c) { AmzBridge.config(JSON.stringify(c), navigator.userAgent); return; }
            } catch(e){}
            if (++tries < 30) setTimeout(look, 1000);
          };
          look();
        })();
    """.trimIndent()
}

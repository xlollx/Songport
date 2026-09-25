package com.xlollx.songport.ytmbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spotify through the web player session.
 *
 * The user signs in once on accounts.spotify.com inside a WebView. Whenever Songport needs to talk to
 * Spotify, the Bridge loads open.spotify.com in a hidden WebView and captures the access token the
 * web player itself requests at start-up (`/api/token`). That token is a first-party token that the
 * public Web API accepts, so Songport reuses its normal Spotify code; it lasts about an hour and is
 * renewed the same way. Letting the real web player fetch the token keeps this working when Spotify
 * changes the anti-abuse parameters of that endpoint, because the page computes them.
 *
 * Three ways to get hold of it, cheapest first: the page's own HTML carries the session as a
 * `<script id="session">` block, so a plain GET with the cookies is often enough; otherwise the
 * hidden WebView loads the player, and the token request is seen both at the network level
 * ([WebViewClient.shouldInterceptRequest], which also covers requests made from workers) and by
 * a hook on fetch/XMLHttpRequest inside the page.
 */
object SpotifyBridge {
    val session = WebSession("spotify", listOf("open.spotify.com", "accounts.spotify.com", "www.spotify.com", "spotify.com"))
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    /** Desktop Chrome: the web player refuses any user agent marked as a WebView ("wv"). */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    class Token(val value: String, val expiresAt: Long)

    @Volatile private var cached: Token? = null
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).withHook().build()

    fun signedIn(cookies: String?): Boolean = WebSession.cookieValue(cookies, "sp_dc") != null

    /** A valid access token, from cache or freshly captured. Blocking; never call on the main thread. */
    @Synchronized
    fun token(ctx: Context): Token {
        if (!session.isConnected(ctx)) throw BridgeException("not connected")
        cached?.takeIf { it.expiresAt - 90_000 > System.currentTimeMillis() }?.let { return it }
        val t = capture(ctx.applicationContext)
        cached = t
        return t
    }

    /** Fetches display name and id once, for the status screen and for playlist creation. */
    fun accountInfo(ctx: Context): Pair<String?, String?> {
        val t = token(ctx)
        val req = Request.Builder().url("https://api.spotify.com/v1/me").header("Authorization", "Bearer ${t.value}").build()
        http.newCall(req).execute().use { resp ->
            val j = parseJson(resp.body?.string() ?: "") as? JsonObject ?: return null to null
            return j["id"].str to (j["display_name"].str ?: j["id"].str)
        }
    }

    private val SESSION_SCRIPT = Regex("""<script[^>]*id="session"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val TOKEN_URL = Regex("""^(?:https?://open\.spotify\.com)?/(?:api/token|get_access_token)(?:[?#]|$)""")

    /** The session block the web player's HTML ships with, fetched with the saved cookies. No WebView. */
    private fun fromHtml(app: Context): Token? {
        val cookies = session.cookies(app) ?: return null
        val req = Request.Builder().url("https://open.spotify.com/")
            .header("User-Agent", USER_AGENT).header("Cookie", cookies).header("Accept", "text/html").build()
        val html = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val json = SESSION_SCRIPT.find(html)?.groupValues?.get(1) ?: return null
        val j = runCatching { parseJson(json) as? JsonObject }.getOrNull() ?: return null
        if (j["isAnonymous"]?.toString() == "true") return null
        val value = j["accessToken"].str ?: return null
        return Token(value, j["accessTokenExpirationTimestampMs"].long ?: (System.currentTimeMillis() + 50 * 60_000))
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun capture(app: Context): Token {
        runCatching { fromHtml(app) }.getOrNull()?.let { return it }
        val latch = CountDownLatch(1)
        var result: Token? = null
        var error: String? = null
        var web: WebView? = null
        val main = Handler(Looper.getMainLooper())

        val sink = object {
            @JavascriptInterface
            fun token(json: String) {
                val j = runCatching { parseJson(json) as? JsonObject }.getOrNull() ?: return
                val value = j["accessToken"].str ?: return
                if (j["isAnonymous"]?.toString() == "true") { error = "the Spotify web session has expired: sign in again"; latch.countDown(); return }
                val exp = j["accessTokenExpirationTimestampMs"].long ?: (System.currentTimeMillis() + 50 * 60_000)
                result = Token(value, exp)
                latch.countDown()
            }
            @JavascriptInterface
            fun fail(message: String) { if (result == null) error = message }
        }

        // The token request, wherever it is made from (page, worker): fetched here with the page's own
        // headers and cookies, answered to the page unchanged, and read on the way.
        fun intercept(request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (!TOKEN_URL.containsMatchIn(url) || request.method != "GET") return null
            return try {
                val b = Request.Builder().url(url)
                request.requestHeaders.forEach { (k, v) -> if (!k.equals("Cookie", true)) b.header(k, v) }
                CookieManager.getInstance().getCookie(url)?.let { b.header("Cookie", it) }
                http.newCall(b.build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    sink.token(body)
                    val headers = resp.headers.names().filter { !it.equals("content-encoding", true) && !it.equals("content-length", true) }
                        .associateWith { resp.header(it) ?: "" }
                    WebResourceResponse(resp.header("content-type")?.substringBefore(';')?.trim() ?: "application/json", "utf-8", resp.code, resp.message.ifBlank { "OK" }, headers, body.byteInputStream())
                }
            } catch (e: Exception) { null }
        }

        main.post {
            try {
                val w = WebView(app)
                web = w
                w.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = USER_AGENT }
                w.addJavascriptInterface(sink, "SpBridge")
                val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (early) WebViewCompat.addDocumentStartJavaScript(w, HOOK, setOf("*"))
                w.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!early) view.evaluateJavascript(HOOK, null)
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        intercept(request) ?: super.shouldInterceptRequest(view, request)
                    override fun onPageFinished(view: WebView, url: String) {
                        // Belt and braces: the session block in the HTML, then the endpoint from the page context.
                        view.evaluateJavascript(FALLBACK, null)
                    }
                }
                w.loadUrl("https://open.spotify.com/")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                latch.countDown()
            }
        }
        latch.await(35, TimeUnit.SECONDS)
        main.post { runCatching { web?.stopLoading(); web?.destroy() } }
        return result ?: throw BridgeException(
            "Spotify did not hand out a token" + (error?.let { ": $it" } ?: " (timed out)") +
                ". Open Songport Bridge and sign in to Spotify again."
        )
    }

    // The web player requests its token at start-up; mirror that response to SpBridge.token.
    private val HOOK = """
        (function(){
          if (window.__spTok) return; window.__spTok = 1;
          var isTok = function(u){ return /^(https?:\/\/open\.spotify\.com)?\/(api\/token|get_access_token)([?#]|$)/.test(String(u||'')); };
          var of = window.fetch;
          if (of) window.fetch = function(input, init){
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            var p = of.apply(this, arguments);
            if (isTok(url)) p.then(function(r){ try { r.clone().text().then(function(t){ SpBridge.token(t); }); } catch(e){} });
            return p;
          };
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(b){
            var x = this;
            if (isTok(x.__u)) x.addEventListener('loadend', function(){ try { SpBridge.token(String(x.responseText || '')); } catch(e){} });
            return os.apply(this, arguments);
          };
        })();
    """.trimIndent()

    private val FALLBACK = """
        (function(){
          try { var s = document.getElementById('session'); if (s && s.textContent) SpBridge.token(s.textContent); } catch(e){}
          var tryUrl = function(u){ return fetch(u, {credentials: 'include'}).then(function(r){ return r.text(); }).then(function(t){ SpBridge.token(t); }); };
          tryUrl('/api/token?reason=init&productType=web-player').catch(function(e){
            tryUrl('/get_access_token?reason=transport&productType=web_player').catch(function(e2){ SpBridge.fail(String(e2)); });
          });
        })();
    """.trimIndent()
}

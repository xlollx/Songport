package com.xlollx.songport.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HttpResponse(val code: Int, val body: String, val headers: Headers) {
    val ok: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name]
}

/** Client HTTP condiviso. Gestisce da solo i 429 (rate limit) con attesa e ripetizione. */
object Http {
    /** Longest Retry-After (seconds) honoured silently inside [send]; anything longer is returned as is. */
    const val MAX_SILENT_WAIT_S = 15L
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(HostLog.interceptor)
        .build()

    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        maxRateLimitRetries: Int = 3,
    ): HttpResponse = withContext(Dispatchers.IO) {
        var last: HttpResponse? = null
        for (attempt in 0..maxRateLimitRetries) {
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                val b = body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null
                method(method, b)
            }.build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: ""
            val out = HttpResponse(resp.code, text, resp.headers)
            last = out
            if (out.code == 429 && attempt < maxRateLimitRetries) {
                // A short pause is absorbed here. A long one (Spotify can ask for hours) is not: the
                // caller gets the 429 at once, with Retry-After, and says so instead of showing a
                // spinner for minutes; the sync engine waits on its own terms, visibly.
                val asked = out.header("Retry-After")?.trim()?.toLongOrNull()
                if (asked != null && asked > MAX_SILENT_WAIT_S) return@withContext out
                delay((asked?.coerceAtLeast(1) ?: (2L * (attempt + 1))) * 1000)
                continue
            }
            // 502/503/504: quasi sempre un singhiozzo del servizio, un secondo tentativo basta.
            if (out.code in listOf(502, 503, 504) && attempt < 1) {
                delay(1500)
                continue
            }
            return@withContext out
        }
        last!!
    }

    fun form(params: Map<String, String>): RequestBody =
        FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()

    fun jsonBody(text: String, mediaType: String = "application/json"): RequestBody =
        text.toRequestBody(mediaType.toMediaType())

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun query(params: Map<String, String?>): String =
        params.filterValues { it != null }.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v!!)}" }
}

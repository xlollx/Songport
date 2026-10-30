package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.SyncState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A small JSON API for other tools (Home Assistant, n8n, scripts): the syncs and their runs, start
 * one or all, the services, the File playlists. Under /api/v1, with a token the user makes on the
 * API page (only its SHA-256 is kept) sent as "Authorization: Bearer <token>"; off until then. And a
 * webhook: when a run ends, a POST of it to the user's address, signed with HMAC-SHA256 when a
 * secret is set. Documented in docs/guide/docker.md#api.
 */
object Api {
    private const val KV = "api"

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    fun enabled(env: ServerEnv) = !env.keyValues(KV)["token"].isNullOrEmpty()

    /** A new token, shown once; the old one stops working. */
    fun newToken(env: ServerEnv): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = "th_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        env.keyValues(KV)["token"] = hash(token)
        return token
    }

    fun revoke(env: ServerEnv) { env.keyValues(KV)["token"] = "" }

    fun authorized(env: ServerEnv, header: String?): Boolean {
        val stored = env.keyValues(KV)["token"].orEmpty().ifEmpty { return false }
        val given = header?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim() ?: return false
        return MessageDigest.isEqual(hash(given).toByteArray(), stored.toByteArray())
    }

    fun webhook(env: ServerEnv): String = env.keyValues(KV)["webhook"].orEmpty()
    fun setWebhook(env: ServerEnv, url: String, secret: String?) {
        env.keyValues(KV)["webhook"] = url
        if (secret != null) env.keyValues(KV)["secret"] = secret
    }

    // ------------------------------------------------------------------ JSON

    fun job(env: ServerEnv, j: SyncJob, running: Boolean): JsonObject = buildJsonObject {
        put("id", j.id)
        put("name", j.name)
        put("source", ref(env, j.source.provider, j.source.playlistId, j.source.playlistName))
        put("target", ref(env, j.target.provider, j.target.playlistId, j.target.playlistName))
        put("schedule", j.schedule.name.lowercase())
        put("mirrorRemovals", j.mirrorRemovals)
        put("enabled", j.enabled)
        put("running", running)
        put("lastRun", if (j.lastRunEpoch > 0) JsonPrimitive(j.lastRunEpoch) else JsonNull)
        put("nextRun", Runner.nextRun(j)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastResult", j.lastReportId?.let { env.store.report(it) }?.let { run(it, tracks = false) } ?: JsonNull)
    }

    private fun ref(env: ServerEnv, provider: String, id: String?, name: String) = buildJsonObject {
        put("service", provider)
        put("serviceName", Providers.byId(provider)?.label(env) ?: provider)
        put("playlistId", id?.let { JsonPrimitive(it) } ?: JsonNull)
        put("playlist", name)
    }

    fun run(r: SyncReport, tracks: Boolean = true): JsonObject = buildJsonObject {
        put("id", r.id)
        put("syncId", r.jobId)
        put("started", r.startedEpoch)
        put("durationMs", r.durationMs)
        put("ok", r.ok)
        put("error", r.error?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sourceTracks", r.sourceCount)
        put("added", r.added)
        put("removed", r.removed)
        put("notFound", r.unmatched.size)
        put("toCheck", r.reviewTracks.size)
        if (tracks) {
            put("notFoundTracks", buildJsonArray { r.unmatchedTracks.forEach { add(buildJsonObject { put("artist", it.artistLine); put("title", it.title) }) } })
            put("addedTracks", buildJsonArray { r.addedTracks.forEach { add(buildJsonObject { put("artist", it.artistLine); put("title", it.title) }) } })
        }
    }

    // ------------------------------------------------------------------ webhook

    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    /** Tells the webhook a run ended. Never fails the run: an error goes to the log. */
    fun notify(env: ServerEnv, job: SyncJob, report: SyncReport) {
        val url = webhook(env).ifEmpty { return }
        val body = buildJsonObject {
            put("event", "sync.finished")
            put("sync", buildJsonObject { put("id", job.id); put("name", job.name) })
            put("run", run(report))
        }.toString()
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("User-Agent", "Trackhop")
        env.keyValues(KV)["secret"].orEmpty().takeIf { it.isNotEmpty() }?.let { secret ->
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            req.header("X-Trackhop-Signature", "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) })
        }
        runCatching { client.sendAsync(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding()) }
            .onFailure { env.log("api", "webhook: ${it.message}") }
    }
}

/** /api/v1/…: answered before the page's own password, with the API's token instead. */
internal fun Web.api(ex: HttpExchange, method: String, path: List<String>, query: Map<String, String>) {
    fun json(code: Int, e: JsonElement) = send(ex, code, e.toString(), "application/json")
    fun error(code: Int, message: String) = json(code, buildJsonObject { put("error", message) })
    if (!Api.enabled(env)) return error(403, "The API is off: make a token on the page, Tools › API.")
    if (!Api.authorized(env, ex.requestHeaders.getFirst("Authorization"))) return error(401, "Send the API token as: Authorization: Bearer <token>")
    val running = SyncState.running.value
    val jobs = env.store.data.jobs
    val rest = path.drop(2)
    when {
        rest == listOf("status") && method == "GET" -> json(200, buildJsonObject {
            put("version", version)
            put("syncs", jobs.size)
            put("running", buildJsonArray { running.keys.forEach { add(it) } })
            put("lastRun", jobs.maxOfOrNull { it.lastRunEpoch } ?: 0)
        })
        rest == listOf("syncs") && method == "GET" -> json(200, JsonArray(jobs.map { Api.job(env, it, it.id in running) }))
        rest == listOf("syncs", "run-all") && method == "POST" -> {
            val started = jobs.filter { it.enabled }
            started.forEach { runner.start(it) }
            json(202, buildJsonObject { put("started", started.size) })
        }
        rest.size >= 2 && rest[0] == "syncs" -> {
            val job = env.store.job(rest[1]) ?: return error(404, "No sync with that id.")
            when {
                rest.size == 2 && method == "GET" -> json(200, buildJsonObject {
                    Api.job(env, job, job.id in running).forEach { (k, v) -> put(k, v) }
                    put("runs", JsonArray(env.store.data.reports.filter { it.jobId == job.id }.take(20).map { Api.run(it, tracks = false) }))
                })
                rest.size == 3 && rest[2] == "run" && method == "POST" -> {
                    val already = job.id in running
                    runner.start(job)
                    json(202, buildJsonObject { put("started", !already); put("running", true) })
                }
                else -> error(404, "Unknown call.")
            }
        }
        rest.size == 2 && rest[0] == "runs" && method == "GET" -> {
            val report = env.store.data.reports.firstOrNull { it.id == rest[1] } ?: return error(404, "No run with that id.")
            json(200, Api.run(report))
        }
        rest == listOf("services") && method == "GET" -> json(200, JsonArray(Providers.connectors().map { p ->
            buildJsonObject {
                put("id", p.id); put("name", p.label(env))
                put("connected", !p.requiresAuth || p.isConnected(env))
                put("account", p.accountName(env)?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }))
        rest == listOf("files") && method == "GET" -> json(200, JsonArray(csvFiles().map { JsonPrimitive(it.name) }))
        rest.size == 2 && rest[0] == "files" && method == "GET" -> {
            val f = csvFiles().firstOrNull { it.name == rest[1] } ?: return error(404, "No such file.")
            val format = ExportFormat.of(query["format"])
            val text = if (format == ExportFormat.CSV) f.readText() else format.encode(f.nameWithoutExtension, com.xlollx.songport.sync.CsvCodec.parse(f.readText()))
            send(ex, 200, text, format.mime)
        }
        else -> error(404, "Unknown call. See https://xlollx.github.io/Trackhop/guide/docker#api")
    }
}

/** Tools › API: the token (made, shown once, revoked) and the webhook. */
internal fun Web.apiPage(r: Web.Req) {
    var shown: String? = null
    if (r.method == "POST") {
        when (r.form["action"]) {
            "token" -> shown = Api.newToken(env)
            "revoke" -> { Api.revoke(env); return redirect(r.ex, "/tools/api?ok=${q("The API is off.")}") }
            "webhook" -> {
                val url = r.form["url"].orEmpty().trim()
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) return redirect(r.ex, "/tools/api?err=${q("The address starts with http:// or https://.")}")
                Api.setWebhook(env, url, r.form["secret"]?.trim()?.takeIf { it.isNotEmpty() })
                return redirect(r.ex, "/tools/api?ok=${q("Saved.")}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p>Other tools (Home Assistant, n8n, Node-RED, a script) can list the syncs, start them and read how they went, through a small JSON API at <code>${h(env.baseUrl)}/api/v1</code>. <a href="https://xlollx.github.io/Trackhop/guide/docker#api">The calls</a></p>""")
    if (shown != null) {
        body.append("""<div class="card ok"><b>Your token</b>: copy it now, it is not shown again.<pre>${h(shown)}</pre><span class="muted">Send it as <code>Authorization: Bearer …</code></span></div>""")
    }
    body.append(if (Api.enabled(env)) """<p class="ok">The API is on.</p>""" else """<p class="muted">The API is off until you make a token.</p>""")
    body.append("<div class=\"actions\">" + Html.postButton("/tools/api", if (Api.enabled(env)) "Make a new token" else "Make a token", fields = mapOf("action" to "token"),
        confirm = if (Api.enabled(env)) "The current token stops working. Make a new one?" else null))
    if (Api.enabled(env)) body.append(Html.postButton("/tools/api", "Turn the API off", "danger", fields = mapOf("action" to "revoke")))
    body.append("</div></div>")
    body.append(
        """<form method="post" action="/tools/api" class="card"><input type="hidden" name="action" value="webhook"><h3>Webhook</h3>
<p class="muted">When a sync run ends, Trackhop POSTs it as JSON (<code>"event":"sync.finished"</code>) to this address. With a secret, the header <code>X-Trackhop-Signature: sha256=…</code> is the HMAC-SHA256 of the body.</p>
<label>Address</label><input type="text" name="url" value="${h(Api.webhook(env))}" placeholder="https://homeassistant.local:8123/api/webhook/trackhop">
<label>Secret (optional; leave empty to keep the current one)</label><input type="password" name="secret" autocomplete="off">
<p><button class="secondary">Save</button></p></form>""",
    )
    send(r.ex, 200, page("API", body.toString()))
}

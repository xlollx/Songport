package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.CredentialsProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.OAuthProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.SyncState
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The web interface: services, syncs, runs and the log, as plain pages and forms. Served by the
 * JDK's own HTTP server; with SONGPORT_PASSWORD every page asks for it (HTTP basic auth).
 */
class Web(
    private val env: ServerEnv,
    private val runner: Runner,
    private val auth: Auth,
    private val password: String?,
    private val version: String,
) {
    fun start(port: Int) {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (e: Exception) {
                env.log("web", "${ex.requestMethod} ${ex.requestURI.path}: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { send(ex, 500, page("Something went wrong", """<div class="card err">${h(e.message ?: e.javaClass.simpleName)}</div><p><a href="/">Back</a></p>""")) }
            } finally {
                ex.close()
            }
        }
        server.start()
        env.log("web", "listening on port $port, reachable as ${env.baseUrl}")
    }

    // ------------------------------------------------------------------ plumbing

    private class Req(val ex: HttpExchange, val method: String, val path: List<String>, val query: Map<String, String>, val form: Map<String, String>)

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path.trim('/').split('/').filter { it.isNotEmpty() }.map { decode(it) }
        // Health checks (Docker, a reverse proxy) need no password.
        if (path == listOf("health")) return send(ex, 200, "ok", "text/plain")
        if (!authorized(ex)) {
            ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Songport\", charset=\"UTF-8\"")
            return send(ex, 401, "Password required", "text/plain")
        }
        val method = ex.requestMethod.uppercase()
        // A form posted from another site is refused: every change comes from these pages.
        if (method == "POST" && !sameOrigin(ex)) return send(ex, 403, "Cross-site request refused", "text/plain")
        val form = if (method == "POST") parseForm(String(ex.requestBody.readBytes(), StandardCharsets.UTF_8)) else emptyMap()
        val r = Req(ex, method, path, parseForm(ex.requestURI.rawQuery ?: ""), form)
        when {
            path.isEmpty() -> home(r)
            path == listOf("style.css") -> send(ex, 200, Html.CSS, "text/css")
            path == listOf("log") -> log(r)
            path == listOf("callback") -> callback(r)
            path[0] == "connectors" -> connectors(r, path.drop(1))
            path[0] == "syncs" -> syncs(r, path.drop(1))
            else -> send(ex, 404, page("Not found", "<p><a href=\"/\">Back</a></p>"))
        }
    }

    private fun authorized(ex: HttpExchange): Boolean {
        val pw = password ?: return true
        val header = ex.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("Basic ")) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8) }.getOrNull() ?: return false
        val given = decoded.substringAfter(':', "")
        return MessageDigest.isEqual(given.toByteArray(), pw.toByteArray())
    }

    private fun sameOrigin(ex: HttpExchange): Boolean {
        val origin = ex.requestHeaders.getFirst("Origin") ?: return true
        val host = ex.requestHeaders.getFirst("Host") ?: return false
        return origin.substringAfter("://").trimEnd('/') == host
    }

    private fun decode(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8)

    private fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }

    private fun send(ex: HttpExchange, code: Int, body: String, type: String = "text/html") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "$type; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.responseHeaders.set("X-Frame-Options", "DENY")
        ex.responseHeaders.set("Referrer-Policy", "no-referrer")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.set("Location", to)
        ex.sendResponseHeaders(303, -1)
    }

    private fun page(title: String, body: String, refresh: Int? = null) = Html.page(title, body, version, refresh)

    private fun notice(r: Req): String =
        (r.query["ok"]?.let { """<div class="card ok">${h(it)}</div>""" } ?: "") +
            (r.query["err"]?.let { """<div class="card err">${h(it)}</div>""" } ?: "")

    private fun q(s: String) = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

    // ------------------------------------------------------------------ home: the syncs

    private fun home(r: Req) {
        val jobs = env.store.data.jobs
        val running = SyncState.running.value
        val connected = Providers.connectors().filter { it.isConnected(env) || !it.requiresAuth }
        val body = StringBuilder(notice(r))
        if (connected.size < 1) {
            body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        }
        body.append("""<p class="actions"><a class="button" href="/syncs/new">New sync</a></p>""")
        if (jobs.isEmpty()) body.append("""<p class="muted">No syncs yet.</p>""")
        for (job in jobs) body.append(jobCard(job, running.containsKey(job.id)))
        send(r.ex, 200, page("Syncs", body.toString(), refresh = if (running.isNotEmpty()) 5 else null))
    }

    private fun label(id: String): String = Providers.byId(id)?.label(env) ?: id

    private fun jobCard(job: SyncJob, isRunning: Boolean): String {
        val last = job.lastReportId?.let { env.store.report(it) }
        val status = when {
            isRunning -> progressText(job.id)
            last == null -> """<span class="muted">Never run</span>"""
            last.error != null -> """<span class="err">${h(last.error)}</span>"""
            else -> """<span class="ok">+${last.added} −${last.removed}</span>""" + (if (last.unmatched.isNotEmpty()) """ <span class="muted">· ${last.unmatched.size} not found</span>""" else "")
        }
        val next = Runner.nextRun(job)?.let { " · next ${Html.date(it)}" } ?: ""
        return """<div class="card"><div class="row"><div><a href="/syncs/${q(job.id)}"><b>${h(job.name)}</b></a><br>
<span class="muted">${h(label(job.source.provider))}: ${h(job.source.playlistName)} → ${h(label(job.target.provider))}: ${h(job.target.playlistName.ifBlank { "new playlist" })}</span><br>
<span class="badge">${h(scheduleName(job.schedule))}</span>${if (job.mirrorRemovals) " <span class=\"badge\">mirror</span>" else ""}${if (!job.enabled) " <span class=\"badge\">off</span>" else ""}
<span class="muted">Last run ${Html.date(job.lastRunEpoch)}$next</span></div>
<div class="actions">${if (isRunning) Html.postButton("/syncs/${q(job.id)}/stop", "Stop", "secondary") else Html.postButton("/syncs/${q(job.id)}/run", "Run now")}</div></div>
<div>$status</div></div>"""
    }

    private fun progressText(jobId: String): String {
        val p = SyncState.running.value[jobId] ?: return """<span class="muted">Starting…</span>"""
        val step = p.step.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        val count = if (p.total > 0) " ${p.done}/${p.total}" else ""
        val paused = if (SyncState.isPaused(jobId)) " (paused)" else ""
        val bar = p.percent?.let { """<progress max="100" value="$it"></progress>""" } ?: ""
        return """<span>${h(step)}$count$paused${p.label?.let { " · " + h(it) } ?: ""}</span>$bar"""
    }

    private fun scheduleName(s: Schedule) = when (s) {
        Schedule.MANUAL -> "manual"
        Schedule.HOURLY -> "hourly"
        Schedule.EVERY_6H -> "every 6 h"
        Schedule.DAILY -> "daily"
        Schedule.WEEKLY -> "weekly"
    }

    // ------------------------------------------------------------------ services

    private fun connectors(r: Req, rest: List<String>) {
        val store = env.store
        if (rest.isEmpty()) return connectorList(r)
        if (rest == listOf("add") && r.method == "POST") {
            val service = r.form["service"].orEmpty()
            if (Providers.byId(service) != null && service !in store.data.settings.connectors) {
                store.updateSettings { it.copy(connectors = it.connectors + service, connectorsInit = true) }
            }
            return redirect(r.ex, "/connectors/${q(service)}")
        }
        val provider = Providers.byId(rest[0]) ?: return send(r.ex, 404, page("Unknown service", "<p><a href=\"/connectors\">Back</a></p>"))
        val back = "/connectors/${q(provider.id)}"
        when (rest.getOrNull(1)) {
            null -> connectorPage(r, provider)
            "settings" -> {
                val id = provider.serviceId
                store.updateSettings { s ->
                    var n = s
                    r.form["clientId"]?.let { v -> n = n.copy(clientIds = if (v.isBlank()) n.clientIds - id else n.clientIds + (id to v.trim())) }
                    r.form["clientSecret"]?.let { v -> n = n.copy(clientSecrets = if (v.isBlank()) n.clientSecrets - id else n.clientSecrets + (id to v.trim())) }
                    r.form["redirect"]?.let { v -> n = n.copy(deezerRedirectUrl = v.trim()) }
                    n
                }
                redirect(r.ex, "$back?ok=${q("Saved.")}")
            }
            "connect" -> {
                if (provider is CredentialsProvider) {
                    val result = runCatching { runBlocking { provider.login(env, r.form["url"].orEmpty(), r.form["user"].orEmpty(), r.form["secret"].orEmpty()) } }
                    result.exceptionOrNull()?.let { return redirect(r.ex, "$back?err=${q(it.message ?: it.javaClass.simpleName)}") }
                    env.log("auth", "${provider.id} connected")
                    return redirect(r.ex, "$back?ok=${q("Connected.")}")
                }
                val url = runCatching { auth.begin(provider) }.getOrElse { return redirect(r.ex, "$back?err=${q(it.message ?: "")}") }
                    ?: return redirect(r.ex, "$back?err=${q("This service can't sign in from the server.")}")
                redirect(r.ex, url)
            }
            "disconnect" -> { provider.disconnect(env); redirect(r.ex, "$back?ok=${q("Disconnected.")}") }
            "remove" -> {
                store.updateSettings { it.copy(connectors = it.connectors - provider.id) }
                redirect(r.ex, "/connectors")
            }
            else -> send(r.ex, 404, page("Not found", ""))
        }
    }

    private fun connectorList(r: Req) {
        val added = Providers.connectors()
        val body = StringBuilder(notice(r))
        for (p in added) {
            val state = when {
                !p.requiresAuth -> """<span class="ok">ready</span>"""
                p.isConnected(env) -> """<span class="ok">connected</span>""" + (p.accountName(env)?.let { " · " + h(it) } ?: "")
                !p.isConfigured(env) -> """<span class="muted">needs your key</span>"""
                else -> """<span class="muted">not connected</span>"""
            }
            body.append("""<div class="card row"><div><a href="/connectors/${q(p.id)}"><b>${h(p.label(env))}</b></a> $state</div></div>""")
        }
        val available = Providers.services().filter { s -> added.none { it.id == s.id } }
        if (available.isNotEmpty()) {
            body.append("""<h2>Add a service</h2><form method="post" action="/connectors/add" class="card"><select name="service">""")
            available.forEach { body.append("""<option value="${h(it.id)}">${h(it.displayName)}</option>""") }
            body.append("""</select><p><button>Add</button></p></form>""")
        }
        send(r.ex, 200, page("Services", body.toString()))
    }

    private fun connectorPage(r: Req, p: MusicProvider) {
        val body = StringBuilder(notice(r))
        body.append("""<div class="card"><p>${h(env.text(p.noteName))}</p>""")
        if (p.beta) body.append("""<p><span class="badge">beta</span></p>""")
        body.append("</div>")
        when {
            !p.requiresAuth -> body.append(
                """<div class="card">Put CSV playlist files in <code>${h(java.io.File(env.dataDir, "csv").path)}</code> inside the data volume: they show up as playlists here.</div>""",
            )
            p.isConnected(env) -> body.append(
                """<div class="card row"><div><span class="ok">Connected</span>${p.accountName(env)?.let { " as <b>" + h(it) + "</b>" } ?: ""}</div>""" +
                    Html.postButton("/connectors/${q(p.id)}/disconnect", "Disconnect", "secondary") + "</div>",
            )
            p is CredentialsProvider -> {
                val f = p.loginForm
                body.append("""<form method="post" action="/connectors/${q(p.id)}/connect" class="card">""")
                body.append("""<p class="muted">${h(env.text(f.hintName))}</p>""")
                if (f.needsUrl) body.append("""<label>${h(env.text("login_url"))}</label><input type="url" name="url" required>""")
                if (f.needsUser) body.append("""<label>${h(env.text("login_user"))}</label><input type="text" name="user" autocomplete="off" required>""")
                if (f.needsSecret) body.append(
                    """<label>${h(env.text(f.secretLabelName))}</label><input type="password" name="secret" autocomplete="new-password"${if (f.secretOptional) "" else " required"}>""",
                )
                body.append("""<p><button>${h(env.text("login_submit"))}</button></p></form>""")
            }
            p is OAuthProvider -> body.append(oauthSetup(p))
            else -> body.append("""<div class="card muted">This service can't sign in from the server.</div>""")
        }
        body.append("""<p>${Html.postButton("/connectors/${q(p.id)}/remove", "Remove from the list", "danger", confirm = "Remove this service from the list?")}</p>""")
        send(r.ex, 200, page(p.label(env), body.toString()))
    }

    /** Your own key for an OAuth service: the setup steps of the app, with this server's redirect address. */
    private fun oauthSetup(p: OAuthProvider): String {
        val guide = p.setupGuide
        val sb = StringBuilder("""<form method="post" action="/connectors/${q(p.id)}/settings" class="card">""")
        if (guide != null) {
            sb.append("""<p>${h(env.text(guide.whyName))}</p><ol>""")
            env.textArray(guide.stepsArrayName).forEachIndexed { i, step ->
                val link = guide.stepUrls.getOrNull(i)?.let { """ <a href="${h(it)}" target="_blank" rel="noopener">open</a>""" } ?: ""
                sb.append("<li>${h(step)}$link</li>")
            }
            sb.append("</ol>")
        }
        val redirect = if (guide?.needsRedirectUrl == true) env.store.data.settings.deezerRedirectUrl.ifBlank { env.redirectUri } else env.redirectUri
        sb.append("""<p>Redirect address for this server: <code>${h(redirect)}</code></p>""")
        val label = guide?.let { env.text(it.fieldLabelName) } ?: "Client ID"
        sb.append("""<label>${h(label)}</label><input type="text" name="clientId" value="${h(p.ownClientId(env).orEmpty())}" autocomplete="off">""")
        if (guide?.needsSecret == true) {
            sb.append("""<label>Client secret</label><input type="password" name="clientSecret" placeholder="${if (p.ownClientSecret(env) != null) "saved" else ""}" autocomplete="new-password">""")
        }
        if (guide?.needsRedirectUrl == true) {
            sb.append("""<label>Redirect URL (https)</label><input type="url" name="redirect" value="${h(env.store.data.settings.deezerRedirectUrl)}" placeholder="${h(env.redirectUri)}">""")
        }
        sb.append("""<p><button class="secondary">Save</button></p></form>""")
        sb.append(
            if (p.isConfigured(env)) """<div class="card">${Html.postButton("/connectors/${q(p.id)}/connect", "Connect")} <span class="muted">You'll sign in on ${h(p.authDomain ?: p.displayName)} and come back here.</span></div>"""
            else """<div class="card muted">Save your key first, then connect.</div>""",
        )
        return sb.toString()
    }

    private fun callback(r: Req) {
        // Deezer puts the token in the fragment, which never reaches a server: the page moves it into the query.
        if (r.query.isEmpty()) {
            return send(
                r.ex, 200,
                page("Signing in…", """<p class="muted">One moment…</p><script>
if (location.hash.length > 1) location.replace('/callback?' + location.hash.substring(1)); else location.replace('/connectors?err=' + encodeURIComponent('Nothing came back from the service.'));
</script>"""),
            )
        }
        val result = runCatching { runBlocking { auth.complete(r.query) } }
        result.onSuccess { p ->
            env.log("auth", "${p.id} connected")
            redirect(r.ex, "/connectors/${q(p.id)}?ok=${q("Connected.")}")
        }.onFailure { e ->
            env.log("auth", "sign-in failed: ${e.message}")
            redirect(r.ex, "/connectors?err=${q(e.message ?: e.javaClass.simpleName)}")
        }
    }

    // ------------------------------------------------------------------ syncs

    private fun syncs(r: Req, rest: List<String>) {
        when {
            rest == listOf("new") -> newSync(r)
            rest.isEmpty() && r.method == "POST" -> createSync(r)
            rest.isEmpty() -> redirect(r.ex, "/")
            else -> {
                val job = env.store.job(rest[0]) ?: return send(r.ex, 404, page("Unknown sync", "<p><a href=\"/\">Back</a></p>"))
                val back = "/syncs/${q(job.id)}"
                if (r.method != "POST") return syncPage(r, job)
                when (rest.getOrNull(1)) {
                    "run" -> { runner.start(job); redirect(r.ex, back) }
                    "stop" -> { SyncState.stop(job.id); redirect(r.ex, back) }
                    "pause" -> { SyncState.pause(job.id); redirect(r.ex, back) }
                    "resume" -> { SyncState.resume(job.id); redirect(r.ex, back) }
                    "toggle" -> { env.store.upsertJob(job.copy(enabled = !job.enabled)); redirect(r.ex, back) }
                    "schedule" -> {
                        val s = runCatching { Schedule.valueOf(r.form["schedule"].orEmpty()) }.getOrDefault(job.schedule)
                        env.store.upsertJob(job.copy(schedule = s, mirrorRemovals = r.form["mirror"] == "on"))
                        redirect(r.ex, "$back?ok=${q("Saved.")}")
                    }
                    "delete" -> { env.store.deleteJob(job.id); redirect(r.ex, "/") }
                    else -> send(r.ex, 404, page("Not found", ""))
                }
            }
        }
    }

    private fun usable(p: MusicProvider) = !p.requiresAuth || p.isConnected(env)

    private fun newSync(r: Req) {
        val list = Providers.connectors().filter(::usable)
        val src = r.query["src"]?.let { Providers.byId(it) }
        val dst = r.query["dst"]?.let { Providers.byId(it) }
        val body = StringBuilder(notice(r))
        if (list.isEmpty()) {
            body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
            return send(r.ex, 200, page("New sync", body.toString()))
        }
        if (src == null || dst == null) {
            fun options(writable: Boolean) = list.filter { !writable || it.canWrite }.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
            body.append(
                """<form method="get" action="/syncs/new" class="card"><label>From</label><select name="src">${options(false)}</select>
<label>To</label><select name="dst">${options(true)}</select><p><button>Next</button></p></form>""",
            )
            return send(r.ex, 200, page("New sync", body.toString()))
        }
        val srcLists = runCatching { runBlocking { src.libraryEntries(env, false) + src.playlists(env) } }
        val dstLists = runCatching { runBlocking { dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe } } }
        srcLists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(src.label(env))}: ${h(it.message)}</div>""") }
        dstLists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(dst.label(env))}: ${h(it.message)}</div>""") }
        fun opts(ps: List<Playlist>) = ps.joinToString("") { p ->
            """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>"""
        }
        val schedules = Schedule.values().joinToString("") { """<option value="${it.name}"${if (it == Schedule.DAILY) " selected" else ""}>${h(scheduleName(it))}</option>""" }
        body.append(
            """<form method="post" action="/syncs" class="card">
<input type="hidden" name="src" value="${h(src.id)}"><input type="hidden" name="dst" value="${h(dst.id)}">
<label>From ${h(src.label(env))}</label><select name="srcPlaylist" required>${opts(srcLists.getOrDefault(emptyList()))}</select>
<label>To ${h(dst.label(env))}</label><select name="dstPlaylist">${if (dst.canCreatePlaylists) "<option value=\"\">A new playlist with the same name</option>" else ""}${opts(dstLists.getOrDefault(emptyList()))}</select>
<label>Name of the sync (optional)</label><input type="text" name="name">
<label>Schedule</label><select name="schedule">$schedules</select>
<label class="check"><input type="checkbox" name="mirror"> Mirror removals (tracks removed from the source are removed from the destination)</label>
<p><button>Create</button> <a class="button secondary" href="/syncs/new">Back</a></p></form>""",
        )
        send(r.ex, 200, page("New sync", body.toString()))
    }

    private fun createSync(r: Req) {
        val src = Providers.byId(r.form["src"].orEmpty()) ?: return redirect(r.ex, "/syncs/new?err=${q("Pick a source.")}")
        val dst = Providers.byId(r.form["dst"].orEmpty()) ?: return redirect(r.ex, "/syncs/new?err=${q("Pick a destination.")}")
        val srcId = r.form["srcPlaylist"].orEmpty().ifBlank { return redirect(r.ex, "/syncs/new?src=${q(src.id)}&dst=${q(dst.id)}&err=${q("Pick a playlist.")}") }
        val dstId = r.form["dstPlaylist"].orEmpty().ifBlank { null }
        // Names as the services list them; a library entry (liked songs...) keeps its label.
        val srcName = runCatching { runBlocking { (src.libraryEntries(env, false) + src.playlists(env)).firstOrNull { it.id == srcId }?.name } }.getOrNull() ?: srcId
        val dstName = dstId?.let { id -> runCatching { runBlocking { (dst.libraryEntries(env, true) + dst.playlists(env)).firstOrNull { it.id == id }?.name } }.getOrNull() } ?: srcName
        val job = SyncJob(
            id = UUID.randomUUID().toString(),
            name = r.form["name"].orEmpty().ifBlank { srcName },
            source = PlaylistRef(src.id, srcId, srcName),
            target = PlaylistRef(dst.id, dstId, dstName),
            schedule = runCatching { Schedule.valueOf(r.form["schedule"].orEmpty()) }.getOrDefault(Schedule.MANUAL),
            mirrorRemovals = r.form["mirror"] == "on",
        )
        env.store.upsertJob(job)
        redirect(r.ex, "/syncs/${q(job.id)}?ok=${q("Sync created. Run it now, or let the schedule start it.")}")
    }

    private fun syncPage(r: Req, job: SyncJob) {
        val running = runner.isRunning(job.id)
        val body = StringBuilder(notice(r))
        body.append(jobCard(job, running))
        val id = q(job.id)
        body.append("""<div class="card actions">""")
        if (running) {
            body.append(if (SyncState.isPaused(job.id)) Html.postButton("/syncs/$id/resume", "Resume") else Html.postButton("/syncs/$id/pause", "Pause", "secondary"))
        }
        body.append(Html.postButton("/syncs/$id/toggle", if (job.enabled) "Turn off" else "Turn on", "secondary"))
        body.append(Html.postButton("/syncs/$id/delete", "Delete", "danger", confirm = "Delete this sync? The playlists stay as they are."))
        body.append("</div>")
        val schedules = Schedule.values().joinToString("") { """<option value="${it.name}"${if (it == job.schedule) " selected" else ""}>${h(scheduleName(it))}</option>""" }
        body.append(
            """<form method="post" action="/syncs/$id/schedule" class="card"><label>Schedule</label><select name="schedule">$schedules</select>
<label class="check"><input type="checkbox" name="mirror"${if (job.mirrorRemovals) " checked" else ""}> Mirror removals</label><p><button class="secondary">Save</button></p></form>""",
        )
        val reports = env.store.data.reports.filter { it.jobId == job.id }.take(10)
        if (reports.isNotEmpty()) body.append("<h2>Last runs</h2>")
        for (rep in reports) {
            body.append("""<div class="card"><div class="row"><b>${Html.date(rep.startedEpoch)}</b><span class="muted">${rep.durationMs / 1000} s · source ${rep.sourceCount} · destination ${rep.targetCount}</span></div>""")
            body.append(if (rep.error != null) """<p class="err">${h(rep.error)}</p>""" else """<p class="ok">+${rep.added} added · −${rep.removed} removed</p>""")
            rep.notes.forEach { body.append("""<p class="muted">${h(it)}</p>""") }
            if (rep.unmatched.isNotEmpty()) {
                body.append("""<details><summary>${rep.unmatched.size} not found</summary><ul>""")
                rep.unmatchedTracks.ifEmpty { null }?.forEach { t -> body.append("<li>${h(t.artistLine)} — ${h(t.title)}</li>") }
                    ?: rep.unmatched.forEach { body.append("<li>${h(it)}</li>") }
                body.append("</ul></details>")
            }
            body.append("</div>")
        }
        send(r.ex, 200, page(job.name, body.toString(), refresh = if (running) 5 else null))
    }

    // ------------------------------------------------------------------ log

    private fun log(r: Req) {
        val lines = Log.recent().takeLast(500).reversed()
        send(r.ex, 200, page("Log", """<p class="muted">Newest first. The full log is <code>songport.log</code> in the data folder, and <code>docker logs</code> shows it too.</p><pre>${h(lines.joinToString("\n"))}</pre>"""))
    }
}

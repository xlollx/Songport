package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.ServiceMarks
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.ShazamLibrary
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Shazam's mark on a Shazam sync's card, as the radio marks a radio sync (Simple Icons, CC0). */
internal const val SHAZAM_ICON = """<svg class="kind" viewBox="0 0 24 24" width="18" height="18" aria-label="Shazam sync" role="img"><path fill="#0088FF" d="${ServiceMarks.SHAZAM}"/></svg>"""

/** Shazam's playlist on a connected account, or why it was not found. */
private class ShazamFind(val host: MusicProvider?, val playlist: Playlist?, val hosts: List<MusicProvider>, val errors: List<String>)

/**
 * Looks for the playlist the Shazam app fills ("My Shazam Tracks") on every connected account that
 * can hold it. On the server that is Spotify, with its key or through the web player; Apple Music
 * signs in only in the app.
 */
private fun Web.findShazam(): ShazamFind {
    val hosts = Providers.connectors().filter { ShazamLibrary.canHost(it.serviceId) && it.isConnected(env) }
    val errors = ArrayList<String>()
    for (p in hosts) {
        val lists = runCatching { runBlocking { p.playlists(env) } }
        lists.exceptionOrNull()?.let { errors += "${p.label(env)}: ${it.message}" }
        ShazamLibrary.find(lists.getOrDefault(emptyList()))?.let { return ShazamFind(p, it, hosts, errors) }
    }
    return ShazamFind(null, null, hosts, errors)
}

/**
 * New Shazam sync: Shazam's playlist found on a connected Spotify (or how to have Shazam keep one),
 * then the service and the playlist to bring the Shazams to. Hourly unless changed.
 */
internal fun Web.shazamNew(r: Web.Req) {
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p>$SHAZAM_ICON Every song you Shazam, in a playlist of your choice on any service. Shazam has no way in for other apps, but it keeps your Shazams in a playlist on Spotify or Apple Music once connected to it: Trackhop syncs that playlist.</p></div>""")
    val found = findShazam()
    found.errors.forEach { body.append("""<div class="card err">${h(it)}</div>""") }
    val src = found.host
    val shazam = found.playlist
    if (src == null || shazam == null) {
        body.append(
            """<div class="card"><h3>Turn on Shazam's playlist</h3>
<p>In the Shazam app, open <b>Settings › Connect to Spotify</b>. From then on Shazam adds every song you Shazam to a playlist called “${h(ShazamLibrary.NAME)}” on your Spotify. Shazam a song, then check again.</p>
${if (found.hosts.isEmpty()) """<p>Then connect the same Spotify account here: <a href="/connectors">Services</a>.</p>""" else ""}
<p class="muted">Apple Music works too in the Trackhop app; the server reads Spotify.</p>
<p><a class="button" href="/syncs/shazam">Check again</a></p></div>""",
        )
        return send(r.ex, 200, page("New Shazam sync", body.toString()))
    }
    val count = if (shazam.trackCount >= 0) " (${shazam.trackCount} ${if (shazam.trackCount == 1) "track" else "tracks"})" else ""
    body.append("""<div class="card"><p>$SHAZAM_ICON Your Shazams: <b>${h(shazam.name)}</b> on ${h(src.label(env))}$count</p></div>""")
    val dst = r.query["dst"]?.let { Providers.byId(it) }?.takeIf { it.canWrite }
    if (dst == null) {
        val options = Providers.connectors().filter { (!it.requiresAuth || it.isConnected(env)) && it.canWrite }
            .joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/syncs/shazam" class="card"><label>Bring them to</label><select name="dst">$options</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page("New Shazam sync", body.toString()))
    }
    val lists = runCatching { runBlocking { dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe } } }
    lists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(dst.label(env))}: ${h(it.message)}</div>""") }
    fun opts(ps: List<Playlist>) = ps.filterNot { it.id == shazam.id && dst.id == src.id }
        .joinToString("") { """<option value="${h(it.id)}">${h(it.name)}${if (it.trackCount >= 0) " (${it.trackCount})" else ""}</option>""" }
    val schedules = Schedule.values().joinToString("") { """<option value="${it.name}"${if (it == Schedule.HOURLY) " selected" else ""}>${h(scheduleName(it))}</option>""" }
    body.append(
        """<form method="post" action="/syncs/shazam" class="card">
<input type="hidden" name="src" value="${h(src.id)}"><input type="hidden" name="srcPlaylist" value="${h(shazam.id)}"><input type="hidden" name="dst" value="${h(dst.id)}">
<label>To ${h(dst.label(env))}</label><select name="dstPlaylist">${if (dst.canCreatePlaylists) """<option value="">A new playlist: “${h(shazam.name)}”</option>""" else ""}${opts(lists.getOrDefault(emptyList()))}</select>
<label>Name of the sync (optional)</label><input type="text" name="name" placeholder="${h(shazam.name)}">
<label>Schedule</label><select name="schedule">$schedules</select>
<label class="check"><input type="checkbox" name="mirror"> Mirror removals (a song removed from your Shazams is removed from the destination)</label>
<p><button>Create</button> <a class="button secondary" href="/syncs/shazam">Back</a></p></form>""",
    )
    send(r.ex, 200, page("New Shazam sync", body.toString()))
}

internal fun Web.shazamCreate(r: Web.Req) {
    val src = Providers.byId(r.form["src"].orEmpty())?.takeIf { ShazamLibrary.canHost(it.serviceId) && it.isConnected(env) }
        ?: return redirect(r.ex, "/syncs/shazam?err=${q("Connect the Spotify account Shazam fills first.")}")
    val dst = Providers.byId(r.form["dst"].orEmpty())?.takeIf { it.canWrite }
        ?: return redirect(r.ex, "/syncs/shazam?err=${q("Pick where to bring your Shazams.")}")
    val back = "/syncs/shazam?dst=${q(dst.id)}"
    // Only Shazam's own playlist, as the service lists it: the id is never taken as typed.
    val shazam = runCatching { runBlocking { ShazamLibrary.find(src.playlists(env)) } }
        .getOrElse { return redirect(r.ex, "$back&err=${q("${src.label(env)}: ${it.message}")}") }
        ?.takeIf { it.id == r.form["srcPlaylist"] }
        ?: return redirect(r.ex, "/syncs/shazam?err=${q("Shazam's playlist is not on ${src.label(env)} any more.")}")
    val dstId = r.form["dstPlaylist"].orEmpty().ifBlank { null }
    if (dstId == null && !dst.canCreatePlaylists) return redirect(r.ex, "$back&err=${q("Pick a playlist.")}")
    if (dstId == shazam.id && dst.id == src.id) return redirect(r.ex, "$back&err=${q("Pick another playlist.")}")
    val dstName = dstId?.let { id ->
        runCatching { runBlocking { (dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe }).firstOrNull { it.id == id }?.name } }
            .getOrElse { return redirect(r.ex, "$back&err=${q("${dst.label(env)}: ${it.message}")}") }
            ?: return redirect(r.ex, "$back&err=${q("Pick a playlist.")}")
    } ?: shazam.name
    val job = SyncJob(
        id = UUID.randomUUID().toString(),
        name = r.form["name"].orEmpty().trim().ifBlank { shazam.name },
        source = PlaylistRef(src.id, shazam.id, shazam.name),
        target = PlaylistRef(dst.id, dstId, dstName),
        schedule = runCatching { Schedule.valueOf(r.form["schedule"].orEmpty()) }.getOrDefault(Schedule.HOURLY),
        mirrorRemovals = r.form["mirror"] == "on",
        shazam = true,
    )
    env.store.upsertJob(job)
    env.log("web", "new Shazam sync: ${src.label(env)}: ${shazam.name} → ${dst.label(env)}: $dstName")
    redirect(r.ex, "/syncs/${q(job.id)}?ok=${q("Created. Every song you Shazam reaches $dstName at the next sync.")}")
}

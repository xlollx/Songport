package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.RadioTitles
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** The radio's mark on a radio sync's card, as the AI's sparkles mark an AI playlist. */
internal const val RADIO_ICON = """<svg class="kind" viewBox="0 0 24 24" width="18" height="18" aria-label="Radio sync" role="img"><path fill="currentColor" d="M3.24 6.15C2.51 6.43 2 7.17 2 8v12c0 1.1.89 2 2 2h16c1.11 0 2-.9 2-2V8c0-1.11-.89-2-2-2H8.3l8.26-3.34L15.88 1 3.24 6.15zM7 20c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm13-8h-2v-2h-2v2H4V8h16v4z"/></svg>"""

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
private fun minutes(v: String?): Int? = v?.trim()?.let { Regex("""^(\d{1,2}):(\d{2})$""").find(it) }?.let { m ->
    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
    if (h in 0..23 && mi in 0..59) h * 60 + mi else null
}

/** The days and hours fields, filled from [r] (a new sync: every day, 08:00 to 10:00). */
internal fun radioWindowFields(r: RadioSource?): String {
    val days = r?.days ?: (1..7).toList()
    val boxes = DAY_NAMES.mapIndexed { i, n -> """<label class="check inline"><input type="checkbox" name="day${i + 1}"${if (i + 1 in days) " checked" else ""}> $n</label>""" }.joinToString(" ")
    return """<label>Days</label><div>$boxes</div>
<label>From</label><input type="time" name="start" value="${hm(r?.startMinute ?: 8 * 60)}" required>
<label>To</label><input type="time" name="end" value="${hm(r?.endMinute ?: 10 * 60)}" required>
<p class="muted">The same time twice listens all day. An end before the start runs past midnight. Times are this computer's or server's.</p>"""
}

/** The window from a submitted form, or an error message. */
internal fun radioWindow(form: Map<String, String>, base: RadioSource): Pair<RadioSource?, String?> {
    val days = (1..7).filter { form["day$it"] == "on" }
    if (days.isEmpty()) return null to "Pick at least one day."
    val start = minutes(form["start"]) ?: return null to "The start time is HH:MM."
    val end = minutes(form["end"]) ?: return null to "The end time is HH:MM."
    return base.copy(days = days, startMinute = start, endMinute = end) to null
}

/**
 * New radio sync, in three steps on one page: find the station (by name in the radio directory, or
 * its stream's address), pick the service to save the songs to, then the playlist and the hours.
 */
internal fun Web.radioNew(r: Web.Req) {
    val body = StringBuilder(notice(r))
    val station = r.query["station"].orEmpty()
    val stream = r.query["stream"].orEmpty()
    val homepage = r.query["homepage"].orEmpty()
    body.append("""<div class="card"><p>$RADIO_ICON Trackhop listens to a radio in the hours you choose and saves the songs it plays to a playlist. It reads only the song titles the station sends with its stream; the audio is never kept. Most internet radios and the streams of FM stations send them.</p></div>""")
    if (stream.isEmpty()) {
        val q = r.query["q"].orEmpty()
        body.append("""<form method="get" action="/syncs/radio" class="card"><label>Find a station</label><input type="search" name="q" value="${h(q)}" placeholder="Radio Deejay, KEXP, BBC Radio 1…" required><p><button>Search</button></p></form>""")
        if (q.isNotBlank()) {
            val found = runCatching { RadioBrowser.search(q) }
            found.exceptionOrNull()?.let { body.append("""<div class="card err">${h(it.message)}</div>""") }
            val list = found.getOrDefault(emptyList())
            if (found.isSuccess && list.isEmpty()) body.append("""<p class="muted">No station by that name. Try another spelling, or paste its stream below.</p>""")
            for (s in list) {
                val link = "/syncs/radio?station=${q(s.name)}&stream=${q(s.stream)}&homepage=${q(s.homepage)}"
                body.append("""<div class="card"><div class="row"><div><b>${h(s.name)}</b><br><span class="muted">${h(listOf(s.country, s.codec, if (s.bitrate > 0) "${s.bitrate} kbps" else "").filter { it.isNotBlank() }.joinToString(" · "))}</span></div><div class="actions"><a class="button" href="$link">Choose</a></div></div></div>""")
            }
        }
        body.append("""<form method="get" action="/syncs/radio" class="card"><label>Or the stream's address</label><input type="url" name="stream" placeholder="https://…/stream.mp3" required><label>Station name</label><input type="text" name="station" required><p><button class="secondary">Use this stream</button></p></form>""")
        return send(r.ex, 200, page("New radio sync", body.toString()))
    }
    val connected = Providers.connectors().filter { (!it.requiresAuth || it.isConnected(env)) && it.canWrite }
    val dst = r.query["dst"]?.let { Providers.byId(it) }
    val keep = """<input type="hidden" name="station" value="${h(station)}"><input type="hidden" name="stream" value="${h(stream)}"><input type="hidden" name="homepage" value="${h(homepage)}">"""
    body.append("""<div class="card"><p>$RADIO_ICON <b>${h(station)}</b><br><span class="muted">${h(stream)}</span></p><a href="/syncs/radio">Another station</a></div>""")
    if (dst == null) {
        val options = connected.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/syncs/radio" class="card">$keep<label>Save the songs to</label><select name="dst">$options</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page("New radio sync", body.toString()))
    }
    val lists = runCatching { runBlocking { dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe } } }
    lists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(dst.label(env))}: ${h(it.message)}</div>""") }
    fun opts(ps: List<Playlist>) = ps.joinToString("") { """<option value="${h(it.id)}">${h(it.name)}</option>""" }
    body.append(
        """<form method="post" action="/syncs/radio" class="card">$keep<input type="hidden" name="dst" value="${h(dst.id)}">
<label>Playlist on ${h(dst.label(env))}</label><select name="dstPlaylist">${if (dst.canCreatePlaylists) """<option value="">A new playlist: "$station radio"</option>""" else ""}${opts(lists.getOrDefault(emptyList()))}</select>
${radioWindowFields(null)}
<p><button>Start listening</button> <a class="button secondary" href="/syncs/radio">Back</a></p></form>""",
    )
    send(r.ex, 200, page("New radio sync", body.toString()))
}

internal fun Web.radioCreate(r: Web.Req) {
    val station = r.form["station"].orEmpty().trim()
    val stream = r.form["stream"].orEmpty().trim()
    val dst = Providers.byId(r.form["dst"].orEmpty())
    val back = "/syncs/radio?station=${q(station)}&stream=${q(stream)}&homepage=${q(r.form["homepage"].orEmpty())}" + (dst?.let { "&dst=${q(it.id)}" } ?: "")
    if (station.isEmpty() || !(stream.startsWith("http://") || stream.startsWith("https://"))) return redirect(r.ex, "/syncs/radio?err=${q("Pick a station.")}")
    if (dst == null || !dst.canWrite) return redirect(r.ex, "$back&err=${q("Pick where to save the songs.")}")
    val (radio, problem) = radioWindow(r.form, RadioSource(station, stream, r.form["homepage"]?.trim()?.takeIf { it.isNotEmpty() }))
    if (radio == null) return redirect(r.ex, "$back&err=${q(problem!!)}")
    val dstId = r.form["dstPlaylist"].orEmpty().ifBlank { null }
    val dstName = dstId?.let { id ->
        runCatching { runBlocking { (dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe }).firstOrNull { it.id == id }?.name } }.getOrNull()
            ?: return redirect(r.ex, "$back&err=${q("Pick a playlist.")}")
    } ?: "$station radio"
    if (dstId == null && !dst.canCreatePlaylists) return redirect(r.ex, "$back&err=${q("Pick a playlist.")}")
    // The songs heard are kept in a File playlist of their own: the sync's source.
    val log = runBlocking { LocalFilesProvider.createPlaylist(env, "Radio - $station", "") }
    val job = SyncJob(
        id = UUID.randomUUID().toString(),
        name = "$station radio",
        source = PlaylistRef(LocalFilesProvider.id, log.id, log.name),
        target = PlaylistRef(dst.id, dstId, dstName),
        schedule = Schedule.MANUAL,
        radio = radio,
    )
    env.store.upsertJob(job)
    runner.radio.tick(java.time.LocalDateTime.now())
    env.log("radio", "new radio sync: $station → ${dst.label(env)}: $dstName (${RadioTitles.describe(radio)})")
    redirect(r.ex, "/syncs/${q(job.id)}?ok=${q("Created. Trackhop listens ${RadioTitles.describe(radio)} and adds the songs to $dstName.")}")
}

/** On a radio sync's page: what it hears now, and its days and hours. */
internal fun Web.radioPanel(job: SyncJob): String {
    val radio = job.radio ?: return ""
    val st = runner.radio.status[job.id]
    val now = when {
        st == null -> """<p class="muted">Not listening now: next in its window (${h(RadioTitles.describe(radio))}).</p>"""
        st.error != null -> """<p class="err">${h(radio.station)}: ${h(st.error)}</p>"""
        else -> """<p class="ok">On air: ${h(st.lastTitle ?: "waiting for the first title…")}</p><p class="muted">${st.songs} new songs since ${Html.date(st.since)}. They reach the playlist every ${RadioListener.RUN_EVERY_MS / 60_000} minutes, and when the window closes.</p>"""
    }
    return """<div class="card">$RADIO_ICON <b>${h(radio.station)}</b>${radio.homepage?.let { """ · <a href="${h(it)}" target="_blank" rel="noopener">site</a>""" } ?: ""}
$now<p><a href="/playlists/csv/${q(job.source.playlistId.orEmpty())}">The songs heard so far</a></p></div>
<form method="post" action="/syncs/${q(job.id)}/radio" class="card"><h3>Days and hours</h3>${radioWindowFields(radio)}<p><button class="secondary">Save</button></p></form>"""
}

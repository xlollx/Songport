package com.xlollx.songport.server

import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.UsageStats
import java.time.ZoneId

/** "Trackhop in numbers", as the app's Stats screen: what the syncs did, on [UsageStats] as the app's. */
internal fun Web.statsPage(r: Web.Req) {
    val s = UsageStats.compute(env.store.data, System.currentTimeMillis(), ZoneId.systemDefault())
    val body = StringBuilder()
    if (s.isEmpty) {
        body.append("""<div class="card">No sync has run yet: the numbers start with the first one. <a href="/syncs/new">New sync</a></div>""")
        return send(r.ex, 200, page("Your numbers", body.toString()))
    }
    fun tile(big: String, small: String) = """<div class="card stat"><b>${h(big)}</b><span class="muted">${h(small)}</span></div>"""
    val hours = s.secondsSaved / 3600
    val minutes = s.secondsSaved / 60 % 60
    body.append("""<div class="hero"><h2>${s.added} tracks moved</h2><p class="muted">in ${s.runs} runs since ${Html.date(s.sinceEpoch).substringBefore(' ')}${if (s.historyCapped) " (older runs are no longer counted)" else ""}</p></div><div class="stats">""")
    body.append(tile("${s.addedThisMonth}", "added this month"))
    body.append(tile(if (hours > 0) "${hours} h ${minutes} min" else "$minutes min", "saved, at ${UsageStats.SECONDS_PER_TRACK} s a track by hand"))
    body.append(tile("${s.syncs}", if (s.syncs == 1) "sync" else "syncs"))
    s.matchPercent?.let { body.append(tile("$it%", "of the tracks found (${s.matchFound} of ${s.matchSearched}, latest runs)")) }
    body.append("</div>")
    s.route?.let { rt ->
        body.append("""<div class="card"><div class="who"><span class="pair">${logo(rt.sourceProvider, 36)}${logo(rt.targetProvider, 36)}</span><div><b>Busiest route</b><br><span class="muted">${h(label(rt.sourceProvider))} → ${h(label(rt.targetProvider))}: ${rt.runs} runs, ${rt.added} tracks</span></div></div></div>""")
    }
    s.longest?.let { l ->
        body.append("""<div class="card"><div class="who"><span class="pair">${logo(l.sourceProvider, 36)}${logo(l.targetProvider, 36)}</span><div><b>Biggest playlist</b><br><span class="muted"><a href="/syncs/${q(l.jobId)}">${h(l.name)}</a>: ${l.tracks} tracks</span></div></div></div>""")
    }
    if (s.topArtists.isNotEmpty()) {
        body.append("""<div class="card"><b>Most added artists</b><ol>""")
        s.topArtists.forEach { body.append("<li>${h(it.name)} <span class=\"muted\">${it.tracks} tracks</span></li>") }
        body.append("</ol></div>")
    }
    send(r.ex, 200, page("Your numbers", body.toString()))
}

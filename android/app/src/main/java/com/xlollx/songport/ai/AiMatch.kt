package com.xlollx.songport.ai

import android.content.Context
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.Matcher

/**
 * Dai nomi proposti dall'AI ai brani veri di un servizio: la stessa ricerca e lo stesso punteggio
 * della sync, uno per uno. Cio' che il servizio non ha resta fuori, e viene detto.
 */
object AiMatch {
    /** [alreadyThere]: proposals the playlist turned out to have already, once matched to real tracks. */
    data class Result(val found: List<Track>, val missing: List<Track>, val alreadyThere: Int = 0)

    /** The same recording through spelling, case, accents, "feat." tails and bracketed versions. */
    fun key(t: Track): String = Matcher.normalizeArtist(t.artists.firstOrNull().orEmpty()) + "|" + Matcher.normalizeTitle(t.title)

    /** Proposals without repeats among themselves and without anything [existing] already holds, by name. */
    fun fresh(proposals: List<Track>, existing: List<Track>): List<Track> {
        val taken = existing.mapTo(HashSet()) { key(it) }
        return proposals.filter { taken.add(key(it)) }
    }

    suspend fun match(ctx: Context, provider: MusicProvider, proposals: List<Track>, existing: List<Track> = emptyList(), onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val found = ArrayList<Track>()
        val missing = ArrayList<Track>()
        var alreadyThere = 0
        // What the playlist holds, by id and by name: a proposal that resolves to one of those is dropped.
        val seen = existing.mapTo(HashSet()) { it.id }
        val seenKeys = existing.mapTo(HashSet()) { key(it) }
        proposals.forEachIndexed { i, p ->
            onProgress(i, proposals.size)
            val best = runCatching { Matcher.best(p, provider.search(ctx, p)) }.getOrNull()
            when {
                best == null -> missing += p
                seen.add(best.id) && seenKeys.add(key(best)) -> found += best
                else -> alreadyThere++
            }
        }
        onProgress(proposals.size, proposals.size)
        return Result(found, missing, alreadyThere)
    }
}

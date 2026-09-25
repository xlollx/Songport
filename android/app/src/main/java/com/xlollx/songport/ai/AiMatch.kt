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
    data class Result(val found: List<Track>, val missing: List<Track>)

    suspend fun match(ctx: Context, provider: MusicProvider, proposals: List<Track>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val found = ArrayList<Track>()
        val missing = ArrayList<Track>()
        val seen = HashSet<String>()
        proposals.forEachIndexed { i, p ->
            onProgress(i, proposals.size)
            val best = runCatching { Matcher.best(p, provider.search(ctx, p)) }.getOrNull()
            if (best != null && seen.add(best.id)) found += best else if (best == null) missing += p
        }
        onProgress(proposals.size, proposals.size)
        return Result(found, missing)
    }
}

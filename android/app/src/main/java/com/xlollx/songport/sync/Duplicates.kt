package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/** Trova i brani doppi in una playlist. Kotlin puro: coperto dai test. */
object Duplicates {

    /**
     * Gruppi di brani che sono lo stesso pezzo: stesso id, stesso ISRC, oppure stesso titolo
     * normalizzato + stessi artisti normalizzati. Ogni gruppo mantiene l'ordine di playlist;
     * il primo elemento e' quello da tenere. Restituisce solo i gruppi con almeno 2 elementi.
     */
    fun groups(tracks: List<Track>): List<List<Track>> {
        val byKey = LinkedHashMap<String, MutableList<Track>>()
        val keyOfId = HashMap<String, String>()
        val keyOfIsrc = HashMap<String, String>()
        for (t in tracks) {
            val textKey = Matcher.normalizeTitle(t.title) + "|" +
                t.artists.map { Matcher.normalizeArtist(it) }.filter { it.isNotEmpty() }.sorted().joinToString(",")
            // Riusa la chiave di un brano gia' visto con stesso id o stesso ISRC, altrimenti quella testuale.
            val key = keyOfId[t.id] ?: t.isrcNorm?.let { keyOfIsrc[it] } ?: textKey
            byKey.getOrPut(key) { ArrayList() }.add(t)
            keyOfId.putIfAbsent(t.id, key)
            t.isrcNorm?.let { keyOfIsrc.putIfAbsent(it, key) }
        }
        return byKey.values.filter { it.size > 1 }
    }

    /** I brani da togliere: tutti tranne il primo di ogni gruppo. */
    fun extras(tracks: List<Track>): List<Track> = groups(tracks).flatMap { it.drop(1) }
}

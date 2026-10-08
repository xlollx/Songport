package com.xlollx.songport.sync

/**
 * How to turn one order into another with the moves the services offer. Pure Kotlin, covered by
 * tests. Both lists hold keys (track ids, or item ids where a playlist can hold a track twice).
 */
object Reorder {
    /**
     * Moves "take the item at [from], insert it before [before]" (Spotify's reorder call), to apply
     * one after another, each on the list as the previous one left it. Items of [desired] that
     * [current] lacks are skipped; items of [current] that [desired] lacks drift to the end.
     */
    fun moves(current: List<String>, desired: List<String>): List<Pair<Int, Int>> {
        val cur = current.toMutableList()
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        for (key in desired) {
            if (i >= cur.size) break
            if (cur[i] == key) { i++; continue }
            val j = (i until cur.size).firstOrNull { cur[it] == key } ?: continue
            cur.add(i, cur.removeAt(j))
            out += j to i
            i++
        }
        return out
    }

    /**
     * Steps "put [first] right before [second]" (YouTube Music's move), the fewest that make
     * [current] follow [desired]: walked from the end, so each step lands next to an item already
     * in place. Keys missing on either side are left where they are.
     */
    fun before(current: List<String>, desired: List<String>): List<Pair<String, String>> {
        val cur = current.toMutableList()
        val out = ArrayList<Pair<String, String>>()
        val wanted = desired.filter { it in cur }
        for (k in wanted.size - 2 downTo 0) {
            val x = wanted[k]
            val y = wanted[k + 1]
            val ix = cur.indexOf(x)
            val iy = cur.indexOf(y)
            if (ix < 0 || iy < 0 || ix + 1 == iy) continue
            cur.removeAt(ix)
            cur.add(cur.indexOf(y), x)
            out += x to y
        }
        return out
    }
}

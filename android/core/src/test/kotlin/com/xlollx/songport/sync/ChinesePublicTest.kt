package com.xlollx.songport.sync

import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** NetEase Cloud Music and QQ Music public playlists, as their sites answer (shapes captured 31/10/2026). */
class ChinesePublicTest {

    private val neteaseDetail = """{"code":200,"playlist":{"id":19723756,"name":"飙升榜","coverImgUrl":"https://p1.music.126.net/x.jpg",
        "trackCount":3,"privacy":0,"description":"每天热度上升最快的100首单曲","updateTime":1793424552249,"trackUpdateTime":1793429688024,
        "tracks":[{"name":"谁的错","id":2600746113,"ar":[{"id":1,"name":"Top Barry"},{"id":2,"name":"INDEcompany"}],
          "al":{"id":239889231,"name":"How I Shine"},"dt":179447,"noCopyrightRcmd":null,"publishTime":1700000000000}],
        "trackIds":[{"id":2600746113},{"id":2060035},{"id":1974443814}]}}"""

    @Test fun neteasePlaylistAndTracks() {
        val d = parseJson(neteaseDetail)
        val p = NeteasePublic.playlistOf(d, "19723756")
        assertEquals("飙升榜", p.name)
        assertEquals(3, p.trackCount)
        assertEquals(true, p.isPublic)
        assertEquals(false, p.ownedByMe)
        assertEquals(listOf(2600746113L, 2060035L, 1974443814L), NeteasePublic.trackIds(d))
        val listed = NeteasePublic.listedTracks(d)
        assertEquals(setOf(2600746113L), listed.keys)
        val t = listed.getValue(2600746113L)
        assertEquals("谁的错", t.title)
        assertEquals(listOf("Top Barry", "INDEcompany"), t.artists)
        assertEquals("How I Shine", t.album)
        assertEquals(179447L, t.durationMs)
        assertEquals(2023, t.year)
        assertNull(t.available)
        assertEquals("https://music.163.com/song?id=2600746113", t.uri)
    }

    @Test fun neteaseSongDetailAndUnlicensedTracks() {
        val song = parseJson("""{"name":"Twilght Years","id":2060035,"ar":[{"name":"The Ghost Of 3.13"}],"al":{"name":"The World Isn’t Here"},"dt":302000,"noCopyrightRcmd":{"type":1}}""")
        val t = NeteasePublic.parseTrack(song)!!
        assertEquals("Twilght Years", t.title)
        assertEquals(false, t.available)
        assertNull(NeteasePublic.parseTrack(parseJson("""{"id":5}""")))
    }

    private val qqAnswer = """{"code":0,"req_0":{"code":0,"data":{"code":0,"dirinfo":{"id":7256912512,"title":"欧美| 流行节奏控","picurl":"https://qpic.y.qq.com/c.jpg",
        "desc":"用节奏带动心跳！","songnum":42,"mtime":1765248775},"total_song_num":42,
        "songlist":[{"id":255852125,"mid":"002q83Sd2G7Y7D","name":"Physical","title":"Physical","interval":193,"time_public":"2020-01-28",
          "singer":[{"id":1,"name":"Dua Lipa"},{"id":2,"name":"华莎","title":"华莎 (화사)"}],"album":{"id":3,"name":"Physical (feat. Hwa Sa)"}}]}}}"""

    @Test fun qqMusicPlaylistAndSongs() {
        val d = QqMusicPublic.data(parseJson(qqAnswer))!!
        val p = QqMusicPublic.playlistOf(d, "7256912512")
        assertEquals("欧美| 流行节奏控", p.name)
        assertEquals(42, p.trackCount)
        assertEquals("用节奏带动心跳！", p.description)
        val t = QqMusicPublic.songs(d).single()
        assertEquals("Physical", t.title)
        assertEquals(listOf("Dua Lipa", "华莎"), t.artists)
        assertEquals("Physical (feat. Hwa Sa)", t.album)
        assertEquals(193_000L, t.durationMs)
        assertEquals(2020, t.year)
        assertEquals("https://y.qq.com/n/ryqq/songDetail/002q83Sd2G7Y7D", t.uri)
        assertNull(QqMusicPublic.data(parseJson("""{"code":0,"req_0":{"code":0,"data":{"code":0}}}""")))
        // A missing or private playlist: the site's own error code and an empty "dirinfo".
        assertNull(QqMusicPublic.data(parseJson("""{"code":0,"req_0":{"code":0,"data":{"code":-100006,"msg":"get diss info from tmem error","dirinfo":{"id":1,"title":"","songnum":0},"songlist":[]}}}""")))
    }
}

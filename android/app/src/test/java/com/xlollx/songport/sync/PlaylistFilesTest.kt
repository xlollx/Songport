package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFilesTest {

    @Test fun detectsFormats() {
        assertEquals(PlaylistFiles.Format.M3U, PlaylistFiles.detect("a.m3u8", "#EXTM3U\n"))
        assertEquals(PlaylistFiles.Format.M3U, PlaylistFiles.detect("boh", "#EXTM3U\n#EXTINF:1,a\n"))
        assertEquals(PlaylistFiles.Format.ITUNES_XML, PlaylistFiles.detect("Libreria.xml", "<?xml version=\"1.0\"?>\n<!DOCTYPE plist>\n<plist version=\"1.0\">"))
        assertEquals(PlaylistFiles.Format.JSON, PlaylistFiles.detect("x", "[{\"title\":\"a\"}]"))
        assertEquals(PlaylistFiles.Format.CSV, PlaylistFiles.detect("x.csv", "title,artist\na,b\n"))
        assertEquals(PlaylistFiles.Format.TEXT, PlaylistFiles.detect("x.txt", "Daft Punk - Get Lucky\n"))
    }

    @Test fun parsesM3u() {
        val m3u = """
            #EXTM3U
            #EXTINF:369,Daft Punk - Get Lucky
            /storage/music/get_lucky.mp3
            #EXTINF:-1,Nirvana - Smells Like Teen Spirit
            http://example.com/x.mp3
            /storage/music/Queen - Bohemian Rhapsody.mp3
        """.trimIndent()
        val t = PlaylistFiles.parse("list.m3u", m3u)
        assertEquals(3, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk"), t[0].artists)
        assertEquals(369_000L, t[0].durationMs)
        assertEquals(0L, t[1].durationMs)
        // Senza EXTINF si usa il nome del file.
        assertEquals("Bohemian Rhapsody", t[2].title)
        assertEquals(listOf("Queen"), t[2].artists)
    }

    @Test fun m3uRoundTrip() {
        val tracks = listOf(Track("1", "Get Lucky", listOf("Daft Punk"), "RAM", 369_000))
        val back = PlaylistFiles.parse("x.m3u8", PlaylistFiles.toM3u(tracks))
        assertEquals("Get Lucky", back[0].title)
        assertEquals(listOf("Daft Punk"), back[0].artists)
        assertEquals(369_000L, back[0].durationMs)
    }

    @Test fun parsesItunesXml() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
            <key>Tracks</key>
            <dict>
              <key>101</key>
              <dict>
                <key>Track ID</key><integer>101</integer>
                <key>Name</key><string>Volare (Nel blu dipinto di blu)</string>
                <key>Artist</key><string>Domenico Modugno</string>
                <key>Album</key><string>Best of</string>
                <key>Total Time</key><integer>211000</integer>
              </dict>
              <key>102</key>
              <dict>
                <key>Track ID</key><integer>102</integer>
                <key>Name</key><string>Rock &amp; Roll</string>
                <key>Artist</key><string>Led Zeppelin</string>
                <key>Total Time</key><integer>220000</integer>
              </dict>
            </dict>
            <key>Playlists</key>
            <array><dict><key>Name</key><string>Preferiti</string></dict></array>
            </dict></plist>
        """.trimIndent()
        val t = PlaylistFiles.parse("Libreria.xml", xml)
        assertEquals(2, t.size)
        assertEquals("Volare (Nel blu dipinto di blu)", t[0].title)
        assertEquals(listOf("Domenico Modugno"), t[0].artists)
        assertEquals(211_000L, t[0].durationMs)
        assertEquals("Rock & Roll", t[1].title)
    }

    @Test fun parsesJsonWithNestedArtists() {
        val json = """
            {"playlistName":"Mix","tracks":[
              {"title":"Get Lucky","artists":[{"name":"Daft Punk"},{"name":"Pharrell Williams"}],
               "album":{"name":"RAM"},"durationMs":369626,"isrc":"GBTDF1300003"},
              {"trackName":"Volare","artistName":"Domenico Modugno","duration":"3:31"}
            ]}
        """.trimIndent()
        val t = PlaylistFiles.parse("export.json", json)
        assertEquals(2, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), t[0].artists)
        assertEquals(369_626L, t[0].durationMs)
        assertEquals("GBTDF1300003", t[0].isrc)
        assertEquals("Volare", t[1].title)
        assertEquals(211_000L, t[1].durationMs)
    }

    @Test fun parsesAppleMusicTsvExport() {
        val tsv = "Name\tArtist\tComposer\tAlbum\tGenre\tSize\tTime\n" +
            "Get Lucky\tDaft Punk\t\tRandom Access Memories\tElectronic\t8880000\t369\n"
        val t = PlaylistFiles.parse("Playlist.txt", tsv)
        assertEquals(1, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk"), t[0].artists)
        assertEquals(369_000L, t[0].durationMs)
    }

    @Test fun parsesPlainText() {
        val text = """
            1. Daft Punk - Get Lucky
            Nirvana – Smells Like Teen Spirit
            # commento
            Imagine
        """.trimIndent()
        val t = PlaylistFiles.parse("lista.txt", text)
        assertEquals(3, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk"), t[0].artists)
        assertEquals("Smells Like Teen Spirit", t[1].title)
        assertEquals("Imagine", t[2].title)
        assertTrue(t[2].artists.isEmpty())
    }

    @Test fun stableIdsAcrossFormats() {
        val fromText = PlaylistFiles.parse("a.txt", "Daft Punk - Get Lucky")
        val fromM3u = PlaylistFiles.parse("a.m3u", "#EXTM3U\n#EXTINF:369,Daft Punk - Get Lucky\nx.mp3")
        assertEquals(fromText[0].id, fromM3u[0].id)
    }
}

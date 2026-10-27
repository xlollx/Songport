package com.xlollx.songport.sync

import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundCloudPublicTest {
    private val full = """{"kind":"track","id":101,"title":"Midnight Drive","duration":30000,"full_duration":215000,
        "permalink_url":"https://soundcloud.com/label/midnight-drive","policy":"ALLOW","release_date":"2021-06-04T00:00:00Z",
        "user":{"username":"Some Label"},
        "publisher_metadata":{"artist":"Nova","album_title":"Night Roads","isrc":"GBABC2100001","explicit":false}}"""

    @Test fun clientIdFromScripts() {
        val html = """<script crossorigin src="https://a-v2.sndcdn.com/assets/0-aa.js"></script>
            <script crossorigin src="https://a-v2.sndcdn.com/assets/55-bb.js"></script><script src="/other.js"></script>"""
        assertEquals(listOf("https://a-v2.sndcdn.com/assets/55-bb.js", "https://a-v2.sndcdn.com/assets/0-aa.js"), SoundCloudPublic.scriptUrls(html))
        assertEquals("3GKD969NJWD1eB6gvQJN0GnueYbcWszv", SoundCloudPublic.clientIdIn("""x={env:"p",client_id:"3GKD969NJWD1eB6gvQJN0GnueYbcWszv",y:1}"""))
        assertNull(SoundCloudPublic.clientIdIn("""client_id:"short""""))
    }

    @Test fun trackWithPublisherMetadata() {
        val t = SoundCloudPublic.parseTrack(parseJson(full))!!
        assertEquals("101", t.id)
        assertEquals("Midnight Drive", t.title)
        assertEquals(listOf("Nova"), t.artists)
        assertEquals("Night Roads", t.album)
        assertEquals("GBABC2100001", t.isrc)
        assertEquals(215000L, t.durationMs)
        assertEquals(2021, t.year)
        assertEquals(true, t.available)
        assertEquals("https://soundcloud.com/label/midnight-drive", t.uri)
    }

    @Test fun trackWithoutMetadataSplitsTheTitle() {
        val t = SoundCloudPublic.parseTrack(parseJson("""{"kind":"track","id":7,"title":"Artist A & Artist B - Song Name (Free Download)",
            "duration":180000,"user":{"username":"repost channel"},"publisher_metadata":null,"policy":"BLOCK"}"""))!!
        assertEquals("Song Name", t.title)
        assertEquals(listOf("Artist A", "Artist B"), t.artists)
        assertEquals(180000L, t.durationMs)
        assertNull(t.isrc)
        assertEquals(false, t.available)
    }

    @Test fun trackWithoutMetadataFallsBackToTheUploader() {
        val t = SoundCloudPublic.parseTrack(parseJson("""{"kind":"track","id":8,"title":"Song-Name","duration":1000,"user":{"username":"Producer"}}"""))!!
        assertEquals("Song-Name", t.title)
        assertEquals(listOf("Producer"), t.artists)
    }

    @Test fun setWithStubsKeepsItsOrder() {
        val set = parseJson("""{"kind":"playlist","title":"Mix","track_count":3,"sharing":"public","description":"d",
            "artwork_url":"https://i1.sndcdn.com/artworks-x-large.jpg",
            "tracks":[$full,{"id":202,"kind":"track","policy":"ALLOW"},{"id":303,"kind":"track"}]}""")
        assertEquals(listOf(101L, 202L, 303L), SoundCloudPublic.trackIds(set))
        assertNull(SoundCloudPublic.parseTrack(set["tracks"][1]))
        val fetched = parseJson("""[{"kind":"track","id":303,"title":"C","duration":1,"user":{"username":"u3"}},
            {"kind":"track","id":202,"title":"B","duration":1,"user":{"username":"u2"}}]""")
        val byId = (fetched as kotlinx.serialization.json.JsonArray).mapNotNull { SoundCloudPublic.parseTrack(it) }.associateBy { it.id.toLong() }
        assertEquals(listOf("Midnight Drive", "B", "C"), SoundCloudPublic.assemble(set, byId).map { it.title })
        val p = SoundCloudPublic.playlistOf(set, "u/sets/mix")
        assertEquals("Mix", p.name)
        assertEquals(3, p.trackCount)
        assertEquals(false, p.ownedByMe)
        assertEquals(true, p.isPublic)
        assertEquals("https://i1.sndcdn.com/artworks-x-t500x500.jpg", p.imageUrl)
    }

    @Test fun likesKeepTracksOnly() {
        val page = parseJson("""{"collection":[
            {"created_at":"2024-01-02T03:04:05Z","kind":"like","track":$full},
            {"created_at":"2024-01-01T00:00:00Z","kind":"like","playlist":{"kind":"playlist","id":5,"title":"A set"}}],
            "next_href":"https://api-v2.soundcloud.com/users/1/likes?offset=abc&limit=200"}""")
        val (tracks, next) = SoundCloudPublic.likesPage(page)
        assertEquals(listOf("101"), tracks.map { it.id })
        assertEquals(1704164645000L, tracks[0].addedAt)
        assertEquals("https://api-v2.soundcloud.com/users/1/likes?offset=abc&limit=200", next)
        assertNull(SoundCloudPublic.likesPage(parseJson("""{"collection":[],"next_href":null}""")).second)
    }

    @Test fun paths() {
        assertEquals("a/sets/b", SoundCloudPublic.pathOf("https://soundcloud.com/a/sets/b?si=x"))
        assertNull(SoundCloudPublic.pathOf("https://open.spotify.com/playlist/abc"))
        assertEquals(true, SoundCloudPublic.isLikes("a/likes"))
        assertEquals(false, SoundCloudPublic.isLikes("a/sets/likes-mix"))
    }
}

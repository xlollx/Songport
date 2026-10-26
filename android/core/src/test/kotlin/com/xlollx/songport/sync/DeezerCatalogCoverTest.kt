package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerCatalogCoverTest {
    @Test
    fun youtubeIdsGetTheirThumbnails() {
        val urls = DeezerCatalog.ownCover("dQw4w9WgXcQ", "ytm")
        assertEquals(listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"), urls)
        assertEquals(urls, DeezerCatalog.ownCover("dQw4w9WgXcQ", "youtube"))
    }

    @Test
    fun otherServicesAndBadIdsGetNone() {
        assertTrue(DeezerCatalog.ownCover("dQw4w9WgXcQ", "spotify").isEmpty())
        assertTrue(DeezerCatalog.ownCover("not-a-video-id", "ytm").isEmpty())
    }
}

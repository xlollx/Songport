package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.R
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * API Subsonic (http://www.subsonic.org/pages/api.jsp): copre Navidrome, Airsonic, Gonic, LMS,
 * Funkwhale e gli altri server compatibili. Nessuna registrazione, nessuna quota: solo il tuo server.
 * Autenticazione con token = md5(password + salt) per richiesta, mai la password in chiaro.
 */
class SubsonicProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Subsonic / Navidrome"
    override val routeNoteRes: Int? get() = R.string.route_note_server
    override val brandColor = 0xFF2E7D32
    override val noteRes = R.string.provider_note_subsonic
    override val beta = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val supportsAlbums = true
    override val supportsArtists = true
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintRes = R.string.login_hint_subsonic)

    override suspend fun login(ctx: Context, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val t = Tokens(accessToken = secret, userName = user.trim(), extra = mapOf("url" to base))
        call(ctx, "ping", emptyList(), t)
        save(ctx, t)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Chiamata REST Subsonic; i parametri ripetibili (songIdToAdd…) passano come lista di coppie. */
    private suspend fun call(ctx: Context, method: String, params: List<Pair<String, String>>, t: Tokens = creds(ctx)): JsonElement {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val common = listOf(
            "u" to t.userName, "t" to md5(t.accessToken + salt), "s" to salt,
            "v" to "1.16.1", "c" to "Songport", "f" to "json",
        )
        val query = (common + params).joinToString("&") { (k, v) -> "${Http.enc(k)}=${Http.enc(v)}" }
        val resp = Http.send("GET", "${t.extra["url"]}/rest/$method?$query", mapOf("Accept" to "application/json"))
        if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
        val r = parseJson(resp.body)["subsonic-response"] ?: throw ProviderException("$displayName: unexpected response")
        if (r["status"].str != "ok") {
            throw ProviderException("$displayName: ${r["error"]["message"].str ?: "error ${r["error"]["code"].int}"}")
        }
        return r
    }

    private fun toTrack(s: JsonElement?, index: Int? = null): Track? {
        val id = s["id"].str ?: return null
        return Track(
            id = id,
            title = s["title"].str ?: "",
            artists = listOfNotNull(s["artist"].str),
            album = s["album"].str ?: "",
            durationMs = (s["duration"].long ?: 0) * 1000,
            itemId = index?.toString(),
            year = s["year"].int ?: 0,
            addedAt = com.xlollx.songport.sync.Durations.parseInstant(s["created"].str),
        )
    }

    override suspend fun playlists(ctx: Context): List<Playlist> {
        val me = creds(ctx).userName
        return call(ctx, "getPlaylists", emptyList())["playlists"]["playlist"].arr.mapNotNull { p ->
            Playlist(p["id"].str ?: return@mapNotNull null, p["name"].str ?: "", p["songCount"].int ?: -1, ownedByMe = (p["owner"].str ?: me) == me, isPublic = p["public"].bool)
        }
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> =
        if (playlistId == MusicProvider.LIKED_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["song"].arr.mapNotNull { toTrack(it) }
        } else if (playlistId == MusicProvider.ALBUMS_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["album"].arr.mapNotNull { toAlbum(it) }
        } else if (playlistId == MusicProvider.ARTISTS_ID) {
            call(ctx, "getStarred2", emptyList())["starred2"]["artist"].arr.mapNotNull { toArtist(it) }
        } else {
            call(ctx, "getPlaylist", listOf("id" to playlistId))["playlist"]["entry"].arr
                .mapIndexedNotNull { i, s -> toTrack(s, i) }
        }

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        when (track.kind) {
            Track.KIND_ALBUM -> {
                val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
                val r = call(ctx, "search3", listOf("query" to q, "songCount" to "0", "albumCount" to "5", "artistCount" to "0"))["searchResult3"]["album"].arr.mapNotNull { toAlbum(it) }
                // Servers index album titles alone too: the artist in the query can hide a match.
                return r.ifEmpty { call(ctx, "search3", listOf("query" to Matcher.searchTitle(track.title), "songCount" to "0", "albumCount" to "5", "artistCount" to "0"))["searchResult3"]["album"].arr.mapNotNull { toAlbum(it) } }
            }
            Track.KIND_ARTIST -> return call(ctx, "search3", listOf("query" to track.title, "songCount" to "0", "albumCount" to "0", "artistCount" to "5"))["searchResult3"]["artist"].arr.mapNotNull { toArtist(it) }
        }
        val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
        return call(ctx, "search3", listOf("query" to q, "songCount" to "5", "albumCount" to "0", "artistCount" to "0"))["searchResult3"]["song"].arr
            .mapNotNull { toTrack(it) }
    }

    private fun toAlbum(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        return Track(id = id, title = a["name"].str ?: a["album"].str ?: "", artists = listOfNotNull(a["artist"].str), kind = Track.KIND_ALBUM)
    }

    private fun toArtist(a: JsonElement?): Track? {
        val id = a["id"].str ?: return null
        val name = a["name"].str ?: return null
        return Track(id = id, title = name, artists = listOf(name), kind = Track.KIND_ARTIST)
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val r = call(ctx, "createPlaylist", listOf("name" to name))
        val id = r["playlist"]["id"].str
            ?: call(ctx, "getPlaylists", emptyList())["playlists"]["playlist"].arr.lastOrNull { it["name"].str == name }?.get("id").str
            ?: throw ProviderException("$displayName: playlist not created")
        return Playlist(id, name, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "id" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "albumId" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "star", chunk.map { "artistId" to it.id }) }
            return
        }
        tracks.chunked(100).forEach { chunk ->
            call(ctx, "updatePlaylist", listOf("playlistId" to playlistId) + chunk.map { "songIdToAdd" to it.id })
        }
    }

    override suspend fun renamePlaylist(ctx: Context, playlistId: String, name: String) {
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId, "name" to name))
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Context, playlistId: String, public: Boolean) {
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId, "public" to public.toString()))
    }

    override suspend fun deletePlaylist(ctx: Context, playlistId: String) {
        call(ctx, "deletePlaylist", listOf("id" to playlistId))
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "id" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "albumId" to it.id }) }
            return
        }
        if (playlistId == MusicProvider.ARTISTS_ID) {
            tracks.chunked(50).forEach { chunk -> call(ctx, "unstar", chunk.map { "artistId" to it.id }) }
            return
        }
        // Rimozione per posizione: gli indici sono quelli letti da getPlaylist, tutti in una chiamata.
        val indexes = tracks.mapNotNull { it.itemId }
        if (indexes.isEmpty()) return
        call(ctx, "updatePlaylist", listOf("playlistId" to playlistId) + indexes.map { "songIndexToRemove" to it })
    }

    companion object { const val SERVICE = "subsonic" }
}

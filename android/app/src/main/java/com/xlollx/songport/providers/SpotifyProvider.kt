package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.auth.AuthFlow
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.isNullish
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement

/**
 * Spotify Web API (https://developer.spotify.com/documentation/web-api).
 * Nota: un'app in "Development mode" accetta al massimo 25 utenti registrati nella dashboard;
 * per un'app pubblica l'utente puo' inserire il PROPRIO client ID in Impostazioni > Avanzate.
 */
open class SpotifyProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    override val revokeUrl = "https://www.spotify.com/account/apps/"
    override val displayName = "Spotify"
    override val brandColor = 0xFF1DB954
    override val noteRes = R.string.provider_note_spotify
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true

    override val setupGuide: SetupGuide? = SetupGuide(
        dashboardUrl = "https://developer.spotify.com/dashboard",
        redirectUri = AuthFlow.REDIRECT_URI,
        stepUrls = listOf("https://developer.spotify.com/dashboard", "https://developer.spotify.com/dashboard/create", null, null, "https://developer.spotify.com/dashboard", "https://developer.spotify.com/dashboard"),
        whyRes = R.string.setup_why_spotify,
        stepsArrayRes = R.array.setup_steps_spotify,
        fieldLabelRes = R.string.setup_field_client_id,
    )

    override val defaultClientId: String get() = BuildConfig.SPOTIFY_CLIENT_ID
    override val authorizeEndpoint = "https://accounts.spotify.com/authorize"
    override val tokenEndpoint = "https://accounts.spotify.com/api/token"
    override val scopes =
        "playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public user-library-read"
    // Forces the consent page, which names the signed-in account and offers "Not you?" to switch.
    override val switchAccountParams = mapOf("show_dialog" to "true")


    override suspend fun enrichAccount(ctx: Context, t: Tokens): Tokens {
        val me = api(ctx, "GET", "$API/me")
        val uid = me["id"].str ?: ""
        return t.copy(userId = uid, userName = me["display_name"].str ?: uid)
    }

    override fun errorMessage(resp: HttpResponse): String =
        parseJson(resp.body)["error"]["message"].str ?: super.errorMessage(resp)

    // Da novembre 2024 le app in Development Mode non possono leggere le playlist create da Spotify
    // (Discover Weekly, Release Radar, mix editoriali): la risposta e' un 403 senza spiegazioni.
    override fun friendlyApiError(ctx: Context, resp: HttpResponse): String? =
        if (resp.code == 403) ctx.getString(R.string.spotify_forbidden, errorMessage(resp).ifBlank { "403" }) else null

    override suspend fun playlists(ctx: Context): List<Playlist> {
        val me = tokens(ctx).userId
        val out = ArrayList<Playlist>()
        var url: String? = "$API/me/playlists?limit=50"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (p in j["items"].arr) {
                val pid = p["id"].str ?: continue
                out += Playlist(
                    id = pid,
                    name = p["name"].str ?: "",
                    trackCount = p["items"]["total"].int ?: p["tracks"]["total"].int ?: -1,
                    ownedByMe = p["owner"]["id"].str == me || p["collaborative"].bool == true,
                )
            }
            url = j["next"].str
        }
        return out
    }

    override suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist {
        val j = api(ctx, "GET", "$API/playlists/$playlistId?fields=name,items(total),tracks(total)")
        return Playlist(playlistId, j["name"].str ?: playlistId, j["items"]["total"].int ?: j["tracks"]["total"].int ?: -1, ownedByMe = false)
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        val out = ArrayList<Track>()
        var url: String? = if (playlistId == MusicProvider.LIKED_ID) {
            "$API/me/tracks?limit=50"
        } else {
            // Migrazione Spotify di febbraio/marzo 2026: /tracks e' stato tolto alle app in Development Mode
            // (risponde 403) e sostituito da /items, con "track" rinominato in "item" nella risposta.
            "$API/playlists/$playlistId/items?limit=100&fields=" +
                Http.enc("next,items(item(id,name,uri,duration_ms,is_local,external_ids(isrc),artists(name),album(name))," +
                    "track(id,name,uri,duration_ms,is_local,external_ids(isrc),artists(name),album(name)))")
        }
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (item in j["items"].arr) {
                val t = item["item"].takeUnless { it.isNullish } ?: item["track"]
                if (t.isNullish || t["is_local"].bool == true) continue
                if (t["id"].str == null) continue
                out += toTrack(t)
            }
            url = j["next"].str
        }
        return out
    }

    private fun toTrack(t: JsonElement?): Track {
        val tid = t["id"].str ?: ""
        return Track(
            id = tid,
            title = t["name"].str ?: "",
            artists = t["artists"].arr.mapNotNull { it["name"].str },
            album = t["album"]["name"].str ?: "",
            durationMs = t["duration_ms"].long ?: 0,
            isrc = t["external_ids"]["isrc"].str,
            uri = t["uri"].str ?: "spotify:track:$tid",
        )
    }

    override suspend fun track(ctx: Context, trackId: String): Track? =
        api(ctx, "GET", "$API/tracks/${Http.enc(trackId)}").takeIf { it["id"].str != null }?.let { toTrack(it) }

    private suspend fun searchQuery(ctx: Context, q: String): List<Track> {
        val j = api(ctx, "GET", "$API/search?type=track&limit=5&q=${Http.enc(q)}")
        return j["tracks"]["items"].arr.filter { it["id"].str != null }.map { toTrack(it) }
    }

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        track.isrcNorm?.let { isrc ->
            val r = searchQuery(ctx, "isrc:$isrc")
            if (r.isNotEmpty()) return r
        }
        val title = Matcher.searchTitle(track.title)
        val artist = track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }
        val fielded = buildString {
            append("track:").append(title)
            if (!artist.isNullOrBlank()) append(" artist:").append(artist)
        }
        val r = searchQuery(ctx, fielded)
        if (r.isNotEmpty()) return r
        return searchQuery(ctx, listOfNotNull(title, artist).joinToString(" "))
    }

    override fun rehydrate(track: Track): Track = track.copy(uri = "spotify:track:${track.id}")

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val me = tokens(ctx).userId
        val j = api(ctx, "POST", "$API/users/${Http.enc(me)}/playlists",
            jsonObj("name" to name, "public" to false, "description" to description))
        return Playlist(j["id"].str ?: throw ProviderException("Spotify: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.map { it.id }.chunked(50).forEach { ids -> api(ctx, "PUT", "$API/me/tracks", jsonObj("ids" to ids)) }
            return
        }
        tracks.mapNotNull { it.uri }.chunked(100).forEach { chunk ->
            api(ctx, "POST", "$API/playlists/$playlistId/items", jsonObj("uris" to chunk))
        }
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.map { it.id }.chunked(50).forEach { ids -> api(ctx, "DELETE", "$API/me/tracks", jsonObj("ids" to ids)) }
            return
        }
        tracks.mapNotNull { it.uri }.chunked(100).forEach { chunk ->
            api(ctx, "DELETE", "$API/playlists/$playlistId/items",
                jsonObj("items" to chunk.map { mapOf("uri" to it) }))
        }
    }

    companion object {
        const val SERVICE = "spotify"
        private const val API = "https://api.spotify.com/v1"
    }
}

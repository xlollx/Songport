package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.TokenStore
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.isNullish
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Deezer API (https://developers.deezer.com). Usa il flusso OAuth "implicit" (response_type=token):
 * il token arriva nel fragment del redirect, non serve un client secret. Deezer accetta solo
 * redirect https: la pagina statica docs/deezer-redirect.html rimanda a songport://callback.
 * BETA: la registrazione di nuove app sul portale Deezer potrebbe non essere disponibile.
 */
class DeezerProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    override val revokeUrl = "https://www.deezer.com/account/apps"
    override val displayName = "Deezer"
    override val brandColor = 0xFFA238FF
    override val noteRes = R.string.provider_note_deezer
    override val beta = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true

    override val setupGuide = SetupGuide(
        dashboardUrl = "https://developers.deezer.com/myapps",
        stepUrls = listOf("https://developers.deezer.com/myapps", "https://developers.deezer.com/myapps/create", null, "https://developers.deezer.com/myapps"),
        whyRes = R.string.setup_why_deezer,
        stepsArrayRes = R.array.setup_steps_deezer,
        fieldLabelRes = R.string.setup_field_app_id,
        needsRedirectUrl = true,
    )

    override val defaultClientId: String get() = BuildConfig.DEEZER_APP_ID
    override val authorizeEndpoint = "https://connect.deezer.com/oauth/auth.php"
    override val tokenEndpoint = "" // non usato: flusso implicito
    override val scopes = "basic_access,manage_library,delete_library,offline_access"


    override fun redirectUri(ctx: Context): String =
        Store.get(ctx).data.settings.deezerRedirectUrl.trim().ifEmpty { BuildConfig.DEEZER_REDIRECT_URL }

    override fun isConfigured(ctx: Context) = clientId(ctx).isNotBlank() && redirectUri(ctx).startsWith("https://")

    override fun authUrl(ctx: Context, state: String, codeChallenge: String): String =
        authorizeEndpoint + "?" + Http.query(
            mapOf(
                "app_id" to clientId(ctx),
                "redirect_uri" to redirectUri(ctx),
                "perms" to scopes,
                "response_type" to "token",
                "state" to state,
            )
        )

    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) {
        val token = params["access_token"] ?: throw ProviderException("Deezer: no access_token in redirect")
        val expires = params["expires"]?.toLongOrNull() ?: 0L
        var t = Tokens(accessToken = token, expiresAt = if (expires > 0) System.currentTimeMillis() + expires * 1000 else 0)
        val store = TokenStore(ctx)
        store.set(id, t)
        val me = dz(ctx, "GET", "/user/me")
        t = t.copy(userId = me["id"].long?.toString() ?: "", userName = me["name"].str ?: "")
        store.set(id, t)
    }

    override suspend fun refresh(ctx: Context, t: Tokens): Tokens {
        TokenStore(ctx).clear(id)
        throw ProviderException("Deezer: session expired, please reconnect")
    }

    /** Chiamata Deezer: token come parametro di query; gli errori arrivano come JSON `error` con HTTP 200. */
    private suspend fun dz(ctx: Context, method: String, path: String, params: Map<String, String?> = emptyMap(), allowNoData: Boolean = false): JsonElement {
        val t = tokens(ctx)
        val url = if (path.startsWith("http")) path + (if ('?' in path) "&" else "?") + "access_token=${Http.enc(t.accessToken)}"
        else "$API$path?" + Http.query(params + ("access_token" to t.accessToken))
        val resp = Http.send(method, url, mapOf("Accept" to "application/json"))
        if (!resp.ok) throw ProviderException("Deezer API ${resp.code}: ${resp.body.take(200)}")
        val j = parseJson(resp.body)
        val err = j["error"]
        if (!err.isNullish) {
            val code = err["code"].int ?: 0
            if (allowNoData && code == 800) return JsonNull
            if (code == 300 || code == 200) TokenStore(ctx).clear(id) // token non valido / permesso mancante
            throw ProviderException("Deezer: ${err["message"].str ?: err.toString()}")
        }
        return j
    }

    override suspend fun playlists(ctx: Context): List<Playlist> {
        val me = tokens(ctx).userId
        val out = ArrayList<Playlist>()
        var url: String? = "$API/user/me/playlists?limit=100"
        while (url != null) {
            val j = dz(ctx, "GET", url)
            for (p in j["data"].arr) {
                if (p["is_loved_track"].bool == true) continue // gia' esposta come "Brani preferiti"
                out += Playlist(
                    id = p["id"].long?.toString() ?: continue,
                    name = p["title"].str ?: "",
                    trackCount = p["nb_tracks"].int ?: -1,
                    ownedByMe = p["creator"]["id"].long?.toString() == me,
                )
            }
            url = j["next"].str
        }
        return out
    }

    private fun toTrack(t: JsonElement?): Track? {
        val id = t["id"].long?.toString() ?: return null
        return Track(
            id = id,
            title = t["title"].str ?: "",
            artists = listOfNotNull(t["artist"]["name"].str),
            album = t["album"]["title"].str ?: "",
            durationMs = (t["duration"].long ?: 0) * 1000,
            isrc = t["isrc"].str,
        )
    }

    override suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist {
        val j = dz(ctx, "GET", "/playlist/$playlistId")
        return Playlist(playlistId, j["title"].str ?: playlistId, j["nb_tracks"].int ?: -1, ownedByMe = false)
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        val out = ArrayList<Track>()
        var url: String? = if (playlistId == MusicProvider.LIKED_ID) "$API/user/me/tracks?limit=100" else "$API/playlist/$playlistId/tracks?limit=100"
        while (url != null) {
            val j = dz(ctx, "GET", url)
            j["data"].arr.forEach { t -> toTrack(t)?.let { out += it } }
            url = j["next"].str
        }
        return out
    }

    override suspend fun track(ctx: Context, trackId: String): Track? = toTrack(dz(ctx, "GET", "/track/$trackId", allowNoData = true))

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        track.isrcNorm?.let { isrc ->
            val j = dz(ctx, "GET", "/track/isrc:$isrc", allowNoData = true)
            toTrack(j)?.let { return listOf(it) }
        }
        val title = Matcher.searchTitle(track.title)
        val artist = track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }
        val fielded = buildString {
            append("track:\"").append(title).append('"')
            if (!artist.isNullOrBlank()) append(" artist:\"").append(artist).append('"')
        }
        var j = dz(ctx, "GET", "/search/track", mapOf("q" to fielded, "limit" to "5"))
        var res = j["data"].arr.mapNotNull { toTrack(it) }
        if (res.isEmpty()) {
            j = dz(ctx, "GET", "/search/track", mapOf("q" to listOfNotNull(artist, title).joinToString(" "), "limit" to "5"))
            res = j["data"].arr.mapNotNull { toTrack(it) }
        }
        return res
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val j = dz(ctx, "POST", "/user/me/playlists", mapOf("title" to name))
        return Playlist(j["id"].long?.toString() ?: throw ProviderException("Deezer: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.forEach { dz(ctx, "POST", "/user/me/tracks", mapOf("track_id" to it.id)) }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            dz(ctx, "POST", "/playlist/$playlistId/tracks", mapOf("songs" to chunk.joinToString(",") { it.id }))
        }
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.forEach { dz(ctx, "DELETE", "/user/me/tracks", mapOf("track_id" to it.id)) }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            dz(ctx, "DELETE", "/playlist/$playlistId/tracks", mapOf("songs" to chunk.joinToString(",") { it.id }))
        }
    }

    companion object {
        const val SERVICE = "deezer"
        private const val API = "https://api.deezer.com"
    }
}

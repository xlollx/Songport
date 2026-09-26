package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement

/**
 * Last.fm: sorgente in sola lettura dei brani "amati" di un utente (profilo pubblico, basta il
 * nome utente). Serve una API key gratuita e immediata (https://www.last.fm/api/account/create).
 */
class LastFmProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Last.fm"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteRes: Int? get() = R.string.route_note_api_readonly
    override val brandColor = 0xFFD51007
    override val noteRes = R.string.provider_note_lastfm
    override val beta = true
    override val canWrite = false
    override val canRemoveTracks = false
    override val supportsLikedSongs = true
    override val supportsRecent = true
    override val loginForm = LoginForm(needsUrl = false, needsUser = true, needsSecret = false, hintRes = R.string.login_hint_lastfm)
    override val setupGuide = SetupGuide(
        dashboardUrl = "https://www.last.fm/api/account/create",
        stepUrls = listOf("https://www.last.fm/api/account/create", null, "https://www.last.fm/api/accounts"),
        whyRes = R.string.setup_why_lastfm,
        stepsArrayRes = R.array.setup_steps_lastfm,
        fieldLabelRes = R.string.setup_field_api_key,
    )

    private fun apiKey(ctx: Context): String =
        Store.get(ctx).data.settings.clientIds[serviceId]?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.LASTFM_API_KEY

    override fun isConfigured(ctx: Context) = apiKey(ctx).isNotBlank()
    override fun usesOwnCredentials(ctx: Context) = !Store.get(ctx).data.settings.clientIds[serviceId].isNullOrBlank()

    private suspend fun call(ctx: Context, params: Map<String, String>): JsonElement {
        val key = apiKey(ctx)
        if (key.isBlank()) throw ProviderException(ctx.getString(R.string.not_configured))
        val resp = Http.send("GET", "https://ws.audioscrobbler.com/2.0/?" + Http.query(params + mapOf("api_key" to key, "format" to "json")))
        val j = parseJson(resp.body)
        j["error"].int?.let { throw ProviderException("$displayName: ${j["message"].str ?: "error $it"}") }
        if (!resp.ok) throw ProviderException("$displayName: HTTP ${resp.code}")
        return j
    }

    override suspend fun login(ctx: Context, url: String, user: String, secret: String) {
        val name = call(ctx, mapOf("method" to "user.getinfo", "user" to user.trim()))["user"]["name"].str
            ?: throw ProviderException("$displayName: user not found")
        save(ctx, Tokens(accessToken = name, userName = name))
    }

    override suspend fun playlists(ctx: Context): List<Playlist> = emptyList()

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        val user = creds(ctx).userName
        if (playlistId == MusicProvider.RECENT_ID) {
            val j = call(ctx, mapOf("method" to "user.getrecenttracks", "user" to user, "limit" to "200"))
            return j["recenttracks"]["track"].arr.mapNotNull { t ->
                if (t["@attr"]["nowplaying"].str == "true") return@mapNotNull null
                val title = t["name"].str ?: return@mapNotNull null
                val artist = t["artist"]["#text"].str ?: t["artist"]["name"].str
                Track(
                    id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|$artist",
                    title = title, artists = listOfNotNull(artist), album = t["album"]["#text"].str ?: "",
                    addedAt = (t["date"]["uts"].str?.toLongOrNull() ?: 0) * 1000,
                )
            }.distinctBy { it.id }
        }
        if (playlistId != MusicProvider.LIKED_ID) return emptyList()
        val out = ArrayList<Track>()
        var page = 1
        var pages = 1
        do {
            val j = call(ctx, mapOf("method" to "user.getlovedtracks", "user" to user, "limit" to "200", "page" to page.toString()))
            val root = j["lovedtracks"]
            pages = root["@attr"]["totalPages"].str?.toIntOrNull() ?: 1
            for (t in root["track"].arr) {
                val title = t["name"].str ?: continue
                out += Track(
                    id = t["mbid"].str?.takeIf { it.isNotBlank() } ?: t["url"].str ?: "$title|${t["artist"]["name"].str}",
                    title = title,
                    artists = listOfNotNull(t["artist"]["name"].str),
                )
            }
            page++
        } while (page <= pages && page <= 50)
        return out
    }

    override suspend fun search(ctx: Context, track: Track): List<Track> = unsupported(ctx)
    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist = unsupported(ctx)
    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) = unsupported(ctx)
    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) = unsupported(ctx)

    companion object { const val SERVICE = "lastfm" }
}

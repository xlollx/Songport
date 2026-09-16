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
import kotlinx.serialization.json.JsonElement

/**
 * ListenBrainz (https://listenbrainz.readthedocs.io): sorgente in sola lettura, API aperta senza
 * chiave. Espone le playlist dell'utente (formato JSPF) e i brani "amati" (feedback +1).
 */
class ListenBrainzProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "ListenBrainz"
    override val brandColor = 0xFFEB743B
    override val noteRes = R.string.provider_note_listenbrainz
    override val canWrite = false
    override val canRemoveTracks = false
    override val supportsLikedSongs = true
    override val loginForm = LoginForm(needsUrl = false, needsUser = true, needsSecret = false, hintRes = R.string.login_hint_listenbrainz)

    private suspend fun api(ctx: Context, path: String): JsonElement {
        val resp = Http.send("GET", "$API$path", mapOf("Accept" to "application/json"))
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${parseJson(resp.body)["error"].str ?: ""}")
        return parseJson(resp.body)
    }

    override suspend fun login(ctx: Context, url: String, user: String, secret: String) {
        val name = user.trim()
        api(ctx, "/1/user/${Http.enc(name)}/playlists?count=1")
        save(ctx, Tokens(accessToken = name, userName = name))
    }

    private fun mbidOf(identifier: String?): String? = identifier?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    override suspend fun playlists(ctx: Context): List<Playlist> {
        val user = Http.enc(creds(ctx).userName)
        val out = ArrayList<Playlist>()
        var offset = 0
        do {
            val j = api(ctx, "/1/user/$user/playlists?count=50&offset=$offset")
            val items = j["playlists"].arr
            for (p in items) {
                val pl = p["playlist"]
                val id = mbidOf(pl["identifier"].str) ?: continue
                val count = pl["extension"]["https://musicbrainz.org/doc/jspf#playlist"]["track_count"].int ?: pl["track"].arr.size
                out += Playlist(id, pl["title"].str ?: id, count)
            }
            offset += items.size
        } while (items.size == 50 && offset < 1000)
        return out
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.LIKED_ID) {
            val user = Http.enc(creds(ctx).userName)
            val out = ArrayList<Track>()
            var offset = 0
            do {
                val j = api(ctx, "/1/feedback/user/$user/get-feedback?score=1&metadata=true&count=100&offset=$offset")
                val items = j["feedback"].arr
                for (f in items) {
                    val md = f["track_metadata"]
                    val title = md["track_name"].str ?: continue
                    out += Track(
                        id = f["recording_mbid"].str ?: "$title|${md["artist_name"].str}",
                        title = title,
                        artists = listOfNotNull(md["artist_name"].str),
                        album = md["release_name"].str ?: "",
                        durationMs = md["additional_info"]["duration_ms"].long ?: 0,
                    )
                }
                offset += items.size
            } while (items.size == 100 && offset < 5000)
            return out
        }
        val pl = api(ctx, "/1/playlist/$playlistId")["playlist"]
        return pl["track"].arr.mapNotNull { t ->
            val title = t["title"].str ?: return@mapNotNull null
            val artists = t["extension"]["https://musicbrainz.org/doc/jspf#track"]["additional_metadata"]["artists"].arr
                .mapNotNull { it["artist_credit_name"].str }.ifEmpty { listOfNotNull(t["creator"].str) }
            Track(
                id = mbidOf(t["identifier"].arr.firstOrNull()?.str ?: t["identifier"].str) ?: "$title|${t["creator"].str}",
                title = title,
                artists = artists,
                album = t["album"].str ?: "",
                durationMs = t["duration"].long ?: 0,
            )
        }
    }

    override suspend fun search(ctx: Context, track: Track): List<Track> = unsupported(ctx)
    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist = unsupported(ctx)
    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) = unsupported(ctx)
    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) = unsupported(ctx)

    companion object {
        const val SERVICE = "listenbrainz"
        private const val API = "https://api.listenbrainz.org"
    }
}

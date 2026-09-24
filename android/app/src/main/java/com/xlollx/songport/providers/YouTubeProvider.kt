package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.auth.AuthFlow
import com.xlollx.songport.auth.AuthScope
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.sync.TitleParser
import kotlinx.serialization.json.JsonElement

/**
 * YouTube Music non ha un'API pubblica: usiamo YouTube Data API v3, le playlist sono le stesse.
 * Quota: 10.000 unita'/giorno per progetto Google. Una ricerca costa 100, un inserimento 50:
 * ~60 brani nuovi al giorno per TUTTI gli utenti dell'app finche' Google non alza la quota.
 * La cache degli abbinamenti (Store.matchCache) evita di ripetere le ricerche.
 */
class YouTubeProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    override val revokeUrl = "https://myaccount.google.com/permissions"
    override val displayName = "YouTube Music (Google API)"
    override val brandColor = 0xFFFF0000
    override val noteRes = R.string.provider_note_youtube

    override val setupGuide = SetupGuide(
        dashboardUrl = "https://console.cloud.google.com/apis/credentials",
        whyRes = R.string.setup_why_youtube,
        stepsArrayRes = R.array.setup_steps_youtube,
        fieldLabelRes = R.string.setup_field_client_id,
        needsSecret = true,
        // La console ricorda l'ultimo progetto scelto: dopo il primo passo i link si aprono gia' dentro.
        stepUrls = listOf(
            "https://console.cloud.google.com/projectcreate",
            "https://console.cloud.google.com/apis/library/youtube.googleapis.com",
            "https://console.cloud.google.com/auth/overview",
            "https://console.cloud.google.com/auth/audience",
            "https://console.cloud.google.com/auth/clients/create",
        ),
    )

    override val defaultClientId: String get() = BuildConfig.GOOGLE_CLIENT_ID
    override val authorizeEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenEndpoint = "https://oauth2.googleapis.com/token"
    override val scopes = "https://www.googleapis.com/auth/youtube"
    // prompt=consent garantisce il refresh token anche ai riaccessi successivi.
    override val extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent")
    // Extra accounts: Google shows the account chooser instead of reusing the signed-in one.
    override val switchAccountParams = mapOf("prompt" to "consent select_account")

    // Client Android della build: Google impone lo schema "client id invertito" (nel manifest).
    override fun redirectUri(ctx: Context) = "${BuildConfig.GOOGLE_REDIRECT_SCHEME}:/oauth2redirect"

    override fun isConfigured(ctx: Context) = clientId(ctx).isNotBlank()

    /**
     * Con le credenziali dell'utente si usa il flusso "installed app": client OAuth di tipo
     * Desktop e redirect su 127.0.0.1, perche' un client Desktop non accetta schemi personalizzati.
     * Cosi' la quota giornaliera YouTube consumata e' quella del progetto Google dell'utente,
     * non quella (condivisa da tutti) della build.
     */
    override fun startAuth(ctx: Context) {
        if (ownClientId(ctx) != null) AuthFlow.startLoopbackFlow(AuthScope, ctx, this)
        else AuthFlow.startBrowserFlow(ctx, this)
    }



    /** Ogni chiamata ha un costo in unita' di quota: lo registriamo per mostrarlo all'utente. */
    private suspend fun counted(ctx: Context, cost: Long, call: suspend () -> JsonElement): JsonElement {
        val r = call()
        QuotaMeter.add(ctx, serviceId, cost)
        return r
    }

    override suspend fun enrichAccount(ctx: Context, t: Tokens): Tokens {
        val j = api(ctx, "GET", "$API/channels?part=snippet&mine=true")
        val ch = j["items"][0]
        return t.copy(userId = ch["id"].str ?: "", userName = ch["snippet"]["title"].str ?: "")
    }

    override fun errorMessage(resp: HttpResponse): String {
        val e = parseJson(resp.body)["error"]
        val reason = e["errors"][0]["reason"].str
        if (reason == "quotaExceeded") return "daily YouTube API quota exceeded, retry tomorrow"
        return e["message"].str ?: super.errorMessage(resp)
    }

    override suspend fun playlists(ctx: Context): List<Playlist> {
        val out = ArrayList<Playlist>()
        var page: String? = null
        do {
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet,contentDetails&mine=true&maxResults=50" +
                (page?.let { "&pageToken=$it" } ?: "")) }
            for (p in j["items"].arr) {
                out += Playlist(
                    id = p["id"].str ?: continue,
                    name = p["snippet"]["title"].str ?: "",
                    trackCount = p["contentDetails"]["itemCount"].int ?: -1,
                )
            }
            page = j["nextPageToken"].str
        } while (page != null)
        return out
    }

    override suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist {
        val p = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=snippet,contentDetails&id=$playlistId") }["items"][0]
        return Playlist(playlistId, p["snippet"]["title"].str ?: playlistId, p["contentDetails"]["itemCount"].int ?: -1, ownedByMe = false)
    }

    private suspend fun isEmptyPlaylist(ctx: Context, playlistId: String): Boolean {
        val j = runCatching { counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlists?part=contentDetails&id=$playlistId") } }.getOrNull()
        val p = j["items"].arr.firstOrNull() ?: return false
        return (p["contentDetails"]["itemCount"].int ?: -1) == 0
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        data class Raw(val itemId: String, val videoId: String, val title: String, val channel: String?)
        val raws = ArrayList<Raw>()
        var page: String? = null
        do {
            val j = try {
                counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId" +
                    (page?.let { "&pageToken=$it" } ?: "")) }
            } catch (e: ProviderException) {
                // The Data API answers 404 "playlist cannot be found" for a playlist that exists but is
                // empty (typically one just created). Check the playlist itself before giving up.
                if (page == null && e.message?.contains("cannot be found") == true && isEmptyPlaylist(ctx, playlistId)) {
                    Diagnostics.log(ctx, id, "playlist $playlistId is empty; YouTube reports it as not found")
                    return emptyList()
                }
                throw e
            }
            for (item in j["items"].arr) {
                val vid = item["contentDetails"]["videoId"].str ?: continue
                val title = item["snippet"]["title"].str ?: continue
                if (title == "Private video" || title == "Deleted video") continue
                raws += Raw(item["id"].str ?: continue, vid, title, item["snippet"]["videoOwnerChannelTitle"].str)
            }
            page = j["nextPageToken"].str
        } while (page != null)

        // Durate (1 unita' di quota ogni 50 video): migliorano molto l'abbinamento.
        val durations = HashMap<String, Long>()
        raws.map { it.videoId }.distinct().chunked(50).forEach { ids ->
            val j = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=contentDetails&id=${ids.joinToString(",")}") }
            for (v in j["items"].arr) {
                val id = v["id"].str ?: continue
                durations[id] = Durations.parseIso8601(v["contentDetails"]["duration"].str)
            }
        }
        return raws.map { r ->
            val (title, artists) = TitleParser.parseYouTube(r.title, r.channel)
            Track(id = r.videoId, title = title, artists = artists, durationMs = durations[r.videoId] ?: 0, itemId = r.itemId)
        }
    }

    override suspend fun track(ctx: Context, trackId: String): Track? {
        val v = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&id=${Http.enc(trackId)}") }
        val item = v["items"].arr.firstOrNull() ?: return null
        val (title, artists) = TitleParser.parseYouTube(item["snippet"]["title"].str ?: "", item["snippet"]["channelTitle"].str)
        return Track(id = trackId, title = title, artists = artists, durationMs = Durations.parseIso8601(item["contentDetails"]["duration"].str))
    }

    override fun webSearchUrl(ctx: Context, query: String): String = "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        val s = counted(ctx, QuotaMeter.COST_SEARCH) { api(ctx, "GET", "$API/search?part=snippet&type=video&videoCategoryId=10&maxResults=5&q=${Http.enc(q)}") }
        val ids = s["items"].arr.mapNotNull { it["id"]["videoId"].str }
        if (ids.isEmpty()) return emptyList()
        val v = counted(ctx, QuotaMeter.COST_LIST) { api(ctx, "GET", "$API/videos?part=snippet,contentDetails&id=${ids.joinToString(",")}") }
        return v["items"].arr.mapNotNull { item ->
            val id = item["id"].str ?: return@mapNotNull null
            val (title, artists) = TitleParser.parseYouTube(item["snippet"]["title"].str ?: "", item["snippet"]["channelTitle"].str)
            Track(id = id, title = title, artists = artists, durationMs = Durations.parseIso8601(item["contentDetails"]["duration"].str))
        }
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val j = counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/playlists?part=snippet,status",
            jsonObj(
                "snippet" to mapOf("title" to name, "description" to description),
                "status" to mapOf("privacyStatus" to "private"),
            )) }
        return Playlist(j["id"].str ?: throw ProviderException("YouTube: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        for (t in tracks) {
            counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "POST", "$API/playlistItems?part=snippet",
                jsonObj("snippet" to mapOf(
                    "playlistId" to playlistId,
                    "resourceId" to mapOf("kind" to "youtube#video", "videoId" to t.id),
                ))) }
        }
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        for (t in tracks) {
            val itemId = t.itemId ?: continue
            counted(ctx, QuotaMeter.COST_WRITE) { api(ctx, "DELETE", "$API/playlistItems?id=$itemId") }
        }
    }

    companion object {
        const val SERVICE = "youtube"
        private const val API = "https://www.googleapis.com/youtube/v3"
    }
}

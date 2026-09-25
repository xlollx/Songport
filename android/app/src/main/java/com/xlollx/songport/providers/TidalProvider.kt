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
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement

/**
 * TIDAL Open API v2 (https://developer.tidal.com), formato JSON:API.
 * BETA: l'API TIDAL e' giovane e cambia; gli endpoint sono isolati qui per aggiornarli in fretta.
 */
class TidalProvider(override val slot: String = "") : OAuthProvider() {
    override val serviceId = SERVICE
    override val revokeUrl = "https://account.tidal.com/"
    override val displayName = "TIDAL"
    override val brandColor = 0xFF111111
    override val noteRes = R.string.provider_note_tidal
    override val beta = true

    override val setupGuide = SetupGuide(
        dashboardUrl = "https://developer.tidal.com/dashboard",
        redirectUri = AuthFlow.REDIRECT_URI,
        stepUrls = listOf("https://developer.tidal.com/dashboard", "https://developer.tidal.com/dashboard/create", null, "https://developer.tidal.com/dashboard"),
        whyRes = R.string.setup_why_tidal,
        stepsArrayRes = R.array.setup_steps_tidal,
        fieldLabelRes = R.string.setup_field_client_id,
    )

    override val defaultClientId: String get() = BuildConfig.TIDAL_CLIENT_ID
    override val authorizeEndpoint = "https://login.tidal.com/authorize"
    override val tokenEndpoint = "https://auth.tidal.com/v1/oauth2/token"
    override val scopes = "user.read playlists.read playlists.write search.read"
    override val apiAccept = "application/vnd.api+json"
    override val apiContentType = "application/vnd.api+json"


    override suspend fun enrichAccount(ctx: Context, t: Tokens): Tokens {
        val me = api(ctx, "GET", "$API/users/me")["data"]
        val a = me["attributes"]
        return t.copy(
            userId = me["id"].str ?: "",
            userName = a["username"].str ?: listOfNotNull(a["firstName"].str, a["lastName"].str).joinToString(" "),
            extra = mapOf("country" to (a["country"].str ?: "US")),
        )
    }

    override fun errorMessage(resp: HttpResponse): String =
        parseJson(resp.body)["errors"][0]["detail"].str ?: super.errorMessage(resp)

    private suspend fun cc(ctx: Context) = tokens(ctx).extra["country"] ?: "US"

    /** Segue `links.next` (percorso relativo alla radice API). */
    private fun nextUrl(j: JsonElement?): String? = j["links"]["next"].str?.let { if (it.startsWith("http")) it else API + it }

    override suspend fun playlists(ctx: Context): List<Playlist> {
        val t = tokens(ctx)
        val out = ArrayList<Playlist>()
        var url: String? = "$API/playlists?countryCode=${cc(ctx)}&filter[r.owners.id]=${Http.enc(t.userId)}"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (p in j["data"].arr) {
                out += Playlist(
                    id = p["id"].str ?: continue,
                    name = p["attributes"]["name"].str ?: "",
                    trackCount = p["attributes"]["numberOfItems"].int ?: -1,
                )
            }
            url = nextUrl(j)
        }
        return out
    }

    private fun trackFrom(res: JsonElement?, artistNames: Map<String, String>, itemId: String? = null): Track? {
        val id = res["id"].str ?: return null
        val a = res["attributes"]
        val artistIds = res["relationships"]["artists"]["data"].arr.mapNotNull { it["id"].str }
        return Track(
            id = id,
            title = a["title"].str ?: "",
            artists = artistIds.mapNotNull { artistNames[it] },
            durationMs = Durations.parseIso8601(a["duration"].str),
            isrc = a["isrc"].str,
            itemId = itemId,
            explicit = a["explicit"].bool,
        )
    }

    private suspend fun artistNames(ctx: Context, ids: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        ids.distinct().chunked(20).forEach { chunk ->
            val j = api(ctx, "GET", "$API/artists?countryCode=${cc(ctx)}&filter[id]=${chunk.joinToString(",")}")
            for (a in j["data"].arr) out[a["id"].str ?: continue] = a["attributes"]["name"].str ?: ""
        }
        return out
    }

    private suspend fun resolve(ctx: Context, trackResources: List<JsonElement>): List<Track> {
        val artistIds = trackResources.flatMap { r -> r["relationships"]["artists"]["data"].arr.mapNotNull { it["id"].str } }
        val names = artistNames(ctx, artistIds)
        return trackResources.mapNotNull { r -> trackFrom(r, names) }
    }

    override suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist {
        val d = api(ctx, "GET", "$API/playlists/$playlistId?countryCode=${cc(ctx)}")["data"]
        return Playlist(playlistId, d["attributes"]["name"].str ?: playlistId, d["attributes"]["numberOfItems"].int ?: -1, ownedByMe = false, description = d["attributes"]["description"].str.orEmpty())
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        val order = ArrayList<Pair<String, String?>>() // (trackId, itemId) nell'ordine della playlist
        val included = LinkedHashMap<String, JsonElement>()
        var url: String? = "$API/playlists/$playlistId/relationships/items?countryCode=${cc(ctx)}&include=items"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (d in j["data"].arr) {
                if (d["type"].str != "tracks") continue
                order += (d["id"].str ?: continue) to d["meta"]["itemId"].str
            }
            for (inc in j["included"].arr) if (inc["type"].str == "tracks") included[inc["id"].str ?: continue] = inc
            url = nextUrl(j)
        }
        val names = artistNames(ctx, included.values.flatMap { r -> r["relationships"]["artists"]["data"].arr.mapNotNull { it["id"].str } })
        return order.mapNotNull { (tid, itemId) -> included[tid]?.let { trackFrom(it, names, itemId) } }
    }

    override suspend fun track(ctx: Context, trackId: String): Track? {
        val j = api(ctx, "GET", "$API/tracks/$trackId?countryCode=${cc(ctx)}&include=artists")
        return resolve(ctx, listOfNotNull(j["data"].takeIf { it["id"].str != null })).firstOrNull()
    }

    override fun webSearchUrl(ctx: Context, query: String): String = "https://listen.tidal.com/search?q=" + android.net.Uri.encode(query)

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        track.isrcNorm?.let { isrc ->
            val j = api(ctx, "GET", "$API/tracks?countryCode=${cc(ctx)}&filter[isrc]=$isrc")
            val r = resolve(ctx, j["data"].arr)
            if (r.isNotEmpty()) return r
        }
        val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
        val j = api(ctx, "GET", "$API/searchResults/${Http.enc(q)}/relationships/tracks?countryCode=${cc(ctx)}&include=tracks")
        val ids = j["data"].arr.mapNotNull { it["id"].str }.take(5).toSet()
        val res = j["included"].arr.filter { it["type"].str == "tracks" && it["id"].str in ids }
        return resolve(ctx, res)
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "$API/playlists?countryCode=${cc(ctx)}",
            jsonObj("data" to mapOf(
                "type" to "playlists",
                "attributes" to mapOf("name" to name, "description" to description, "accessType" to "UNLISTED"),
            )))
        return Playlist(j["data"]["id"].str ?: throw ProviderException("TIDAL: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        tracks.chunked(20).forEach { chunk ->
            api(ctx, "POST", "$API/playlists/$playlistId/relationships/items?countryCode=${cc(ctx)}",
                jsonObj("data" to jsonArr(chunk.map { mapOf("type" to "tracks", "id" to it.id) })))
        }
    }

    override suspend fun renamePlaylist(ctx: Context, playlistId: String, name: String) {
        api(ctx, "PATCH", "$API/playlists/$playlistId?countryCode=${cc(ctx)}",
            jsonObj("data" to mapOf("type" to "playlists", "id" to playlistId, "attributes" to mapOf("name" to name))))
    }

    override suspend fun deletePlaylist(ctx: Context, playlistId: String) {
        api(ctx, "DELETE", "$API/playlists/$playlistId")
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        tracks.filter { it.itemId != null }.chunked(20).forEach { chunk ->
            api(ctx, "DELETE", "$API/playlists/$playlistId/relationships/items",
                jsonObj("data" to jsonArr(chunk.map { mapOf("type" to "tracks", "id" to it.id, "meta" to mapOf("itemId" to it.itemId)) })))
        }
    }

    companion object {
        const val SERVICE = "tidal"
        private const val API = "https://openapi.tidal.com/v2"
    }
}

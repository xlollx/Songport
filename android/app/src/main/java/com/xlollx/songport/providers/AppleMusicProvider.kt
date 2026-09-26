package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.auth.AppleAuthActivity
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
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Durations
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement
import java.util.Locale

/**
 * Apple Music API (https://developer.apple.com/documentation/applemusicapi).
 *
 * Due credenziali:
 *  - **developer token**: JWT ES256 firmato con la chiave MusicKit dell'account Apple Developer.
 *    Arriva dalla build (APPLE_DEVELOPER_TOKEN) o lo incolla l'utente nelle impostazioni avanzate.
 *    Dura al massimo 6 mesi, poi va rigenerato.
 *  - **music user token**: ottenuto con MusicKit JS dentro una WebView (AppleAuthActivity).
 *
 * Limite dell'API (non dell'app): si possono **aggiungere** brani a una playlist ma non toglierli,
 * quindi `canRemoveTracks` e' false e le sync a specchio verso Apple Music aggiungono soltanto.
 * Le playlist del catalogo (id `pl.…`, quelle dei link pubblici) si leggono col solo developer token.
 */
open class AppleMusicProvider(override val slot: String = "") : MusicProvider {
    override val serviceId = SERVICE
    override val authDomain = "appleid.apple.com"
    override val revokeUrl = "https://appleid.apple.com/account/manage"
    override val displayName = "Apple Music"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.OFFICIAL
    override val routeNoteRes: Int? get() = R.string.route_note_apple_api
    override val familyName: String get() = "Apple Music"
    override val brandColor = 0xFFFA243C
    override val noteRes = R.string.provider_note_apple
    override val beta = true
    override val searchesByIsrc: Boolean get() = true
    override val canRemoveTracks: Boolean get() = false
    override val canRenamePlaylists: Boolean get() = false
    override val canDeletePlaylists: Boolean get() = false
    /** Library albums can be listed and added; the API has no "followed artists". */
    override val supportsAlbums = true


    override val setupGuide: SetupGuide? = SetupGuide(
        dashboardUrl = "https://developer.apple.com/account/resources/authkeys/list",
        stepUrls = listOf("https://developer.apple.com/programs/enroll/", "https://developer.apple.com/account/resources/identifiers/list/musicId", "https://developer.apple.com/account/resources/authkeys/add", "https://github.com/xlollx/Songport#api-configuration", null),
        whyRes = R.string.setup_why_apple,
        stepsArrayRes = R.array.setup_steps_apple,
        fieldLabelRes = R.string.setup_field_apple_token,
        multiline = true,
    )

    override fun usesOwnCredentials(ctx: Context): Boolean =
        !Store.get(ctx).data.settings.clientIds[serviceId].isNullOrBlank()

    open fun developerToken(ctx: Context): String =
        Store.get(ctx).data.settings.clientIds[serviceId]?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.APPLE_DEVELOPER_TOKEN

    /** Il music user token della libreria dell'utente (null = non collegato). */
    protected open fun userToken(ctx: Context): String? = TokenStore(ctx).get(id)?.accessToken

    override fun isConfigured(ctx: Context) = developerToken(ctx).isNotBlank()
    override fun isConnected(ctx: Context) = TokenStore(ctx).get(id) != null
    override fun accountName(ctx: Context): String? =
        TokenStore(ctx).get(id)?.let { t -> t.extra["storefront"]?.uppercase()?.let { "Apple Music ($it)" } ?: displayName }

    override fun canRead(ctx: Context, playlistId: String): Boolean =
        if (playlistId.startsWith(CATALOG_PREFIX)) isConfigured(ctx) else isConnected(ctx)

    override fun startAuth(ctx: Context) {
        ctx.startActivity(
            Intent(ctx, AppleAuthActivity::class.java)
                .putExtra(AppleAuthActivity.EXTRA_PROVIDER, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Chiamata da AppleAuthActivity con il music user token ottenuto da MusicKit JS. */
    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) {
        val mut = params["music_user_token"] ?: throw ProviderException("Apple Music: no user token")
        val store = TokenStore(ctx)
        store.set(id, Tokens(accessToken = mut, userName = displayName))
        val sf = try {
            api(ctx, "GET", "$API/v1/me/storefront")["data"][0]["id"].str ?: defaultStorefront()
        } catch (e: Exception) {
            defaultStorefront()
        }
        store.set(id, Tokens(accessToken = mut, userName = displayName, extra = mapOf("storefront" to sf)))
    }

    override fun disconnect(ctx: Context) = TokenStore(ctx).clear(id)

    private fun defaultStorefront(): String =
        Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"

    protected open fun storefront(ctx: Context): String =
        TokenStore(ctx).get(id)?.extra?.get("storefront") ?: defaultStorefront()

    /**
     * Richiesta all'API. Il developer token basta per il catalogo; il music user token serve
     * solo per la libreria (endpoint /v1/me/...).
     */
    private suspend fun api(ctx: Context, method: String, url: String, body: JsonElement? = null): JsonElement {
        val dev = developerToken(ctx)
        if (dev.isBlank()) throw ProviderException(ctx.getString(R.string.apple_token_missing))
        val headers = HashMap<String, String>()
        headers["Authorization"] = "Bearer $dev"
        headers["Accept"] = "application/json"
        val needsUser = "/v1/me/" in url
        if (needsUser) {
            val mut = userToken(ctx)
                ?: throw ProviderException(ctx.getString(R.string.error_not_connected, displayName))
            headers["Music-User-Token"] = mut
        }
        val resp = Http.send(method, url, headers, body?.let { Http.jsonBody(it.toString()) })
        if (!resp.ok) {
            val detail = parseJson(resp.body)["errors"][0]["detail"].str ?: resp.body.take(200)
            // 401 sul developer token = scaduto; 403 con user token = sessione da rifare.
            if (resp.code == 401 && needsUser) TokenStore(ctx).clear(id)
            throw ProviderException("$displayName API ${resp.code}: $detail")
        }
        return parseJson(resp.body)
    }

    /** `next` e' un percorso relativo tipo "/v1/me/library/playlists?offset=100". */
    private fun nextUrl(j: JsonElement?): String? =
        j["next"].str?.let { if (it.startsWith("http")) it else API + it }

    override suspend fun playlists(ctx: Context): List<Playlist> {
        val out = ArrayList<Playlist>()
        var url: String? = "$API/v1/me/library/playlists?limit=100"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (p in j["data"].arr) {
                val a = p["attributes"]
                out += Playlist(
                    id = p["id"].str ?: continue,
                    name = a["name"].str ?: "",
                    // canEdit=false per le playlist sincronizzate da iTunes o create da Apple.
                    ownedByMe = a["canEdit"].bool ?: true,
                )
            }
            url = nextUrl(j)
        }
        return out
    }

    override suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist {
        val url = if (playlistId.startsWith(CATALOG_PREFIX)) "$API/v1/catalog/${storefront(ctx)}/playlists/$playlistId"
        else "$API/v1/me/library/playlists/$playlistId"
        val d = api(ctx, "GET", url)["data"][0]
        return Playlist(playlistId, d["attributes"]["name"].str ?: playlistId, ownedByMe = !playlistId.startsWith(CATALOG_PREFIX), description = d["attributes"]["description"]["standard"].str.orEmpty())
    }

    /** Brano di libreria: l'id utile per aggiungerlo altrove e' quello di catalogo (playParams.catalogId). */
    private fun libraryTrack(d: JsonElement?): Track? {
        val libId = d["id"].str ?: return null
        val a = d["attributes"]
        val catalogId = a["playParams"]["catalogId"].str ?: a["playParams"]["id"].str
        return Track(
            id = catalogId ?: libId,
            title = a["name"].str ?: "",
            artists = splitArtists(a["artistName"].str),
            album = a["albumName"].str ?: "",
            durationMs = a["durationInMillis"].long ?: 0,
            itemId = libId,
            year = Durations.year(a["releaseDate"].str),
            addedAt = Durations.parseInstant(a["dateAdded"].str),
        )
    }

    private fun catalogAlbum(d: JsonElement?): Track? {
        val id = d["id"].str ?: return null
        val a = d["attributes"]
        return Track(id = id, title = a["name"].str ?: "", artists = splitArtists(a["artistName"].str), isrc = a["upc"].str, kind = Track.KIND_ALBUM)
    }

    private fun catalogTrack(d: JsonElement?): Track? {
        val cid = d["id"].str ?: return null
        val a = d["attributes"]
        return Track(
            id = cid,
            title = a["name"].str ?: "",
            artists = splitArtists(a["artistName"].str),
            album = a["albumName"].str ?: "",
            durationMs = a["durationInMillis"].long ?: 0,
            isrc = a["isrc"].str,
            explicit = a["contentRating"].str?.let { it == "explicit" },
            year = Durations.year(a["releaseDate"].str),
        )
    }

    /** Apple restituisce un unico campo "artistName" tipo "Daft Punk & Pharrell Williams". */
    private fun splitArtists(s: String?): List<String> =
        s?.split(Regex("""\s*(,|&|feat\.|ft\.|featuring)\s*""", RegexOption.IGNORE_CASE))
            ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        if (playlistId == MusicProvider.ALBUMS_ID) {
            val out = ArrayList<Track>()
            var url: String? = "$API/v1/me/library/albums?limit=100"
            while (url != null) {
                val j = api(ctx, "GET", url)
                for (d in j["data"].arr) {
                    // The catalogue id, when the library album is linked to one, is what other services can match.
                    val id = d["attributes"]["playParams"]["catalogId"].str ?: d["id"].str ?: continue
                    val a = d["attributes"]
                    out += Track(id = id, title = a["name"].str ?: "", artists = splitArtists(a["artistName"].str), kind = Track.KIND_ALBUM)
                }
                url = nextUrl(j)
            }
            return out
        }
        val catalog = playlistId.startsWith(CATALOG_PREFIX)
        val out = ArrayList<Track>()
        var url: String? = if (catalog) "$API/v1/catalog/${storefront(ctx)}/playlists/$playlistId/tracks?limit=100"
        else "$API/v1/me/library/playlists/$playlistId/tracks?limit=100"
        while (url != null) {
            val j = api(ctx, "GET", url)
            for (d in j["data"].arr) {
                val t = if (catalog) catalogTrack(d) else libraryTrack(d)
                if (t != null && t.title.isNotBlank()) out += t
            }
            url = nextUrl(j)
        }
        return out
    }

    override suspend fun track(ctx: Context, trackId: String): Track? =
        catalogTrack(api(ctx, "GET", "$API/v1/catalog/${storefront(ctx)}/songs/${Http.enc(trackId)}")["data"].arr.firstOrNull())

    override fun webSearchUrl(ctx: Context, query: String): String = "https://music.apple.com/search?term=" + android.net.Uri.encode(query)

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        val sf = storefront(ctx)
        if (track.kind == Track.KIND_ALBUM) {
            track.isrcNorm?.let { upc ->
                val j = api(ctx, "GET", "$API/v1/catalog/$sf/albums?filter[upc]=$upc")
                val r = j["data"].arr.mapNotNull { catalogAlbum(it) }
                if (r.isNotEmpty()) return r
            }
            val term = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
            val j = api(ctx, "GET", "$API/v1/catalog/$sf/search?types=albums&limit=5&term=${Http.enc(term)}")
            return j["results"]["albums"]["data"].arr.mapNotNull { catalogAlbum(it) }
        }
        if (track.kind == Track.KIND_ARTIST) return emptyList()
        track.isrcNorm?.let { isrc ->
            val j = api(ctx, "GET", "$API/v1/catalog/$sf/songs?filter[isrc]=$isrc&limit=5")
            val r = j["data"].arr.mapNotNull { catalogTrack(it) }
            if (r.isNotEmpty()) return r
        }
        val term = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title))
            .joinToString(" ")
        val j = api(ctx, "GET", "$API/v1/catalog/$sf/search?types=songs&limit=5&term=${Http.enc(term)}")
        return j["results"]["songs"]["data"].arr.mapNotNull { catalogTrack(it) }
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "$API/v1/me/library/playlists",
            jsonObj("attributes" to mapOf("name" to name, "description" to description)))
        val pid = j["data"][0]["id"].str ?: throw ProviderException("Apple Music: playlist not created")
        return Playlist(pid, name, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.ALBUMS_ID) {
            tracks.chunked(25).forEach { chunk -> api(ctx, "POST", "$API/v1/me/library?ids[albums]=${chunk.joinToString(",") { it.id }}") }
            return
        }
        // Solo id di catalogo: un brano trovato nella libreria altrui non e' aggiungibile.
        tracks.chunked(25).forEach { chunk ->
            api(ctx, "POST", "$API/v1/me/library/playlists/$playlistId/tracks",
                jsonObj("data" to jsonArr(chunk.map { mapOf("id" to it.id, "type" to "songs") })))
        }
    }

    /** L'API Apple Music non espone la rimozione di brani da una playlist (vedi canRemoveTracks). */
    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        throw ProviderException(ctx.getString(R.string.error_no_removals, displayName))
    }

    companion object {
        const val SERVICE = "apple"
        private const val API = "https://api.music.apple.com"
        /** Prefisso degli id delle playlist del catalogo (pubbliche/editoriali). */
        private const val CATALOG_PREFIX = "pl."
    }
}

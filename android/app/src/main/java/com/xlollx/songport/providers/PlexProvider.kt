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

/**
 * Plex Media Server. Si accede con l'indirizzo del server e il proprio X-Plex-Token (Plex
 * documenta come trovarlo). Plex non permette di creare una playlist vuota via API: la
 * destinazione va creata nell'app Plex e poi scelta qui.
 */
class PlexProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Plex"
    override val brandColor = 0xFFE5A00D
    override val noteRes = R.string.provider_note_plex
    override val canCreatePlaylists = false
    override val loginForm = LoginForm(needsUrl = true, needsUser = false, needsSecret = true, secretLabelRes = R.string.login_token, hintRes = R.string.login_hint_plex)

    override suspend fun login(ctx: Context, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val t = Tokens(accessToken = secret.trim(), extra = mapOf("url" to base))
        val id = api(ctx, "GET", "/identity", t)["MediaContainer"]["machineIdentifier"].str
            ?: throw ProviderException("$displayName: server not recognised")
        save(ctx, t.copy(extra = mapOf("url" to base, "machine" to id), userName = base.removePrefix("https://").removePrefix("http://")))
    }

    private suspend fun api(ctx: Context, method: String, path: String, t: Tokens = creds(ctx)): JsonElement {
        val resp = Http.send(method, "${t.extra["url"]}$path", mapOf("X-Plex-Token" to t.accessToken, "Accept" to "application/json"))
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}")
        return parseJson(resp.body)
    }

    private fun toTrack(m: JsonElement?): Track? {
        val id = m["ratingKey"].str ?: return null
        return Track(
            id = id,
            title = m["title"].str ?: "",
            artists = listOfNotNull(m["originalTitle"].str ?: m["grandparentTitle"].str),
            album = m["parentTitle"].str ?: "",
            durationMs = m["duration"].long ?: 0,
            itemId = m["playlistItemID"].str ?: m["playlistItemID"].long?.toString(),
        )
    }

    override suspend fun playlists(ctx: Context): List<Playlist> =
        api(ctx, "GET", "/playlists?playlistType=audio")["MediaContainer"]["Metadata"].arr.mapNotNull { p ->
            Playlist(p["ratingKey"].str ?: return@mapNotNull null, p["title"].str ?: "", p["leafCount"].int ?: -1)
        }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> =
        api(ctx, "GET", "/playlists/$playlistId/items")["MediaContainer"]["Metadata"].arr.mapNotNull { toTrack(it) }

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
        val hubs = api(ctx, "GET", "/hubs/search?query=${Http.enc(q)}&limit=5")["MediaContainer"]["Hub"].arr
        return hubs.filter { it["type"].str == "track" }.flatMap { it["Metadata"].arr }.mapNotNull { toTrack(it) }.take(5)
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist =
        throw ProviderException(ctx.getString(R.string.error_create_unsupported, displayName))

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        val machine = creds(ctx).extra["machine"] ?: throw ProviderException("$displayName: reconnect")
        tracks.chunked(50).forEach { chunk ->
            val uri = "server://$machine/com.plexapp.plugins.library/library/metadata/${chunk.joinToString(",") { it.id }}"
            api(ctx, "PUT", "/playlists/$playlistId/items?uri=${Http.enc(uri)}")
        }
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        tracks.mapNotNull { it.itemId }.forEach { api(ctx, "DELETE", "/playlists/$playlistId/items/$it") }
    }

    companion object { const val SERVICE = "plex" }
}

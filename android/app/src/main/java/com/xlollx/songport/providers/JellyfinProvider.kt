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
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.json.JsonElement

/** Jellyfin (https://api.jellyfin.org): server personale, nessuna registrazione, nessuna quota. */
class JellyfinProvider(override val slot: String = "") : CredentialsProvider() {
    override val serviceId = SERVICE
    override val displayName = "Jellyfin"
    override val brandColor = 0xFF00A4DC
    override val noteRes = R.string.provider_note_jellyfin
    override val beta = true
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, hintRes = R.string.login_hint_jellyfin)

    private fun authHeader(token: String?) =
        "MediaBrowser Client=\"Songport\", Device=\"Android\", DeviceId=\"songport-android\", Version=\"1.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    override suspend fun login(ctx: Context, url: String, user: String, secret: String) {
        val base = normalizeUrl(url)
        val resp = Http.send(
            "POST", "$base/Users/AuthenticateByName",
            mapOf("Authorization" to authHeader(null), "Accept" to "application/json"),
            Http.jsonBody(jsonObj("Username" to user.trim(), "Pw" to secret).toString()),
        )
        if (!resp.ok) throw ProviderException("$displayName: login failed (HTTP ${resp.code})")
        val j = parseJson(resp.body)
        val token = j["AccessToken"].str ?: throw ProviderException("$displayName: no token")
        save(ctx, Tokens(accessToken = token, userId = j["User"]["Id"].str ?: "", userName = user.trim(), extra = mapOf("url" to base)))
    }

    private suspend fun api(ctx: Context, method: String, path: String, body: JsonElement? = null): JsonElement {
        val t = creds(ctx)
        val resp = Http.send(
            method, "${t.extra["url"]}$path",
            mapOf("Authorization" to authHeader(t.accessToken), "Accept" to "application/json"),
            body?.let { Http.jsonBody(it.toString()) },
        )
        if (!resp.ok) throw ProviderException("$displayName API ${resp.code}: ${resp.body.take(200)}")
        return parseJson(resp.body)
    }

    private fun uid(ctx: Context) = creds(ctx).userId

    private fun toTrack(i: JsonElement?): Track? {
        val id = i["Id"].str ?: return null
        return Track(
            id = id,
            title = i["Name"].str ?: "",
            artists = i["Artists"].arr.mapNotNull { it.str }.ifEmpty { listOfNotNull(i["AlbumArtist"].str) },
            album = i["Album"].str ?: "",
            durationMs = (i["RunTimeTicks"].long ?: 0) / 10_000,
            itemId = i["PlaylistItemId"].str,
        )
    }

    override suspend fun playlists(ctx: Context): List<Playlist> =
        api(ctx, "GET", "/Users/${uid(ctx)}/Items?IncludeItemTypes=Playlist&Recursive=true&Fields=ChildCount")["Items"].arr
            .mapNotNull { p -> Playlist(p["Id"].str ?: return@mapNotNull null, p["Name"].str ?: "", p["ChildCount"].int ?: -1) }

    override suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist {
        val p = api(ctx, "GET", "/Users/${uid(ctx)}/Items/$playlistId")
        return Playlist(playlistId, p["Name"].str ?: playlistId, p["ChildCount"].int ?: -1)
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        val path = if (playlistId == MusicProvider.LIKED_ID) {
            "/Users/${uid(ctx)}/Items?Filters=IsFavorite&IncludeItemTypes=Audio&Recursive=true&Limit=5000"
        } else {
            "/Playlists/$playlistId/Items?UserId=${uid(ctx)}"
        }
        return api(ctx, "GET", path)["Items"].arr.mapNotNull { toTrack(it) }
    }

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        val q = (listOfNotNull(track.artists.firstOrNull()?.let { Matcher.searchArtist(it) }) + Matcher.searchTitle(track.title)).joinToString(" ")
        return api(ctx, "GET", "/Items?UserId=${uid(ctx)}&searchTerm=${Http.enc(q)}&IncludeItemTypes=Audio&Recursive=true&Limit=5")["Items"].arr
            .mapNotNull { toTrack(it) }
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val j = api(ctx, "POST", "/Playlists", jsonObj("Name" to name, "Ids" to emptyList<String>(), "UserId" to uid(ctx), "MediaType" to "Audio"))
        return Playlist(j["Id"].str ?: throw ProviderException("$displayName: playlist not created"), name, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.forEach { api(ctx, "POST", "/Users/${uid(ctx)}/FavoriteItems/${it.id}") }
            return
        }
        tracks.chunked(50).forEach { chunk ->
            api(ctx, "POST", "/Playlists/$playlistId/Items?Ids=${chunk.joinToString(",") { it.id }}&UserId=${uid(ctx)}")
        }
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (playlistId == MusicProvider.LIKED_ID) {
            tracks.forEach { api(ctx, "DELETE", "/Users/${uid(ctx)}/FavoriteItems/${it.id}") }
            return
        }
        tracks.mapNotNull { it.itemId }.chunked(50).forEach { chunk ->
            api(ctx, "DELETE", "/Playlists/$playlistId/Items?EntryIds=${chunk.joinToString(",")}")
        }
    }

    companion object { const val SERVICE = "jellyfin" }
}

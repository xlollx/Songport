package com.xlollx.songport.ytmbridge

import android.content.Context
import android.os.Bundle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The connectors' methods, the same the Songport Bridge app answers through its ContentProvider
 * (`status`, `playlists`, `tracks`, `search`, `add`, `amazon.*`, `spotify.*`, `apple.*`...), called by
 * Songport in-process. No caller check is needed: nothing outside this app can reach it.
 * Callers block, so this is never called on the main thread.
 */
object BridgeCore {
    /** Reported as "version" by the status calls: the built-in connectors have every feature. */
    const val VERSION = 1000
    const val PAGE = 400
    /** Songport's ids for the library objects, the same on every connector. */
    const val ALBUMS = "__albums__"
    const val ARTISTS = "__artists__"
    const val RECENT = "__recent__"

    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle {
        return try {
            val client = YtmClient(ctx)
            when (method) {
                "status" -> Bundle().apply {
                    putBoolean("connected", Session.isConnected(ctx))
                    putString("account", Session.account(ctx))
                    putInt("version", VERSION)
                }
                "disconnect" -> { Session.clear(ctx); Bundle() }
                "playlists" -> ok(json.encodeToString(client.playlists()))
                "playlistInfo" -> ok(json.encodeToString(client.playlistInfo(arg ?: return err("missing playlist id"))))
                "tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = cachedTracks(client, id)
                    val page = all.drop(offset).take(PAGE)
                    Bundle().apply {
                        putString("json", json.encodeToString(page))
                        putInt("total", all.size)
                        if (offset + PAGE < all.size) putInt("next", offset + PAGE)
                    }
                }
                "search" -> {
                    val kind = extras?.getString("kind")
                    ok(json.encodeToString(if (kind == "album" || kind == "artist") client.searchKind(arg ?: "", kind) else client.search(arg ?: "", extras?.getBoolean("videos") == true)))
                }
                "stats" -> Bundle().apply { putString("stats", YtmClient.Stats.summary()) }
                "create" -> ok(json.encodeToString(client.createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "add" -> {
                    val id = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids")?.toList() ?: emptyList()
                    when (id) {
                        ALBUMS -> client.rateAlbums(ids, extras?.getStringArray("uris")?.toList() ?: emptyList(), like = true)
                        ARTISTS -> client.subscribeArtists(ids, on = true)
                        else -> client.addTracks(id, ids)
                    }
                    invalidate(id)
                    Bundle()
                }
                "rename" -> { client.renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "delete" -> { client.deletePlaylist(arg ?: return err("missing playlist id")); invalidate(arg); Bundle() }
                "remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    when (id) {
                        ALBUMS -> client.rateAlbums(extras?.getStringArray("ids")?.toList() ?: emptyList(), extras?.getStringArray("uris")?.toList() ?: emptyList(), like = false)
                        ARTISTS -> client.subscribeArtists(extras?.getStringArray("ids")?.toList() ?: emptyList(), on = false)
                        else -> client.removeTracks(id, json.decodeFromString<List<RemoveItem>>(extras?.getString("json") ?: "[]"))
                    }
                    invalidate(id)
                    Bundle()
                }
                // ---- Amazon Music (experimental): only what the protocol mapping covers so far.
                "amazon.status" -> Bundle().apply {
                    putBoolean("connected", AmazonSession.isConnected(ctx))
                    putString("account", AmazonSession.account(ctx))
                    putString("domain", AmazonSession.domain(ctx))
                    putInt("version", VERSION)
                }
                "amazon.disconnect" -> { AmazonSession.clear(ctx); Bundle() }
                "amazon.search" -> ok(json.encodeToString(AmazonClient(ctx).searchTracks(arg ?: "")))
                "amazon.tracks" -> ok(json.encodeToString(AmazonClient(ctx).playlistTracks(arg ?: return err("missing playlist id"))))
                "amazon.playlists" -> ok(json.encodeToString(AmazonClient(ctx).libraryPlaylists()))
                "amazon.create" -> ok(json.encodeToString(AmazonClient(ctx).createPlaylist(extras?.getString("name") ?: "Playlist")))
                "amazon.add" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val titles = extras?.getStringArray("titles") ?: emptyArray()
                    val pname = extras?.getString("playlistName") ?: ""
                    val c = AmazonClient(ctx)
                    ids.forEachIndexed { i, id -> c.addTrack(pid, pname, id, titles.getOrNull(i) ?: ""); Thread.sleep(300) }
                    Bundle()
                }
                "amazon.remove" -> {
                    val pid = arg ?: return err("missing playlist id")
                    val ids = extras?.getStringArray("ids") ?: emptyArray()
                    val entries = extras?.getStringArray("entryIds") ?: emptyArray()
                    val c = AmazonClient(ctx)
                    // Entry ids missing (e.g. old cache): look them up from the playlist itself.
                    val byTrack = if (entries.any { it.isEmpty() }) c.playlistTracks(pid).associate { it.id to (it.setVideoId ?: "") } else emptyMap()
                    ids.forEachIndexed { i, id ->
                        val entry = entries.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: byTrack[id] ?: return@forEachIndexed
                        c.removeTrack(pid, entry, id); Thread.sleep(300)
                    }
                    Bundle()
                }
                // ---- Spotify web session: Songport gets a short-lived Web API token, never the cookies.
                "spotify.status" -> Bundle().apply {
                    putBoolean("connected", SpotifyBridge.session.isConnected(ctx))
                    putString("account", SpotifyBridge.session.account(ctx))
                    putString("userId", SpotifyBridge.session.get(ctx, "userId"))
                    putInt("version", VERSION)
                }
                "spotify.disconnect" -> { SpotifyBridge.session.clear(ctx); Bundle() }
                "spotify.playlists" -> ok(json.encodeToString(SpotifyWebClient(ctx).libraryPlaylists()))
                "spotify.version" -> Bundle().apply { SpotifyWebClient(ctx).version(arg ?: return err("missing playlist id"))?.let { putString("version", it) } }
                "spotify.playlistInfo" -> ok(json.encodeToString(SpotifyWebClient(ctx).playlistInfo(arg ?: return err("missing playlist id"))))
                "spotify.tracks" -> {
                    val id = arg ?: return err("missing playlist id")
                    val offset = extras?.getInt("offset") ?: 0
                    val all = cachedSpotifyTracks(ctx, id)
                    val page = all.drop(offset).take(PAGE)
                    Bundle().apply {
                        putString("json", json.encodeToString(page))
                        putInt("total", all.size)
                        if (offset + PAGE < all.size) putInt("next", offset + PAGE)
                    }
                }
                "spotify.search" -> ok(json.encodeToString(SpotifyWebClient(ctx).search(arg ?: "", extras?.getString("kind"))))
                "spotify.track" -> ok(json.encodeToString(SpotifyWebClient(ctx).track(arg ?: return err("missing track id"))))
                "spotify.create" -> ok(json.encodeToString(SpotifyWebClient(ctx).createPlaylist(extras?.getString("name") ?: "Playlist", extras?.getString("description") ?: "")))
                "spotify.add" -> {
                    val id = arg ?: return err("missing playlist id")
                    val uris = extras?.getStringArray("uris")?.toList() ?: emptyList()
                    val c = SpotifyWebClient(ctx)
                    when (id) {
                        "__liked__", "__albums__", "__artists__" -> c.addToLibrary(uris)
                        else -> c.addToPlaylist(id, uris)
                    }
                    invalidateSpotify(id)
                    Bundle()
                }
                "spotify.remove" -> {
                    val id = arg ?: return err("missing playlist id")
                    val c = SpotifyWebClient(ctx)
                    when (id) {
                        "__liked__", "__albums__", "__artists__" -> c.removeFromLibrary(extras?.getStringArray("uris")?.toList() ?: emptyList())
                        else -> c.removeFromPlaylist(id, json.decodeFromString<List<RemoveItem>>(extras?.getString("json") ?: "[]"))
                    }
                    invalidateSpotify(id)
                    Bundle()
                }
                "spotify.rename" -> { SpotifyWebClient(ctx).renamePlaylist(arg ?: return err("missing playlist id"), extras?.getString("name") ?: ""); Bundle() }
                "spotify.delete" -> { SpotifyWebClient(ctx).deletePlaylist(arg ?: return err("missing playlist id")); invalidateSpotify(arg); Bundle() }
                "spotify.token" -> {
                    val t = SpotifyBridge.token(ctx)
                    if (SpotifyBridge.session.get(ctx, "userId") == null) runCatching {
                        val (id, name) = SpotifyBridge.accountInfo(ctx)
                        id?.let { SpotifyBridge.session.put(ctx, "userId", it) }
                        SpotifyBridge.session.save(ctx, SpotifyBridge.session.cookies(ctx) ?: "", name)
                    }
                    Bundle().apply {
                        putString("token", t.value)
                        putLong("expiresAt", t.expiresAt)
                        putString("userId", SpotifyBridge.session.get(ctx, "userId"))
                        putString("account", SpotifyBridge.session.account(ctx))
                    }
                }
                // ---- Apple Music web session: Apple's web developer token plus the user's music user token.
                "apple.status" -> Bundle().apply {
                    putBoolean("connected", AppleBridge.session.isConnected(ctx))
                    putString("account", AppleBridge.session.account(ctx))
                    putInt("version", VERSION)
                }
                "apple.disconnect" -> { AppleBridge.session.clear(ctx); Bundle() }
                "apple.tokens" -> {
                    val user = AppleBridge.userToken(AppleBridge.session.cookies(ctx)) ?: return err("not connected")
                    Bundle().apply {
                        putString("developerToken", AppleBridge.developerToken(ctx))
                        putString("userToken", user)
                        putString("storefront", runCatching { AppleBridge.storefront(ctx) }.getOrNull())
                    }
                }
                else -> err("unknown method $method")
            }
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    // A playlist is fetched once and served in pages (Binder transactions are capped at ~1 MB).
    private var lastTracks: Triple<String, Long, List<TrackDto>>? = null

    @Synchronized
    private fun cachedTracks(client: YtmClient, id: String): List<TrackDto> {
        val now = System.currentTimeMillis()
        lastTracks?.let { (pid, at, list) -> if (pid == id && now - at < 120_000) return list }
        val list = when (id) { ALBUMS -> client.libraryAlbums(); ARTISTS -> client.librarySubscriptions(); RECENT -> client.history(); else -> client.tracks(id) }
        lastTracks = Triple(id, now, list)
        return list
    }

    @Synchronized
    private fun invalidate(id: String) { if (lastTracks?.first == id) lastTracks = null }

    private var lastSpotify: Triple<String, Long, List<TrackDto>>? = null

    @Synchronized
    private fun cachedSpotifyTracks(ctx: Context, id: String): List<TrackDto> {
        val now = System.currentTimeMillis()
        lastSpotify?.let { (pid, at, list) -> if (pid == id && now - at < 120_000) return list }
        val c = SpotifyWebClient(ctx)
        val list = when (id) {
            "__liked__" -> c.likedTracks()
            "__albums__" -> c.libraryItems("album")
            "__artists__" -> c.libraryItems("artist")
            else -> c.playlistTracks(id)
        }
        lastSpotify = Triple(id, now, list)
        return list
    }

    @Synchronized
    private fun invalidateSpotify(id: String) { if (lastSpotify?.first == id) lastSpotify = null }

    private fun ok(jsonText: String) = Bundle().apply { putString("json", jsonText) }
    private fun err(message: String) = Bundle().apply { putString("error", message) }
}

package com.xlollx.songport.server

import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.SoundCloudProvider
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.Providers.Factory
import com.xlollx.songport.providers.SpotifyProvider
import com.xlollx.songport.providers.SubsonicProvider
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.providers.YouTubeProvider
import java.io.File

/**
 * The connectors a server can use: the official APIs, the personal servers, YouTube Music, Spotify
 * and Deezer with cookies pasted from a browser (unofficial: [CookieConnector]), and public
 * SoundCloud links as a read-only source (unofficial too: [SoundCloudProvider]). The other web
 * connectors (Amazon Music, Apple Music without a key) run the service's own web player in an
 * Android WebView, and Apple Music's official route signs in with MusicKit JS in one too: none of
 * them exists without a phone.
 *
 * [officialOnly] (the Microsoft Store's package, like the app's Play build) leaves the unofficial
 * ones out: the official APIs, the personal servers, Last.fm, ListenBrainz and files remain.
 */
fun installServerConnectors(officialOnly: Boolean = false) = Providers.install(
    listOfNotNull(
        Factory(SpotifyProvider.SERVICE) { SpotifyProvider(it) },
        Factory(SpotifyWebServerProvider.SERVICE) { SpotifyWebServerProvider(it) }.takeUnless { officialOnly },
        Factory(YouTubeProvider.SERVICE) { YouTubeProvider(it) },
        Factory(YtmServerProvider.SERVICE) { YtmServerProvider(it) }.takeUnless { officialOnly },
        Factory(TidalProvider.SERVICE) { TidalProvider(it) },
        Factory(DeezerProvider.SERVICE) { DeezerProvider(it) },
        Factory(DeezerWebServerProvider.SERVICE) { DeezerWebServerProvider(it) }.takeUnless { officialOnly },
        Factory(SubsonicProvider.SERVICE) { SubsonicProvider(it) },
        Factory(JellyfinProvider.SERVICE) { JellyfinProvider(it) },
        Factory(PlexProvider.SERVICE) { PlexProvider(it) },
        Factory(LastFmProvider.SERVICE) { LastFmProvider(it) },
        Factory(ListenBrainzProvider.SERVICE) { ListenBrainzProvider(it) },
    ),
    singletons = listOfNotNull(LocalFilesProvider, SoundCloudProvider.takeUnless { officialOnly }),
)

/** The settings, from the environment (what `docker run -e` sets). */
class Config(
    env: Map<String, String>,
    defaultDataDir: String = "/data",
    defaultPort: Int = 8080,
    defaultHost: String = "127.0.0.1",
) {
    val dataDir = File(env["SONGPORT_DATA"]?.takeIf { it.isNotBlank() } ?: defaultDataDir)
    val port = env["SONGPORT_PORT"]?.toIntOrNull() ?: defaultPort
    /** Where the browser reaches this server: OAuth sign-ins come back to <url>/callback. */
    val baseUrl = (env["SONGPORT_URL"]?.takeIf { it.isNotBlank() } ?: "http://$defaultHost:$port").trimEnd('/')
    val password = env["SONGPORT_PASSWORD"]?.takeIf { it.isNotEmpty() }
    val locale = env["SONGPORT_LOCALE"]

    /** Keys given to the container instead of being typed in the page (same names as the app's build). */
    val buildDefaults: Map<String, String> = buildMap {
        for (k in listOf("SPOTIFY_CLIENT_ID", "TIDAL_CLIENT_ID", "DEEZER_APP_ID", "GOOGLE_CLIENT_ID", "LASTFM_API_KEY")) {
            env[k]?.takeIf { it.isNotBlank() }?.let { put(k, it.trim()) }
        }
        put("DEEZER_REDIRECT_URL", env["DEEZER_REDIRECT_URL"]?.takeIf { it.isNotBlank() } ?: "$baseUrl/callback")
    }
}

fun main() {
    System.setProperty("java.awt.headless", "true")
    val config = Config(System.getenv())
    val app = launch(config, Html.Edition.SERVER)
    app.web.start(config.port)
    app.started(config.password == null)
}

/** The engine set up on [config]'s data folder, not yet serving: [web] is started by the caller. */
class Launched(val env: ServerEnv, val runner: Runner, val web: Web, val version: String, private val config: Config, private val edition: Html.Edition) {
    /** Once the pages are served: the scheduler, the first log line, the data saved on exit. */
    fun started(warnNoPassword: Boolean) {
        runner.startScheduler()
        env.log("server", "${edition.product} $version (${edition.stage.uppercase()}: keep a copy of the data folder), data in ${config.dataDir.absolutePath}" + if (warnNoPassword) " (no password: set SONGPORT_PASSWORD if others can reach this port)" else "")
        Runtime.getRuntime().addShutdownHook(Thread { env.store.flush() })
    }
}

fun launch(config: Config, edition: Html.Edition, onQuit: (() -> Unit)? = null, onShow: (() -> Unit)? = null, officialOnly: Boolean = false): Launched {
    config.dataDir.mkdirs()
    Log.init(config.dataDir)
    fun resource(name: String) = Config::class.java.getResourceAsStream(name)?.use { String(it.readBytes()).trim() }
    // The server's own version, then the app version whose engine it runs.
    val version = (resource("/songport-version.txt") ?: "dev") + (resource("/engine-version.txt")?.let { " (engine $it)" } ?: "")

    BuildDefaults.values = config.buildDefaults
    installServerConnectors(officialOnly)
    val env = ServerEnv(config.dataDir, Strings.load(config.locale), config.baseUrl)
    // A first start: the services the server offers are not listed until the user adds them, as in the app.
    if (!env.store.data.settings.connectorsInit) env.store.updateSettings { it.copy(connectorsInit = true) }

    val runner = Runner(env)
    val web = Web(env, runner, Auth(env), config.password, version, edition, onQuit, onShow)
    return Launched(env, runner, web, version, config, edition)
}

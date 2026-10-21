package com.xlollx.songport.providers

import com.xlollx.songport.providers.Providers.Factory

/**
 * The connectors of the Android app, in the order the service picker shows them: the engine's,
 * with the web connectors (built in, or through a plugin app) next to their official route.
 */
fun installConnectors() = Providers.install(
    listOf(
        Factory(SpotifyProvider.SERVICE) { SpotifyProvider(it) },
        Factory(SpotifyBridgeProvider.SERVICE) { SpotifyBridgeProvider(it) },
        Factory(AppleMusicProvider.SERVICE) { AppleMusicProvider(it) },
        Factory(AppleBridgeProvider.SERVICE) { AppleBridgeProvider(it) },
        Factory(YouTubeBridgeProvider.SERVICE) { YouTubeBridgeProvider(it) },
        Factory(YouTubeProvider.SERVICE) { YouTubeProvider(it) },
        Factory(AmazonBridgeProvider.SERVICE) { AmazonBridgeProvider(it) },
        Factory(TidalProvider.SERVICE) { TidalProvider(it) },
        Factory(DeezerProvider.SERVICE) { DeezerProvider(it) },
        Factory(SubsonicProvider.SERVICE) { SubsonicProvider(it) },
        Factory(JellyfinProvider.SERVICE) { JellyfinProvider(it) },
        Factory(PlexProvider.SERVICE) { PlexProvider(it) },
        Factory(LastFmProvider.SERVICE) { LastFmProvider(it) },
        Factory(ListenBrainzProvider.SERVICE) { ListenBrainzProvider(it) },
    ),
)

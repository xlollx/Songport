package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track

/**
 * Un servizio musicale. Tutte le operazioni ricevono il Context per leggere token e impostazioni.
 * Le implementazioni sono `object` senza stato: lo stato vive in TokenStore/Store.
 */
interface MusicProvider {
    /** Servizio ("spotify"): le credenziali dell'app e le impostazioni sono per servizio. */
    val serviceId: String
    /** Account: "" = principale, "2", "3"… per gli account aggiuntivi dello stesso servizio. */
    val slot: String get() = ""
    /** Identificativo dell'istanza: token, cache abbinamenti e job puntano a questo. */
    val id: String get() = if (slot.isEmpty()) serviceId else "$serviceId@$slot"
    val displayName: String
    /** Colore ARGB per la UI (chip/badge). */
    val brandColor: Long
    /** Risorsa stringa con una breve nota mostrata nella scheda Account. */
    val noteRes: Int
    /** Se il servizio espone i "brani preferiti" come origine (id speciale LIKED_ID). */
    val supportsLikedSongs: Boolean get() = false
    /** Se si puo' anche scrivere nei "brani preferiti" (sync preferiti -> preferiti). */
    val supportsLikedTarget: Boolean get() = false
    /** False per sorgenti locali (file) che non richiedono login. */
    val requiresAuth: Boolean get() = true
    /** False per le sorgenti in sola lettura (Last.fm, ListenBrainz): mai come destinazione. */
    val canWrite: Boolean get() = true
    /** False dove la playlist va creata nell'app del servizio (Plex). */
    val canCreatePlaylists: Boolean get() = true
    /** True se ha senso collegare piu' account dello stesso servizio. */
    val supportsMultipleAccounts: Boolean get() = requiresAuth
    /** Dominio su cui l'utente fara' davvero il login (mostrato prima di aprire il browser). */
    val authDomain: String? get() = null
    /** Pagina del servizio dove revocare l'accesso all'app. */
    val revokeUrl: String? get() = null

    /**
     * False se l'API del servizio non permette di togliere brani da una playlist
     * (es. Apple Music: si possono solo aggiungere). Le sync con "rispecchia rimozioni"
     * verso questi servizi aggiungono soltanto, e lo segnalano nel report.
     */
    val canRemoveTracks: Boolean get() = true

    /**
     * Istruzioni per far usare all'utente le proprie credenziali. Null = non applicabile
     * (il ponte a file non ha nulla da configurare).
     */
    val setupGuide: SetupGuide? get() = null

    /** Servizi che passano da un'app compagna: dove scaricarla quando manca (null = non applicabile). */
    val installUrl: String? get() = null
    /** Testo mostrato al posto del generico "serve una chiave" quando il servizio non e' configurato. */
    val notConfiguredRes: Int? get() = null
    /** Ricerche in parallelo tollerate dal servizio (1 per le interfacce web non ufficiali, che bloccano le raffiche). */
    val searchParallelism: Int get() = 4
    /** True se il servizio passa da un'app plugin installata a parte (vedi BridgePlugin). */
    val pluginBased: Boolean get() = false

    /** True se questa build (o le impostazioni utente) hanno le credenziali per il servizio. */
    fun isConfigured(ctx: Context): Boolean

    /** True se le credenziali in uso sono quelle inserite dall'utente e non quelle della build. */
    fun usesOwnCredentials(ctx: Context): Boolean = false
    fun isConnected(ctx: Context): Boolean
    fun accountName(ctx: Context): String?

    /** Nome da mostrare: quello scelto dall'utente per il connettore, altrimenti il servizio (e l'account). */
    fun label(ctx: Context): String =
        Providers.customName(id) ?: if (slot.isEmpty()) displayName else "$displayName · ${accountName(ctx) ?: slot}"

    /**
     * Avvia il login. Default: OAuth nel browser (vedi OAuthProvider); Apple Music usa una
     * schermata dedicata con MusicKit JS.
     */
    fun startAuth(ctx: Context)
    /** Completa il login con i parametri del redirect (query + fragment). Solo per i flussi browser. */
    suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String)
    fun disconnect(ctx: Context)

    suspend fun playlists(ctx: Context): List<Playlist>
    suspend fun tracks(ctx: Context, playlistId: String): List<Track>
    /** Candidati per un brano di un altro servizio: prima per ISRC (se supportato), poi per testo. */
    suspend fun search(ctx: Context, track: Track): List<Track>
    suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist
    suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>)
    suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>)

    /** Nome (e conteggio) di una playlist di cui si conosce solo l'id, es. da un link incollato. */
    suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist = Playlist(playlistId, playlistId)

    /**
     * True se questa playlist e' leggibile adesso. Di default richiede l'account collegato;
     * le playlist pubbliche del catalogo Apple Music si leggono anche senza login.
     */
    fun canRead(ctx: Context, playlistId: String): Boolean = isConnected(ctx)

    /** Ricostruisce i campi nativi (es. URI) di un brano noto solo per id (dalla cache abbinamenti). */
    fun rehydrate(track: Track): Track = track

    companion object {
        const val LIKED_ID = "__liked__"
        const val DESCRIPTION = "Synced with Songport"
    }
}

object Providers {
    @Volatile private var extraAccounts: Map<String, List<String>> = emptyMap()
    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile private var connectorIds: List<String> = emptyList()
    @Volatile private var cached: List<MusicProvider> = build()

    /** Chiamato dallo Store quando cambiano i connettori scelti, gli account aggiuntivi o i loro nomi. */
    fun configure(extra: Map<String, List<String>>, connectorNames: Map<String, String> = names, connectors: List<String> = connectorIds) {
        names = connectorNames
        connectorIds = connectors
        if (extra != extraAccounts) {
            extraAccounts = extra
            cached = build()
        }
    }

    /** Nome scelto dall'utente per un connettore, se c'e'. */
    fun customName(id: String): String? = names[id]?.trim()?.takeIf { it.isNotEmpty() }

    private fun build(): List<MusicProvider> {
        val out = ArrayList<MusicProvider>()
        fun add(service: String, factory: (String) -> MusicProvider) {
            out += factory("")
            extraAccounts[service].orEmpty().forEach { out += factory(it) }
        }
        add(SpotifyProvider.SERVICE) { SpotifyProvider(it) }
        add(SpotifyBridgeProvider.SERVICE) { SpotifyBridgeProvider(it) }
        add(AppleMusicProvider.SERVICE) { AppleMusicProvider(it) }
        add(AppleBridgeProvider.SERVICE) { AppleBridgeProvider(it) }
        add(YouTubeBridgeProvider.SERVICE) { YouTubeBridgeProvider(it) }
        add(YouTubeProvider.SERVICE) { YouTubeProvider(it) }
        add(AmazonBridgeProvider.SERVICE) { AmazonBridgeProvider(it) }
        add(TidalProvider.SERVICE) { TidalProvider(it) }
        add(DeezerProvider.SERVICE) { DeezerProvider(it) }
        add(SubsonicProvider.SERVICE) { SubsonicProvider(it) }
        add(JellyfinProvider.SERVICE) { JellyfinProvider(it) }
        add(PlexProvider.SERVICE) { PlexProvider(it) }
        add(LastFmProvider.SERVICE) { LastFmProvider(it) }
        add(ListenBrainzProvider.SERVICE) { ListenBrainzProvider(it) }
        out += LocalFilesProvider
        return out
    }

    fun all(): List<MusicProvider> = cached

    /**
     * I connettori aggiunti dall'utente, nell'ordine in cui li ha aggiunti. E' questo l'elenco che la
     * UI deve mostrare: un servizio tolto dalla scheda Account non deve piu' comparire altrove, anche
     * se il token o la sessione nell'app plugin sono ancora validi.
     */
    fun connectors(): List<MusicProvider> = connectorIds.mapNotNull { id -> cached.firstOrNull { it.id == id } }
    fun byId(id: String): MusicProvider? = cached.firstOrNull { it.id == id }
    /** Un'istanza per servizio (account principale). */
    fun services(): List<MusicProvider> = cached.filter { it.slot.isEmpty() }
    /** Tutte le istanze (account) di un servizio. */
    fun accountsOf(serviceId: String): List<MusicProvider> = cached.filter { it.serviceId == serviceId }
}

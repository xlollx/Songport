package com.xlollx.songport.model

import kotlinx.serialization.Serializable

/** Un brano cosi' come lo vede un servizio. `id` e' l'identificativo nel servizio. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
    val isrc: String? = null,
    /** URI nativo (es. spotify:track:xxx) quando il servizio lo richiede per aggiungere/rimuovere. */
    val uri: String? = null,
    /** Id dell'elemento dentro la playlist (YouTube playlistItem, TIDAL itemId): serve per rimuovere. */
    val itemId: String? = null,
    /**
     * Cosa rappresenta: "" un brano, [KIND_ALBUM] un album salvato (title = titolo dell'album, isrc = UPC),
     * [KIND_ARTIST] un artista seguito (title = nome). Album e artisti passano dalla stessa pipeline dei
     * brani: elenco, ricerca, abbinamento, revisione; i connettori guardano il tipo per cercare nel
     * catalogo giusto.
     */
    val kind: String = "",
    /** Testo esplicito, dove il servizio lo dice; null = non noto. */
    val explicit: Boolean? = null,
    /** Anno di uscita (0 = non noto) e data di aggiunta alla playlist in epoch ms (0 = non nota): per gli ordinamenti. */
    val year: Int = 0,
    val addedAt: Long = 0,
) {
    val artistLine: String get() = artists.joinToString(", ")
    val isrcNorm: String? get() = isrc?.trim()?.uppercase()?.takeIf { it.length >= 10 }
    override fun toString(): String = if (artists.isEmpty() || kind == KIND_ARTIST) title else "$artistLine – $title"

    companion object {
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"
        /** Un podcast seguito (title = nome dello show, artists = editore). */
        const val KIND_PODCAST = "podcast"
    }
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int = -1,
    val ownedByMe: Boolean = true,
    val description: String = "",
    /** The cover's address, when the service lists one cheaply: carried to a created target where allowed. */
    val imageUrl: String? = null,
    /** Public (on the profile, findable) or private; null when the service does not say in its listing. */
    val isPublic: Boolean? = null,
)

@Serializable
enum class Schedule(val minutes: Long) {
    MANUAL(0), HOURLY(60), EVERY_6H(360), DAILY(1440), WEEKLY(10080)
}

/** Riferimento a una playlist di un servizio. `playlistId` null = "da creare" (solo destinazione). */
@Serializable
data class PlaylistRef(
    val provider: String,
    val playlistId: String? = null,
    val playlistName: String = "",
)

@Serializable
data class SyncJob(
    val id: String,
    val name: String,
    val source: PlaylistRef,
    val target: PlaylistRef,
    val schedule: Schedule = Schedule.MANUAL,
    val mirrorRemovals: Boolean = false,
    val wifiOnly: Boolean = false,
    val enabled: Boolean = true,
    val lastRunEpoch: Long = 0,
    val lastReportId: String? = null,
    /** Brani dell'origine che l'utente ha scelto di ignorare (non piu' cercati ne' segnalati). */
    val ignoredSourceIds: List<String> = emptyList(),
    /** Sync gemella nella direzione opposta (bidirezionale). */
    val linkedJobId: String? = null,
    /** Playlist scritta dall'AI dell'utente: la descrizione data, per riconoscerla e per allungarla. */
    val aiPrompt: String? = null,
    /** Versioni (vedi MusicProvider.playlistVersion) viste all'ultimo giro riuscito: uguali = niente da fare. */
    val sourceVersion: String? = null,
    val targetVersion: String? = null,
    val policy: MatchPolicy = MatchPolicy(),
)

/**
 * Come una sync sceglie fra i candidati. Le regole di base (ISRC, titolo, artista, durata, versioni)
 * valgono sempre; queste le stringono o le allargano per chi sa cosa vuole: chi ha una playlist di
 * versioni esplicite, chi non vuole mai un live al posto dello studio, chi preferisce meno "da
 * rivedere" e piu' abbinamenti automatici.
 */
@Serializable
data class MatchPolicy(
    /** -1 permissiva, 0 normale, 1 severa: sposta le soglie di accettazione e di riesame. */
    val strictness: Int = 0,
    /** -1 preferisci pulite, 0 indifferente, 1 preferisci esplicite (dove il servizio lo indica). */
    val explicit: Int = 0,
    /** Un album diverso pesa molto invece che poco. */
    val sameAlbum: Boolean = false,
    /** Mai una versione live/remix/acustica/karaoke al posto di una che non lo e'. */
    val studioOnly: Boolean = false,
) {
    val acceptThreshold: Double get() = 0.70 + 0.08 * strictness
    val reviewThreshold: Double get() = 0.86 + 0.05 * strictness
}

/** Un abbinamento trovato con punteggio basso: giusto probabilmente, ma da far confermare. */
@Serializable
data class MatchReview(val source: Track, val chosen: Track, val score: Double)

@Serializable
data class SyncReport(
    val id: String,
    val jobId: String,
    val jobName: String,
    val startedEpoch: Long,
    val durationMs: Long,
    val sourceCount: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val unmatched: List<String> = emptyList(),
    /** Gli stessi brani non trovati, ma strutturati: servono per la risoluzione manuale. */
    val unmatchedTracks: List<Track> = emptyList(),
    /** Brani saltati perche' l'utente li ha messi fra gli ignorati. */
    val ignored: Int = 0,
    /** Abbinamenti incerti da confermare o cambiare. */
    val reviewTracks: List<MatchReview> = emptyList(),
    /** Brani tolti dalla destinazione con "rispecchia rimozioni": si possono ripristinare. */
    val removedTracks: List<Track> = emptyList(),
    /** Avvisi non bloccanti (es. destinazione che non permette di rimuovere brani). */
    val notes: List<String> = emptyList(),
    val error: String? = null,
    /** True finche' la sync e' in corso: contiene gia' i brani da rivedere, il resto arriva alla fine. */
    val partial: Boolean = false,
    /**
     * Per i non trovati, il candidato migliore rimasto sotto soglia (id del brano d'origine -> brano
     * della destinazione): una proposta da accettare con un tocco, senza rifare la ricerca.
     */
    val suggestions: Map<String, Track> = emptyMap(),
) {
    val ok: Boolean get() = error == null
}

/**
 * Cosa farebbe una sync, calcolato senza toccare nulla. Serve all'anteprima e come prima meta'
 * dell'esecuzione vera (che poi applica il piano).
 */
data class SyncPlan(
    val targetPlaylistId: String,
    val sourceCount: Int,
    val alreadyPresent: Int,
    val toAdd: List<Track>,
    val toRemove: List<Track>,
    val unmatched: List<Track>,
    val ignored: Int,
    /** Abbinamenti con punteggio sotto la soglia di fiducia: da mostrare per conferma. */
    val uncertain: List<MatchReview>,
    val notes: List<String>,
    /** Abbinamenti scoperti durante il piano, da salvare in cache quando si applica. */
    val newMatches: Map<String, String>,
    /** True se la destinazione e' stata creata adesso (il job e' gia' stato aggiornato). */
    val targetCreated: Boolean,
)

/** Progresso di una sync in corso, mostrato nella UI. */
/**
 * Esito di una ricerca manuale sulla destinazione: tutti i risultati con punteggio e provenienza,
 * gia' ordinati dal piu' simile, e l'album del brano d'origine (trovato o no; null se il servizio non
 * riporta gli album e quindi non si puo' dire nulla).
 */
data class TargetSearch(val hits: List<Hit>, val album: String? = null, val albumFound: Boolean? = null) {
    /** Where a result came from: the song catalogue, the source track's album, or a wider catalogue (YouTube videos). */
    enum class Kind { SONG, ALBUM, VIDEO }
    data class Hit(val track: Track, val score: Double, val kind: Kind)
    fun of(kind: Kind): List<Hit> = hits.filter { it.kind == kind }
}

data class Progress(val step: Step, val done: Int = 0, val total: Int = 0, val label: String? = null) {
    /** WAITING: il servizio limita le richieste; done/total = secondi trascorsi/da attendere. */
    enum class Step { FETCH_SOURCE, CREATE_TARGET, FETCH_TARGET, MATCHING, WAITING, ADDING, REMOVING, BACKUP, DEDUPE }
    /** Percentuale del passo corrente, quando il totale e' noto. */
    val percent: Int? get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else null
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

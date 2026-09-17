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
) {
    val artistLine: String get() = artists.joinToString(", ")
    val isrcNorm: String? get() = isrc?.trim()?.uppercase()?.takeIf { it.length >= 10 }
    override fun toString(): String = if (artists.isEmpty()) title else "$artistLine – $title"
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int = -1,
    val ownedByMe: Boolean = true,
    val description: String = "",
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
)

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
data class Progress(val step: Step, val done: Int = 0, val total: Int = 0) {
    /** WAITING: il servizio limita le richieste; done/total = secondi trascorsi/da attendere. */
    enum class Step { FETCH_SOURCE, CREATE_TARGET, FETCH_TARGET, MATCHING, WAITING, ADDING, REMOVING, BACKUP, DEDUPE }
    /** Percentuale del passo corrente, quando il totale e' noto. */
    val percent: Int? get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else null
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

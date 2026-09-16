package com.xlollx.songport.providers

/**
 * Dati della procedura guidata "usa le tue credenziali".
 *
 * Non e' un vezzo per smanettoni: su Spotify, dal febbraio 2026, un'app in modalita' sviluppo
 * serve al massimo 5 utenti, e la quota e' legata al client ID indipendentemente da dove partono
 * le chiamate. L'unico modo perche' l'app funzioni per tutti e' che ognuno registri la propria
 * app sul portale del servizio: gratis, immediato, e la quota diventa sua.
 */
data class SetupGuide(
    /** Portale sviluppatori dove creare l'app. */
    val dashboardUrl: String,
    /** Redirect da incollare nella dashboard (null se il servizio non lo richiede). */
    val redirectUri: String? = null,
    /** Perche' conviene farlo, in una riga. */
    val whyRes: Int,
    /** Array di stringhe con i passi da seguire. */
    val stepsArrayRes: Int,
    /** Etichetta del campo principale (client ID, developer token…). */
    val fieldLabelRes: Int,
    /** Google: serve anche il client secret dell'app "Desktop". */
    val needsSecret: Boolean = false,
    /** Deezer: l'utente deve fornire anche il proprio URL di redirect https. */
    val needsRedirectUrl: Boolean = false,
    /** Campo lungo su piu' righe (il JWT di Apple Music). */
    val multiline: Boolean = false,
)

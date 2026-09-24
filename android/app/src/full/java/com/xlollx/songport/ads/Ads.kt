package com.xlollx.songport.ads

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Build GitHub: nessuna pubblicita' e nessun SDK pubblicitario. AdMob serve annunci solo alle app
 * pubblicate su Google Play o sull'App Store, quindi in un APK scaricato da GitHub l'SDK porterebbe
 * soltanto il modulo del consenso e il codice di Google, senza alcun annuncio. Stessa interfaccia
 * della versione Play (src/play), cosi' il resto dell'app non distingue le due build.
 */
object Ads {
    /** False in questa build: niente nota sugli annunci, niente opzioni privacy degli annunci. */
    const val enabled = false

    private val none = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> get() = none
    val privacyOptionsRequired: StateFlow<Boolean> get() = none

    @Suppress("UNUSED_PARAMETER")
    fun start(activity: Activity) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun showPrivacyOptions(activity: Activity) = Unit
}

/** Nessun banner: non occupa spazio. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AdBanner(modifier: Modifier = Modifier) = Unit

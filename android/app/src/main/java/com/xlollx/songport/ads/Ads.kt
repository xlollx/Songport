package com.xlollx.songport.ads

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.xlollx.songport.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monetizzazione "non invasiva": un solo banner adattivo in fondo alla schermata principale.
 * Niente interstitial, niente video. Prima di caricare annunci si raccoglie il consenso GDPR/UE
 * con Google UMP (User Messaging Platform); senza consenso valido non si richiedono annunci.
 */
object Ads {
    private val initialized = AtomicBoolean(false)
    private val _canRequestAds = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> get() = _canRequestAds

    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> get() = _privacyOptionsRequired

    fun start(activity: Activity) {
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()
        consent.requestConsentInfoUpdate(
            activity, params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    _privacyOptionsRequired.value =
                        consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                    if (consent.canRequestAds()) initSdk(activity)
                }
            },
            { _ -> if (consent.canRequestAds()) initSdk(activity) },
        )
        // Consenso gia' raccolto in una sessione precedente: non aspettiamo la rete.
        if (consent.canRequestAds()) initSdk(activity)
    }

    private fun initSdk(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        MobileAds.initialize(context.applicationContext) { _canRequestAds.value = true }
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }
    }
}

/** Banner adattivo ancorato; occupa spazio solo quando gli annunci sono consentiti. */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    val can by Ads.canRequestAds.collectAsState()
    if (!can) return
    val widthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdView(ctx).apply {
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}

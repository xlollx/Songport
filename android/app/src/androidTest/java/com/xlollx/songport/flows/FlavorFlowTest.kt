package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.installConnectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What differs between the two builds, on the real connector list of this build (nothing signed
 * in, nothing fetched): the GitHub/F-Droid build ("full") carries the web connectors and
 * SoundCloud links; the Google Play build ("play") hides plugin-only services unless a plugin is
 * installed, points to the plugin documentation, and says it shows a banner.
 */
@RunWith(AndroidJUnit4::class)
class FlavorFlowTest : FlowTest() {
    override val connectedAtStart = emptyList<String>()
    private val full get() = BuildConfig.FLAVOR == "full"

    private fun openServicePicker() {
        installConnectors()
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.connectors_empty_title))
        click(s(R.string.connector_add))
        waitText(s(R.string.connector_pick_title))
    }

    @Test
    fun servicePickerMatchesTheBuild() {
        openServicePicker()
        // Everywhere: the services with an official API.
        listOf("Spotify", "Deezer", "TIDAL", "Jellyfin", "Plex").forEach { waitText(it) }
        if (full) {
            assertTrue("Amazon Music (web connector) is offered", exists("Amazon Music"))
            assertFalse("no plugin talk where the connectors are built in", exists(s(R.string.plugins_more)))
            // Spotify can be reached two ways here: the family page lists both routes.
            click("Spotify")
            waitText(s(R.string.connector_pick_hint))
            assertTrue(exists(s(R.string.route_easy)))
            assertTrue(exists(s(R.string.route_official)))
        } else {
            assertFalse("Amazon Music needs a plugin the Play build does not name", exists("Amazon Music"))
            assertTrue("the Play build points to the plugin documentation", exists(s(R.string.plugins_more)))
            // Only the official Spotify route: straight to the name.
            click("Spotify")
            waitText(s(R.string.connector_name_label))
        }
    }

    @Test
    fun soundCloudLinksOnlyInTheFullBuild() {
        installConnectors()
        if (full) assertNotNull(Providers.byId("soundcloud")) else assertNull(Providers.byId("soundcloud"))
        assertEquals(full, com.xlollx.songport.providers.BuiltIn.available)
        assertEquals(full, com.xlollx.songport.providers.BridgePlugin.builtIn)
    }

    @Test
    fun aboutTheAdsOrTheirAbsence() {
        installConnectors()
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        val note = s(if (full) R.string.settings_free_note else R.string.settings_ads_note)
        waitText(note)
        assertEquals(!full, com.xlollx.songport.ads.Ads.enabled)
    }
}

package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.ui.WhatsNew
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "What's new": someone updating from a version before the sheet (welcome done, nothing seen) gets
 * it once at the start; "Got it" closes it for good, and Settings › About › What's new opens it again.
 */
@RunWith(AndroidJUnit4::class)
class WhatsNewFlowTest : FlowTest() {
    override fun baseSettings() = super.baseSettings().copy(onboardingDone = true, whatsNewSeen = "")

    private fun title() = s(R.string.whats_new_title, WhatsNew.newestVersion)

    @Test
    fun showsOnceAfterAnUpdateAndAgainFromSettings() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(title())
        waitText(s(WhatsNew.releases.first().items.first().title))
        snapshot("whats-new")

        click(s(R.string.whats_new_got_it))
        waitGone(title())
        waitFor("the newest release marked seen") { store.data.settings.whatsNewSeen == WhatsNew.newestVersion }

        // Opened anew: not shown again.
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.tab_accounts))
        compose.waitForIdle()
        assertFalse("shown again after Got it", exists(title()))

        // On demand from Settings › About.
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_about))
        click(s(R.string.whats_new_row))
        waitText(title())
        click(s(R.string.whats_new_got_it))
        waitGone(title())
        assertEquals(WhatsNew.newestVersion, store.data.settings.whatsNewSeen)
    }
}

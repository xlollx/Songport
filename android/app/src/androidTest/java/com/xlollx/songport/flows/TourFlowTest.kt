package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.ui.Tour
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guided first sync: offered on the empty Syncs tab of someone who has not taken it, never
 * started by itself; Skip ends it for good, and Next walks to the editor of a new sync.
 */
@RunWith(AndroidJUnit4::class)
class TourFlowTest : FlowTest() {
    override val connectedAtStart = emptyList<String>()
    override fun baseSettings() = super.baseSettings().copy(tourDone = false)

    @Test
    fun theTourCanBeSkippedAndDoesNotComeBack() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        // Offered, not started.
        assertNull(Tour.step.value)
        assertFalse(exists(s(R.string.tour_new_sync)))

        click(s(R.string.tour_offer))
        waitText(s(R.string.tour_new_sync))
        waitText(s(R.string.tour_progress, 1, Tour.steps.size))
        click(s(R.string.onboarding_next))
        waitText(s(R.string.tour_accounts))
        click(s(R.string.onboarding_skip))
        waitGone(s(R.string.tour_accounts))
        waitFor("the tour marked done") { store.data.settings.tourDone }
        assertNull(Tour.step.value)
        // Not offered again, not even after the app is opened anew.
        waitGone(s(R.string.tour_offer))
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        assertFalse(exists(s(R.string.tour_offer)))
        assertFalse(exists(s(R.string.tour_new_sync)))
    }

    @Test
    fun theTourWalksToANewSyncAndEnds() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.tour_offer))
        waitText(s(R.string.tour_new_sync))
        // Every step has its sentence; from the third one on, the editor of a new sync is open under it.
        Tour.steps.drop(1).forEach { step ->
            click(s(R.string.onboarding_next))
            waitText(s(step.text))
        }
        waitText(s(R.string.editor_source))
        click(s(R.string.tour_done))
        waitGone(s(R.string.tour_results))
        assertTrue(store.data.settings.tourDone)
        // The editor stays: the guided sync goes on for real.
        assertTrue(exists(s(R.string.editor_source)))
    }
}

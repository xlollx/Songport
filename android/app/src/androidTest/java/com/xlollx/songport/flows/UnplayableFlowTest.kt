package com.xlollx.songport.flows

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.Demo
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tools > Unplayable tracks: Spotify's "Old Favourites" has two entries it no longer plays; the
 * check finds them, proposes the playable version, applies it in place and can undo it. A service
 * that does not report unplayable tracks says so.
 */
@RunWith(AndroidJUnit4::class)
class UnplayableFlowTest : FlowTest() {
    override val connectedAtStart = listOf("spotify", "ytm")

    private val deadTitles = listOf(Demo.SONGS[14].title, Demo.SONGS[16].title)

    @Test
    fun findReplaceInPlaceAndUndo() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_unplayable_title))
        click(s(R.string.tools_unplayable_title))
        waitText(s(R.string.unplayable_title, "Spotify"))
        waitText(s(R.string.unplayable_all_playlists))
        click(s(R.string.unplayable_scan))

        val count = plural(R.plurals.unplayable_tracks_count, 2, 2)
        waitText(plural(R.plurals.unplayable_found_in, 1, count, 1), ms = 20_000)
        waitText("Old Favourites")
        deadTitles.forEach { waitText(it, substring = true) }
        // Both have a sure playable version, accepted already: two changes to apply.
        waitText(s(R.string.unplayable_apply, 2), ms = 20_000)

        click(s(R.string.unplayable_apply, 2))
        waitText(s(R.string.unplayable_confirm_title))
        // The dialog's own button (the last of the two with that label).
        val buttons = compose.onAllNodes(hasClickAction() and hasText(s(R.string.unplayable_apply, 2)))
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        waitText(s(R.string.unplayable_done, 2, 0), ms = 20_000)

        val fixed = FakeWorld.playlist("spotify", "Old Favourites")!!.tracks
        assertTrue("no unplayable entry left", fixed.none { it.available == false })
        // The replacements sit where the dead entries were.
        assertEquals((13 until 18).map { Demo.SONGS[it].title }, fixed.map { it.title })

        click(s(R.string.unplayable_undo))
        waitText(s(R.string.unplayable_undone, 2), ms = 20_000)
        val back = FakeWorld.playlist("spotify", "Old Favourites")!!.tracks
        assertEquals(2, back.count { it.available == false })
    }

    @Test
    fun aServiceThatCannotTellSaysSo() {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(R.string.tools_unplayable_title))
        // Pick YouTube Music in the service row of Tools.
        compose.onAllNodes(hasClickAction() and hasText("YouTube Music"))[0].apply { performScrollTo(); performClick() }
        // Tools says it on the row itself, which is dimmed and does not open.
        waitText(s(R.string.tools_unavailable_on, "YouTube Music"))
        compose.onAllNodes(hasClickAction() and hasText(s(R.string.tools_unplayable_title))).onFirst().assertIsNotEnabled()
    }

    @Test
    fun theNotificationOpensTheToolOnTheFirstServiceThatCanTell() {
        launch(mainIntent().putExtra(MainActivity.EXTRA_TOOL, MainActivity.TOOL_UNPLAYABLE))
        waitText(s(R.string.unplayable_title, "Spotify"))
    }
}

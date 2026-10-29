package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-app help: a "?" opens the sheet of the setting it sits next to (and only that one), and the
 * "?" of a list explains its parts.
 */
@RunWith(AndroidJUnit4::class)
class HelpFlowTest : FlowTest() {

    private fun help(label: String) = s(R.string.help_cd, label)

    @Test
    fun aQuestionMarkOpensTheSheetOfItsSetting() {
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(s(R.string.empty_syncs_title))
        click(s(R.string.new_sync))
        waitText(s(R.string.editor_source))

        // Mirror removals: its own text, with an example, and not the text of the switch next to it.
        clickDesc(help(s(R.string.editor_mirror)))
        waitText(s(R.string.help_mirror_body))
        waitText(s(R.string.help_example))
        waitText(s(R.string.help_mirror_example))
        assertFalse(exists(s(R.string.help_two_way_body)))
        androidx.test.espresso.Espresso.pressBack()
        waitGone(s(R.string.help_mirror_body))

        // Two-way: another "?", another sheet.
        clickDesc(help(s(R.string.editor_bidirectional)))
        waitText(s(R.string.help_two_way_body))
        assertFalse(exists(s(R.string.help_mirror_body)))
    }

    @Test
    fun theSyncListQuestionMarkExplainsTheCard() {
        val report = SyncReport(id = "r1", jobId = "job-1", jobName = "Road Trip", startedEpoch = System.currentTimeMillis() - 60_000,
            durationMs = 9_000, sourceCount = 8, targetCount = 6, added = 6, removed = 0, unmatched = listOf("a", "b"))
        store.update { d ->
            d.copy(
                jobs = listOf(SyncJob("job-1", "Road Trip", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "d1", "Road Trip"), lastRunEpoch = report.startedEpoch, lastReportId = "r1")),
                reports = listOf(report),
            )
        }
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Road Trip")
        clickDesc(help(s(R.string.help_cards_title)))
        waitText(s(R.string.help_status_body))
        waitText(s(R.string.help_route_body))
    }
}

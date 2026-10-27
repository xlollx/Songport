package com.xlollx.songport.flows

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.Demo
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.playlists
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Backup and files: the full backup of a service into dated files, a list pasted or a file
 * imported, and a file exported. The system file pickers are answered by Espresso-Intents with a
 * file in the app's cache, so nothing leaves the app.
 */
@RunWith(AndroidJUnit4::class)
class BackupFlowTest : FlowTest() {
    private val exported by lazy { File(app.cacheDir, "ui-test-export.csv") }
    private val toImport by lazy { File(app.cacheDir, "ui-test-import.txt") }

    @Before fun stubPickers() {
        exported.delete()
        Intents.init()
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(exported))))
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(toImport))))
    }

    @After fun releaseIntents() = Intents.release()

    private fun openTool(title: Int) {
        launch(tabIntent(MainActivity.TAB_TOOLS))
        waitText(s(title))
        click(s(title))
    }

    @Test
    fun fullBackupWritesEveryPlaylistAndASecondRunSkipsTheUnchanged() {
        openTool(R.string.tools_backup_title)
        click(s(R.string.tools_backup_run))
        // Spotify: Road Trip (8), Chill Evening (5), Old Favourites (5).
        waitText(s(R.string.tools_backup_done, 3, 18), ms = 30_000, substring = true)
        val files = runBlocking { LocalFilesProvider.playlists(app) }
        assertEquals(3, files.size)
        assertEquals(18, files.sumOf { it.trackCount })

        click(s(R.string.tools_backup_run))
        waitText(s(R.string.tools_backup_unchanged, 3), ms = 30_000, substring = true)
        assertEquals("nothing new written", 3, runBlocking { LocalFilesProvider.playlists(app) }.size)
    }

    @Test
    fun pasteAListThenExportItAsCsv() {
        openTool(R.string.files_title)
        click(s(R.string.paste_list))
        waitText(s(R.string.paste_list_hint))
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_name))).performTextReplacement("Pasted")
        val lines = (0 until 3).joinToString("\n") { "${Demo.SONGS[it].artist} - ${Demo.SONGS[it].title}" }
        compose.onNode(hasSetTextAction() and hasText(s(R.string.paste_list_text))).performTextReplacement(lines)
        click(s(R.string.import_action))
        waitText(s(R.string.csv_imported, 3))
        waitText(s(R.string.files_summary, 1, 3))

        // Files screen: the file's menu, Export…, CSV; the "save as" answer is a file in the cache.
        click(s(R.string.files_manage))
        waitText("Pasted")
        clickDesc(s(R.string.csv_export))
        click(s(R.string.export_menu))
        waitText(s(R.string.export_choose))
        click("CSV")
        waitText(s(R.string.csv_exported))
        val text = exported.readText()
        (0 until 3).forEach { assertTrue("export has ${Demo.SONGS[it].title}", Demo.SONGS[it].title in text) }
    }

    @Test
    fun importAFileFromThePicker() {
        toImport.writeText((3 until 7).joinToString("\n") { "${Demo.SONGS[it].artist} - ${Demo.SONGS[it].title}" })
        openTool(R.string.files_title)
        click(s(R.string.csv_import))
        waitFor("the picked file imported") { runBlocking { LocalFilesProvider.playlists(app) }.isNotEmpty() }
        waitText(s(R.string.csv_imported, 4))
        val files = runBlocking { LocalFilesProvider.playlists(app) }
        assertEquals(1, files.size)
        assertEquals(4, files.single().trackCount)
    }
}

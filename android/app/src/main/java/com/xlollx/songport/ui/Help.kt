package com.xlollx.songport.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R

// ---------------------------------------------------------------------------------------------
// Contextual help: a small "?" next to a setting or a label opens a sheet with a few sentences
// on what it really does, an example where one helps, and the way to the longer guide.
// ---------------------------------------------------------------------------------------------

/**
 * One help text: a [title] (usually the setting's own label), two to four short sentences in
 * [body] (and [extra] when a second paragraph reads better), an optional [example].
 */
class HelpTopic(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val extra: Int? = null,
    @StringRes val example: Int? = null,
)

/**
 * The "?" button: 48dp to touch, a quiet 20dp glyph to see, read by TalkBack as "Help: [label]".
 * With several [topics] the sheet shows each under its own title, [label] heading them all.
 */
@Composable
fun HelpButton(
    vararg topics: HelpTopic,
    modifier: Modifier = Modifier,
    label: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val name = label ?: stringResource(topics.first().title)
    IconButton(onClick = { open = true }, modifier = modifier.size(48.dp)) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.help_cd, name), tint = tint, modifier = Modifier.size(20.dp))
    }
    if (open) HelpSheet(if (topics.size > 1) name else null, topics.toList()) { open = false }
}

/** The sheet behind a "?": the topics one after the other, scrolling when they are long. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(title: String?, topics: List<HelpTopic>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            topics.forEach { t ->
                if (title != null) Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(t.title),
                    style = if (title == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                    color = if (title == null) Color.Unspecified else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(t.body), style = MaterialTheme.typography.bodyMedium)
                t.extra?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                t.example?.let { HelpExample(stringResource(it)) }
            }
        }
    }
}

/** "Example:" and the example, on a tinted block so it reads apart from the explanation. */
@Composable
private fun HelpExample(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.help_example), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        }
    }
}

/** Every help text in the app, by screen, so the same topic reads the same wherever it opens. */
object Helps {
    // Syncs
    val status = HelpTopic(R.string.help_status_title, R.string.help_status_body, example = R.string.help_status_example)
    val route = HelpTopic(R.string.help_route_title, R.string.help_route_body)
    val figures = HelpTopic(R.string.help_figures_title, R.string.help_figures_body)
    val toFix = HelpTopic(R.string.help_to_fix_title, R.string.help_to_fix_body)
    val runNow = HelpTopic(R.string.run_now, R.string.help_run_now_body)
    val syncAll = HelpTopic(R.string.sync_all, R.string.help_sync_all_body, example = R.string.help_sync_all_example)
    val cached = HelpTopic(R.string.help_cached_title, R.string.help_cached_body)

    // Editor
    val oneService = HelpTopic(R.string.help_one_service_title, R.string.help_one_service_body, example = R.string.help_one_service_example)
    val library = HelpTopic(R.string.help_library_title, R.string.help_library_body)
    val link = HelpTopic(R.string.editor_source_link, R.string.help_link_body)
    val target = HelpTopic(R.string.editor_target, R.string.help_target_body)
    val liked = HelpTopic(R.string.liked_songs, R.string.help_liked_body, example = R.string.help_liked_example)
    val newPlaylist = HelpTopic(R.string.editor_new_playlist, R.string.help_new_playlist_body)
    val schedule = HelpTopic(R.string.editor_schedule, R.string.help_schedule_body, example = R.string.help_schedule_example)
    val mirror = HelpTopic(R.string.editor_mirror, R.string.help_mirror_body, example = R.string.help_mirror_example)
    val keepOrder = HelpTopic(R.string.editor_keep_order, R.string.help_keep_order_body)
    val twoWay = HelpTopic(R.string.editor_bidirectional, R.string.help_two_way_body)
    val enabled = HelpTopic(R.string.editor_enabled, R.string.help_enabled_body)
    val strictness = HelpTopic(R.string.policy_strictness, R.string.help_strictness_body, example = R.string.help_strictness_example)
    val explicit = HelpTopic(R.string.policy_explicit, R.string.help_explicit_body)
    val sameAlbum = HelpTopic(R.string.policy_same_album, R.string.help_same_album_body, example = R.string.help_same_album_example)

    // Preview
    val preview = HelpTopic(R.string.preview, R.string.help_preview_body)
    val previewRun = HelpTopic(R.string.run_now, R.string.help_preview_run_body)
    val removals = HelpTopic(R.string.help_removals_title, R.string.help_removals_body)
    val uncertain = HelpTopic(R.string.help_uncertain_title, R.string.help_uncertain_body)
    val quota = HelpTopic(R.string.help_quota_title, R.string.help_quota_body)

    // Review
    val change = HelpTopic(R.string.review_change, R.string.help_change_body)
    val proposal = HelpTopic(R.string.search_suggested, R.string.help_proposal_body, example = R.string.help_proposal_example)
    val ignore = HelpTopic(R.string.unmatched_ignore, R.string.help_ignore_body)
    val results = HelpTopic(R.string.help_results_title, R.string.help_results_body)
    val askAi = HelpTopic(R.string.search_ask_ai, R.string.help_ask_ai_body)

    // Sync log
    val notes = HelpTopic(R.string.log_notes_title, R.string.help_notes_body)
    val restore = HelpTopic(R.string.help_restore_title, R.string.help_restore_body)

    // Accounts
    val connectors = HelpTopic(R.string.help_connectors_title, R.string.help_connectors_body)
    val tags = HelpTopic(R.string.help_tags_title, R.string.help_tags_body)
    val disconnect = HelpTopic(R.string.disconnect, R.string.help_disconnect_body)
    val removeAccount = HelpTopic(R.string.account_remove, R.string.help_remove_account_body)
    val test = HelpTopic(R.string.account_test, R.string.help_test_body)
    val extraActions = HelpTopic(R.string.help_extra_actions_title, R.string.help_extra_actions_body)
    val routes = HelpTopic(R.string.help_routes_title, R.string.help_routes_body)
    val unavailable = HelpTopic(R.string.services_group_unavailable, R.string.help_unavailable_body)

    // Setup wizard
    val redirect = HelpTopic(R.string.help_redirect_title, R.string.help_redirect_body)
    val fields = HelpTopic(R.string.help_fields_title, R.string.help_fields_body)
    val clearKey = HelpTopic(R.string.setup_clear, R.string.help_clear_key_body)

    // Tools
    val files = HelpTopic(R.string.files_title, R.string.help_files_body)
    val export = HelpTopic(R.string.help_export_title, R.string.help_export_body)
    val make = HelpTopic(R.string.tools_make_title, R.string.help_make_body, example = R.string.help_make_example)
    val transfer = HelpTopic(R.string.tools_transfer_title, R.string.tools_transfer_desc, extra = R.string.help_transfer_body, example = R.string.help_transfer_example)
    val dedupe = HelpTopic(R.string.tools_dedupe_title, R.string.help_dedupe_body)
    val unplayable = HelpTopic(R.string.tools_unplayable_title, R.string.help_unplayable_body)

    // Settings
    val newLook = HelpTopic(R.string.settings_new_look, R.string.settings_new_look_desc)
    val isrc = HelpTopic(R.string.settings_isrc, R.string.settings_isrc_desc)
    val musicLinks = HelpTopic(R.string.settings_music_links, R.string.settings_music_links_desc)
    val notifications = HelpTopic(R.string.settings_notifications, R.string.settings_notifications_desc)
    val backups = HelpTopic(R.string.backup_folder_title, R.string.help_backup_body, example = R.string.help_backup_example)
    val plugins = HelpTopic(R.string.plugins_title, R.string.help_plugins_body)
    val ai = HelpTopic(R.string.ai_setup_title, R.string.help_ai_body)
}

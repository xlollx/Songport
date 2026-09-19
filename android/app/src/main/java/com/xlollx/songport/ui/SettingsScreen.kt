package com.xlollx.songport.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.ads.Ads
import com.xlollx.songport.auth.LockActivity
import com.xlollx.songport.data.Store
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.launch

/** Apre la pagina Ko-fi: donazione volontaria, nessuna funzione legata. */
fun openSupport(ctx: android.content.Context) {
    if (BuildConfig.KOFI_URL.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.KOFI_URL))) }
}

@Composable
fun SettingsScreen(
    data: StoreData,
    store: Store,
    snackbar: SnackbarHostState,
    onSetup: (MusicProvider) -> Unit,
    onOpenLog: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val privacyRequired by Ads.privacyOptionsRequired.collectAsState()
    var advanced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(stringResource(R.string.settings_section_general)) {
            SettingRow(stringResource(R.string.settings_log), stringResource(R.string.settings_log_desc)) {
                TextButton(onClick = onOpenLog) { Text(stringResource(R.string.settings_open)) }
            }
            SettingRow(stringResource(R.string.settings_notifications), stringResource(R.string.settings_notifications_desc)) {
                Switch(checked = data.settings.notifyOnSync, onCheckedChange = { v -> store.updateSettings { it.copy(notifyOnSync = v) } })
            }
            SettingRow(stringResource(R.string.security_lock), stringResource(R.string.security_lock_desc)) {
                Switch(checked = data.settings.appLock, onCheckedChange = { v ->
                    if (v && !LockActivity.available(ctx)) {
                        scope.launch { snackbar.showSnackbar(ctx.getString(R.string.security_lock_unavailable)) }
                    } else {
                        store.updateSettings { it.copy(appLock = v) }
                    }
                })
            }
            // Chosen in-app and applied at once (the activity is recreated), also while a sync runs.
            var langOpen by remember { mutableStateOf(false) }
            SettingRow(stringResource(R.string.settings_language), AppLocale.label(ctx, AppLocale.current(ctx))) {
                TextButton(onClick = { langOpen = true }) { Text(stringResource(R.string.settings_language_open)) }
            }
            if (langOpen) AlertDialog(
                onDismissRequest = { langOpen = false },
                title = { Text(stringResource(R.string.settings_language)) },
                text = {
                    Column {
                        AppLocale.CHOICES.forEach { tag ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    langOpen = false
                                    (ctx as? Activity)?.let { AppLocale.set(it, tag) }
                                }.padding(vertical = 10.dp),
                            ) { Text(AppLocale.label(ctx, tag), style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { langOpen = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        SectionCard(stringResource(R.string.security_title)) {
            listOf(R.string.security_fact_1, R.string.security_fact_2, R.string.security_fact_3, R.string.security_fact_4).forEach {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        SectionCard(stringResource(R.string.support_title)) {
            Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodyMedium)
            if (BuildConfig.KOFI_URL.isNotBlank()) {
                FilledTonalButton(onClick = { openSupport(ctx) }) {
                    Icon(Icons.Filled.Favorite, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.support_button))
                }
            }
            Text(stringResource(R.string.settings_ads_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.settings_advanced)) {
            Text(stringResource(R.string.settings_advanced_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (advanced) {
                Providers.all().filter { it.setupGuide != null && it.slot.isEmpty() }.forEach { p ->
                    val configured = remember(p.id, data.settings) { p.isConfigured(ctx) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        ProviderBadge(p, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(if (configured) R.string.setup_using_own else R.string.not_configured),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onSetup(p) }) {
                            Text(stringResource(if (configured) R.string.edit else R.string.setup_now))
                        }
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(stringResource(if (advanced) R.string.hide_details else R.string.show_details))
            }
        }

        SectionCard(stringResource(R.string.settings_battery)) {
            Text(stringResource(R.string.settings_battery_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                // Apre l'elenco di sistema (nessun permesso speciale): l'utente sceglie Songport e lo esclude.
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text(stringResource(R.string.settings_battery_button)) }
        }

        SectionCard(stringResource(R.string.settings_about)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(28.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }) {
                    Text(stringResource(R.string.settings_privacy_policy))
                }
                if (privacyRequired) {
                    OutlinedButton(onClick = { (ctx as? Activity)?.let { Ads.showPrivacyOptions(it) } }) {
                        Text(stringResource(R.string.settings_privacy_options))
                    }
                }
            }
            // Open source: the repository holds the code, the docs and the connector-plugin page.
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xlollx/Songport"))) } }) {
                Text(stringResource(R.string.settings_source_code))
            }
            TextButton(onClick = { store.updateSettings { it.copy(onboardingDone = false) } }) { Text(stringResource(R.string.settings_show_onboarding)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingRow(title: String, desc: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

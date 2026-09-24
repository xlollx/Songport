package com.xlollx.songport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.xlollx.songport.ads.AdBanner
import com.xlollx.songport.auth.AuthEvents
import com.xlollx.songport.auth.LockActivity
import com.xlollx.songport.ads.Ads
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.YouTubeBridgeProvider
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.sync.PlaylistLinks
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncWorker
import com.xlollx.songport.ui.AccountsScreen
import com.xlollx.songport.ui.AppTopBar
import com.xlollx.songport.ui.AppTheme
import com.xlollx.songport.ui.LockScreen
import com.xlollx.songport.ui.LogScreen
import com.xlollx.songport.ui.OnboardingScreen
import com.xlollx.songport.ui.PreviewScreen
import com.xlollx.songport.ui.SetupWizardScreen
import com.xlollx.songport.ui.SettingsScreen
import com.xlollx.songport.ui.SyncEditorScreen
import com.xlollx.songport.ui.TransferScreen
import com.xlollx.songport.ui.SyncsScreen
import com.xlollx.songport.ui.ToolsScreen
import com.xlollx.songport.ui.UnmatchedScreen
import com.xlollx.songport.ui.FilesScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.xlollx.songport.ui.AppLocale.wrap(newBase))
    }

    private val messages = MutableStateFlow<String?>(null)
    private val tabRequests = MutableStateFlow(-1)
    /** Testo arrivato con "Condividi con Songport": contiene (si spera) il link di una playlist. */
    private val sharedText = MutableStateFlow<String?>(null)
    /** Scorciatoia "Nuova sync" dall'icona: apre subito l'editor. */
    private val newSyncRequests = MutableStateFlow(false)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Blocco con impronta/PIN: una volta per processo, cosi' non si ripete a ogni rotazione. */
    private val locked = MutableStateFlow(false)
    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) { unlockedThisProcess = true; locked.value = false }
    }

    private fun requestUnlock() = lockLauncher.launch(Intent(this, LockActivity::class.java))

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ads.start(this)
        askNotificationPermission()
        handleIntent(intent)
        if (Store.get(this).data.settings.appLock && !unlockedThisProcess) {
            locked.value = true
            requestUnlock()
        }
        setContent {
            AppTheme {
                val isLocked by locked.collectAsState()
                if (isLocked) LockScreen { requestUnlock() } else MainScreen(messages, tabRequests, sharedText, newSyncRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            ACTION_SYNC_ALL -> { Scheduler.runNow(this, SyncWorker.ALL); messages.value = getString(R.string.running); i.action = null }
            ACTION_NEW_SYNC -> { newSyncRequests.value = true; i.action = null }
        }
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
            i.action = null
        }
        i?.getStringExtra(EXTRA_MESSAGE)?.let { messages.value = it; i.removeExtra(EXTRA_MESSAGE) }
        if (i?.hasExtra(EXTRA_TAB) == true) { tabRequests.value = i.getIntExtra(EXTRA_TAB, 0); i.removeExtra(EXTRA_TAB) }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TAB = "tab"
        const val ACTION_SYNC_ALL = "com.xlollx.songport.SYNC_ALL"
        const val ACTION_NEW_SYNC = "com.xlollx.songport.NEW_SYNC"
        const val TAB_SYNCS = 0
        const val TAB_ACCOUNTS = 1
        const val TAB_TOOLS = 2
        /** Non e' una scheda: la notifica di esito chiede cosi' la schermata del registro. */
        const val TAB_LOG = 3
        const val TAB_SETTINGS = 4

        @Volatile var unlockedThisProcess = false
    }
}

@Composable
private fun MainScreen(
    messages: MutableStateFlow<String?>,
    tabRequests: MutableStateFlow<Int>,
    sharedText: MutableStateFlow<String?>,
    newSyncRequests: MutableStateFlow<Boolean>,
) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SyncJob?>(null) }
    var wizardFor by remember { mutableStateOf<MusicProvider?>(null) }
    var unmatchedReport by remember { mutableStateOf<String?>(null) }
    var filesOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var transferOpen by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<SyncJob?>(null) }
    var addConnector by remember { mutableStateOf(false) }

    val newSync by newSyncRequests.collectAsState()
    LaunchedEffect(newSync) { if (newSync) { newSyncRequests.value = false; tab = MainActivity.TAB_SYNCS; editing = newJob() } }

    // Link condiviso da un'altra app: apre l'editor con l'origine gia' impostata.
    val shared by sharedText.collectAsState()
    LaunchedEffect(shared) {
        val text = shared ?: return@LaunchedEffect
        sharedText.value = null
        val ref = PlaylistLinks.parse(text)
        if (ref == null) {
            snackbar.showSnackbar(ctx.getString(R.string.share_invalid))
        } else {
            tab = MainActivity.TAB_SYNCS
            // Link di YouTube: se il Bridge e' collegato, la sync nasce gia' su quel connettore.
            val providerId = if (ref.providerId == YouTubeProvider.SERVICE &&
                Providers.byId(YouTubeBridgeProvider.SERVICE)?.isConnected(ctx) == true
            ) YouTubeBridgeProvider.SERVICE else ref.providerId
            editing = newJob().copy(source = PlaylistRef(provider = providerId, playlistId = ref.playlistId, playlistName = ""))
            snackbar.showSnackbar(ctx.getString(R.string.share_received))
        }
    }

    val message by messages.collectAsState()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); messages.value = null } }
    // Esiti dei login che non tornano da un'Activity (flusso loopback di Google).
    val authMessage by AuthEvents.message.collectAsState()
    LaunchedEffect(authMessage) { authMessage?.let { snackbar.showSnackbar(it); AuthEvents.consume() } }
    val requestedTab by tabRequests.collectAsState()
    LaunchedEffect(requestedTab) {
        if (requestedTab >= 0) {
            // Il registro non e' piu' una scheda: la notifica lo apre sopra le Opzioni, da cui si raggiunge.
            if (requestedTab == MainActivity.TAB_LOG) { tab = MainActivity.TAB_SETTINGS; logOpen = true } else tab = requestedTab
            tabRequests.value = -1
        }
    }

    if (!data.settings.onboardingDone) {
        OnboardingScreen { store.updateSettings { it.copy(onboardingDone = true) } }
        return
    }
    val preview = previewJob
    if (preview != null) {
        PreviewScreen(preview, onClose = { previewJob = null }) { j -> Scheduler.runNow(ctx, j.id); previewJob = null }
        return
    }
    val wizard = wizardFor
    if (wizard != null) {
        SetupWizardScreen(wizard) { wizardFor = null }
        return
    }
    val unmatched = unmatchedReport
    if (unmatched != null) {
        UnmatchedScreen(unmatched) { unmatchedReport = null }
        return
    }
    if (filesOpen) {
        FilesScreen { filesOpen = false }
        return
    }
    if (logOpen) {
        LogScreen(data, onClose = { logOpen = false }) { unmatchedReport = it }
        return
    }
    if (transferOpen) {
        TransferScreen(onClose = { transferOpen = false }, onDone = { transferOpen = false; tab = MainActivity.TAB_SYNCS })
        return
    }

    val current = editing
    if (current != null) {
        SyncEditorScreen(
            job = current,
            onCancel = { editing = null },
            onSave = { job, reverse ->
                val previous = store.job(job.id)
                store.upsertJob(job)
                Scheduler.apply(ctx, job)
                if (reverse != null) {
                    store.upsertJob(reverse)
                    Scheduler.apply(ctx, reverse)
                } else previous?.linkedJobId?.let { old ->
                    // Bidirezionale disattivato: la gemella non ha piu' senso.
                    store.deleteJob(old); Scheduler.cancel(ctx, old)
                }
                editing = null
            },
        )
        return
    }

    val tabs = listOf(
        Triple(R.string.tab_syncs, Icons.Filled.Sync, MainActivity.TAB_SYNCS),
        Triple(R.string.tab_accounts, Icons.Filled.AccountCircle, MainActivity.TAB_ACCOUNTS),
        Triple(R.string.tab_tools, Icons.Filled.Build, MainActivity.TAB_TOOLS),
        Triple(R.string.tab_settings, Icons.Filled.Settings, MainActivity.TAB_SETTINGS),
    )

    Scaffold(
        topBar = {
            val label = tabs.firstOrNull { it.third == tab }?.first ?: R.string.app_name
            AppTopBar(stringResource(if (tab == MainActivity.TAB_SYNCS) R.string.app_name else label))
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                AdBanner()
                NavigationBar {
                    tabs.forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(stringResource(label), maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                MainActivity.TAB_SYNCS -> ExtendedFloatingActionButton(
                    onClick = { editing = newJob() },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_sync)) },
                )
                MainActivity.TAB_ACCOUNTS -> ExtendedFloatingActionButton(
                    onClick = { addConnector = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.connector_add)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainActivity.TAB_SYNCS -> SyncsScreen(
                    data = data,
                    onEdit = { editing = it },
                    onPreview = { previewJob = it },
                    onRun = { Scheduler.runNow(ctx, it.id) },
                    onRunAll = { Scheduler.runNow(ctx, SyncWorker.ALL) },
                    onDelete = { j ->
                        store.deleteJob(j.id); Scheduler.cancel(ctx, j.id)
                        j.linkedJobId?.let { l -> store.deleteJob(l); Scheduler.cancel(ctx, l) }
                    },
                    onGoToAccounts = { tab = MainActivity.TAB_ACCOUNTS },
                    onReview = { unmatchedReport = it },
                )
                MainActivity.TAB_ACCOUNTS -> AccountsScreen(snackbar, addConnector, { addConnector = it }, { wizardFor = it }, onManageFiles = { filesOpen = true })
                MainActivity.TAB_TOOLS -> ToolsScreen(snackbar, onManageFiles = { filesOpen = true }, onTransfer = { transferOpen = true }, onSyncStarted = { tab = MainActivity.TAB_SYNCS })
                else -> SettingsScreen(data, store, snackbar, onSetup = { wizardFor = it }, onOpenLog = { logOpen = true })
            }
        }
    }
}

private fun newJob() = SyncJob(
    id = UUID.randomUUID().toString(),
    name = "",
    source = PlaylistRef(provider = ""),
    target = PlaylistRef(provider = ""),
)

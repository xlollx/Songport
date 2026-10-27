package com.xlollx.songport

import com.xlollx.songport.providers.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.xlollx.songport.ui.LocalBottomBarInset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
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
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.YouTubeBridgeProvider
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.sync.PlaylistLinks
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncWorker
import com.xlollx.songport.ui.AccountsScreen
import com.xlollx.songport.ui.AddTrackDialog
import com.xlollx.songport.ui.AppTopBar
import com.xlollx.songport.ui.AppTheme
import com.xlollx.songport.ui.BackAwareScreen
import com.xlollx.songport.ui.TabContent
import com.xlollx.songport.ui.ThemeMode
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.style.TextOverflow
import com.xlollx.songport.ui.LockScreen
import com.xlollx.songport.ui.LogScreen
import com.xlollx.songport.ui.OnboardingScreen
import com.xlollx.songport.ui.PreviewScreen
import com.xlollx.songport.ui.SetupWizardScreen
import com.xlollx.songport.ui.SettingsScreen
import com.xlollx.songport.ui.SyncEditorScreen
import com.xlollx.songport.ui.TransferScreen
import com.xlollx.songport.ui.PlaylistsScreen
import com.xlollx.songport.ui.LibraryScanScreen
import com.xlollx.songport.ui.UnplayableScreen
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
    /** A tool a notification asks to open (TOOL_*), once the Tools tab is up. */
    private val toolRequests = MutableStateFlow<String?>(null)
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
        // Asked for on the first sync run or schedule, not here (see NotificationAsk).
        NotificationAsk.request = { askNotificationPermission() }
        handleIntent(intent)
        if (savedInstanceState == null) com.xlollx.songport.sync.SupportPrompt.opened(Store.get(this))
        if (Store.get(this).data.settings.appLock && !unlockedThisProcess) {
            locked.value = true
            requestUnlock()
        }
        setContent {
            val settings by Store.get(this).state.collectAsState()
            val mode = ThemeMode.of(settings.settings.themeMode)
            // enableEdgeToEdge() picks the bar icons from the phone's light or dark mode; with the theme
            // forced in Settings the app's own choice decides, or the icons vanish (dark on dark).
            val dark = when (mode) { ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark },
                )
            }
            AppTheme(mode = mode, dynamicColor = settings.settings.dynamicColor) {
                val isLocked by locked.collectAsState()
                if (isLocked) LockScreen { requestUnlock() } else MainScreen(messages, tabRequests, sharedText, newSyncRequests, toolRequests)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) NotificationAsk.request = null
        super.onDestroy()
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
        // A music link opened with Songport (the MusicLinkActivity alias, off unless the user turns it on
        // in Settings): the address is handled like a shared one. The app's own callback links stay apart.
        if (i?.action == Intent.ACTION_VIEW) {
            val url = i.dataString
            if (url != null && (com.xlollx.songport.sync.TrackLinks.parse(url) != null || com.xlollx.songport.sync.PlaylistLinks.parse(url) != null)) {
                sharedText.value = url
                i.action = null
            }
        }
        i?.getStringExtra(EXTRA_MESSAGE)?.let { messages.value = it; i.removeExtra(EXTRA_MESSAGE) }
        if (i?.hasExtra(EXTRA_TAB) == true) { tabRequests.value = i.getIntExtra(EXTRA_TAB, 0); i.removeExtra(EXTRA_TAB) }
        i?.getStringExtra(EXTRA_TOOL)?.let { toolRequests.value = it; i.removeExtra(EXTRA_TOOL) }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TAB = "tab"
        const val EXTRA_TOOL = "tool"
        const val TOOL_UNPLAYABLE = "unplayable"
        const val ACTION_SYNC_ALL = "com.xlollx.songport.SYNC_ALL"
        const val ACTION_NEW_SYNC = "com.xlollx.songport.NEW_SYNC"
        const val TAB_SYNCS = 0
        const val TAB_ACCOUNTS = 1
        const val TAB_TOOLS = 2
        /** Non e' una scheda: la notifica di esito chiede cosi' la schermata del registro. */
        const val TAB_LOG = 3
        const val TAB_SETTINGS = 4

        @Volatile var unlockedThisProcess = false

        /** The navigation bar scrims enableEdgeToEdge() uses by default (three-button navigation). */
        private val LIGHT_NAV_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_NAV_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun MainScreen(
    messages: MutableStateFlow<String?>,
    tabRequests: MutableStateFlow<Int>,
    sharedText: MutableStateFlow<String?>,
    newSyncRequests: MutableStateFlow<Boolean>,
    toolRequests: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    // The screen on top survives a rotation and a fold: jobs travel as JSON, providers by id.
    var editing by rememberSaveable(stateSaver = JobSaver) { mutableStateOf<SyncJob?>(null) }
    var wizardFor by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    var unmatchedReport by rememberSaveable { mutableStateOf<String?>(null) }
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var transferOpen by rememberSaveable { mutableStateOf(false) }
    var manageProvider by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    var scanProvider by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    var unplayableProvider by rememberSaveable(stateSaver = ProviderSaver) { mutableStateOf<MusicProvider?>(null) }
    // A notification asked for a tool: the first connected service the tool works with.
    val toolRequest by toolRequests.collectAsState()
    LaunchedEffect(toolRequest) {
        if (toolRequest == MainActivity.TOOL_UNPLAYABLE) {
            toolRequests.value = null
            tab = MainActivity.TAB_TOOLS
            unplayableProvider = Providers.connectors().firstOrNull { it.detectsUnplayable && it.isConnected(ctx) }
        }
    }
    var previewJob by rememberSaveable(stateSaver = JobSaver) { mutableStateOf<SyncJob?>(null) }
    // Two playlist links shared at once: "how compatible are we?" in Tools.
    // Saved, so a rotation keeps the comparison; dropped once its page is left.
    var compareLinks by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    // A tool page open in Tools: it brings its own top bar.
    var toolSubpage by remember { mutableStateOf(false) }
    var addConnector by rememberSaveable { mutableStateOf(false) }
    // A single track shared from a service's app: which of your playlists it goes to.
    var sharedTrack by remember { mutableStateOf<Pair<MusicProvider, com.xlollx.songport.model.Track>?>(null) }
    // A track link from any music app, headed for any connected service (ShareTrackSheet).
    var sharedLink by remember { mutableStateOf<Pair<String, com.xlollx.songport.sync.TrackLinks.Ref>?>(null) }

    val newSync by newSyncRequests.collectAsState()
    LaunchedEffect(newSync) { if (newSync) { newSyncRequests.value = false; tab = MainActivity.TAB_SYNCS; editing = newJob() } }

    // Link condiviso da un'altra app: apre l'editor con l'origine gia' impostata.
    val shared by sharedText.collectAsState()
    LaunchedEffect(shared) {
        val text = shared ?: return@LaunchedEffect
        sharedText.value = null
        val links = Regex("""https?://\S+|spotify:playlist:\S+""").findAll(text).map { it.value }.filter { PlaylistLinks.parse(it) != null }.distinct().toList()
        if (links.size >= 2) { compareLinks = links[0] to links[1]; tab = MainActivity.TAB_TOOLS; return@LaunchedEffect }
        // A service this build does not carry (SoundCloud on Google Play) is no link it can use; a
        // SoundCloud short link is followed to its set first.
        val soundCloud = com.xlollx.songport.sync.SoundCloudPublic
        val ref = (PlaylistLinks.parse(text)
            ?: (if (Providers.byId(soundCloud.SERVICE) != null) soundCloud.pathFromLink(text)?.let { PlaylistLinks.Ref(soundCloud.SERVICE, it) } else null))
            ?.takeIf { Providers.byId(it.providerId) != null }
        val setlist = com.xlollx.songport.sync.SetlistImport.linkIn(text)
        val trackRef = if (ref == null && setlist == null) com.xlollx.songport.sync.TrackLinks.parse(text) else null
        // Several "Artist - Title" lines shared as text become a file playlist, like a setlist.
        val lines = if (ref == null && setlist == null && trackRef == null && text.lines().count { it.isNotBlank() } >= 2)
            com.xlollx.songport.sync.PlaylistFiles.parseText(text) else emptyList()
        if (trackRef != null) {
            sharedLink = text to trackRef
        } else if (lines.size >= 2) {
            tab = MainActivity.TAB_SYNCS
            val name = ctx.getString(R.string.share_file_name) + " " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT).format(java.util.Date())
            val id = LocalFilesProvider.importTracks(ctx, name, lines)
            editing = newJob().copy(name = name, source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id))
            snackbar.showSnackbar(ctx.getString(R.string.share_text_imported, lines.size, id))
        } else if (setlist != null) {
            // A concert setlist becomes a file playlist, the source of a new sync.
            tab = MainActivity.TAB_SYNCS
            try {
                val s = com.xlollx.songport.sync.SetlistImport.fetch(setlist)
                val id = LocalFilesProvider.importTracks(ctx, s.name, s.tracks)
                editing = newJob().copy(name = s.name, source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = id, playlistName = id))
                snackbar.showSnackbar(ctx.getString(R.string.setlist_imported, s.tracks.size))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            }
        } else if (ref == null) {
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
    sharedTrack?.let { (p, t) ->
        AddTrackDialog(p, t, onClose = { sharedTrack = null }) { messages.value = it }
    }
    sharedLink?.let { (text, ref) ->
        com.xlollx.songport.ui.ShareTrackSheet(
            text = text, ref = ref,
            onClose = { sharedLink = null },
            onAdd = { p, t -> sharedLink = null; sharedTrack = p to t },
            onMessage = { messages.value = it },
        )
    }
    // One screen on top of the tabs at a time, in this order of precedence. The key names which
    // one is open, so switching animates and the predictive-back preview knows what to move.
    val preview = previewJob
    val wizard = wizardFor
    val unmatched = unmatchedReport
    val manage = manageProvider
    val scan = scanProvider
    val unplayable = unplayableProvider
    val current = editing
    val overlay: String? = when {
        preview != null -> "preview:" + preview.id
        wizard != null -> "wizard:" + wizard.id
        unmatched != null -> "unmatched:$unmatched"
        filesOpen -> "files"
        logOpen -> "log"
        manage != null -> "manage:" + manage.id
        scan != null -> "scan:" + scan.id
        unplayable != null -> "unplayable:" + unplayable.id
        transferOpen -> "transfer"
        current != null -> "edit:" + current.id
        else -> null
    }
    if (overlay != null) {
        // The tabs' Scaffold (and its snackbar host) is not composed under a full-screen page: its
        // messages ("Link received: choose where to sync it.") show here, above the page's own bottom bar.
        Box(Modifier.fillMaxSize()) {
        BackAwareScreen(overlay) {
            when {
                preview != null -> PreviewScreen(preview, onClose = { previewJob = null }) { j -> Scheduler.runNow(ctx, j.id); previewJob = null }
                wizard != null -> SetupWizardScreen(wizard) { wizardFor = null }
                unmatched != null -> UnmatchedScreen(unmatched) { unmatchedReport = null }
                filesOpen -> FilesScreen { filesOpen = false }
                logOpen -> LogScreen(data, onClose = { logOpen = false }) { unmatchedReport = it }
                manage != null -> PlaylistsScreen(manage) { manageProvider = null }
                scan != null -> LibraryScanScreen(scan) { scanProvider = null }
                unplayable != null -> UnplayableScreen(unplayable) { unplayableProvider = null }
                transferOpen -> TransferScreen(onClose = { transferOpen = false }, onDone = { transferOpen = false; tab = MainActivity.TAB_SYNCS })
                current != null -> SyncEditorScreen(
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
                        // A sync just made: what its first run would do, before it does it.
                        if (previous == null) previewJob = job
                    },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
        }
        return
    }

    val haze = remember { HazeState() }
    var barHeightPx by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        Triple(R.string.tab_syncs, Icons.Filled.Sync, MainActivity.TAB_SYNCS),
        Triple(R.string.tab_accounts, Icons.Filled.AccountCircle, MainActivity.TAB_ACCOUNTS),
        Triple(R.string.tab_tools, Icons.Filled.Build, MainActivity.TAB_TOOLS),
        Triple(R.string.tab_settings, Icons.Filled.Settings, MainActivity.TAB_SETTINGS),
    )

    Scaffold(
        topBar = {
            // An open tool shows its own bar with the way back: no tab header above it.
            if (!(tab == MainActivity.TAB_TOOLS && toolSubpage)) {
                val label = tabs.firstOrNull { it.third == tab }?.first ?: R.string.app_name
                AppTopBar(stringResource(if (tab == MainActivity.TAB_SYNCS) R.string.app_name else label))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Glass: the bar is translucent and blurs the content scrolling under it (Android 12+;
            // older phones get a plain translucent surface).
            Column(
                Modifier
                    .onSizeChanged { barHeightPx = it.height }
                    .hazeEffect(state = haze, style = HazeMaterials.thin(MaterialTheme.colorScheme.surface)),
            ) {
                HorizontalDivider(thickness = Dp.Hairline, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                AdBanner()
                // At a large font size the four labels no longer fit side by side: only the
                // selected tab keeps its label, the others are their icon (still read out by name).
                val bigText = LocalDensity.current.fontScale > 1.3f
                NavigationBar(containerColor = Color.Transparent, windowInsets = NavigationBarDefaults.windowInsets) {
                    tabs.forEach { (label, icon, index) ->
                        val name = stringResource(label)
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, contentDescription = if (bigText) name else null) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            alwaysShowLabel = !bigText,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                MainActivity.TAB_SYNCS -> ExtendedFloatingActionButton(
                    onClick = { editing = newJob() },
                    // The extended button clears its text from the semantics: the icon names it for TalkBack.
                    icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_sync)) },
                    text = { Text(stringResource(R.string.new_sync)) },
                )
                MainActivity.TAB_ACCOUNTS -> ExtendedFloatingActionButton(
                    onClick = { addConnector = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.connector_add)) },
                    text = { Text(stringResource(R.string.connector_add)) },
                )
            }
        },
    ) { padding ->
        val barInset = with(LocalDensity.current) { barHeightPx.toDp() }
        // Only the top inset: the content runs under the glass bar, and each screen pads its own list by LocalBottomBarInset.
        CompositionLocalProvider(LocalBottomBarInset provides barInset) {
            Box(Modifier.fillMaxSize().hazeSource(state = haze).padding(top = padding.calculateTopPadding())) {
            TabContent(tab) { shown -> when (shown) {
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
                MainActivity.TAB_TOOLS -> ToolsScreen(snackbar, onManageFiles = { filesOpen = true }, onTransfer = { transferOpen = true }, onSyncStarted = { tab = MainActivity.TAB_SYNCS }, onManage = { manageProvider = it }, onScan = { scanProvider = it }, onUnplayable = { unplayableProvider = it }, compareLinks = compareLinks, onSubpage = { toolSubpage = it }, onCompareClosed = { compareLinks = null })
                else -> SettingsScreen(data, store, snackbar, onSetup = { wizardFor = it }, onOpenLog = { logOpen = true })
            } }
            }
        }
    }
}

/** A sync job in the saved instance state, as the JSON the store already writes. */
private val JobSaver = Saver<SyncJob?, String>(
    save = { it?.let { j -> com.xlollx.songport.net.json.encodeToString(SyncJob.serializer(), j) } },
    restore = { runCatching { com.xlollx.songport.net.json.decodeFromString(SyncJob.serializer(), it) }.getOrNull() },
)

/** A provider in the saved instance state, by id. */
private val ProviderSaver = Saver<MusicProvider?, String>(
    save = { it?.id },
    restore = { Providers.byId(it) },
)

private fun newJob() = SyncJob(
    id = UUID.randomUUID().toString(),
    name = "",
    source = PlaylistRef(provider = ""),
    target = PlaylistRef(provider = ""),
)

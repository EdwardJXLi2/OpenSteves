@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hydranet.opensteves.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.data.TourRepository
import dev.hydranet.opensteves.playback.PlaybackConnection
import dev.hydranet.opensteves.session.SessionManager
import dev.hydranet.opensteves.ui.theme.AppThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun TourApp(
    repository: TourRepository,
    playback: PlaybackConnection,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    openPlaying: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tours by repository.tours.collectAsState()
    val installed by repository.installed.collectAsState()
    val downloads by repository.downloads.collectAsState()
    val message by repository.message.collectAsState()
    val player by playback.state.collectAsState()
    val session = remember { SessionManager.get(context) }
    val group by session.state.collectAsState()
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var playerTourId by rememberSaveable { mutableStateOf<Int?>(null) }
    var deletion by remember { mutableStateOf<Tour?>(null) }
    var sharingDialog by remember { mutableStateOf(false) }
    var startingSession by remember { mutableStateOf(false) }
    var pendingSessionTourId by rememberSaveable { mutableStateOf<Int?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var preparingSessionDownload by remember { mutableStateOf(false) }
    var sessionDownloadError by remember(group.requiredTourId) { mutableStateOf<String?>(null) }
    val savedScreens = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val report: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun download(tour: Tour) {
        if (playback.state.value.tourId == tour.id && repository.installed.value.containsKey(tour.id)) {
            report("Stop this tour before updating its download.")
        } else {
            notificationPermission()
            repository.download(tour)
        }
    }
    fun refresh() {
        scope.launch {
            refreshing = true
            try { repository.refreshCatalog() }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "Could not refresh tours.") }
            finally { refreshing = false }
        }
    }
    fun navigate(destination: Int) {
        selectedId = null
        screen = destination
    }
    fun play(tour: Tour, positionMs: Long? = null) {
        if (session.state.value.role != "none" && playback.state.value.tourId != tour.id) {
            report("Leave the current session to play a different tour.")
            return
        }
        notificationPermission()
        playback.play(tour, positionMs)
        playerTourId = tour.id
        navigate(1)
    }

    fun startSession(tour: Tour) {
        if (startingSession || session.state.value.role != "none") return
        startingSession = true
        scope.launch {
            try {
                val before = playback.state.value
                if (before.tourId != tour.id || !before.playing || !before.ready) {
                    notificationPermission()
                    playback.play(tour)
                    playerTourId = tour.id
                }
                val ready = withTimeoutOrNull(15_000) {
                    playback.state.first { it.error != null || (it.tourId == tour.id && it.ready && it.playing) }
                }
                if (ready == null || ready.error != null) {
                    report(ready?.error ?: "Could not start this tour. Please try again.")
                } else if (session.state.value.role == "none" && playback.state.value.tourId == tour.id) {
                    session.host()
                    if (session.state.value.role == "host") navigate(2)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                report("Could not start the session. Please try again.")
            } finally {
                startingSession = false
            }
        }
    }
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val tour = pendingSessionTourId?.let { repository.installed.value[it]?.tour }
        pendingSessionTourId = null
        if (!granted) report("Allow Nearby devices permission to start a session.")
        else if (tour != null) startSession(tour)
    }
    fun requestSession(tour: Tour) {
        if (startingSession || pendingSessionTourId != null) return
        sharingDialog = false
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingSessionTourId = tour.id
            bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else startSession(tour)
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(openPlaying) { if (openPlaying > 0) navigate(1) }
    LaunchedEffect(player.tourId) { player.tourId?.let { playerTourId = it } }
    LaunchedEffect(message) { message?.let(report) }
    LaunchedEffect(player.error) { player.error?.let(report) }
    LaunchedEffect(group.error) { group.error?.let(report) }
    BackHandler(selectedId != null) { selectedId = null }
    val selected = selectedId?.let { id -> tours.find { it.id == id } ?: installed[id]?.tour }
    val current = player.tourId?.let { id -> installed[id]?.tour ?: tours.find { it.id == id } }
    val playingTour = current ?: playerTourId?.let { installed[it]?.tour }
    var playerFullScreen by rememberSaveable(playingTour?.id, screen, selectedId) { mutableStateOf(false) }
    var showingSessionDebug by rememberSaveable(screen, selectedId) { mutableStateOf(false) }
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // Reset the app bar's color animation when the theme changes.
            key(darkTheme) {
                if (!playerFullScreen) TopAppBar(
                    expandedHeight = 56.dp,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    title = {
                        Text(
                            if (selected != null) "Tour details" else when (screen) {
                                1 -> "Playing"
                                2 -> "Together"
                                else -> "Explore"
                            },
                            fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (selectedId != null) IconButton(onClick = {
                            if (selectedId != null) selectedId = null else navigate(0)
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (selected == null && screen == 0) {
                            IconButton(onClick = ::refresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, "Refresh tours") }
                            IconButton(onClick = { onThemeModeChange(if (darkTheme) AppThemeMode.LIGHT else AppThemeMode.DARK) }) {
                                Icon(if (darkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (darkTheme) "Switch to light theme" else "Switch to dark theme")
                            }
                        } else if (selected != null && installed.containsKey(selected.id)) {
                            IconButton(onClick = {
                                when {
                                    player.tourId == selected.id && group.role != "none" -> report("End the session and stop this tour before deleting its download.")
                                    player.tourId == selected.id -> report("Stop this tour before deleting its download.")
                                    downloads[selected.id]?.let { it.error == null } == true -> report("Cancel the download before deleting this tour.")
                                    else -> deletion = selected
                                }
                            }) { Icon(Icons.Outlined.DeleteOutline, "Delete download", tint = MaterialTheme.colorScheme.error) }
                        } else if (selected == null && screen == 1 && playingTour != null) {
                            IconButton(onClick = { if (group.role != "none") navigate(2) else sharingDialog = true }, enabled = !startingSession && pendingSessionTourId == null) {
                                if (startingSession) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Group, if (group.role != "none") "Go to session" else "Share this tour")
                            }
                        } else if (selected == null && screen == 2 && !showingSessionDebug) {
                            IconButton(onClick = { showingSessionDebug = true }) {
                                Icon(Icons.Outlined.BugReport, "Session debug")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!playerFullScreen) AppNavigation(selected = when (screen) { 1, 2 -> screen; else -> 0 }, sessionActive = group.role != "none", onSelect = ::navigate)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selected != null) {
                TourInfoScreen(selected, repository, installed[selected.id], downloads[selected.id], player.tourId, group.role != "none",
                    onDownload = { download(selected) }, onCancel = { repository.cancelDownload(selected.id) },
                    onPlay = { play(selected) }, onOpenPlayer = { navigate(1) })
            } else when (screen) {
                1 -> if (playingTour != null) savedScreens.SaveableStateProvider("playing") {
                    PlayingScreen(playingTour, repository, playback, installed[playingTour.id]?.directoryName, group.role != "none",
                        fullScreen = playerFullScreen, onFullScreenChange = { playerFullScreen = it },
                        onPlay = { position -> play(playingTour, position) })
                } else Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    EmptyState(Icons.Outlined.Headphones, "Nothing playing", "Start a tour from Explore to listen here.")
                }
                2 -> if (showingSessionDebug) {
                    SessionDebugScreen(session, onBack = { showingSessionDebug = false }, report = report)
                } else TogetherScreen(session, repository, playback, current, startingSession,
                    onStartSession = { current?.let(::requestSession) }, onOpenPlayer = { navigate(1) }, report = report)
                else -> savedScreens.SaveableStateProvider("explore") {
                    ExploreScreen(tours, installed, downloads, refreshing, repository, player.tourId,
                        onSelect = { selectedId = it.id }, onDownload = ::download, onCancel = repository::cancelDownload,
                        onPlay = { play(it) })
                }
            }
        }
    }
    group.requiredTourId?.let { tourId ->
        val tour = tours.find { it.id == tourId } ?: installed[tourId]?.tour
        val downloading = preparingSessionDownload || group.downloadingFromHost
        fun beginDownload(fromHost: Boolean) {
            if (preparingSessionDownload || session.state.value.downloadingFromHost) return
            preparingSessionDownload = true
            sessionDownloadError = null
            scope.launch {
                try {
                    val available = if (fromHost) null else {
                        repository.tours.value.find { it.id == tourId } ?: run {
                            repository.refreshCatalog()
                            repository.tours.value.find { it.id == tourId }
                        }
                    }
                    if (!fromHost && available == null) {
                        sessionDownloadError = "Could not find this tour. Connect to the internet and try again."
                        return@launch
                    }
                    if (session.state.value.requiredTourId != tourId) return@launch
                    if (playback.state.value.tourId == tourId) {
                        playback.stop()
                        if (withTimeoutOrNull(5_000) { playback.state.first { it.tourId != tourId } } == null) {
                            sessionDownloadError = "Stop this tour in Playing, then try the download again."
                            return@launch
                        }
                    }
                    if (session.state.value.requiredTourId != tourId) return@launch
                    if (fromHost) {
                        notificationPermission()
                        session.downloadRequiredTourFromHost()
                        navigate(2)
                    } else {
                        download(checkNotNull(available))
                        session.dismissRequiredTour()
                        navigate(0)
                        selectedId = tourId
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    sessionDownloadError = if (fromHost) "Could not start the Bluetooth download. Try again."
                        else "Could not start the download. Check your connection and try again."
                } finally {
                    preparingSessionDownload = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!preparingSessionDownload) session.dismissRequiredTour() },
            title = { Text("Download tour?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when {
                        tour == null -> "You need the host’s tour on this phone to join."
                        installed.containsKey(tourId) -> "Your copy of “${tour.title}” differs from the host’s."
                        else -> "Download “${tour.title}” to join."
                    })
                    if (downloading) {
                        val progress = group.hostDownloadProgress
                        if (progress != null && group.downloadingFromHost) {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(if (progress == 100) "Verifying download…" else "Downloading from host… $progress%")
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (group.downloadingFromHost) Text("Preparing Bluetooth download…")
                        }
                    } else if (!group.hostDownloadAvailable) {
                        Text("Host download is unavailable for this session.")
                    }
                    (sessionDownloadError ?: group.hostDownloadError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                        enabled = !downloading, onClick = { beginDownload(fromHost = false) },
                    ) { Text("Download Locally (Recommended)", textAlign = TextAlign.Center) }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                        enabled = !downloading && group.hostDownloadAvailable, onClick = { beginDownload(fromHost = true) },
                    ) { Text("Download from Host") }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                        enabled = !preparingSessionDownload, onClick = session::dismissRequiredTour,
                    ) { Text("Cancel") }
                }
            },
        )
    }
    if (sharingDialog && playingTour != null) {
        AlertDialog(onDismissRequest = { sharingDialog = false }, title = { Text("Start a session?") },
            text = { Text("Friends can join from Together on their paired phones and listen with you over Bluetooth.") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { requestSession(playingTour) }) {
                    Text(if (player.tourId == playingTour.id && player.playing) "Start session" else "Start tour and share")
                }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { sharingDialog = false }) { Text("Cancel") } },
        )
    }
    deletion?.let { tour ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Delete download?") },
            text = { Text("The audio, map, and saved subtitles will be removed from this phone.") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    deletion = null
                    scope.launch {
                        if (playback.state.value.tourId == tour.id) snackbar.showSnackbar("Stop this tour before deleting it.")
                        else if (repository.downloads.value[tour.id]?.error == null && repository.downloads.value.containsKey(tour.id)) {
                            snackbar.showSnackbar("Cancel the download before deleting this tour.")
                        } else try { repository.delete(tour.id) }
                        catch (error: Exception) { snackbar.showSnackbar(error.message ?: "Could not delete this tour.") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { deletion = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AppNavigation(selected: Int, sessionActive: Boolean, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(64.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("Explore" to Icons.Outlined.Explore, "Playing" to Icons.Outlined.Headphones, "Together" to Icons.Outlined.People).forEachIndexed { index, (label, icon) ->
                    val color = if (index == selected) colors.primary else colors.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxHeight().selectable(selected = index == selected, role = Role.Tab, onClick = { onSelect(index) }),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        BadgedBox(badge = { if (index == 2 && sessionActive) Badge(containerColor = colors.primary) }) {
                            Icon(icon, null, Modifier.size(23.dp), tint = color)
                        }
                        Text(label, Modifier.padding(top = 4.dp), color = color, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

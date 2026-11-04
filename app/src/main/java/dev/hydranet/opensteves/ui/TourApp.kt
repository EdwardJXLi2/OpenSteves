@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hydranet.opensteves.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.data.*
import dev.hydranet.opensteves.playback.PlaybackConnection
import dev.hydranet.opensteves.playback.PlaybackUiState
import dev.hydranet.opensteves.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TourApp(repository: TourRepository, playback: PlaybackConnection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tours by repository.tours.collectAsState()
    val installed by repository.installed.collectAsState()
    val downloads by repository.downloads.collectAsState()
    val message by repository.message.collectAsState()
    val player by playback.state.collectAsState()
    val session = remember { SessionManager.get(context) }
    val group by session.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var deletion by remember { mutableStateOf<Tour?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun download(tour: Tour) {
        if (playback.state.value.tourId == tour.id && installed.containsKey(tour.id)) {
            scope.launch { snackbar.showSnackbar("Stop this tour before updating its download.") }
        } else {
            notificationPermission()
            repository.download(tour)
        }
    }
    fun refresh() {
        scope.launch {
            refreshing = true
            try { repository.refreshCatalog() } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Could not refresh tours.") }
            finally { refreshing = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(player.error) { player.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(group.error) { group.error?.let { snackbar.showSnackbar(it) } }
    BackHandler(selectedId != null) { selectedId = null }
    val selected = selectedId?.let { id -> tours.find { it.id == id } ?: installed[id]?.tour }
    val current = player.tourId?.let { id -> installed[id]?.tour ?: tours.find { it.id == id } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (selected != null) selected.region else "OpenSteves", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (selectedId != null) IconButton(onClick = { selectedId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (group.role != "none") TextButton(onClick = { selectedId = null; screen = 2 }) { Icon(Icons.Default.Group, null); Text(" Session") }
                    if (selected == null && screen == 0) IconButton(onClick = { refresh() }, enabled = !refreshing) { Icon(Icons.Default.Refresh, "Refresh tours") }
                },
            )
        },
        bottomBar = {
            Column {
                if (current != null && selected?.id != current.id) {
                    Surface(tonalElevation = 4.dp, onClick = { selectedId = current.id }) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                Text("${time(player.positionMs)} / ${time(player.durationMs)}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = playback::toggle) { Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pause" else "Play") }
                        }
                    }
                }
                NavigationBar {
                    listOf("Explore" to Icons.Default.Explore, "Downloads" to Icons.Default.DownloadDone, "Together" to Icons.Default.Group).forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(selected = screen == index, onClick = { selectedId = null; screen = index }, icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selected != null) {
                TourDetail(selected, repository, playback, installed.containsKey(selected.id), installed[selected.id]?.audioHash, downloads[selected.id], group.role != "none",
                    onDownload = { download(selected) },
                    onPlay = { notificationPermission(); playback.play(selected) },
                    onDelete = { deletion = selected },
                    report = { scope.launch { snackbar.showSnackbar(it) } })
            } else when (screen) {
                0 -> Catalog(tours, installed, downloads, refreshing, onSelect = { selectedId = it.id },
                    onDownload = ::download, onCancel = repository::cancelDownload)
                1 -> Downloads(tours, installed, downloads, player.tourId, onSelect = { selectedId = it.id },
                    onDelete = { deletion = it }, onCancel = repository::cancelDownload, onExplore = { screen = 0 },
                    onRetry = ::download)
                2 -> GroupScreen(session, current, onPrepare = { screen = 1 }, report = { scope.launch { snackbar.showSnackbar(it) } })
            }
        }
    }
    deletion?.let { tour ->
        AlertDialog(onDismissRequest = { deletion = null }, title = { Text("Delete download?") },
            text = { Text("${tour.title}\n\nThe audio, map, and saved subtitles will be removed from this phone.") },
            confirmButton = { TextButton(onClick = {
                deletion = null
                scope.launch {
                    if (playback.state.value.tourId == tour.id) snackbar.showSnackbar("Stop this tour before deleting it.")
                    else if (repository.downloads.value[tour.id]?.let { it.error == null } == true) snackbar.showSnackbar("Cancel the download before deleting this tour.")
                    else try { repository.delete(tour.id) } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Could not delete this tour.") }
                }
            }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletion = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Catalog(
    tours: List<Tour>, installed: Map<Int, InstalledTour>, downloads: Map<Int, DownloadStatus>, refreshing: Boolean,
    onSelect: (Tour) -> Unit, onDownload: (Tour) -> Unit, onCancel: (Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var walkingOnly by rememberSaveable { mutableStateOf(true) }
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    var regionsOpen by remember { mutableStateOf(false) }
    val filtered = remember(tours, query, walkingOnly, region) {
        tours.filter { (!walkingOnly || it.type == "Walking tour") && (region == null || it.region == region) &&
            (it.title.contains(query, true) || it.region.contains(query, true)) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                Text("A closer look\nat Europe.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text("Audio tours with Rick Steves", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Find a city or tour") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } }, shape = RoundedCornerShape(18.dp))
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = walkingOnly, onClick = { walkingOnly = !walkingOnly }, label = { Text(if (walkingOnly) "Walking tours" else "All audio") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(18.dp)) })
                Box {
                    FilterChip(selected = region != null, onClick = { regionsOpen = true }, label = { Text(region ?: "All regions") }, trailingIcon = { Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp)) })
                    DropdownMenu(expanded = regionsOpen, onDismissRequest = { regionsOpen = false }) {
                        DropdownMenuItem(text = { Text("All regions") }, onClick = { region = null; regionsOpen = false })
                        tours.map { it.region }.distinct().forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { region = item; regionsOpen = false }) }
                    }
                }
            }
        }
        if (refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Text("${filtered.size} ${if (walkingOnly) "walking tour" else "audio guide"}${if (filtered.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (filtered.isEmpty() && !refreshing) item {
            EmptyState(Icons.Default.TravelExplore, if (tours.isEmpty()) "Your next walk starts here" else "No matching tours",
                if (tours.isEmpty()) "Connect to the internet and refresh to load the tour catalog." else "Try another city or region.")
        }
        items(filtered, key = { it.id }) { tour ->
            TourCard(tour, installed[tour.id], downloads[tour.id], onSelect = { onSelect(tour) }, onDownload = { onDownload(tour) }, onCancel = { onCancel(tour.id) })
        }
    }
}

@Composable
private fun TourCard(tour: Tour, installed: InstalledTour?, download: DownloadStatus?, onSelect: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit) {
    OutlinedCard(onClick = onSelect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(if (tour.type == "Walking tour") Icons.Default.LocationOn else Icons.Default.Headphones, null, Modifier.padding(14.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(tour.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(tour.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tour.details.ifBlank { bytes(tour.bytes) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (installed != null) Text(if (installed.tour.version < tour.version) "Update available" else "Available offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            when {
                download != null && download.error == null -> IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel download") }
                installed == null || installed.tour.version < tour.version -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "Download ${tour.title}", tint = MaterialTheme.colorScheme.primary) }
                else -> Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (download != null) {
            if (download.error != null) Text(download.error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else LinearProgressIndicator(progress = { download.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Downloads(tours: List<Tour>, installed: Map<Int, InstalledTour>, downloads: Map<Int, DownloadStatus>, currentId: Int?, onSelect: (Tour) -> Unit, onDelete: (Tour) -> Unit, onCancel: (Int) -> Unit, onExplore: () -> Unit, onRetry: (Tour) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ready to wander", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold) }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null)
                    Column { Text(bytes(installed.values.sumOf { it.sizeBytes }), style = MaterialTheme.typography.headlineSmall); Text("${installed.size} ${if (installed.size == 1) "tour" else "tours"} on this phone", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        downloads.filterKeys { !installed.containsKey(it) }.forEach { (id, status) ->
            val tour = tours.find { it.id == id }
            if (tour != null) item(key = "download-$id") { TourCard(tour, null, status, { onSelect(tour) }, { onRetry(tour) }, { onCancel(id) }) }
        }
        if (installed.isEmpty() && downloads.isEmpty()) item {
            EmptyState(Icons.Default.Download, "Take your tours offline", "Download audio before you leave. Maps and transcripts stay with the tour.")
            Button(onClick = onExplore, modifier = Modifier.fillMaxWidth()) { Text("Explore tours") }
        }
        items(installed.values.sortedBy { it.tour.title }, key = { it.tour.id }) { item ->
            OutlinedCard(onClick = { onSelect(item.tour) }, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.tour.title, style = MaterialTheme.typography.titleMedium)
                        Text(bytes(item.sizeBytes), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (currentId == item.tour.id) Text("In player", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { onDelete(item.tour) }, enabled = currentId != item.tour.id && downloads[item.tour.id]?.let { it.error == null } != true) { Icon(Icons.Default.DeleteOutline, "Delete ${item.tour.title}") }
                }
            }
        }
    }
}

@Composable
private fun TourDetail(tour: Tour, repository: TourRepository, playback: PlaybackConnection, downloaded: Boolean, assetVersion: String?, download: DownloadStatus?, sessionActive: Boolean, onDownload: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit, report: (String) -> Unit) {
    val player by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(tour.id) { mutableStateOf(0) }
    var captionsVersion by remember(tour.id) { mutableIntStateOf(0) }
    var generationDialog by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    val chapters by produceState<List<Chapter>>(emptyList(), tour.id, assetVersion) {
        value = withContext(Dispatchers.IO) { runCatching { repository.chapterFile(tour.id)?.readText()?.let(TimedText::parseChapters).orEmpty() }.getOrDefault(emptyList()) }
    }
    val captionResult by produceState<Result<List<SubtitleCue>>?>(null, tour.id, assetVersion, captionsVersion) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { repository.subtitlesFile(tour.id)?.readText()?.let(TimedText::parseSubtitles).orEmpty() } }
    }
    val transcriptResult by produceState<Result<String>?>(null, tour.id, assetVersion) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { repository.transcriptFile(tour.id)?.readText()?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString() }.orEmpty() } }
    }
    val captions = captionResult?.getOrNull().orEmpty()
    val transcript = transcriptResult?.getOrNull().orEmpty()
    val loadingText = captionResult == null || transcriptResult == null
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { repository.importSubtitles(tour.id, uri); captionsVersion++; report("Subtitles imported.") }
            catch (e: Exception) { report(e.message ?: "Could not import subtitles.") }
        }
    }
    val active = player.tourId == tour.id
    val canPlay = !sessionActive || active
    val chapter = if (active) chapters.lastOrNull { player.positionMs >= it.startMs && player.positionMs < it.endMs } else chapters.firstOrNull()
    val caption = if (active) captions.lastOrNull { player.positionMs >= it.startMs && player.positionMs < it.endMs } else null
    Column(Modifier.fillMaxSize()) {
        if (downloaded) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Listen", "Map", "Transcript").forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) }) }
            }
        }
        when {
            downloaded && tab == 1 -> ZoomableMap(repository.mapFile(tour.id), tour.title)
            downloaded && tab == 2 -> {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { picker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) }) { Icon(Icons.Default.Subtitles, null, Modifier.size(18.dp)); Text(" Import subtitles") }
                        TextButton(onClick = { generationDialog = true }, enabled = !generating) { Text(if (generating) "Generating…" else "Generate") }
                    }
                    if (generating || loadingText) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (captionResult?.isFailure == true || transcriptResult?.isFailure == true) Text("Could not read saved text. Download the tour again or re-import its subtitles.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                    if (captions.isNotEmpty()) Text("${captions.size} timed captions", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (!loadingText && transcript.isEmpty() && captions.isEmpty()) EmptyState(Icons.Default.Subtitles, "No transcript available", "Import an SRT or VTT file, or generate timed subtitles with a transcription service.")
                    else if (!loadingText) SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                        Text(transcript.ifEmpty { captions.joinToString("\n\n") { it.text } }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    TourArtwork(repository.imageFile(tour.id, chapter?.imagePath ?: "images/01.jpg"), tour.title)
                }
                item {
                    Text(tour.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(tour.details, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (!downloaded) {
                    item {
                        if (download != null && download.error == null) Column {
                            LinearProgressIndicator(progress = { download.progress.coerceIn(0, 100) / 100f }, Modifier.fillMaxWidth())
                            TextButton(onClick = { repository.cancelDownload(tour.id) }) { Text("Cancel download (${download.progress.coerceIn(0, 100)}%)") }
                        } else Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Download ${bytes(tour.bytes)}")
                        }
                        download?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    item { Text(remember(tour.description) { Html.fromHtml(tour.description, Html.FROM_HTML_MODE_COMPACT).toString() }, style = MaterialTheme.typography.bodyLarge) }
                } else {
                    item {
                        PlayerControls(if (active) player else PlaybackUiState(), active, canPlay, onPlay, playback)
                        if (!canPlay) Text("Leave the current session to play a different tour.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (chapter != null) item { Text(chapter.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
                    if (caption != null) item {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
                            Text(caption.text, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (captions.isEmpty()) item {
                        TextButton(onClick = { tab = 2 }) { Icon(Icons.Default.Subtitles, null, Modifier.size(20.dp)); Text(if (transcript.isNotEmpty()) " Read transcript or add live subtitles" else " Add live subtitles") }
                    }
                    if (chapters.isNotEmpty()) item { Text("Along the way", style = MaterialTheme.typography.titleLarge) }
                    items(chapters, key = { "${it.startMs}-${it.title}" }) { item ->
                        Surface(onClick = { if (!active) playback.play(tour, item.startMs) else playback.seek(item.startMs) }, enabled = canPlay, shape = RoundedCornerShape(14.dp),
                            color = if (item == chapter && active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(time(item.startMs), Modifier.width(58.dp), style = MaterialTheme.typography.labelLarge)
                                Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Icon(Icons.Default.PlayArrow, "Play chapter", Modifier.size(20.dp))
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (!active) TextButton(onClick = onDelete, enabled = download?.let { it.error == null } != true) { Text("Delete download") }
                            TextButton(onClick = { tab = 2 }) { Text("Transcript") }
                        }
                    }
                }
            }
        }
    }
    if (generationDialog) TranscriptionDialog(onDismiss = { generationDialog = false }, onGenerate = { endpoint, key, model ->
        generationDialog = false
        generating = true
        scope.launch {
            try { repository.generateSubtitles(tour.id, endpoint, key, model); captionsVersion++; report("Timed subtitles are ready.") }
            catch (e: Exception) { report(e.message ?: "Transcription failed.") }
            finally { generating = false }
        }
    })
}

@Composable
private fun PlayerControls(state: PlaybackUiState, active: Boolean, canPlay: Boolean, onPlay: () -> Unit, playback: PlaybackConnection) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    Column {
        Slider(value = dragging ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()), onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { playback.seek(it.toLong()) }; dragging = null },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = active && state.durationMs > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(dragging?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelMedium)
            Text(time(state.durationMs), style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { speedMenu = true }, enabled = active) { Text("${state.speed}×") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed -> DropdownMenuItem(text = { Text("${speed}×") }, onClick = { playback.setSpeed(speed); speedMenu = false }) }
                }
            }
            IconButton(onClick = { playback.seek((state.positionMs - 20_000).coerceAtLeast(0)) }, enabled = active) { Icon(Icons.Default.Replay, "Back 20 seconds") }
            FilledIconButton(onClick = { if (active) playback.toggle() else onPlay() }, enabled = canPlay, modifier = Modifier.size(72.dp)) {
                Icon(if (active && state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (active && state.playing) "Pause" else "Play", Modifier.size(38.dp))
            }
            IconButton(onClick = { playback.seek((state.positionMs + 20_000).coerceAtMost(state.durationMs)) }, enabled = active) { Icon(Icons.Default.FastForward, "Forward 20 seconds") }
            IconButton(onClick = playback::stop, enabled = active) { Icon(Icons.Default.Stop, "Stop tour") }
        }
    }
}

@Composable
private fun GroupScreen(session: SessionManager, current: Tour?, onPrepare: () -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    var paired by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pairedLoaded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke() else report("Allow nearby devices to connect a tour session.")
        pending = null
    }
    fun withBluetooth(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pending = action
            permissions.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else action()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { withBluetooth { paired = session.pairedDevices(); pairedLoaded = true } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("The same walk.\nTogether.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Bluetooth, null, Modifier.size(36.dp))
                Text(if (state.role == "none") "A shared tour over Bluetooth" else if (state.role == "host") "You’re hosting" else "Your shared tour", style = MaterialTheme.typography.titleLarge)
                Text(if (state.role == "none") "Download the same tour on every phone, then pair the phones in Bluetooth settings. Sessions work without Wi-Fi or mobile data." else state.status)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.role != "none") {
            Text("Everyone can pause, seek, and change the playback speed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${state.peers.size} connected ${if (state.peers.size == 1) "phone" else "phones"}", style = MaterialTheme.typography.titleMedium)
            state.peers.forEach { name -> ListItem(headlineContent = { Text(name) }, leadingContent = { Icon(Icons.Default.PhoneAndroid, null) }) }
            OutlinedButton(onClick = session::leave, modifier = Modifier.fillMaxWidth()) { Text(if (state.role == "host") "End session" else "Leave session") }
        } else {
            if (current != null) {
                Text("Host with", style = MaterialTheme.typography.labelLarge)
                Text(current.title, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { withBluetooth { session.host() } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.GroupAdd, null); Text(" Host a tour") }
            } else {
                Text("Open a downloaded tour in the player to host a session.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPrepare, modifier = Modifier.fillMaxWidth()) { Text("Choose a downloaded tour") }
            }
            HorizontalDivider()
            Text("Join a friend", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { withBluetooth { paired = session.pairedDevices(); pairedLoaded = true } }, modifier = Modifier.fillMaxWidth()) { Text("Show paired phones") }
            if (pairedLoaded && paired.isEmpty()) Text("No paired phones found. Turn on Bluetooth and pair with the host in Bluetooth settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            paired.forEach { (address, name) ->
                OutlinedCard(onClick = { withBluetooth { session.join(address) } }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PhoneAndroid, null)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(name); Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Icons.Default.ChevronRight, "Join")
                    }
                }
            }
            TextButton(onClick = { runCatching { settings.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.onFailure { report("Bluetooth settings are unavailable on this device.") } }) { Icon(Icons.Default.SettingsBluetooth, null); Text(" Bluetooth settings") }
        }
    }
}

@Composable
private fun TranscriptionDialog(onDismiss: () -> Unit, onGenerate: (String, String, String) -> Unit) {
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("whisper-1") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Generate live subtitles") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The tour audio will be uploaded to your transcription provider. Provider charges and upload limits apply. The API key is used for this request only.")
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS transcription endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Requires a multipart audio endpoint that returns verbose JSON with segment timestamps.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onGenerate(endpoint.trim(), key.trim(), model.trim()); key = "" }, enabled = endpoint.startsWith("https://") && model.isNotBlank()) { Text("Upload and generate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TourArtwork(file: File?, title: String) {
    val bitmap = rememberBitmap(file, 1200).image
    Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Surface(shape = RoundedCornerShape(24.dp)) { Image(bitmap, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        else Icon(Icons.Default.Explore, null, Modifier.size(84.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .6f))
    }
}

@Composable
private fun ZoomableMap(file: File?, title: String) {
    val loaded = rememberBitmap(file, 4096)
    val bitmap = loaded.image
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    if (file == null) { EmptyState(Icons.Default.Map, "No map for this audio", "Maps are included with walking tours."); return }
    if (loaded.loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (bitmap == null) { EmptyState(Icons.Default.Map, "Could not open this map", "Delete and download the tour again to restore its map."); return }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Box(Modifier.fillMaxSize().clipToBounds().pointerInput(file) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                val limitX = size.width * (scale - 1) / 2f
                val limitY = size.height * (scale - 1) / 2f
                offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
            }
        }, contentAlignment = Alignment.Center) {
            Image(bitmap, "Map of $title", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), shape = RoundedCornerShape(30.dp), tonalElevation = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); offset = Offset.Zero }) { Icon(Icons.Default.Remove, "Zoom out") }
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Reset") }
                IconButton(onClick = { scale = (scale * 1.5f).coerceAtMost(8f) }) { Icon(Icons.Default.Add, "Zoom in") }
            }
        }
    }
}

private data class LoadedBitmap(val image: ImageBitmap? = null, val loading: Boolean = true)

@Composable
private fun rememberBitmap(file: File?, maxDimension: Int): LoadedBitmap {
    val bitmap by produceState(LoadedBitmap(), file?.absolutePath, maxDimension) {
        value = LoadedBitmap()
        val image = withContext(Dispatchers.IO) {
            if (file == null) null else runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        value = LoadedBitmap(image, false)
    }
    return bitmap
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun bytes(value: Long): String = if (value >= 1_000_000_000) String.format(Locale.getDefault(), "%.1f GB", value / 1_000_000_000.0) else String.format(Locale.getDefault(), "%.1f MB", value / 1_000_000.0)
private fun time(value: Long): String { val seconds = value.coerceAtLeast(0) / 1000; return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) else String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60) }

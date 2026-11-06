@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hydranet.opensteves.ui

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hydranet.opensteves.data.*
import dev.hydranet.opensteves.playback.PlaybackConnection
import dev.hydranet.opensteves.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PlayingScreen(
    tour: Tour,
    repository: TourRepository,
    playback: PlaybackConnection,
    assetVersion: String?,
    sessionActive: Boolean,
    fullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit,
    onPlay: (Long?) -> Unit,
) {
    val player by playback.state.collectAsState()
    var tab by rememberSaveable(tour.id) { mutableIntStateOf(0) }
    val chapterResult by produceState<Result<List<Chapter>>?>(null, tour.id, assetVersion) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { repository.chapterFile(tour.id)?.readText()?.let(TimedText::parseChapters).orEmpty() }
        }
    }
    val captionResult by produceState<Result<List<SubtitleCue>>?>(null, tour.id, assetVersion) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { repository.subtitlesFile(tour.id)?.readText()?.let(TimedText::parseSubtitles).orEmpty() }
        }
    }
    val transcriptResult by produceState<Result<String>?>(null, tour.id, assetVersion) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                repository.transcriptFile(tour.id)?.readText()
                    ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString() }
                    ?.let { text -> text.trim().replace(Regex("[\\t ]*\\n(?:[\\t ]*\\n)*"), "\n") }
                    .orEmpty()
            }
        }
    }
    val mapResult by produceState<Result<File?>?>(null, tour.id, assetVersion) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { repository.mapFile(tour.id) } }
    }
    val chapters = chapterResult?.getOrNull().orEmpty()
    val captions = captionResult?.getOrNull().orEmpty()
    val transcript = transcriptResult?.getOrNull().orEmpty()
    val active = player.tourId == tour.id
    val canPlay = !sessionActive || active
    val state = if (active) player else PlaybackUiState()
    val chapter = chapters.lastOrNull { state.positionMs >= it.startMs && state.positionMs < it.endMs }
    val caption = captions.lastOrNull { active && state.positionMs >= it.startMs && state.positionMs < it.endMs }
    BackHandler(fullScreen || tab != 0) {
        if (fullScreen) onFullScreenChange(false) else tab = 0
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 400.dp
        Column(Modifier.fillMaxSize()) {
            if (!fullScreen) {
                if (!compact) PlayerHeading(tour, repository, chapter, assetVersion)
                if (!compact || tab == 0) Column(Modifier.padding(horizontal = 20.dp)) {
                    PlayerControls(state, active, canPlay, { onPlay(null) }, playback)
                    if (!canPlay) Text("Leave the current session to play a different tour.", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionTabs(listOf("Chapters", "Map", "Transcript"), tab) { tab = it }
            }
            if (tab == 0) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (caption != null) item {
                        Surface(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text(caption.text, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (chapterResult == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (chapterResult?.isFailure == true) item {
                        Text("Could not read the saved chapters. Download the tour again to restore them.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                    }
                    if (chapterResult?.isSuccess == true && chapters.isEmpty()) item {
                        Text("No chapters included with this audio.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    itemsIndexed(chapters, key = { _, item -> "${item.startMs}-${item.title}" }) { index, item ->
                        val selected = item == chapter && active
                        Surface(
                            onClick = { if (active) playback.seek(item.startMs) else onPlay(item.startMs) },
                            enabled = canPlay,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        ) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selected) Icon(Icons.Default.Equalizer, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                else Text((index + 1).toString().padStart(2, '0'), Modifier.width(20.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.title, Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(time(item.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (tab == 1) Box(Modifier.weight(1f)) {
                if (mapResult == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else ZoomableMap(mapResult?.getOrNull(), tour.title)
                Surface(Modifier.align(Alignment.TopEnd).padding(12.dp), shape = MaterialTheme.shapes.small, tonalElevation = 4.dp) {
                    FullScreenButton(fullScreen) { onFullScreenChange(!fullScreen) }
                }
            } else Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tour script", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    FullScreenButton(fullScreen) { onFullScreenChange(!fullScreen) }
                }
                if (captionResult == null || transcriptResult == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (captionResult?.isFailure == true || transcriptResult?.isFailure == true) {
                    Text("Could not read saved text. Download the tour again to restore it.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                }
                if (captions.isNotEmpty()) Text("${captions.size} timed ${if (captions.size == 1) "caption" else "captions"}", Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (captionResult != null && transcriptResult != null) {
                    if (transcript.isEmpty() && captions.isEmpty()) {
                        EmptyState(Icons.AutoMirrored.Outlined.Subject, "No transcript available", "This tour does not include a transcript.")
                    } else SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp)) {
                        Text(transcript.ifEmpty { captions.joinToString("\n\n") { it.text } }.trim(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenButton(fullScreen: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, colors = TourButtonDefaults.iconColors()) {
        Icon(if (fullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            if (fullScreen) "Exit full screen" else "Full screen")
    }
}

@Composable
private fun PlayerHeading(tour: Tour, repository: TourRepository, chapter: Chapter?, assetVersion: String?) {
    val file by produceState<File?>(null, tour.id, assetVersion, chapter?.imagePath) {
        value = withContext(Dispatchers.IO) { repository.imageFile(tour.id, chapter?.imagePath ?: "images/01.jpg") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        TourThumbnail(file, Modifier.size(72.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(tourLocation(tour), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(displayTitle(tour), Modifier.padding(top = 4.dp), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlayerControls(state: PlaybackUiState, active: Boolean, canPlay: Boolean, onPlay: () -> Unit, playback: PlaybackConnection) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    val sliderInteractions = remember { MutableInteractionSource() }
    LaunchedEffect(state.tourId) { dragging = null }
    Column {
        Slider(
            value = dragging ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { playback.seek(it.toLong()) }; dragging = null },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = active && state.durationMs > 0,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            interactionSource = sliderInteractions,
            thumb = { SliderDefaults.Thumb(sliderInteractions, enabled = active && state.durationMs > 0, thumbSize = androidx.compose.ui.unit.DpSize(20.dp, 20.dp)) },
            track = { SliderDefaults.Track(it, modifier = Modifier.height(4.dp), thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp, drawStopIndicator = null) },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(dragging?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time(state.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp)) {
                TextButton(shape = MaterialTheme.shapes.small, onClick = { speedMenu = true }, enabled = active, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(0.dp)) {
                    Text(speedLabel(state.speed), style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                        DropdownMenuItem(text = { Text(speedLabel(speed)) }, onClick = { playback.setSpeed(speed); speedMenu = false })
                    }
                }
            }
            SeekButton(Icons.Default.Replay, "Back 20 seconds", active) { playback.seek((state.positionMs - 20_000).coerceAtLeast(0)) }
            FilledIconButton(onClick = { if (active) playback.setPlaying(!state.playing) else onPlay() }, enabled = canPlay, modifier = Modifier.size(64.dp), shape = androidx.compose.foundation.shape.CircleShape) {
                Icon(if (active && state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (active && state.playing) "Pause" else "Play", Modifier.size(34.dp))
            }
            SeekButton(Icons.Default.FastForward, "Forward 20 seconds", active) { playback.seek((state.positionMs + 20_000).coerceAtMost(state.durationMs)) }
            IconButton(onClick = playback::stop, enabled = active, colors = TourButtonDefaults.iconColors()) { Icon(Icons.Outlined.StopCircle, "Stop playback") }
        }
    }
}

@Composable
private fun SeekButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), colors = TourButtonDefaults.iconColors()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, description, Modifier.size(24.dp))
            Text("20 s", fontSize = 10.sp, lineHeight = 12.sp)
        }
    }
}

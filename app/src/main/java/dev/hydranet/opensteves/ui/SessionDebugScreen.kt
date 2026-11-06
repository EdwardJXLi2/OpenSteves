package dev.hydranet.opensteves.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.session.SessionDiagnostics
import dev.hydranet.opensteves.session.SessionDebugSettings
import dev.hydranet.opensteves.session.SessionManager
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun SessionDebugScreen(session: SessionManager, onBack: () -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val events by session.debugEvents.collectAsState()
    val settings by session.debugSettings.collectAsState()
    var editingSettings by rememberSaveable { mutableStateOf(false) }
    val diagnostics by produceState(session.diagnostics(), session, state) {
        while (true) {
            value = session.diagnostics()
            delay(1_000)
        }
    }
    val details = diagnosticDetails(diagnostics)
    val colors = MaterialTheme.colorScheme
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to session") }
            Text("Session debug", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = {
                val text = buildString {
                    appendLine("OpenSteves session debug")
                    appendLine("Role: ${state.role}")
                    appendLine("Status: ${state.status}")
                    state.error?.let { appendLine("Error: $it") }
                    appendLine(details)
                    appendLine()
                    appendLine(settingsDetails(settings))
                    appendLine()
                    appendLine("Latest ${events.size} events (oldest first)")
                    events.forEach { appendLine("+${it.elapsedMs} ms [${it.category}] ${it.detail}") }
                }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Session debug", text))
                report("Session debug copied")
            }) { Icon(Icons.Outlined.ContentCopy, "Copy session debug") }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.role == "none") "Session inactive" else "${state.role.replaceFirstChar { it.uppercase() }} / ${state.status}",
                        style = MaterialTheme.typography.titleSmall)
                    state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
                    SelectionContainer {
                        Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Synchronization settings", style = MaterialTheme.typography.titleMedium)
                    Text(settingsDetails(settings), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    OutlinedButton(onClick = { editingSettings = true }, shape = MaterialTheme.shapes.small) {
                        Text("Edit settings")
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Bluetooth traffic and events", style = MaterialTheme.typography.titleMedium)
                Text("Latest 200 events", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (events.isEmpty()) item { Text("No session events recorded.", style = MaterialTheme.typography.bodyMedium) }
            items(events.asReversed(), key = { it.id }) { event ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("+${event.elapsedMs} ms  ${event.category}", style = MaterialTheme.typography.labelMedium,
                        color = if (event.category == "ERROR") colors.error else colors.primary)
                    SelectionContainer {
                        Text(event.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outlineVariant)
                }
            }
        }
    }
    if (editingSettings) DebugSettingsDialog(
        settings = settings,
        onDismiss = { editingSettings = false },
        onApply = {
            session.updateDebugSettings(it)
            editingSettings = false
            report("Synchronization settings saved")
        },
    )
}

@Composable
private fun DebugSettingsDialog(settings: SessionDebugSettings, onDismiss: () -> Unit, onApply: (SessionDebugSettings) -> Unit) {
    var values by rememberSaveable(settings) { mutableStateOf(settingsValues(settings)) }
    val draft = parseDebugSettings(values)
    val error = if (draft == null) "Enter a whole number for every setting." else draft.validationError()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Synchronization settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved on this phone. Pending actions keep their timing.")
                debugSettingLabels.forEachIndexed { index, label ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { value -> values = values.toMutableList().apply { this[index] = value } },
                        label = { Text(label) },
                        suffix = { if (index != 2) Text("ms") },
                        isError = values[index].toIntOrNull() == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { values = settingsValues(SessionDebugSettings()) }) { Text("Reset defaults") }
            }
        },
        confirmButton = {
            TextButton(onClick = { draft?.let(onApply) }, enabled = error == null && draft != settings) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val debugSettingLabels = listOf(
    "Paused drift tolerance", "Playing drift threshold", "Consecutive samples", "Correction cooldown",
    "Scheduling lead (host)", "Clock sync interval", "Playback sync (host)",
    "Connection timeout", "Clock sample age limit", "Clock RTT limit",
)

private fun settingsValues(settings: SessionDebugSettings): List<String> = listOf(
    settings.pausedDriftToleranceMs.toString(), settings.playingDriftThresholdMs.toString(),
    settings.requiredDriftSamples.toString(), settings.correctionCooldownMs.toString(),
    settings.schedulingLeadMs.toString(), settings.clockSyncIntervalMs.toString(), settings.playbackSyncIntervalMs.toString(),
    settings.peerTimeoutMs.toString(), settings.clockSampleMaxAgeMs.toString(), settings.maxClockRttMs.toString(),
)

private fun parseDebugSettings(values: List<String>): SessionDebugSettings? {
    val numbers = values.map { it.toIntOrNull() ?: return null }
    return SessionDebugSettings(
        pausedDriftToleranceMs = numbers[0].toLong(), playingDriftThresholdMs = numbers[1].toLong(),
        requiredDriftSamples = numbers[2], correctionCooldownMs = numbers[3].toLong(),
        schedulingLeadMs = numbers[4].toLong(), clockSyncIntervalMs = numbers[5].toLong(),
        playbackSyncIntervalMs = numbers[6].toLong(), peerTimeoutMs = numbers[7].toLong(),
        clockSampleMaxAgeMs = numbers[8].toLong(), maxClockRttMs = numbers[9].toLong(),
    )
}

private fun settingsDetails(settings: SessionDebugSettings): String = buildString {
    debugSettingLabels.zip(settingsValues(settings)).forEachIndexed { index, (label, value) ->
        appendLine("$label: $value${if (index == 2) "" else " ms"}")
    }
}.trimEnd()

private fun milliseconds(value: Long?): String = value?.let { "$it ms" } ?: "not measured"
private fun milliseconds(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "not measured"

private fun diagnosticDetails(info: SessionDiagnostics): String = buildString {
    appendLine("Time since start: ${info.elapsedMs / 1_000} s")
    appendLine("Bluetooth: ${when {
        info.listening -> "listening"
        info.connecting -> "connecting"
        info.peers.isNotEmpty() -> "connected"
        else -> "idle"
    }}")
    appendLine("TX / RX frames: ${info.sentFrames} / ${info.receivedFrames}")
    appendLine("Connections: ${info.peers.size}")
    info.peers.forEach { peer ->
        appendLine("  Peer ${peer.id}: ${if (peer.ready) "ready" else "synchronizing"}, last RX ${peer.lastReceivedMs} ms ago")
        appendLine("    TX / RX frames: ${peer.sentFrames} / ${peer.receivedFrames}")
        appendLine("    Current RTT: ${milliseconds(peer.rtt.latestMs)}")
        appendLine("    Best / worst RTT: ${milliseconds(peer.rtt.bestMs)} / ${milliseconds(peer.rtt.worstMs)}")
        appendLine("    RTT jitter: ${milliseconds(peer.rtt.jitterMs)}")
        appendLine("    RTT samples: ${peer.rtt.samples}")
        appendLine("    Pending pings: ${peer.pendingPings}")
        peer.lastPongAgeMs?.let { appendLine("    Last pong: $it ms ago") }
    }
    appendLine("Pending commands: ${info.pendingCommands}")
    appendLine("Last sequence: ${info.sequence.takeIf { it >= 0 } ?: "none"}")
    info.hostSchedulingLeadMs?.let { appendLine("Host scheduling lead: $it ms") }
    info.clockSampleAgeMs?.let { appendLine("Clock sample age: $it ms") }
    appendLine("Last guest drift: ${info.driftMs?.let { "$it ms (local minus host)" } ?: "not measured"}")
    appendLine("Automatic seeks: ${info.automaticSeeks}")
    appendLine("Correction cooldown remaining: ${info.cooldownRemainingMs} ms")
    info.playbackDiagnostics?.let {
        appendLine("Player state: ${it.state}")
        appendLine("Actually playing: ${it.actuallyPlaying}")
    }
    info.playback?.let {
        appendLine("Tour ID: ${it.tourId}")
        appendLine("Position: ${it.positionMs} ms")
        appendLine("Speed: ${it.speed}x")
        appendLine("Audio hash: ${it.hash}")
    }
}.trimEnd()

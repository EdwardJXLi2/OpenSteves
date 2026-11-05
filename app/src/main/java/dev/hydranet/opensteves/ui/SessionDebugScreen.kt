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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.session.SessionDiagnostics
import dev.hydranet.opensteves.session.SessionManager
import dev.hydranet.opensteves.session.SessionTiming
import kotlinx.coroutines.delay

@Composable
internal fun SessionDebugScreen(session: SessionManager, onBack: () -> Unit, report: (String) -> Unit) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val events by session.debugEvents.collectAsState()
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
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Bluetooth traffic and events", style = MaterialTheme.typography.titleMedium)
                Text("Latest 200 events, newest first. Times are since session start.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
}

private fun diagnosticDetails(info: SessionDiagnostics): String = buildString {
    appendLine("Time since start: ${info.elapsedMs / 1_000} s")
    appendLine("Bluetooth: ${when {
        info.listening -> "listening"
        info.connecting -> "connecting"
        info.peers.isNotEmpty() -> "connected"
        else -> "idle"
    }}")
    appendLine("Frames sent / received: ${info.sentFrames} / ${info.receivedFrames}")
    appendLine("Connections: ${info.peers.size}")
    info.peers.forEach { peer ->
        appendLine("  Peer ${peer.id}: ${if (peer.ready) "ready" else "synchronizing"}, last RX ${peer.lastReceivedMs} ms ago")
    }
    appendLine("Pending commands: ${info.pendingCommands}")
    appendLine("Last sequence: ${info.sequence.takeIf { it >= 0 } ?: "none"}")
    appendLine("Scheduling lead: ${SessionTiming.LEAD_MS} ms")
    appendLine("Best clock RTT: ${info.rttMs?.let { "$it ms" } ?: "not measured"}")
    appendLine("Host clock offset: ${info.clockOffsetMs?.let { "$it ms" } ?: "not measured"}")
    info.clockSampleAgeMs?.let { appendLine("Clock sample age: $it ms") }
    appendLine("Last guest drift: ${info.driftMs?.let { "$it ms (local minus host)" } ?: "not measured"}")
    appendLine("Player ready: ${info.playbackReady}")
    info.playback?.let {
        appendLine("Tour ID: ${it.tourId}")
        appendLine("Position: ${it.positionMs} ms")
        appendLine("Playing: ${it.playing}")
        appendLine("Speed: ${it.speed}x")
        append("Audio hash: ${it.hash}")
    }
}.trimEnd()

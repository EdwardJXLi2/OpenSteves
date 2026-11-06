package dev.hydranet.opensteves.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SettingsBluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hydranet.opensteves.data.Chapter
import dev.hydranet.opensteves.data.TimedText
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.data.TourRepository
import dev.hydranet.opensteves.playback.PlaybackConnection
import dev.hydranet.opensteves.playback.PlaybackUiState
import dev.hydranet.opensteves.session.SessionManager
import dev.hydranet.opensteves.session.SessionPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun TogetherScreen(
    session: SessionManager,
    repository: TourRepository,
    playback: PlaybackConnection,
    current: Tour?,
    startingSession: Boolean,
    onStartSession: () -> Unit,
    onOpenPlayer: () -> Unit,
    report: (String) -> Unit,
) {
    val context = LocalContext.current
    val state by session.state.collectAsState()
    val player by playback.state.collectAsState()
    val installed by repository.installed.collectAsState()
    var paired by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showingPaired by rememberSaveable { mutableStateOf(false) }
    var showAllPaired by rememberSaveable { mutableStateOf(false) }
    var pairedLoaded by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmRole by remember { mutableStateOf<String?>(null) }
    var guestToRemove by remember { mutableStateOf<SessionPeer?>(null) }
    var completedRole by rememberSaveable { mutableStateOf<String?>(null) }
    var resultDismissed by rememberSaveable { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingPermission?.invoke() else report("Allow Nearby devices permission to connect a session.")
        pendingPermission = null
    }
    fun withBluetooth(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = action
            permissions.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else action()
    }
    fun refreshPaired() {
        withBluetooth {
            paired = session.pairedDevices(includeAll = showAllPaired)
            pairedLoaded = true
            showingPaired = true
        }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (showingPaired) refreshPaired()
    }
    fun openBluetoothSettings() {
        runCatching { settings.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { report("Bluetooth settings are unavailable on this device.") }
    }
    LaunchedEffect(state.role) {
        if (state.role != "host") guestToRemove = null
        if (state.role != "none") {
            completedRole = null
            resultDismissed = false
            showingPaired = false
        } else confirmRole = null
    }
    BackHandler(showingPaired && state.role == "none") { showingPaired = false }

    val colors = MaterialTheme.colorScheme
    val active = state.role != "none"
    val host = state.role == "host"
    val starting = state.status.startsWith("Starting")
    val connecting = state.role == "guest" && state.peers.isEmpty()
    val completion = if (resultDismissed) null else (
        completedRole ?: "ended".takeIf { state.role == "none" && state.status == "Session ended" && state.error == null }
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (completion != null && !active) {
            Surface(color = colors.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(when (completion) {
                            "guest" -> "You left the session"
                            "cancel" -> "Connection cancelled"
                            else -> "Session ended"
                        }, style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
                        Text(if (completion == "host") "Playback is paused on all connected phones." else "Your playback is paused.", style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
                    }
                    IconButton(onClick = { completedRole = null; resultDismissed = true }, colors = TourButtonDefaults.iconColors()) { Icon(Icons.Default.Close, "Dismiss session result", modifier = Modifier.size(20.dp)) }
                }
            }
        }
        state.error?.let { error ->
            Surface(color = colors.errorContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                Text(error, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer)
            }
        }
        when {
            connecting -> {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Joining session", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                }
                Text(state.status, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                OutlinedButton(shape = MaterialTheme.shapes.small, colors = TourButtonDefaults.outlinedColors(), onClick = { session.leave(); completedRole = "cancel" }, modifier = Modifier.fillMaxWidth().padding(20.dp).height(48.dp)) { Text("Cancel connection") }
            }
            active -> {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (starting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.BluetoothConnected, null, tint = colors.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (starting) "Starting session" else if (host) "You’re hosting" else "Listening together", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                        if (!host || starting) Text(state.status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                current?.let { tour ->
                    val playbackState = player
                    SessionTour(repository, tour, playbackState, onOpenPlayer, onToggle = { playback.setPlaying(!playbackState.playing) })
                }
                SessionSection(if (host) {
                    if (state.peers.isEmpty()) "Waiting for friends" else "Connected phones"
                } else "Your connection")
                if (host) {
                    SessionPhone("This phone", "Hosting the session", host = true)
                    state.peers.forEach { guest -> SessionPhone(guest.name, "Joined the session", onRemove = { guestToRemove = guest }) }
                } else {
                    state.peers.forEach { peer -> SessionPhone(peer.name, "Hosting the session", host = true) }
                    SessionPhone("This phone", "Joined the session")
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (host && state.peers.isEmpty()) "Friends can join from Together on their paired phones." else "Everyone can pause, seek, and change speed.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    if (host && state.peers.isEmpty()) {
                        OutlinedButton(shape = MaterialTheme.shapes.small, colors = TourButtonDefaults.outlinedColors(), onClick = ::openBluetoothSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Bluetooth settings") }
                    }
                    OutlinedButton(shape = MaterialTheme.shapes.small, colors = TourButtonDefaults.outlinedColors(), onClick = onOpenPlayer, enabled = current != null, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Back to tour") }
                    if (host || state.peers.isNotEmpty()) {
                        OutlinedButton(shape = MaterialTheme.shapes.small, colors = TourButtonDefaults.outlinedColors(), onClick = playback::stop, enabled = current != null, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Stop playback for everyone") }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(shape = MaterialTheme.shapes.small, colors = TourButtonDefaults.outlinedColors().copy(contentColor = colors.error), onClick = { confirmRole = state.role }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(if (host) "End session" else "Leave session")
                    }
                }
            }
            showingPaired -> {
                Column(Modifier.padding(20.dp)) {
                    Icon(Icons.Outlined.Bluetooth, null, tint = colors.primary, modifier = Modifier.size(30.dp))
                    Text("Join a session", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                    Text("Choose a paired phone that is hosting.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showAllPaired) "Paired devices" else "Paired phones and tablets", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    TextButton(shape = MaterialTheme.shapes.small, onClick = ::refreshPaired) { Text("Refresh") }
                }
                paired.forEach { (address, name) -> SessionPhone(name, if (showAllPaired) "Paired device" else "Paired phone or tablet", onJoin = { withBluetooth { session.join(address) } }) }
                if (pairedLoaded && paired.isEmpty()) Text(
                    if (showAllPaired) "No paired devices found. Pair with the host in Bluetooth settings, then refresh."
                    else "No paired phones or tablets found. If your host is missing, show all paired devices or pair it in Bluetooth settings.",
                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = showAllPaired, role = Role.Checkbox, onValueChange = {
                        showAllPaired = it
                        refreshPaired()
                    }).padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = showAllPaired, onCheckedChange = null)
                    Text("Show all paired devices", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(shape = MaterialTheme.shapes.small, colors = TourButtonDefaults.outlinedColors(), onClick = ::openBluetoothSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Outlined.SettingsBluetooth, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pair another phone")
                    }
                    TextButton(shape = MaterialTheme.shapes.small, onClick = { showingPaired = false }) { Text("Back to joining") }
                }
            }
            else -> {
                Surface(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    color = colors.surfaceContainer, shape = RoundedCornerShape(6.dp),
                ) {
                    Column {
                        Text("Host a session", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium)
                        if (current != null && installed.containsKey(current.id)) {
                            SessionTour(repository, current, null, onOpenPlayer, null, framed = false)
                            Button(shape = MaterialTheme.shapes.small, onClick = onStartSession, enabled = !startingSession, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).height(48.dp)) { Text(if (startingSession) "Starting session…" else "Start a session") }
                        } else {
                            Text("Start a tour from Explore first.", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
                Surface(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    color = colors.surfaceContainer, shape = RoundedCornerShape(6.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Join a session", style = MaterialTheme.typography.titleMedium)
                        Button(shape = MaterialTheme.shapes.small, onClick = ::refreshPaired, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) { Text("Show paired phones") }
                        TextButton(shape = MaterialTheme.shapes.small, onClick = ::openBluetoothSettings, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Outlined.SettingsBluetooth, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Bluetooth settings")
                        }
                    }
                }
            }
        }
    }
    guestToRemove?.let { guest ->
        AlertDialog(
            onDismissRequest = { guestToRemove = null },
            title = { Text("Remove ${guest.name}?") },
            text = { Text("Their playback will pause and they will leave the session. Everyone else will keep listening.") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    session.removeGuest(guest.address)
                    guestToRemove = null
                }) { Text("Remove", color = colors.error) }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { guestToRemove = null }) { Text("Cancel") } },
        )
    }
    confirmRole?.let { role ->
        val ending = role == "host"
        AlertDialog(
            onDismissRequest = { confirmRole = null },
            title = { Text(if (ending) "End session?" else "Leave session?") },
            text = { Text(if (ending) "Playback pauses on every connected phone. Everyone will need to join a new session to listen together again." else "Playback pauses on this phone. The host’s session continues.") },
            confirmButton = {
                TextButton(shape = MaterialTheme.shapes.small, onClick = {
                    confirmRole = null
                    if (session.state.value.role == role) {
                        session.leave()
                        completedRole = role
                    }
                }) { Text(if (ending) "End session" else "Leave session", color = colors.error) }
            },
            dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = { confirmRole = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionTour(repository: TourRepository, tour: Tour, player: PlaybackUiState?, onOpen: () -> Unit, onToggle: (() -> Unit)?, framed: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val installed by repository.installed.collectAsState()
    val chapters by produceState<List<Chapter>>(emptyList(), tour.id, installed[tour.id]?.audioHash) {
        value = withContext(Dispatchers.IO) { runCatching { repository.chapterFile(tour.id)?.readText()?.let(TimedText::parseChapters).orEmpty() }.getOrDefault(emptyList()) }
    }
    val chapter = player?.let { state -> chapters.lastOrNull { state.positionMs >= it.startMs && state.positionMs < it.endMs } }
    val artwork by produceState<File?>(null, tour.id, installed[tour.id]?.directoryName, chapter?.imagePath) {
        value = withContext(Dispatchers.IO) { repository.imageFile(tour.id, chapter?.imagePath ?: "images/01.jpg") }
    }
    Surface(shape = RoundedCornerShape(7.dp), color = if (framed) colors.surfaceContainerLow else colors.surfaceContainer,
        border = if (framed) BorderStroke(1.dp, colors.outlineVariant) else null,
        modifier = if (framed) Modifier.padding(horizontal = 16.dp) else Modifier) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TourThumbnail(artwork, Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(displayTitle(tour), style = MaterialTheme.typography.titleMedium)
                    Text("${tourLocation(tour)} / Downloaded", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, "Back to tour", tint = colors.primary, modifier = Modifier.size(20.dp))
            }
            if (player != null && onToggle != null) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (player.playing) Icons.Default.Equalizer else Icons.Outlined.Headphones, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(chapter?.title ?: if (player.playing) "Playing" else "Paused", style = MaterialTheme.typography.titleSmall)
                        Text("${time(player.positionMs)} / ${time(player.durationMs)} / ${speedLabel(player.speed)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onToggle, enabled = player.tourId == tour.id, colors = TourButtonDefaults.iconColors()) { Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playing) "Pause for everyone" else "Play for everyone") }
                }
            }
        }
    }
}

@Composable
private fun SessionSection(title: String) {
    Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SessionPhone(name: String, status: String, host: Boolean = false, onJoin: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.PhoneAndroid, null, tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(status, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        when {
            onJoin != null -> TextButton(shape = MaterialTheme.shapes.small, onClick = onJoin) { Text("Join") }
            onRemove != null -> IconButton(onClick = onRemove) { Icon(Icons.Outlined.PersonRemove, "Remove $name", tint = colors.error) }
            host -> Text("HOST", style = MaterialTheme.typography.labelSmall, color = colors.primary)
            else -> Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
    }
    HorizontalDivider(Modifier.padding(start = 58.dp), color = colors.outlineVariant)
}

package dev.hydranet.opensteves.session

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.abs

@SuppressLint("MissingPermission")
class SessionManager private constructor(private val context: Context) {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val debugPreferences = context.getSharedPreferences("session_debug", Context.MODE_PRIVATE)
    private val mutableDebugSettings = MutableStateFlow(readDebugSettings())
    internal val debugSettings: StateFlow<SessionDebugSettings> = mutableDebugSettings.asStateFlow()
    private var hostSchedulingLeadMs: Long? = null
    internal val schedulingLeadMs: Long
        get() = if (state.value.role == "guest") hostSchedulingLeadMs ?: 600L else debugSettings.value.schedulingLeadMs
    private var playback: SessionPlayback? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: BluetoothServerSocket? = null
    private var connectingSocket: BluetoothSocket? = null
    private val peers = mutableMapOf<String, Peer>()
    private var correction: Job? = null
    private var guestApplyTail: Job? = null
    private val hostSchedule = linkedMapOf<Long, Pair<PlaybackSnapshot, Long>>()
    private val guestSchedule = mutableSetOf<Long>()
    private var generation = 0L
    private var sequence = 0L
    private var receivedSequence = -1L
    private var clockOffset = 0L
    private var selectedClockRttMs = Long.MAX_VALUE
    private var clockSampleAt = 0L
    private var hostGreeting: PlaybackSnapshot? = null
    private val mutableDebugEvents = MutableStateFlow<List<SessionDebugEvent>>(emptyList())
    internal val debugEvents: StateFlow<List<SessionDebugEvent>> = mutableDebugEvents.asStateFlow()
    private var debugStartedAt = 0L
    private var debugEventId = 0L
    private var debugPeerId = 0
    private var sentFrames = 0L
    private var receivedFrames = 0L
    private var lastDriftMs: Long? = null
    private var automaticSeeks = 0L
    private val driftCorrection = SessionDriftCorrection()

    private class Peer(val socket: BluetoothSocket, val address: String, val name: String, val debugId: Int) {
        val outgoing = Channel<ByteArray>(16)
        var ready = false
        var acceptsPing = false
        var lastReceived = SystemClock.elapsedRealtime()
        var sentFrames = 0L
        var receivedFrames = 0L
        var lastPongAt: Long? = null
        val pendingPings = mutableSetOf<Long>()
        val rtt = SessionRttStatistics()
        fun close() {
            outgoing.close()
            runCatching { socket.close() }
        }
    }

    internal fun diagnostics(): SessionDiagnostics {
        val timestamp = now()
        return SessionDiagnostics(
            elapsedMs = if (debugStartedAt == 0L) 0 else timestamp - debugStartedAt,
            listening = server != null,
            connecting = connectingSocket != null,
            peers = peers.values.map {
                SessionDebugPeer(
                    id = it.debugId,
                    ready = it.ready,
                    lastReceivedMs = timestamp - it.lastReceived,
                    sentFrames = it.sentFrames,
                    receivedFrames = it.receivedFrames,
                    rtt = it.rtt.snapshot(),
                    pendingPings = it.pendingPings.size,
                    lastPongAgeMs = it.lastPongAt?.let { at -> timestamp - at },
                )
            },
            sentFrames = sentFrames,
            receivedFrames = receivedFrames,
            clockSampleAgeMs = (timestamp - clockSampleAt).takeIf { selectedClockRttMs != Long.MAX_VALUE },
            hostSchedulingLeadMs = hostSchedulingLeadMs,
            driftMs = lastDriftMs,
            automaticSeeks = automaticSeeks,
            cooldownRemainingMs = driftCorrection.cooldownRemainingMs(timestamp, debugSettings.value),
            pendingCommands = hostSchedule.size + guestSchedule.size,
            sequence = if (state.value.role == "guest") receivedSequence else sequence,
            playback = playback?.snapshot(),
            playbackDiagnostics = playback?.diagnostics(),
        )
    }

    internal fun updateDebugSettings(settings: SessionDebugSettings) {
        require(settings.validationError() == null)
        debugPreferences.edit()
            .putLong("paused_drift_ms", settings.pausedDriftToleranceMs)
            .putLong("playing_drift_ms", settings.playingDriftThresholdMs)
            .putInt("drift_samples", settings.requiredDriftSamples)
            .putLong("correction_cooldown_ms", settings.correctionCooldownMs)
            .putLong("scheduling_lead_ms", settings.schedulingLeadMs)
            .putLong("heartbeat_ms", settings.clockSyncIntervalMs)
            .putLong("playback_sync_ms", settings.playbackSyncIntervalMs)
            .putLong("peer_timeout_ms", settings.peerTimeoutMs)
            .putLong("clock_sample_age_ms", settings.clockSampleMaxAgeMs)
            .putLong("max_clock_rtt_ms", settings.maxClockRttMs)
            .apply()
        mutableDebugSettings.value = settings
        driftCorrection.resetSamples()
        debug("SETTINGS", settings.toString())
    }

    private fun readDebugSettings(): SessionDebugSettings {
        val defaults = SessionDebugSettings()
        val clockSyncInterval = debugPreferences.getLong("heartbeat_ms", defaults.clockSyncIntervalMs)
        return SessionDebugSettings(
            pausedDriftToleranceMs = debugPreferences.getLong("paused_drift_ms", defaults.pausedDriftToleranceMs),
            playingDriftThresholdMs = debugPreferences.getLong("playing_drift_ms", defaults.playingDriftThresholdMs),
            requiredDriftSamples = debugPreferences.getInt("drift_samples", defaults.requiredDriftSamples),
            correctionCooldownMs = debugPreferences.getLong("correction_cooldown_ms", defaults.correctionCooldownMs),
            schedulingLeadMs = debugPreferences.getLong("scheduling_lead_ms", defaults.schedulingLeadMs),
            clockSyncIntervalMs = clockSyncInterval,
            playbackSyncIntervalMs = debugPreferences.getLong("playback_sync_ms", clockSyncInterval),
            peerTimeoutMs = debugPreferences.getLong("peer_timeout_ms", defaults.peerTimeoutMs),
            clockSampleMaxAgeMs = debugPreferences.getLong("clock_sample_age_ms", defaults.clockSampleMaxAgeMs),
            maxClockRttMs = debugPreferences.getLong("max_clock_rtt_ms", defaults.maxClockRttMs),
        ).takeIf { it.validationError() == null } ?: defaults
    }

    private fun debug(category: String, detail: String) {
        if (debugStartedAt == 0L) debugStartedAt = now()
        val event = SessionDebugEvent(++debugEventId, now() - debugStartedAt, category, detail.take(1_024))
        mutableDebugEvents.value = (mutableDebugEvents.value + event).takeLast(200)
    }

    fun attachPlayback(playback: SessionPlayback?) {
        if (playback == null && state.value.role != "none") leave()
        this.playback = playback
    }

    fun pairedDevices(includeAll: Boolean = false): List<Pair<String, String>> = try {
        if (!hasPermission()) emptyList() else adapter()?.bondedDevices.orEmpty()
            .filter { device ->
                includeAll || when (device.bluetoothClass?.majorDeviceClass) {
                    BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.COMPUTER -> true
                    else -> false
                }
            }
            .map { it.address to (it.name ?: "Paired phone") }.sortedBy { it.second }
    } catch (_: SecurityException) {
        emptyList()
    }

    fun host() {
        if (!canStart()) return
        if (playback?.snapshot() == null) {
            fail("Start a tour from Explore before hosting a session.")
            return
        }
        if (playback?.isReady() != true) {
            fail("The tour is still starting. Please try again in a moment.")
            return
        }
        start(SessionService.ACTION_HOST)
    }

    fun join(address: String) {
        if (!canStart()) return
        if (!BluetoothAdapter.checkBluetoothAddress(address) || pairedDevices(includeAll = true).none { it.first == address }) {
            fail("Pair with the host phone in Android Bluetooth settings first.")
            return
        }
        if (playback == null) {
            fail("Still getting ready. Please try again in a moment.")
            return
        }
        start(SessionService.ACTION_JOIN, address)
    }

    private fun canStart(): Boolean {
        if (state.value.role != "none") return false
        if (!hasPermission()) {
            fail("Allow Nearby devices permission to use group listening.")
            return false
        }
        if (adapter() == null) {
            fail("This device does not support Bluetooth.")
            return false
        }
        return try {
            if (adapter()?.isEnabled != true) {
                fail("Turn on Bluetooth in Android settings.")
                false
            } else true
        } catch (_: SecurityException) {
            fail("Allow Nearby devices permission to use group listening.")
            false
        }
    }

    private fun start(action: String, address: String? = null) {
        debugStartedAt = now()
        debugPeerId = 0
        sentFrames = 0
        receivedFrames = 0
        mutableDebugEvents.value = emptyList()
        mutableState.value = SessionState(if (action == SessionService.ACTION_HOST) "host" else "guest", "Starting Bluetooth session…")
        debug("SESSION", "Starting ${state.value.role}; protocol $PROTOCOL_VERSION; RFCOMM $SERVICE_UUID")
        debug("SETTINGS", debugSettings.value.toString())
        try {
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(action).putExtra("address", address))
        } catch (error: RuntimeException) {
            debug("ERROR", "Starting session service failed: ${error.javaClass.simpleName}")
            fail("Could not start Bluetooth. Keep the app open and check Nearby devices permission.")
        }
    }

    internal fun beginHost() {
        resetConnections()
        val snapshot = playback?.snapshot()
        if (snapshot == null || playback?.isReady() != true) {
            fail("Start a tour from Explore, then try hosting again.")
            return
        }
        mutableState.value = SessionState("host", "Waiting for friends to join")
        val currentGeneration = generation
        scope.launch {
            try {
                val listener = adapter()?.listenUsingRfcommWithServiceRecord("OpenSteves", SERVICE_UUID)
                    ?: throw IOException("Bluetooth unavailable")
                server = listener
                debug("SESSION", "Listening for paired guests")
                scope.launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val socket = listener.accept()
                            var accepted = false
                            try {
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == currentGeneration &&
                                        socket.remoteDevice.bondState == BluetoothDevice.BOND_BONDED &&
                                        !peers.containsKey(socket.remoteDevice.address)
                                    ) {
                                        val peer = register(socket)
                                        accepted = true
                                        val greeting = playback?.snapshot() ?: snapshot
                                        send(peer, frame("hello").put("audio", encode(greeting)).put("lead", schedulingLeadMs))
                                        refreshPeers()
                                    } else if (generation == currentGeneration) {
                                        debug("CONNECTION", "Rejected connection: unpaired or duplicate")
                                    }
                                }
                            } finally {
                                if (!accepted) runCatching { socket.close() }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == currentGeneration) {
                                debug("ERROR", "Bluetooth accept failed: ${error.javaClass.simpleName}")
                                fail("Bluetooth stopped. Start a new session to reconnect.")
                            }
                        }
                    }
                }
                startSynchronization()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                debug("ERROR", "Opening Bluetooth listener failed: ${error.javaClass.simpleName}")
                fail("Could not host a session. Check Bluetooth and Nearby devices permission.")
            }
        }
    }

    internal fun beginJoin(address: String) {
        resetConnections()
        mutableState.value = SessionState("guest", "Connecting to host…")
        val currentGeneration = generation
        scope.launch {
            try {
                val device = adapter()?.getRemoteDevice(address) ?: throw IOException("Bluetooth unavailable")
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                connectingSocket = socket
                debug("CONNECTION", "Connecting to paired host")
                val timeout = scope.launch {
                    delay(20_000)
                    if (generation == currentGeneration && connectingSocket != null) fail("The host did not respond. Check that its session is open.")
                }
                withContext(Dispatchers.IO) { socket.connect() }
                timeout.cancel()
                connectingSocket = null
                register(socket)
                mutableState.value = state.value.copy(status = "Checking tour and synchronizing clocks…")
                startSynchronization()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == currentGeneration) {
                    debug("ERROR", "Connecting to host failed: ${error.javaClass.simpleName}")
                    fail("Could not connect. Keep the host nearby with its session open.")
                }
            }
        }
    }

    fun leave() {
        if (state.value.role != "none") debug("SESSION", "Leaving ${state.value.role} session; pausing playback")
        if (state.value.role != "none") playback?.pause()
        resetConnections()
        mutableState.value = SessionState(status = "Session ended")
        context.stopService(Intent(context, SessionService::class.java))
    }

    fun removeGuest(address: String) {
        if (state.value.role != "host") return
        val peer = peers.remove(address) ?: return
        debug("CONNECTION", "Removing peer ${peer.debugId}")
        val notice = frame("removed").toString().toByteArray(Charsets.UTF_8)
        if (peer.outgoing.trySend(notice).isSuccess) {
            peer.outgoing.close()
            scope.launch {
                try { delay(1_000) }
                finally { peer.close() }
            }
        } else peer.close()
        refreshPeers()
    }

    internal fun fail(message: String) {
        debug("ERROR", message)
        leave()
        mutableState.value = SessionState(error = message)
    }

    private fun requireTour(tourId: Int) {
        debug("ERROR", "Tour $tourId is missing or its audio hash differs from the host")
        leave()
        mutableState.value = SessionState(requiredTourId = tourId)
    }

    fun dismissRequiredTour() {
        mutableState.value = state.value.copy(requiredTourId = null)
    }

    internal fun serviceDestroyed() {
        if (state.value.role != "none") {
            debug("SESSION", "Session service stopped; pausing playback")
            playback?.pause()
            resetConnections()
            mutableState.value = SessionState(status = "Session ended")
        }
    }

    fun request(command: PlaybackCommand): Boolean {
        if (state.value.role == "none") return false
        if (!SessionTiming.valid(command)) {
            debug("COMMAND", "Rejected invalid local command: ${command.action}")
            return true
        }
        debug("COMMAND", "Local ${command.action}: position=${command.positionMs} ms, speed=${command.speed}")
        if (state.value.role == "host") {
            scheduleCommand(command)
        } else {
            val peer = peers.values.firstOrNull()
            if (peer?.ready == true) {
                send(peer, frame("command").put("action", command.action).put("position", command.positionMs).put("speed", command.speed))
            } else debug("COMMAND", "Ignored command while host connection is not ready")
        }
        return true
    }

    private fun scheduleCommand(command: PlaybackCommand) {
        if (hostSchedule.size >= 64) {
            debug("COMMAND", "Ignored command: schedule is full")
            return
        }
        val now = now()
        // Earlier commands must execute at their original deadlines for this projection to remain valid.
        val queued = hostSchedule.values.lastOrNull()
        val previous = queued
            ?: playback?.snapshot()?.let { it to now }
            ?: return
        val at = maxOf(now + schedulingLeadMs, queued?.second?.plus(1) ?: now)
        val target = SessionTiming.scheduled(previous.first, previous.second, command, at)
        val seek = command.action != "speed"
        val seq = broadcastState(target, at, force = true, seek = seek)
        hostSchedule[seq] = target to at
        debug("SCHEDULE", "${command.action}: sequence=$seq, lead=${at - now()} ms")
        scope.launch {
            delay((at - now()).coerceAtLeast(0))
            playback?.apply(target.copy(positionMs = SessionTiming.position(target, at, now())), seek = seek)
            debug("APPLY", "Host sequence=$seq, lateness=${(now() - at).coerceAtLeast(0)} ms")
            hostSchedule.remove(seq)
        }
    }

    private fun register(socket: BluetoothSocket): Peer {
        val address = socket.remoteDevice.address
        val peer = Peer(socket, address, socket.remoteDevice.name ?: "Friend", ++debugPeerId)
        peers[address] = peer
        debug("CONNECTION", "Peer ${peer.debugId} connected; checking tour and clock")
        val currentGeneration = generation
        scope.launch(Dispatchers.IO) {
            try {
                val output = DataOutputStream(socket.outputStream)
                for (bytes in peer.outgoing) {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == currentGeneration) {
                            sentFrames++
                            peer.sentFrames++
                            debug("TX", "Peer ${peer.debugId}: ${bytes.toString(Charsets.UTF_8)}")
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) {
                    disconnected(address, peer, currentGeneration, "Write failed: ${error.javaClass.simpleName}")
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val input = DataInputStream(socket.inputStream)
                while (isActive) {
                    val size = input.readInt()
                    require(size in 1..MAX_FRAME_SIZE)
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val message = JSONObject(bytes.toString(Charsets.UTF_8))
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == currentGeneration && peers[address] === peer) {
                            receivedFrames++
                            peer.receivedFrames++
                            debug("RX", "Peer ${peer.debugId}: $message")
                            receive(peer, message)
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) {
                    disconnected(address, peer, currentGeneration, "Read or protocol failure: ${error.javaClass.simpleName}")
                }
            }
        }
        return peer
    }

    private fun disconnected(address: String, peer: Peer, currentGeneration: Long, reason: String) {
        if (generation != currentGeneration || peers[address] !== peer) return
        debug("CONNECTION", "Peer ${peer.debugId} disconnected: $reason")
        peers.remove(address)
        peer.close()
        if (state.value.role == "guest") {
            fail("Connection to the host was lost. Playback paused. Join again when the host is nearby.")
        } else refreshPeers()
    }

    private fun receive(peer: Peer, message: JSONObject) {
        require(message.getInt("version") == PROTOCOL_VERSION)
        peer.lastReceived = now()
        val type = message.getString("type")
        if (type == "ping") {
            send(peer, frame("pong").put("sent", message.getLong("sent")).put("host", now()))
            return
        }
        if (type == "pong") {
            receivePong(peer, message)
            return
        }
        if (state.value.role == "host") {
            when (type) {
                "ready" -> {
                    peer.ready = true
                    peer.acceptsPing = message.optBoolean("acceptsPing")
                    debug("CONNECTION", "Peer ${peer.debugId} ready")
                    val queued = hostSchedule.values.lastOrNull()
                    val at = maxOf(now() + schedulingLeadMs, queued?.second ?: 0)
                    val target = queued?.let { it.first.copy(positionMs = SessionTiming.position(it.first, it.second, at)) }
                        ?: playback?.snapshot()?.let { it.copy(positionMs = SessionTiming.position(it, now(), at)) }
                    if (target != null) sendState(peer, target, at, ++sequence, force = true)
                    refreshPeers()
                }
                "command" -> if (peer.ready) {
                    val command = PlaybackCommand(message.getString("action"), message.optLong("position"), message.optDouble("speed", 1.0).toFloat())
                    require(SessionTiming.valid(command))
                    scheduleCommand(command)
                }
                else -> throw IOException("Unexpected session frame")
            }
        } else {
            when (type) {
                "removed" -> fail("The host removed you from the session. Your playback is paused.")
                "hello" -> {
                    require(hostGreeting == null)
                    readHostSchedulingLead(message)
                    val audio = decode(message.getJSONObject("audio"))
                    if (playback?.hasAudio(audio.tourId, audio.hash) != true) {
                        requireTour(audio.tourId)
                        return
                    }
                    hostGreeting = audio
                    playback?.apply(audio.copy(playing = false))
                    scope.launch {
                        repeat(4) {
                            ping(peer)
                            delay(150)
                        }
                        val deadline = now() + 10_000
                        while (now() < deadline) {
                            if (selectedClockRttMs != Long.MAX_VALUE && playback?.isReady() == true &&
                                playback?.snapshot()?.tourId == audio.tourId
                            ) {
                                peer.ready = true
                                debug("CONNECTION", "Host connection ready; audio verified and clock synchronized")
                                send(peer, frame("ready").put("acceptsPing", true))
                                refreshPeers()
                                return@launch
                            }
                            delay(100)
                        }
                        fail("Could not sync this tour. Start it again, then rejoin the session.")
                    }
                }
                "state" -> if (peer.ready) receiveState(message)
                else -> throw IOException("Unexpected session frame")
            }
        }
    }

    private fun receivePong(peer: Peer, message: JSONObject) {
        val sent = message.getLong("sent")
        if (!peer.pendingPings.remove(sent)) {
            debug("PING", "Peer ${peer.debugId}: ignored expired or duplicate pong")
            return
        }
        val received = now()
        val rtt = received - sent
        val remoteAt = message.getLong("host")
        require(rtt >= 0 && remoteAt in 0..Long.MAX_VALUE / 4)
        peer.rtt.record(rtt)
        peer.lastPongAt = received
        debug("PING", "Peer ${peer.debugId}: RTT=$rtt ms")
        val settings = debugSettings.value
        if (state.value.role == "guest" && rtt <= settings.maxClockRttMs &&
            (rtt < selectedClockRttMs || received - clockSampleAt > settings.clockSampleMaxAgeMs)
        ) {
            selectedClockRttMs = rtt
            clockSampleAt = received
            clockOffset = SessionTiming.clockOffset(sent, received, remoteAt)
            debug("CLOCK", "RTT=$rtt ms, host clock offset=$clockOffset ms")
        }
    }

    private fun readHostSchedulingLead(message: JSONObject) {
        val lead = message.optLong("lead", 600L)
        require(lead in 0..10_000)
        hostSchedulingLeadMs = lead
    }

    private fun receiveState(message: JSONObject) {
        val seq = message.getLong("sequence")
        if (seq <= receivedSequence) {
            debug("SYNC", "Ignored stale sequence=$seq; last=$receivedSequence")
            return
        }
        readHostSchedulingLead(message)
        val target = decode(message.getJSONObject("audio"))
        if (playback?.hasAudio(target.tourId, target.hash) != true) {
            requireTour(target.tourId)
            return
        }
        val hostAt = message.getLong("at")
        require(hostAt in 0..Long.MAX_VALUE / 4)
        val at = hostAt - clockOffset
        require(abs(at - now()) < 60_000)
        receivedSequence = seq
        val force = message.optBoolean("force")
        val requestedSeek = message.optBoolean("seek", true)
        if (force) correction?.cancel()
        if (!force && (guestSchedule.isNotEmpty() || correction?.isActive == true)) {
            debug("SYNC", "Skipped periodic state while an earlier update is pending")
            return
        }
        if (force) {
            require(guestSchedule.size < 64)
            guestSchedule.add(seq)
        }
        val previous = guestApplyTail
        val apply = scope.launch {
            // Clock estimates can move deadlines, but must not reorder playback commands.
            if (force) previous?.join()
            delay((at - now()).coerceAtLeast(0))
            val position = SessionTiming.position(target, at, now())
            val current = playback?.snapshot()
            lastDriftMs = current?.takeIf { it.tourId == target.tourId }?.let { it.positionMs - position }
            val seek = when {
                current == null || current.tourId != target.tourId || current.playing != target.playing -> true
                force -> requestedSeek
                else -> driftCorrection.shouldCorrect(current.positionMs - position, target.playing, now(), debugSettings.value)
            }
            if (force || seek || current?.speed != target.speed) {
                playback?.apply(target.copy(positionMs = position), seek = seek)
                if (seek) {
                    driftCorrection.positionApplied(now())
                    if (!force) automaticSeeks++
                }
                debug("APPLY", "Guest sequence=$seq, forced=$force, seek=$seek, drift=$lastDriftMs ms, lateness=${(now() - at).coerceAtLeast(0)} ms")
            }
            guestSchedule.remove(seq)
        }
        if (force) guestApplyTail = apply else correction = apply
    }

    private fun startSynchronization() {
        scope.launch {
            debugSettings.map { it.clockSyncIntervalMs }.distinctUntilChanged().collectLatest { interval ->
                while (isActive) {
                    delay(interval)
                    peers.toMap().forEach { (address, peer) ->
                        if (now() - peer.lastReceived > debugSettings.value.peerTimeoutMs) {
                            disconnected(address, peer, generation, "No frames received for ${debugSettings.value.peerTimeoutMs} ms")
                        } else if (state.value.role == "host" && peer.ready && peer.acceptsPing) ping(peer)
                    }
                    if (state.value.role == "guest") peers.values.firstOrNull()?.let { ping(it) }
                }
            }
        }
        if (state.value.role != "host") return
        scope.launch {
            debugSettings.map { it.playbackSyncIntervalMs }.distinctUntilChanged().collectLatest { interval ->
                while (isActive) {
                    delay(interval)
                    if (hostSchedule.isEmpty()) {
                        playback?.snapshot()?.let { snapshot ->
                            val now = now()
                            val at = now + schedulingLeadMs
                            broadcastState(snapshot.copy(positionMs = SessionTiming.position(snapshot, now, at)), at, force = false)
                        }
                    }
                }
            }
        }
    }

    private fun ping(peer: Peer) {
        val timestamp = now()
        peer.pendingPings.removeAll { timestamp - it > debugSettings.value.peerTimeoutMs }
        if (peer.pendingPings.add(timestamp)) send(peer, frame("ping").put("sent", timestamp))
    }

    private fun broadcastState(snapshot: PlaybackSnapshot, at: Long, force: Boolean, seek: Boolean = true): Long {
        val seq = ++sequence
        peers.values.filter { it.ready }.forEach { sendState(it, snapshot, at, seq, force, seek) }
        return seq
    }

    private fun sendState(peer: Peer, snapshot: PlaybackSnapshot, at: Long, seq: Long, force: Boolean, seek: Boolean = true) {
        send(peer, frame("state").put("audio", encode(snapshot)).put("at", at).put("sequence", seq)
            .put("force", force).put("seek", seek).put("lead", schedulingLeadMs))
    }

    private fun send(peer: Peer, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_SIZE || !peer.outgoing.trySend(bytes).isSuccess) {
            disconnected(peer.address, peer, generation, "Outgoing frame too large or send queue unavailable")
        }
    }

    private fun refreshPeers() {
        val connected = peers.values.filter { it.ready }.map { SessionPeer(it.address, it.name) }
        mutableState.value = state.value.copy(
            peers = connected,
            status = if (state.value.role == "host") {
                if (connected.isEmpty()) "Waiting for friends to join" else "Hosting ${connected.size} ${if (connected.size == 1) "friend" else "friends"}"
            } else if (connected.isEmpty()) "Synchronizing with host…" else "Connected to ${connected.first().name}",
        )
    }

    private fun resetConnections() {
        generation++
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        runCatching { server?.close() }
        server = null
        runCatching { connectingSocket?.close() }
        connectingSocket = null
        peers.values.forEach { it.close() }
        peers.clear()
        correction = null
        guestApplyTail = null
        hostSchedule.clear()
        guestSchedule.clear()
        receivedSequence = -1
        sequence = 0
        selectedClockRttMs = Long.MAX_VALUE
        clockSampleAt = 0
        clockOffset = 0
        hostGreeting = null
        lastDriftMs = null
        automaticSeeks = 0
        hostSchedulingLeadMs = null
        driftCorrection.reset()
    }

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun now(): Long = SystemClock.elapsedRealtime()
    private fun frame(type: String): JSONObject = JSONObject().put("version", PROTOCOL_VERSION).put("type", type)
    private fun encode(snapshot: PlaybackSnapshot): JSONObject = JSONObject()
        .put("id", snapshot.tourId).put("hash", snapshot.hash).put("position", snapshot.positionMs)
        .put("playing", snapshot.playing).put("speed", snapshot.speed)

    private fun decode(json: JSONObject): PlaybackSnapshot {
        val snapshot = PlaybackSnapshot(json.getInt("id"), json.getString("hash"), json.getLong("position"), json.getBoolean("playing"), json.getDouble("speed").toFloat())
        require(snapshot.tourId > 0 && snapshot.hash.matches(Regex("[a-fA-F0-9]{64}")))
        require(snapshot.positionMs in 0..SessionTiming.MAX_POSITION_MS && snapshot.speed.isFinite() && snapshot.speed in 0.5f..2f)
        return snapshot
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val MAX_FRAME_SIZE = 8_192
        private val SERVICE_UUID: UUID = UUID.fromString("f0a79b2c-8103-49e5-9d93-75007ea59814")
        @Volatile private var instance: SessionManager? = null
        fun get(context: Context): SessionManager = instance ?: synchronized(this) {
            instance ?: SessionManager(context.applicationContext).also { instance = it }
        }
    }
}

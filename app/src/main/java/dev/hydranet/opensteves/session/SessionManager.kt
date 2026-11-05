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
    private var bestRtt = Long.MAX_VALUE
    private var clockSampleAt = 0L
    private var hostGreeting: PlaybackSnapshot? = null
    private val pendingPings = mutableSetOf<Long>()

    private class Peer(val socket: BluetoothSocket, val address: String, val name: String) {
        val outgoing = Channel<ByteArray>(16)
        var ready = false
        var lastReceived = SystemClock.elapsedRealtime()
        fun close() {
            outgoing.close()
            runCatching { socket.close() }
        }
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
        mutableState.value = SessionState(if (action == SessionService.ACTION_HOST) "host" else "guest", "Starting Bluetooth session…")
        try {
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(action).putExtra("address", address))
        } catch (_: RuntimeException) {
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
                                        send(peer, frame("hello").put("audio", encode(greeting)))
                                        refreshPeers()
                                    }
                                }
                            } finally {
                                if (!accepted) runCatching { socket.close() }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == currentGeneration) fail("Bluetooth stopped. Start a new session to reconnect.")
                        }
                    }
                }
                startHeartbeat()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
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
                val timeout = scope.launch {
                    delay(20_000)
                    if (generation == currentGeneration && connectingSocket != null) fail("The host did not respond. Check that its session is open.")
                }
                withContext(Dispatchers.IO) { socket.connect() }
                timeout.cancel()
                connectingSocket = null
                register(socket)
                mutableState.value = state.value.copy(status = "Checking tour and synchronizing clocks…")
                startHeartbeat()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == currentGeneration) fail("Could not connect. Keep the host nearby with its session open.")
            }
        }
    }

    fun leave() {
        if (state.value.role != "none") playback?.pause()
        resetConnections()
        mutableState.value = SessionState(status = "Session ended")
        context.stopService(Intent(context, SessionService::class.java))
    }

    fun removeGuest(address: String) {
        if (state.value.role != "host") return
        val peer = peers.remove(address) ?: return
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
        leave()
        mutableState.value = SessionState(error = message)
    }

    private fun requireTour(tourId: Int) {
        leave()
        mutableState.value = SessionState(requiredTourId = tourId)
    }

    fun dismissRequiredTour() {
        mutableState.value = state.value.copy(requiredTourId = null)
    }

    internal fun serviceDestroyed() {
        if (state.value.role != "none") {
            playback?.pause()
            resetConnections()
            mutableState.value = SessionState(status = "Session ended")
        }
    }

    fun request(command: PlaybackCommand): Boolean {
        if (state.value.role == "none") return false
        if (!SessionTiming.valid(command)) return true
        if (state.value.role == "host") {
            scheduleCommand(command)
        } else {
            val peer = peers.values.firstOrNull()
            if (peer?.ready == true) {
                send(peer, frame("command").put("action", command.action).put("position", command.positionMs).put("speed", command.speed))
            }
        }
        return true
    }

    private fun scheduleCommand(command: PlaybackCommand) {
        if (hostSchedule.size >= 64) return
        val now = now()
        val at = now + SessionTiming.LEAD_MS
        // Earlier commands must execute at their original deadlines for this projection to remain valid.
        val previous = hostSchedule.values.lastOrNull()
            ?: playback?.snapshot()?.let { it to now }
            ?: return
        val target = SessionTiming.scheduled(previous.first, previous.second, command, at)
        val seq = broadcastState(target, at, force = true)
        hostSchedule[seq] = target to at
        scope.launch {
            delay((at - now()).coerceAtLeast(0))
            playback?.apply(target.copy(positionMs = SessionTiming.position(target, at, now())))
            hostSchedule.remove(seq)
        }
    }

    private fun register(socket: BluetoothSocket): Peer {
        val address = socket.remoteDevice.address
        val peer = Peer(socket, address, socket.remoteDevice.name ?: "Friend")
        peers[address] = peer
        val currentGeneration = generation
        scope.launch(Dispatchers.IO) {
            try {
                val output = DataOutputStream(socket.outputStream)
                for (bytes in peer.outgoing) {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) { disconnected(address, peer, currentGeneration) }
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
                        if (generation == currentGeneration && peers[address] === peer) receive(peer, message)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main.immediate) { disconnected(address, peer, currentGeneration) }
            }
        }
        return peer
    }

    private fun disconnected(address: String, peer: Peer, currentGeneration: Long) {
        if (generation != currentGeneration || peers[address] !== peer) return
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
        if (state.value.role == "host") {
            when (type) {
                "ping" -> send(peer, frame("pong").put("sent", message.getLong("sent")).put("host", now()))
                "ready" -> {
                    peer.ready = true
                    val at = now() + SessionTiming.LEAD_MS
                    val target = hostSchedule.values.lastOrNull()?.let { it.first.copy(positionMs = SessionTiming.position(it.first, it.second, at)) }
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
                            if (bestRtt != Long.MAX_VALUE && playback?.isReady() == true &&
                                playback?.snapshot()?.tourId == audio.tourId
                            ) {
                                peer.ready = true
                                send(peer, frame("ready"))
                                refreshPeers()
                                return@launch
                            }
                            delay(100)
                        }
                        fail("Could not sync this tour. Start it again, then rejoin the session.")
                    }
                }
                "pong" -> {
                    val sent = message.getLong("sent")
                    require(pendingPings.remove(sent))
                    val received = now()
                    val rtt = received - sent
                    val hostAt = message.getLong("host")
                    require(hostAt in 0..Long.MAX_VALUE / 4)
                    if (rtt in 0..5_000 && (rtt < bestRtt || received - clockSampleAt > 30_000)) {
                        bestRtt = rtt
                        clockSampleAt = received
                        clockOffset = SessionTiming.clockOffset(sent, received, hostAt)
                    }
                }
                "state" -> if (peer.ready) receiveState(message)
                else -> throw IOException("Unexpected session frame")
            }
        }
    }

    private fun receiveState(message: JSONObject) {
        val seq = message.getLong("sequence")
        if (seq <= receivedSequence) return
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
        correction?.cancel()
        if (!force && guestSchedule.isNotEmpty()) return
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
            if (force || current == null || current.tourId != target.tourId || current.playing != target.playing ||
                current.speed != target.speed || abs(current.positionMs - position) > 90
            ) playback?.apply(target.copy(positionMs = position))
            guestSchedule.remove(seq)
        }
        if (force) guestApplyTail = apply else correction = apply
    }

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                delay(2_000)
                peers.toMap().forEach { (address, peer) ->
                    if (now() - peer.lastReceived > 12_000) disconnected(address, peer, generation)
                }
                if (state.value.role == "host" && hostSchedule.isEmpty()) {
                    playback?.snapshot()?.let { snapshot ->
                        val now = now()
                        val at = now + SessionTiming.LEAD_MS
                        broadcastState(snapshot.copy(positionMs = SessionTiming.position(snapshot, now, at)), at, force = false)
                    }
                } else if (state.value.role == "guest") {
                    peers.values.firstOrNull()?.let { ping(it) }
                }
            }
        }
    }

    private fun ping(peer: Peer) {
        val timestamp = now()
        pendingPings.removeAll { timestamp - it > 12_000 }
        if (pendingPings.add(timestamp)) send(peer, frame("ping").put("sent", timestamp))
    }

    private fun broadcastState(snapshot: PlaybackSnapshot, at: Long, force: Boolean): Long {
        val seq = ++sequence
        peers.values.filter { it.ready }.forEach { sendState(it, snapshot, at, seq, force) }
        return seq
    }

    private fun sendState(peer: Peer, snapshot: PlaybackSnapshot, at: Long, seq: Long, force: Boolean) {
        send(peer, frame("state").put("audio", encode(snapshot)).put("at", at).put("sequence", seq).put("force", force))
    }

    private fun send(peer: Peer, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_SIZE || !peer.outgoing.trySend(bytes).isSuccess) {
            disconnected(peer.address, peer, generation)
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
        bestRtt = Long.MAX_VALUE
        clockSampleAt = 0
        clockOffset = 0
        hostGreeting = null
        pendingPings.clear()
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

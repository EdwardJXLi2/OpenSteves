package dev.hydranet.opensteves.session

data class SessionState(
    val role: String = "none",
    val status: String = "Listen together over Bluetooth",
    val peers: List<SessionPeer> = emptyList(),
    val error: String? = null,
    val requiredTourId: Int? = null,
    val requiredAudioTitle: String? = null,
    val hostDownloadAvailable: Boolean = false,
    val downloadingFromHost: Boolean = false,
    val hostDownloadProgress: Int? = null,
    val hostDownloadError: String? = null,
)

data class SessionPeer(val address: String, val name: String)

data class PlaybackCommand(val action: String, val positionMs: Long = 0, val speed: Float = 1f, val interruption: Boolean = false)

data class PlaybackSnapshot(
    val tourId: Int,
    val hash: String,
    val positionMs: Long,
    val playing: Boolean,
    val speed: Float,
)

internal data class ScheduledPlayback(val snapshot: PlaybackSnapshot, val atMs: Long) {
    fun project(nowMs: Long): PlaybackSnapshot = snapshot.copy(positionMs = SessionTiming.position(snapshot, atMs, nowMs))
}

interface SessionPlayback {
    fun snapshot(): PlaybackSnapshot?
    fun diagnostics(): PlaybackDiagnostics
    fun isReady(): Boolean
    fun localAudioId(tourId: Int, hash: String): Int?
    fun apply(snapshot: PlaybackSnapshot, seek: Boolean = true)
    fun pause()
}

data class PlaybackDiagnostics(val state: String, val actuallyPlaying: Boolean)

internal object SessionTiming {
    const val MAX_POSITION_MS = 86_400_000L

    fun position(snapshot: PlaybackSnapshot, at: Long, now: Long): Long =
        (snapshot.positionMs + if (snapshot.playing) ((now - at).coerceAtLeast(0) * snapshot.speed).toLong() else 0)
            .coerceIn(0, MAX_POSITION_MS)

    fun clockOffset(sentAt: Long, receivedAt: Long, hostAt: Long): Long =
        hostAt - (sentAt + (receivedAt - sentAt) / 2)

    fun scheduled(snapshot: PlaybackSnapshot, snapshotAt: Long, command: PlaybackCommand, executeAt: Long): PlaybackSnapshot {
        require(valid(command))
        val current = snapshot.copy(positionMs = position(snapshot, snapshotAt, executeAt))
        return when (command.action) {
            "play" -> current.copy(playing = true)
            "pause" -> current.copy(playing = false)
            "stop" -> current.copy(playing = false, positionMs = 0)
            "seek" -> current.copy(positionMs = command.positionMs)
            "speed" -> current.copy(speed = command.speed)
            else -> error("Unsupported command")
        }
    }

    fun valid(command: PlaybackCommand): Boolean {
        if (command.interruption && command.action != "pause") return false
        return when (command.action) {
            "play", "pause", "stop" -> true
            "seek" -> command.positionMs in 0..MAX_POSITION_MS
            "speed" -> command.speed.isFinite() && command.speed in 0.5f..2f
            else -> false
        }
    }
}

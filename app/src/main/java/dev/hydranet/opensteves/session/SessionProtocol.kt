package dev.hydranet.opensteves.session

data class SessionState(
    val role: String = "none",
    val status: String = "Listen together over Bluetooth",
    val peers: List<SessionPeer> = emptyList(),
    val error: String? = null,
    val requiredTourId: Int? = null,
)

data class SessionPeer(val address: String, val name: String)

data class PlaybackCommand(val action: String, val positionMs: Long = 0, val speed: Float = 1f)

data class PlaybackSnapshot(
    val tourId: Int,
    val hash: String,
    val positionMs: Long,
    val playing: Boolean,
    val speed: Float,
)

interface SessionPlayback {
    fun snapshot(): PlaybackSnapshot?
    fun isReady(): Boolean
    fun hasAudio(tourId: Int, hash: String): Boolean
    fun apply(snapshot: PlaybackSnapshot, seek: Boolean = true)
    fun pause()
}

internal object SessionTiming {
    const val LEAD_MS = 600L
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

    fun valid(command: PlaybackCommand): Boolean = when (command.action) {
        "play", "pause", "stop" -> true
        "seek" -> command.positionMs in 0..MAX_POSITION_MS
        "speed" -> command.speed.isFinite() && command.speed in 0.5f..2f
        else -> false
    }
}

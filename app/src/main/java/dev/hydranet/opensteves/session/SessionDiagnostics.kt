package dev.hydranet.opensteves.session

internal data class SessionDebugEvent(
    val id: Long,
    val elapsedMs: Long,
    val category: String,
    val detail: String,
)

internal data class SessionDebugPeer(
    val id: Int,
    val ready: Boolean,
    val lastReceivedMs: Long,
)

internal data class SessionDiagnostics(
    val elapsedMs: Long,
    val listening: Boolean,
    val connecting: Boolean,
    val peers: List<SessionDebugPeer>,
    val sentFrames: Long,
    val receivedFrames: Long,
    val rttMs: Long?,
    val clockOffsetMs: Long?,
    val clockSampleAgeMs: Long?,
    val driftMs: Long?,
    val pendingCommands: Int,
    val sequence: Long,
    val playback: PlaybackSnapshot?,
    val playbackReady: Boolean,
)

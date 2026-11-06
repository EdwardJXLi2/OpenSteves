package dev.hydranet.opensteves.session

internal class SessionPlaybackDebouncer {
    private data class AcceptedCommand(val action: String, val source: String?, val atMs: Long)
    private var lastAccepted: AcceptedCommand? = null

    fun shouldAccept(command: PlaybackCommand, playing: Boolean, source: String?, nowMs: Long, debounceMs: Long): Boolean {
        if (!isPlayPause(command) || command.interruption) return true
        if (playing == (command.action == "play")) return false
        if (debounceMs == 0L) return true
        val previous = lastAccepted ?: return true
        return command.action != previous.action || source == previous.source || nowMs - previous.atMs > debounceMs
    }

    fun scheduled(command: PlaybackCommand, source: String?, nowMs: Long) {
        if (!isPlayPause(command)) return
        lastAccepted = AcceptedCommand(command.action, source, nowMs)
    }

    fun reset() {
        lastAccepted = null
    }

    private fun isPlayPause(command: PlaybackCommand): Boolean = command.action == "play" || command.action == "pause"
}

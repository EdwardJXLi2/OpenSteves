package dev.hydranet.opensteves.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.session.PlaybackCommand
import dev.hydranet.opensteves.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val tourId: Int? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
    val ready: Boolean = false,
)

class PlaybackConnection(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutableState
    private var controller: MediaController? = null
    private var commandError: String? = null
    private val groups = SessionManager.get(context)
    private val future = MediaController.Builder(context.applicationContext,
        SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()

    init {
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it
                it.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = update()
                })
                update()
            }.onFailure { mutableState.value = mutableState.value.copy(error = "Could not start playback. Please try again.") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch {
            combine(groups.state, groups.pendingPlayback) { _, _ -> Unit }.collect { update() }
        }
        scope.launch { while (true) { update(); delay(200) } }
    }

    private fun update() {
        val player = controller ?: return
        val shared = groups.playbackForUi()
        mutableState.value = PlaybackUiState(
            tourId = shared?.tourId ?: player.currentMediaItem?.mediaId?.toIntOrNull(),
            playing = shared?.playing ?: (player.playWhenReady && player.playbackState != Player.STATE_ENDED),
            positionMs = shared?.positionMs ?: player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            speed = shared?.speed ?: player.playbackParameters.speed,
            ready = player.playbackState == Player.STATE_READY,
            error = player.playerError?.let { "Audio playback failed: ${it.errorCodeName}" } ?: commandError,
        )
    }

    fun play(tour: Tour, positionMs: Long? = null) {
        commandError = null
        mutableState.value = mutableState.value.copy(error = null)
        val player = controller ?: run {
            mutableState.value = mutableState.value.copy(error = "Still getting ready. Please try again in a moment.")
            return
        }
        if (groups.state.value.role != "none" && groups.playbackForUi()?.tourId == tour.id) {
            positionMs?.let { groups.request(PlaybackCommand("seek", it.coerceAtLeast(0))) }
            groups.request(PlaybackCommand("play"))
            update()
            return
        }
        val result = player.sendCustomCommand(SessionCommand(PlaybackService.OPEN_TOUR, Bundle.EMPTY),
            Bundle().apply { putInt("tourId", tour.id); positionMs?.let { putLong("positionMs", it) } })
        result.addListener({
            runCatching { result.get() }.onSuccess {
                if (it.resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                    commandError = "Download this tour first, or leave the session to start a different tour."
                    update()
                }
            }
        }, { task -> scope.launch { task.run() }; Unit })
    }

    private fun command(command: PlaybackCommand, action: (MediaController) -> Unit) {
        val player = controller ?: return
        if (!groups.request(command)) action(player)
        update()
    }

    fun setPlaying(playing: Boolean) = command(PlaybackCommand(if (playing) "play" else "pause")) {
        if (playing) it.play() else it.pause()
    }
    fun seek(positionMs: Long) = command(PlaybackCommand("seek", positionMs.coerceAtLeast(0))) {
        it.seekTo(positionMs.coerceAtLeast(0))
    }
    fun setSpeed(speed: Float) = command(PlaybackCommand("speed", speed = speed.coerceIn(0.5f, 2f))) {
        it.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
    }
    fun stop() = command(PlaybackCommand("stop")) { it.stop() }
    fun close() { scope.cancel(); MediaController.releaseFuture(future); controller = null }
}

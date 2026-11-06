package dev.hydranet.opensteves.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.hydranet.opensteves.MainActivity
import dev.hydranet.opensteves.data.TourRepository
import dev.hydranet.opensteves.session.PlaybackCommand
import dev.hydranet.opensteves.session.PlaybackDiagnostics
import dev.hydranet.opensteves.session.PlaybackSnapshot
import dev.hydranet.opensteves.session.SessionManager
import dev.hydranet.opensteves.session.SessionPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService(), SessionPlayback {
    private lateinit var player: ExoPlayer
    private lateinit var repository: TourRepository
    private lateinit var groups: SessionManager
    private var session: MediaSession? = null
    private var interrupted = false
    private var interruptionDeadline = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progress by lazy { getSharedPreferences("playback", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        repository = TourRepository.get(this)
        groups = SessionManager.get(this)
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(20_000).setSeekForwardIncrementMs(20_000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) interruptGroup()
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) interruptGroup()
            }
        })
        session = MediaSession.Builder(this, SharedPlayer(player))
            .setSessionActivity(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYING, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted) {
                        return MediaSession.ConnectionResult.reject()
                    }
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if (controller.packageName == packageName) commands.add(SessionCommand(OPEN_TOUR, Bundle.EMPTY))
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(commands.build()).setAvailablePlayerCommands(playerCommands).build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction != OPEN_TOUR || controller.packageName != packageName) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    val id = args.getInt("tourId", -1)
                    if (groups.state.value.role != "none") {
                        if (player.currentMediaItem?.mediaId == id.toString()) groups.request(PlaybackCommand("play"))
                        else return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    } else if (!load(id, if (args.containsKey("positionMs")) args.getLong("positionMs").coerceAtLeast(0)
                        else if (player.currentMediaItem?.mediaId == id.toString() && player.playbackState != Player.STATE_ENDED)
                            player.currentPosition else progress.getLong("position_$id", 0))) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    } else player.play()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        groups.attachPlayback(this)
        scope.launch { while (true) { delay(3_000); saveProgress() } }
    }

    private fun load(id: Int, position: Long): Boolean {
        val installed = repository.installed.value[id] ?: return false
        val audio = repository.audioFile(id) ?: return false
        if (player.currentMediaItem?.mediaId != id.toString()) {
            saveProgress()
            player.setMediaItem(MediaItem.Builder().setMediaId(id.toString()).setUri(android.net.Uri.fromFile(audio))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(installed.tour.title)
                    .setArtist("Rick Steves Audio Europe").setAlbumTitle(installed.tour.region).build()).build())
        }
        player.seekTo(position.coerceAtLeast(0))
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) player.prepare()
        return true
    }

    override fun snapshot(): PlaybackSnapshot? {
        val id = player.currentMediaItem?.mediaId?.toIntOrNull() ?: return null
        val installed = repository.installed.value[id] ?: return null
        return PlaybackSnapshot(id, installed.audioHash, player.currentPosition.coerceAtLeast(0),
            player.playWhenReady && player.playbackState != Player.STATE_ENDED, player.playbackParameters.speed)
    }

    override fun hasAudio(tourId: Int, hash: String): Boolean =
        repository.installed.value[tourId]?.audioHash == hash && repository.audioFile(tourId) != null

    override fun apply(snapshot: PlaybackSnapshot, seek: Boolean) {
        if (!hasAudio(snapshot.tourId, snapshot.hash)) return
        if (!snapshot.playing) {
            if (SystemClock.elapsedRealtime() >= interruptionDeadline) interrupted = false
            if (player.playWhenReady) player.pause()
        }
        if ((seek || player.currentMediaItem?.mediaId != snapshot.tourId.toString()) &&
            !load(snapshot.tourId, snapshot.positionMs)
        ) return
        if (player.playbackParameters.speed != snapshot.speed) player.setPlaybackSpeed(snapshot.speed)
        val playWhenReady = snapshot.playing && !interrupted
        if (player.playWhenReady != playWhenReady) {
            player.playWhenReady = playWhenReady
        }
    }

    override fun isReady(): Boolean = player.playbackState == Player.STATE_READY

    override fun diagnostics(): PlaybackDiagnostics = PlaybackDiagnostics(
        state = when (player.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "unknown"
        },
        actuallyPlaying = player.isPlaying,
    )

    private fun interruptGroup() {
        if (groups.state.value.role != "none") {
            interrupted = true
            interruptionDeadline = SystemClock.elapsedRealtime() + groups.schedulingLeadMs
            player.pause()
            groups.request(PlaybackCommand("pause", interruption = true))
        }
    }

    override fun pause() { player.pause(); saveProgress() }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (groups.state.value.role == "none") super.onTaskRemoved(rootIntent)
    }

    private fun saveProgress() {
        val id = player.currentMediaItem?.mediaId ?: return
        val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)
        progress.edit().putLong("position_$id", position).apply()
    }

    override fun onDestroy() {
        saveProgress()
        groups.attachPlayback(null)
        scope.cancel()
        session?.release()
        player.release()
        super.onDestroy()
    }

    private inner class SharedPlayer(private val raw: Player) : ForwardingPlayer(raw) {
        private fun route(command: PlaybackCommand, action: () -> Unit) {
            if (!groups.request(command)) action()
        }
        override fun play() = route(PlaybackCommand("play")) { if (raw.playbackState == Player.STATE_IDLE) raw.prepare(); raw.play() }
        override fun pause() = route(PlaybackCommand("pause")) { raw.pause() }
        override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
        override fun seekTo(positionMs: Long) = route(PlaybackCommand("seek", positionMs)) { raw.seekTo(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekTo(positionMs)
        override fun seekToDefaultPosition() = seekTo(0)
        override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(0)
        override fun seekBack() = seekTo((raw.currentPosition - 20_000).coerceAtLeast(0))
        override fun seekForward() = seekTo((raw.currentPosition + 20_000).coerceAtMost(raw.duration.coerceAtLeast(0)))
        override fun setPlaybackSpeed(speed: Float) = route(PlaybackCommand("speed", speed = speed.coerceIn(0.5f, 2f))) { raw.setPlaybackSpeed(speed) }
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = setPlaybackSpeed(playbackParameters.speed)
        override fun stop() = route(PlaybackCommand("stop")) {
            raw.pause()
            raw.seekTo(0)
            saveProgress()
            raw.stop()
            raw.clearMediaItems()
        }
    }

    companion object { const val OPEN_TOUR = "dev.hydranet.opensteves.OPEN_TOUR" }
}

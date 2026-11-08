package dev.hydranet.opensteves.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.hydranet.opensteves.MainActivity

class SessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var playerConnection: ListenableFuture<MediaController>? = null
    private var destroying = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Group listening", NotificationManager.IMPORTANCE_LOW),
        )
        val connection = MediaController.Builder(
            this, SessionToken(this, ComponentName(packageName, "$packageName.playback.PlaybackService")),
        ).buildAsync()
        playerConnection = connection
        connection.addListener({
            if (!destroying) {
                runCatching { connection.get() }.onFailure {
                    SessionManager.get(this).fail("The tour could not start. Please start it again and retry the session.")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = SessionManager.get(this)
        if (intent?.action == ACTION_LEAVE) {
            manager.leave()
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_LEAVE), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenSteves group session")
            .setContentText("Shared playback over Bluetooth")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Leave session", stop).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7401, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(7401, notification)
            }
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenSteves:group")
                    .apply { acquire(6 * 60 * 60 * 1000L) }
            }
            when (intent?.action) {
                ACTION_HOST -> manager.beginHost()
                ACTION_JOIN -> manager.beginJoin(intent.getStringExtra("address").orEmpty(), intent.getBooleanExtra("downloadFromHost", false))
                else -> manager.leave()
            }
        } catch (_: SecurityException) {
            manager.fail("Allow Nearby devices permission to start a group session.")
        } catch (_: IllegalStateException) {
            manager.fail("Open the app to start a group session.")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroying = true
        SessionManager.get(this).serviceDestroyed()
        playerConnection?.let { MediaController.releaseFuture(it) }
        playerConnection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_HOST = "dev.hydranet.opensteves.HOST"
        const val ACTION_JOIN = "dev.hydranet.opensteves.JOIN"
        const val ACTION_LEAVE = "dev.hydranet.opensteves.LEAVE"
        private const val CHANNEL = "group_session"
    }
}

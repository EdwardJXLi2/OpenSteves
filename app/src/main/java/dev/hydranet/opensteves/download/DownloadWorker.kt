package dev.hydranet.opensteves.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.hydranet.opensteves.data.Tour
import dev.hydranet.opensteves.data.TourRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val tour = Tour.fromJson(JSONObject(inputData.getString("tour") ?: return Result.failure()))
            setForeground(notification(tour, 0))
            TourRepository.get(applicationContext).installDownload(tour) { progress ->
                setProgress(workDataOf("progress" to progress))
                if (progress % 5 == 0) setForeground(notification(tour, progress))
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message ?: "Download failed. Try again.").take(300)))
        }
    }

    private fun notification(tour: Tour, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tour downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tour.title)
            .setContentText(if (progress < 95) "Downloading tour" else "Preparing offline tour")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(10_000 + tour.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(10_000 + tour.id, notification)
    }

    companion object { private const val CHANNEL = "tour-downloads" }
}

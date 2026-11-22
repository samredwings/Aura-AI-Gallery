package ai.sakana.aura.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import ai.sakana.aura.gallery.domain.repository.MediaRecognitionRepository
import ai.sakana.aura.gallery.domain.repository.MediaRepository
import ai.sakana.aura.gallery.domain.repository.PersonClusterRepository
import ai.sakana.aura.gallery.domain.repository.RecognitionProgressState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber

@HiltWorker
class RecognitionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mediaRepository: MediaRepository,
    private val recognitionRepository: MediaRecognitionRepository,
    private val personClusterRepository: PersonClusterRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val savedProgress = personClusterRepository.getRecognitionProgress()
            if (savedProgress?.isPaused == true) return Result.success()

            // Revisit the full current library on every run. The repository's source-fingerprint
            // cache skips unchanged media, while newly imported older-dated items are still found.
            val items = mediaRepository.observeMedia().first()
            if (items.isEmpty()) {
                personClusterRepository.saveRecognitionProgress(
                    RecognitionProgressState(0, 0, 0, System.currentTimeMillis(), false),
                )
                return Result.success(resultData(0, 0, 0, 0))
            }

            setForeground(createForegroundInfo(0, items.size))
            val summary = recognitionRepository.analyze(items) { completed, total ->
                personClusterRepository.saveRecognitionProgress(
                    RecognitionProgressState(
                        currentItemIndex = completed,
                        totalItems = total,
                        processedCount = completed,
                        lastRunEpochMillis = System.currentTimeMillis(),
                        isPaused = false,
                    ),
                )
                setProgress(
                    Data.Builder()
                        .putInt(KEY_COMPLETED, completed)
                        .putInt(KEY_TOTAL, total)
                        .putInt(KEY_PROCESSED, completed)
                        .build(),
                )
                setForeground(createForegroundInfo(completed, total))
            }

            personClusterRepository.saveRecognitionProgress(
                RecognitionProgressState(
                    currentItemIndex = items.size,
                    totalItems = items.size,
                    processedCount = summary.processed + summary.alreadyAnalyzed,
                    lastRunEpochMillis = System.currentTimeMillis(),
                    isPaused = false,
                ),
            )

            val output = resultData(
                summary.processed,
                summary.alreadyAnalyzed,
                summary.failed,
                items.size,
            )
            when {
                summary.failed == 0 -> Result.success(output)
                runAttemptCount < MAX_RETRIES -> Result.retry()
                else -> Result.failure(output)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "Background recognition failed")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private fun createForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "On-device AI Recognition",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Analyzing photos and videos")
            .setContentText("$current of $total items checked")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), current.coerceIn(0, total.coerceAtLeast(1)), false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "aura_on_device_recognition"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_CACHED = "cached"
        const val KEY_FAILED = "failed"
        private const val CHANNEL_ID = "aura_recognition_progress"
        private const val NOTIFICATION_ID = 2002
        private const val MAX_RETRIES = 2
        const val INCREMENTAL_TRIGGER_MIN_NEW_ITEMS = 10

        private fun resultData(
            processed: Int,
            cached: Int,
            failed: Int,
            total: Int,
        ): Data = Data.Builder()
            .putInt(KEY_COMPLETED, total)
            .putInt(KEY_TOTAL, total)
            .putInt(KEY_PROCESSED, processed)
            .putInt(KEY_CACHED, cached)
            .putInt(KEY_FAILED, failed)
            .build()

        /** Automatic import scans wait for charging, but do not require network access or expedited work. */
        fun enqueueIfNeeded(context: Context, newItemsCount: Int) {
            if (newItemsCount <= INCREMENTAL_TRIGGER_MIN_NEW_ITEMS) return
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .build()
            val request = OneTimeWorkRequestBuilder<RecognitionWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** Explicit user scans are immediate and do not inherit the automatic charging constraint. */
        fun enqueueManual(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecognitionWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

package ai.sakana.aura.gallery.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.sakana.aura.gallery.data.deduplication.ExactDuplicateDetector
import ai.sakana.aura.gallery.data.deduplication.HashStore
import ai.sakana.aura.gallery.data.deduplication.SimilarPhotoDetector
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import timber.log.Timber

@HiltWorker
class DedupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val store: HashStore,
    private val exactDetector: ExactDuplicateDetector,
    private val similarDetector: SimilarPhotoDetector,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        val items = store.availableItems()
        val exact = exactDetector.detect(
            items = items,
            knownHashes = store.knownSha256(),
            onHashComputed = store::saveSha256,
        )
        val similar = similarDetector.detect(
            items = items,
            knownHashes = store.knownPerceptualHashes(),
            onHashComputed = store::savePerceptualHash,
        )
        Result.success(
            Data.Builder()
                .putInt(KEY_EXACT_GROUPS, exact.groups.size)
                .putLong(KEY_EXACT_WASTED_BYTES, exact.totalWastedBytes)
                .putInt(KEY_SIMILAR_GROUPS, similar.groups.size)
                .putInt(KEY_HASHED_EXACT, exact.hashedCount)
                .putInt(KEY_HASHED_PERCEPTUAL, similar.hashedCount)
                .putInt(KEY_FAILED, exact.unreadableUris.size + similar.failedUris.size)
                .build(),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.e(error, "Background duplicate analysis failed")
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        const val WORK_NAME = "aura_background_deduplication"
        const val KEY_EXACT_GROUPS = "exactGroups"
        const val KEY_EXACT_WASTED_BYTES = "exactWastedBytes"
        const val KEY_SIMILAR_GROUPS = "similarGroups"
        const val KEY_HASHED_EXACT = "hashedExact"
        const val KEY_HASHED_PERCEPTUAL = "hashedPerceptual"
        const val KEY_FAILED = "failed"
        private const val MAX_RETRIES = 2

        /** Hashing is local work. It waits for charging, not for a network connection. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<DedupWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DedupWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

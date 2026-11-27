package ai.sakana.aura.gallery.data.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class AgentModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val spec = AgentModelCatalog.find(inputData.getString(KEY_MODEL_ID).orEmpty())
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Unknown model"))
        val destination = File(applicationContext.filesDir, "ai-models/${spec.fileName}")
        val temporary = File(destination.parentFile, "${spec.fileName}.download")
        runCatching {
            destination.parentFile?.mkdirs()
            temporary.delete()
            setForeground(createForegroundInfo(spec, 0))
            val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 90_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Aura-AI-Gallery/1.0")
            }
            try {
                val status = connection.responseCode
                check(status in 200..299) { "Model server returned HTTP $status" }
                val totalBytes = connection.contentLengthLong
                check(totalBytes >= MIN_MODEL_BYTES) { "The model download is unexpectedly small ($totalBytes bytes)." }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastProgress = -1
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered(256 * 1024).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (isStopped) throw InterruptedException("Model download was cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            val progress = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                setProgress(workDataOf(KEY_PROGRESS to progress, KEY_DOWNLOADED to downloaded, KEY_TOTAL to totalBytes))
                                setForeground(createForegroundInfo(spec, progress))
                            }
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
                check(actualHash.equals(spec.sha256, ignoreCase = true)) {
                    "Model checksum mismatch. Remove the download and try again."
                }
                check(temporary.length() == totalBytes) { "Model download was incomplete." }
                check(temporary.renameTo(destination)) { "Unable to install the downloaded model." }
                setProgress(workDataOf(KEY_PROGRESS to 100, KEY_DOWNLOADED to downloaded, KEY_TOTAL to totalBytes))
                Result.success(workDataOf(KEY_MODEL_PATH to destination.absolutePath))
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            temporary.delete()
            if (error is InterruptedException) Result.failure(workDataOf(KEY_ERROR to "Download cancelled"))
            else Result.failure(workDataOf(KEY_ERROR to (error.message ?: "Model download failed")))
        }
    }

    private fun createForegroundInfo(spec: AgentModelSpec, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aura AI model downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${spec.displayName}")
            .setContentText(if (progress > 0) "$progress% · Saved on this device" else "Saved on this device")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID + if (spec.id == AgentModelCatalog.INPAINTING.id) 1 else 0, notification)
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_MODEL_PATH = "model_path"
        private const val MIN_MODEL_BYTES = 80_000_000L
        private const val CHANNEL_ID = "aura_ai_model_downloads"
        private const val NOTIFICATION_ID = 74120
    }
}

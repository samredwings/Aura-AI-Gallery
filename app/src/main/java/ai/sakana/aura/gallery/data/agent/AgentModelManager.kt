package ai.sakana.aura.gallery.data.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentModelManager @Inject constructor(@ApplicationContext context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun modelFile(modelId: String): File? {
        val spec = AgentModelCatalog.find(modelId) ?: return null
        return File(appContext.filesDir, "ai-models/${spec.fileName}").takeIf { it.isFile && it.length() >= 80_000_000L }
    }

    fun startDownload(modelId: String) {
        requireNotNull(AgentModelCatalog.find(modelId)) { "Unknown Aura model: $modelId" }
        val request = OneTimeWorkRequestBuilder<AgentModelDownloadWorker>()
            .setInputData(workDataOf(AgentModelDownloadWorker.KEY_MODEL_ID to modelId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(workName(modelId), ExistingWorkPolicy.KEEP, request)
    }

    fun observeDownload(modelId: String) = workManager.getWorkInfosForUniqueWorkLiveData(workName(modelId))

    fun cancelDownload(modelId: String) = workManager.cancelUniqueWork(workName(modelId))

    private fun workName(modelId: String) = "aura-local-model-$modelId"
}

package ai.sakana.aura.gallery.presentation

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.sakana.aura.gallery.data.agent.AgentModelCatalog
import ai.sakana.aura.gallery.data.agent.AgentModelManager
import ai.sakana.aura.gallery.data.agent.AiEraseProcessor
import ai.sakana.aura.gallery.data.local.MediaVersionDao
import ai.sakana.aura.gallery.data.local.MediaVersionEntity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NormalizedTextRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class AiEraseUiState(
    val isProcessing: Boolean = false,
    val error: String? = null,
    val savedVersionUri: String? = null,
)

@HiltViewModel
class AiEraseViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AgentModelManager,
    private val mediaVersionDao: MediaVersionDao,
) : ViewModel() {
    private val _state = MutableStateFlow(AiEraseUiState())
    internal val state: StateFlow<AiEraseUiState> = _state.asStateFlow()

    internal fun detectText(bitmap: Bitmap, onDetected: (List<NormalizedTextRect>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    suspendCancellableCoroutine { continuation ->
                        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener { visionText ->
                                val rects = visionText.textBlocks.mapNotNull { block ->
                                    block.boundingBox?.let { bounds ->
                                        NormalizedTextRect(
                                            left = (bounds.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                                            top = (bounds.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                                            right = (bounds.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                                            bottom = (bounds.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                                if (continuation.isActive) continuation.resume(rects)
                            }
                            .addOnFailureListener { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        continuation.invokeOnCancellation { recognizer.close() }
                    }
                } finally {
                    recognizer.close()
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess(onDetected).onFailure { error ->
                    Timber.w(error, "On-device text detection failed")
                    _state.update { it.copy(error = error.message ?: "Aura couldn't detect text in this photo.") }
                }
            }
        }
    }

    fun applyErase(sourceUri: String, original: Bitmap, mask: Bitmap, prompt: String) {
        if (_state.value.isProcessing) return
        _state.value = AiEraseUiState(isProcessing = true)
        viewModelScope.launch(Dispatchers.IO) {
            val sourceCopy = original.copy(Bitmap.Config.ARGB_8888, false)
            val maskCopy = mask.copy(Bitmap.Config.ARGB_8888, false)
            var output: Bitmap? = null
            try {
                val model = modelManager.modelFile(AgentModelCatalog.INPAINTING.id)
                    ?: error("Download the LaMa inpainting model from Aura Agent first.")
                output = AiEraseProcessor.erase(sourceCopy, maskCopy, model)
                val savedUri = saveNewCopy(output)
                runCatching {
                    mediaVersionDao.insertVersion(
                        MediaVersionEntity(
                            sourceContentUri = sourceUri,
                            versionContentUri = savedUri.toString(),
                            operation = if (prompt.contains("text", true) || prompt.contains("watermark", true)) "TEXT_ERASE" else "AI_ERASE",
                            prompt = prompt.take(2_000),
                            modelId = AgentModelCatalog.INPAINTING.id,
                            createdAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                }.onFailure { error -> Timber.e(error, "AI erase copy saved but version metadata failed") }
                withContext(Dispatchers.Main) { _state.value = AiEraseUiState(savedVersionUri = savedUri.toString()) }
            } catch (error: Exception) {
                Timber.e(error, "Local AI erase failed")
                withContext(Dispatchers.Main) {
                    _state.value = AiEraseUiState(error = error.message ?: "Aura couldn't remove the selected area.")
                }
            } finally {
                sourceCopy.recycle()
                maskCopy.recycle()
                output?.recycle()
            }
        }
    }

    fun clearResult() { _state.update { it.copy(error = null, savedVersionUri = null) } }

    private fun saveNewCopy(bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Aura_AI_Erase_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Aura AI Edits")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android couldn't create an edited copy in the gallery.")
        try {
            resolver.openOutputStream(destination, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "Aura couldn't encode the edited image." }
            } ?: error("Android couldn't open the edited image destination.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(destination, ready, null, null)
            }
            return destination
        } catch (error: Exception) {
            runCatching { resolver.delete(destination, null, null) }
            throw error
        }
    }
}

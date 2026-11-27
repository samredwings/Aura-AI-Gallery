package ai.sakana.aura.gallery.presentation

import android.content.Context
import android.net.Uri
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.viewModelScope
import ai.sakana.aura.gallery.domain.model.MediaItem
import ai.sakana.aura.gallery.domain.agent.ShotCandidate
import ai.sakana.aura.gallery.domain.agent.ShotSequence
import ai.sakana.aura.gallery.domain.agent.ShotSequenceGrouper
import ai.sakana.aura.gallery.data.local.EditHistoryEntity
import ai.sakana.aura.gallery.data.local.MediaItemDao
import ai.sakana.aura.gallery.data.local.MediaVersionDao
import ai.sakana.aura.gallery.data.local.MediaVersionEntity
import ai.sakana.aura.gallery.data.export.ExportOptions
import ai.sakana.aura.gallery.data.export.ExportSettingsStore
import ai.sakana.aura.gallery.data.export.MediaEditorExporter
import ai.sakana.aura.gallery.domain.editing.EditParams
import ai.sakana.aura.gallery.domain.repository.EditHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import ai.sakana.aura.gallery.domain.usecase.ImportPickedMediaUseCase
import ai.sakana.aura.gallery.domain.usecase.ObserveMediaUseCase
import ai.sakana.aura.gallery.domain.usecase.RefreshMediaStoreUseCase
import ai.sakana.aura.gallery.domain.usecase.ToggleFavoriteUseCase
import ai.sakana.aura.gallery.domain.usecase.ObserveTrashedMediaUseCase
import ai.sakana.aura.gallery.domain.usecase.MoveToTrashUseCase
import ai.sakana.aura.gallery.domain.usecase.RestoreFromTrashUseCase
import ai.sakana.aura.gallery.domain.usecase.ObserveSceneClustersUseCase
import ai.sakana.aura.gallery.domain.usecase.ObserveDetectedFacesUseCase
import ai.sakana.aura.gallery.domain.usecase.ObservePersonClustersUseCase
import ai.sakana.aura.gallery.domain.recognition.AiSet
import ai.sakana.aura.gallery.domain.usecase.ObserveAiSetsUseCase
import ai.sakana.aura.gallery.domain.usecase.SaveAiSetUseCase
import ai.sakana.aura.gallery.domain.usecase.RenameAiSetUseCase
import ai.sakana.aura.gallery.domain.usecase.MergeAiSetsUseCase
import ai.sakana.aura.gallery.domain.usecase.RemoveMediaFromAiSetUseCase
import ai.sakana.aura.gallery.domain.usecase.DeleteAiSetUseCase
import ai.sakana.aura.gallery.domain.recognition.SceneCluster
import ai.sakana.aura.gallery.domain.recognition.DetectedFace
import ai.sakana.aura.gallery.domain.recognition.PersonCluster
import ai.sakana.aura.gallery.domain.recognition.PersonClusterCandidate
import ai.sakana.aura.gallery.domain.usecase.ObserveSavedPeopleUseCase
import ai.sakana.aura.gallery.domain.usecase.SavePersonUseCase
import ai.sakana.aura.gallery.domain.usecase.RenamePersonUseCase
import ai.sakana.aura.gallery.domain.usecase.MergePersonsUseCase
import ai.sakana.aura.gallery.domain.usecase.SplitPersonUseCase
import ai.sakana.aura.gallery.domain.usecase.DeletePersonUseCase
import ai.sakana.aura.gallery.worker.RecognitionWorker
import ai.sakana.aura.gallery.data.deduplication.HashProgress
import ai.sakana.aura.gallery.domain.usecase.DetectExactDuplicatesUseCase
import ai.sakana.aura.gallery.domain.usecase.DetectSimilarPhotosUseCase
import ai.sakana.aura.gallery.ui.dedup.DuplicateReviewUiState
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import ai.sakana.aura.gallery.diagnostics.PerformanceMetrics
import ai.sakana.aura.gallery.diagnostics.GalleryErrorReporter

data class ExportProgressUiState(
    val percent: Int = 0,
    val isExporting: Boolean = true,
    val errorMessage: String? = null,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val mediaItemDao: MediaItemDao,
    private val mediaVersionDao: MediaVersionDao,
    observeMedia: ObserveMediaUseCase,
    private val refreshMediaStore: RefreshMediaStoreUseCase,
    private val importPickedMedia: ImportPickedMediaUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    observeTrashedMedia: ObserveTrashedMediaUseCase,
    private val moveToTrash: MoveToTrashUseCase,
    private val restoreFromTrash: RestoreFromTrashUseCase,
    observeSceneClusters: ObserveSceneClustersUseCase,
    observeDetectedFaces: ObserveDetectedFacesUseCase,
    observePersonClusters: ObservePersonClustersUseCase,
    observeSavedPeople: ObserveSavedPeopleUseCase,
    observeAiSets: ObserveAiSetsUseCase,
    private val savePersonUseCase: SavePersonUseCase,
    private val renamePersonUseCase: RenamePersonUseCase,
    private val mergePersonsUseCase: MergePersonsUseCase,
    private val splitPersonUseCase: SplitPersonUseCase,
    private val deletePersonUseCase: DeletePersonUseCase,
    private val detectExactDuplicates: DetectExactDuplicatesUseCase,
    private val detectSimilarPhotos: DetectSimilarPhotosUseCase,
    private val saveAiSet: SaveAiSetUseCase,
    private val renameAiSet: RenameAiSetUseCase,
    private val mergeAiSets: MergeAiSetsUseCase,
    private val removeMediaFromAiSet: RemoveMediaFromAiSetUseCase,
    private val deleteAiSet: DeleteAiSetUseCase,
    private val galleryErrorReporter: GalleryErrorReporter,
    private val editHistoryRepository: EditHistoryRepository,
) : ViewModel() {
    val sceneClusters: StateFlow<List<SceneCluster>> = observeSceneClusters().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val aiSets: StateFlow<List<AiSet>> = observeAiSets().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val detectedFaces: StateFlow<List<DetectedFace>> = observeDetectedFaces().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val personClusters: StateFlow<List<PersonCluster>> = observePersonClusters().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val savedPeople: StateFlow<List<PersonClusterCandidate>> = observeSavedPeople().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val trashedItems = observeTrashedMedia().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val mediaItems = observeMedia().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _exportProgress = MutableStateFlow<ExportProgressUiState?>(null)
    val exportProgress: StateFlow<ExportProgressUiState?> = _exportProgress.asStateFlow()
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _recognitionProgress = MutableStateFlow(RecognitionProgress())
    val recognitionProgress: StateFlow<RecognitionProgress> = _recognitionProgress.asStateFlow()

    private val _duplicateReviewState = MutableStateFlow(DuplicateReviewUiState())
    val duplicateReviewState: StateFlow<DuplicateReviewUiState> = _duplicateReviewState.asStateFlow()

    private val refreshRequestCoalescer = RefreshRequestCoalescer()
    private var refreshJob: Job? = null
    private var hasCompletedMediaStoreScan = false
    private val recognitionWorkManager = WorkManager.getInstance(applicationContext)
    private val recognitionWorkObserver = Observer<List<WorkInfo>> { workInfos ->
        val work = workInfos?.firstOrNull()
        if (work == null) {
            if (!_recognitionProgress.value.isRunning) _recognitionProgress.value = RecognitionProgress()
            return@Observer
        }
        val done = work.progress.getInt(
            RecognitionWorker.KEY_COMPLETED,
            work.outputData.getInt(RecognitionWorker.KEY_COMPLETED, 0),
        )
        val total = work.progress.getInt(
            RecognitionWorker.KEY_TOTAL,
            work.outputData.getInt(RecognitionWorker.KEY_TOTAL, _recognitionProgress.value.total),
        )
        when (work.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                _recognitionProgress.value = RecognitionProgress(
                    completed = done,
                    total = total,
                    isRunning = true,
                )
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> {
                val data = work.outputData
                _recognitionProgress.value = RecognitionProgress(
                    completed = data.getInt(RecognitionWorker.KEY_COMPLETED, done),
                    total = data.getInt(RecognitionWorker.KEY_TOTAL, total),
                    processed = data.getInt(RecognitionWorker.KEY_PROCESSED, 0),
                    alreadyAnalyzed = data.getInt(RecognitionWorker.KEY_CACHED, 0),
                    failed = data.getInt(RecognitionWorker.KEY_FAILED, 0),
                    isRunning = false,
                    error = if (work.state == WorkInfo.State.FAILED) "Recognition needs attention. Check media access and retry." else null,
                )
            }
            WorkInfo.State.CANCELLED -> {
                _recognitionProgress.value = _recognitionProgress.value.copy(isRunning = false)
            }
        }
    }

    init {
        recognitionWorkManager.getWorkInfosForUniqueWorkLiveData(RecognitionWorker.WORK_NAME)
            .observeForever(recognitionWorkObserver);
        viewModelScope.launch {
            galleryErrorReporter.message.collect { message ->
                if (!message.isNullOrBlank()) _errorMessage.value = message
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) {
            refreshRequestCoalescer.requestAnotherPass()
            Timber.d("Gallery refresh queued until the active scan finishes")
            return
        }

        refreshRequestCoalescer.reset()
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                do {
                    _errorMessage.value = null
                    try {
                        val metric = if (hasCompletedMediaStoreScan) "incremental_media_store_refresh" else "first_media_store_scan"
                        val itemCountBefore = mediaItems.value.size
                        val scanStartedAt = SystemClock.elapsedRealtime()
                        Timber.i("Gallery refresh started")
                        refreshMediaStore()
                        val itemCountAfter = mediaItems.value.size
                        PerformanceMetrics.recordMediaStoreScan(
                            metric = metric,
                            durationMs = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L),
                            itemCountBefore = itemCountBefore,
                            itemCountAfter = itemCountAfter,
                        )
                        hasCompletedMediaStoreScan = true
                        Timber.i("Gallery refresh completed")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        reportGalleryFailure("refresh device gallery", error, "Aura couldn't refresh your gallery. Check access and try again.")
                    }
                } while (refreshRequestCoalescer.consumeQueuedPass())
            } finally {
                _isRefreshing.value = false
                refreshJob = null
            }
        }
    }

    fun addPickedMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                Timber.i("Import of %d user-selected media items started", uris.size)
                importPickedMedia(uris)
                Timber.i("User-selected media import completed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportGalleryFailure("import selected media", error, "Some selected photos couldn't be added. Please try again.")
            }
        }
    }

    fun toggleFavorite(contentUri: String) {
        viewModelScope.launch {
            try {
                toggleFavorite.invoke(contentUri)
                Timber.i("Favourite state updated")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportGalleryFailure("update favourite", error, "The favourite couldn't be saved. Please try again.")
            }
        }
    }

    fun moveToTrash(contentUri: String) {
        viewModelScope.launch {
            try {
                moveToTrash.invoke(contentUri)
                Timber.i("Media item moved to Aura trash")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportGalleryFailure("move media to Aura trash", error, "This item couldn't be moved to Recently Deleted.")
            }
        }
    }

    fun restoreFromTrash(contentUri: String) {
        viewModelScope.launch {
            try {
                restoreFromTrash.invoke(contentUri)
                Timber.i("Media item restored from Aura trash")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportGalleryFailure("restore media from Aura trash", error, "This item couldn't be restored.")
            }
        }
    }

    suspend fun findContinuationShotSuggestions(): List<ShotSequence> = mediaItemDao.getAvailableNotTrashed()
        .map { item ->
            ShotCandidate(
                id = item.id.toString(),
                contentUri = item.contentUri,
                capturedAtEpochMillis = item.dateTakenEpochMillis,
                width = item.width,
                height = item.height,
                perceptualHash = item.perceptualHash,
            )
        }
        .let(ShotSequenceGrouper::group)

    fun observeMediaVersions(contentUri: String): Flow<List<MediaVersionEntity>> =
        mediaVersionDao.observeVersions(contentUri)

    fun recordMediaVersion(
        sourceContentUri: String,
        versionContentUri: String,
        operation: String,
        prompt: String,
        modelId: String,
    ) {
        viewModelScope.launch {
            runCatching {
                require(sourceContentUri != versionContentUri) { "An edited version must be a separate file." }
                mediaVersionDao.insertVersion(
                    MediaVersionEntity(
                        sourceContentUri = sourceContentUri,
                        versionContentUri = versionContentUri,
                        operation = operation,
                        prompt = prompt.take(2_000),
                        modelId = modelId,
                        createdAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { error ->
                Timber.e(error, "Unable to save edited media version metadata")
                reportGalleryFailure("save media version history", error as? Exception ?: Exception(error), "The edited copy was saved, but Aura couldn't add it to version history.")
            }
        }
    }

    fun analyzeLibrary(items: List<MediaItem>) {
        if (_recognitionProgress.value.isRunning || items.isEmpty()) return
        try {
            _recognitionProgress.value = RecognitionProgress(isRunning = true, total = items.size)
            RecognitionWorker.enqueueManual(applicationContext)
        } catch (error: Exception) {
            Timber.e(error, "Unable to queue on-device media analysis")
            _recognitionProgress.value = RecognitionProgress(
                total = items.size,
                error = "Analysis could not be queued. Check background-work permissions and try again.",
            )
        }
    }

    fun scanExactDuplicates() {
        if (_duplicateReviewState.value.isScanning) return
        viewModelScope.launch {
            _duplicateReviewState.value = _duplicateReviewState.value.copy(
                isScanning = true,
                progressDone = 0,
                progressTotal = 0,
                progressLabel = "Hashing exact matches",
                errorMessage = null,
            )
            try {
                val result = detectExactDuplicates.scan { progress ->
                    updateDuplicateProgress(progress, "Hashing exact matches")
                }
                _duplicateReviewState.value = _duplicateReviewState.value.copy(
                    isScanning = false,
                    progressDone = result.hashedCount + result.reusedHashCount,
                    progressTotal = result.hashedCount + result.reusedHashCount,
                    exactGroups = result.groups,
                    hasScannedExact = true,
                    errorMessage = if (result.unreadableUris.isNotEmpty()) {
                        "${result.unreadableUris.size} media items could not be read; the rest of the scan completed."
                    } else null,
                )
            } catch (cancelled: CancellationException) {
                _duplicateReviewState.value = _duplicateReviewState.value.copy(isScanning = false)
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Exact duplicate scan failed")
                _duplicateReviewState.value = _duplicateReviewState.value.copy(
                    isScanning = false,
                    errorMessage = error.message ?: "Exact duplicate scan failed.",
                )
            }
        }
    }

    fun scanSimilarPhotos() {
        if (_duplicateReviewState.value.isScanning) return
        viewModelScope.launch {
            _duplicateReviewState.value = _duplicateReviewState.value.copy(
                isScanning = true,
                progressDone = 0,
                progressTotal = 0,
                progressLabel = "Calculating perceptual hashes",
                errorMessage = null,
            )
            try {
                val result = detectSimilarPhotos.scan { progress ->
                    updateDuplicateProgress(progress, "Calculating perceptual hashes")
                }
                _duplicateReviewState.value = _duplicateReviewState.value.copy(
                    isScanning = false,
                    progressDone = result.hashedCount + result.reusedHashCount,
                    progressTotal = result.hashedCount + result.reusedHashCount,
                    similarGroups = result.groups,
                    hasScannedSimilar = true,
                    errorMessage = if (result.failedUris.isNotEmpty()) {
                        "${result.failedUris.size} media items could not be decoded; the rest of the scan completed."
                    } else null,
                )
            } catch (cancelled: CancellationException) {
                _duplicateReviewState.value = _duplicateReviewState.value.copy(isScanning = false)
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Similar-photo scan failed")
                _duplicateReviewState.value = _duplicateReviewState.value.copy(
                    isScanning = false,
                    errorMessage = error.message ?: "Similar-photo scan failed.",
                )
            }
        }
    }

    private fun updateDuplicateProgress(progress: HashProgress, label: String) {
        _duplicateReviewState.value = _duplicateReviewState.value.copy(
            progressDone = progress.done,
            progressTotal = progress.total,
            progressLabel = label,
        )
    }

    fun moveDuplicateCopiesToTrash(contentUris: List<String>) {
        if (contentUris.isEmpty()) return
        viewModelScope.launch {
            try {
                contentUris.distinct().forEach { moveToTrash(it) }
                // The scan results are snapshots; invalidate them after a Trash operation so
                // the UI never displays stale groups or offers an already-trashed item again.
                _duplicateReviewState.value = _duplicateReviewState.value.copy(
                    exactGroups = emptyList(),
                    similarGroups = emptyList(),
                    hasScannedExact = false,
                    hasScannedSimilar = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Could not move selected duplicate copies to Trash")
                _duplicateReviewState.value = _duplicateReviewState.value.copy(
                    errorMessage = error.message ?: "Could not move the selected copies to Trash.",
                )
            }
        }
    }

    fun savePerson(cluster: PersonCluster) = launchPersonOperation("save person") {
        val faceIds = cluster.faceIds.distinct()
        require(faceIds.isNotEmpty()) { "This suggestion has no face samples to save." }
        val candidate = PersonClusterCandidate(
            representativeFaceId = faceIds.first(),
            faceIds = faceIds,
            mediaUris = cluster.mediaUris.distinct(),
            averageSimilarity = cluster.averageSimilarity,
        )
        val name = "Person ${savedPeople.value.size + 1}"
        savePersonUseCase(candidate, name)
    }

    fun renamePerson(personId: Long, name: String) = launchPersonOperation("rename person") {
        renamePersonUseCase(personId, name)
    }

    fun mergePersons(sourcePersonId: Long, targetPersonId: Long) = launchPersonOperation("merge people") {
        mergePersonsUseCase(sourcePersonId, targetPersonId)
    }

    fun splitPerson(personId: Long, faceId: Long) = launchPersonOperation("split person") {
        splitPersonUseCase(personId, faceId)
    }

    fun deletePerson(personId: Long) = launchPersonOperation("delete person") {
        deletePersonUseCase(personId)
    }

    fun observeEditHistory(contentUri: String): Flow<List<EditHistoryEntity>?> =
        editHistoryRepository.observeForUri(contentUri)
            .map<List<EditHistoryEntity>, List<EditHistoryEntity>?> { it }
            .onStart { emit(null) }

    fun saveEditPreset(contentUri: String, name: String, params: EditParams) =
        launchEditHistoryOperation("save edit preset") {
            editHistoryRepository.savePreset(contentUri, name, params, System.currentTimeMillis())
        }

    fun applyEditPreset(presetId: Long) = launchEditHistoryOperation("apply edit preset") {
        editHistoryRepository.applyPreset(presetId, System.currentTimeMillis())
    }

    fun branchEditPreset(presetId: Long, name: String) =
        launchEditHistoryOperation("branch edit preset") {
            editHistoryRepository.branchPreset(presetId, name, System.currentTimeMillis())
        }

    fun deleteEditPreset(preset: EditHistoryEntity) =
        launchEditHistoryOperation("delete edit preset") {
            check(editHistoryRepository.deletePreset(preset)) {
                "The active edit cannot be deleted. Apply another preset or reset first."
            }
        }

    fun resetEditHistory(contentUri: String) = launchEditHistoryOperation("reset edit history") {
        editHistoryRepository.reset(contentUri, System.currentTimeMillis())
    }

    fun recordExportedEdit(contentUri: String, params: EditParams) =
        launchEditHistoryOperation("record exported edit") {
            editHistoryRepository.recordExportedEdit(contentUri, params, System.currentTimeMillis())
        }

    fun exportEditedCopy(
        item: MediaItem,
        matrixValues: FloatArray,
        params: EditParams,
        rotationDegrees: Int = 0,
        cropAspectRatio: Float? = null,
    ) {
        if (_exportProgress.value?.isExporting == true) return
        viewModelScope.launch(Dispatchers.IO) {
            _exportProgress.value = ExportProgressUiState(percent = 0, isExporting = true)
            try {
                val settings = ExportSettingsStore(applicationContext).load()
                val options = ExportOptions.from(settings, presetName = "Custom edit", params = params).copy(
                    rotationDegrees = rotationDegrees,
                    cropAspectRatio = cropAspectRatio,
                )
                MediaEditorExporter.saveToneEdit(
                    context = applicationContext,
                    item = item,
                    matrixValues = matrixValues,
                    options = options,
                    onProgress = { percent ->
                        // Reserve 100% for the point after the output and edit-history row are both committed.
                        _exportProgress.value = ExportProgressUiState(percent = percent.coerceIn(0, 99), isExporting = true)
                    },
                    onComplete = { uri -> Timber.i("Edited copy exported to %s", uri) },
                )
                try {
                    editHistoryRepository.recordExportedEdit(item.contentUri, params, System.currentTimeMillis())
                } catch (historyError: Exception) {
                    Timber.w(historyError, "Edited copy was saved, but its history entry could not be recorded")
                    _errorMessage.value = "Edited copy saved, but its edit-history entry couldn't be saved."
                }
                _exportProgress.value = ExportProgressUiState(percent = 100, isExporting = false)
                refresh()
            } catch (cancelled: CancellationException) {
                _exportProgress.value = null
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Edited copy export failed")
                _exportProgress.value = ExportProgressUiState(
                    percent = _exportProgress.value?.percent ?: 0,
                    isExporting = false,
                    errorMessage = error.message ?: "The edited copy couldn't be saved.",
                )
            }
        }
    }

    fun dismissExportProgress() {
        if (_exportProgress.value?.isExporting != true) _exportProgress.value = null
    }

    private fun launchEditHistoryOperation(label: String, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
                Timber.i("Edit history operation completed: %s", label)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Edit history operation failed: %s", label)
                _errorMessage.value = error.message ?: "The edit history couldn't be updated."
            }
        }
    }

    private fun launchPersonOperation(label: String, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
                Timber.i("Person operation completed: %s", label)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Person operation failed: %s", label)
                _errorMessage.value = error.message ?: "The saved person couldn't be updated."
            }
        }
    }

    override fun onCleared() {
        recognitionWorkManager.getWorkInfosForUniqueWorkLiveData(RecognitionWorker.WORK_NAME)
            .removeObserver(recognitionWorkObserver)
        super.onCleared()
    }

    suspend fun saveAiSetForAgent(name: String, mediaUris: List<String>) {
        require(name.isNotBlank()) { "Name the continuation set before saving it." }
        require(mediaUris.distinct().size >= 2) { "At least two photos are needed for a continuation set." }
        saveAiSet(name, mediaUris.distinct())
    }

    fun saveAiSet(name: String, mediaUris: List<String>) = launchAiSetOperation("save AI Set") {
        saveAiSet.invoke(name, mediaUris)
    }

    fun renameAiSet(setId: String, name: String) = launchAiSetOperation("rename AI Set") {
        renameAiSet.invoke(setId, name)
    }

    fun mergeAiSets(targetSetId: String, sourceSetId: String) = launchAiSetOperation("merge AI Sets") {
        mergeAiSets.invoke(targetSetId, sourceSetId)
    }

    fun removeMediaFromAiSet(setId: String, contentUri: String) = launchAiSetOperation("remove AI Set member") {
        removeMediaFromAiSet.invoke(setId, contentUri)
    }

    fun deleteAiSet(setId: String) = launchAiSetOperation("delete AI Set") {
        deleteAiSet.invoke(setId)
    }

    private fun launchAiSetOperation(label: String, operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
                Timber.i("AI Set operation completed: %s", label)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "AI Set operation failed: %s", label)
                _errorMessage.value = error.message ?: "The AI Set couldn't be updated."
            }
        }
    }

    private fun reportGalleryFailure(operation: String, error: Exception, message: String) {
        if (error is SQLiteException) {
            Timber.e(error, "Room database operation failed: %s", operation)
        } else {
            Timber.e(error, "Gallery operation failed: %s", operation)
        }
        _errorMessage.value = message
    }

    fun clearError() {
        _errorMessage.value = null
        galleryErrorReporter.clear()
    }
}

data class RecognitionProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val processed: Int = 0,
    val alreadyAnalyzed: Int = 0,
    val failed: Int = 0,
    val isRunning: Boolean = false,
    val error: String? = null,
)

/** Coalesces any number of overlapping refresh requests into one follow-up scan. */
internal class RefreshRequestCoalescer {
    private var queued = false

    @Synchronized
    fun requestAnotherPass() {
        queued = true
    }

    @Synchronized
    fun consumeQueuedPass(): Boolean = queued.also { queued = false }

    @Synchronized
    fun reset() {
        queued = false
    }
}

package ai.sakana.aura.gallery.presentation

import android.content.ClipData
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.app.ActivityCompat
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import ai.sakana.aura.gallery.BuildConfig
import ai.sakana.aura.gallery.diagnostics.DiagnosticReportExporter
import ai.sakana.aura.gallery.design.AuraTokens
import ai.sakana.aura.gallery.design.AuraToneColorMatrix
import ai.sakana.aura.gallery.ui.edit.ExportProgressSheet
import ai.sakana.aura.gallery.design.InstrumentSerif
import ai.sakana.aura.gallery.design.JetBrainsMono
import ai.sakana.aura.gallery.design.Manrope
import ai.sakana.aura.gallery.domain.model.MediaItem
import ai.sakana.aura.gallery.domain.agent.AgentActionPolicy
import ai.sakana.aura.gallery.domain.agent.ShotSequence
import ai.sakana.aura.gallery.data.local.EditHistoryEntity
import ai.sakana.aura.gallery.domain.editing.EditParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ai.sakana.aura.gallery.ui.dedup.DuplicateReviewScreen
import ai.sakana.aura.gallery.domain.recognition.AiSet
import ai.sakana.aura.gallery.domain.recognition.AiSetRules
import ai.sakana.aura.gallery.domain.recognition.DetectedFace
import ai.sakana.aura.gallery.domain.recognition.PersonCluster
import ai.sakana.aura.gallery.domain.recognition.SceneCluster
import ai.sakana.aura.gallery.platform.MediaAccessLevel
import ai.sakana.aura.gallery.platform.MediaPermissionPolicy
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import timber.log.Timber

private enum class Destination { PHOTOS, ALBUMS, EXPLORE, AGENT, PEOPLE, AI_SETS, OPTIMIZE, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryRoute(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val agentViewModel: LocalAgentViewModel = hiltViewModel()
    val aiEraseViewModel: AiEraseViewModel = hiltViewModel()
    val agentState by agentViewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var access by remember { mutableStateOf(readMediaAccess(context)) }
    var mediaPermissionRequestCount by rememberSaveable { mutableIntStateOf(0) }
    var permissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val items by viewModel.mediaItems.collectAsState()
    val trashedItems by viewModel.trashedItems.collectAsState()
    val visibleItems = if (MediaPermissionPolicy.canDisplayCachedMedia(access)) items else emptyList()
    val visibleTrashedItems = if (MediaPermissionPolicy.canDisplayCachedMedia(access)) trashedItems else emptyList()
    val refreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val sceneClusters by viewModel.sceneClusters.collectAsState()
    val savedAiSets by viewModel.aiSets.collectAsState()
    val detectedFaces by viewModel.detectedFaces.collectAsState()
    val personClusters by viewModel.personClusters.collectAsState()
    val savedPeople by viewModel.savedPeople.collectAsState()
    val recognitionProgress by viewModel.recognitionProgress.collectAsState()
    val duplicateReviewState by viewModel.duplicateReviewState.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    var destination by remember { mutableStateOf(Destination.PHOTOS) }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeAlbum by remember { mutableStateOf<ai.sakana.aura.gallery.domain.grouping.MediaAlbum?>(null) }
    var showRecentlyDeleted by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<MediaItem?>(null) }
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var aiErasing by remember { mutableStateOf<MediaItem?>(null) }
    var aiErasePrompt by remember { mutableStateOf("Remove the highlighted object, text or watermark from this photo.") }
    var continuationShotSuggestions by remember { mutableStateOf<List<ShotSequence>>(emptyList()) }
    val editorHistoryFlow: Flow<List<EditHistoryEntity>?> = remember(editing?.contentUri) {
        editing?.let { viewModel.observeEditHistory(it.contentUri) } ?: flowOf(null)
    }
    val editorHistory by editorHistoryFlow.collectAsState(initial = null)
    var pendingTrashUris by remember { mutableStateOf<List<String>?>(null) }
    var moreOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50),
        viewModel::addPickedMedia,
    )
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        { results ->
            access = readMediaAccess(context)
            val denied = results.filterValues { !it }.keys
            val activity = context as? android.app.Activity
            val permissionsWithRationale = if (activity == null) emptySet() else denied.filter {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }.toSet()
            permissionPermanentlyDenied = access == MediaAccessLevel.DENIED &&
                MediaPermissionPolicy.shouldOpenAppSettings(
                    hasRequestedBefore = mediaPermissionRequestCount > 1 && activity != null,
                    deniedPermissions = denied,
                    permissionsWithRationale = permissionsWithRationale,
                )
        },
    )
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val resumedAccess = readMediaAccess(context)
                access = resumedAccess
                if (resumedAccess != MediaAccessLevel.DENIED) {
                    permissionPermanentlyDenied = false
                    // Refresh the catalogue after returning from another app or a permission prompt.
                    // GalleryViewModel coalesces overlapping requests.
                    viewModel.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(access) {
        if (access != MediaAccessLevel.DENIED) {
            viewModel.refresh()
        } else {
            // Keep the local catalogue for a possible future re-grant, but never render cached
            // device-media metadata or stale screens after Android revokes every media grant.
            selected = emptySet()
            activeAlbum = null
            showRecentlyDeleted = false
            viewing = null
            editing = null
            aiErasing = null
            pendingTrashUris = null
        }
    }

    val chooseMedia = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }
    val requestAccess = {
        if (permissionPermanentlyDenied) {
            val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(settingsIntent)
        } else {
            mediaPermissionRequestCount += 1
            permissionRequest.launch(MediaPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT).toTypedArray())
        }
    }

    suspend fun executeAgentActions(actions: List<PendingAgentAction>) {
        if (actions.isEmpty()) return
        fun matchingMedia(query: String, mediaType: String, source: List<MediaItem>): List<MediaItem> {
            val terms = query.trim().lowercase(Locale.ROOT)
            val typed = source.filter { item ->
                when (mediaType.lowercase(Locale.ROOT)) {
                    "photo", "photos", "image", "images" -> !item.isVideo
                    "video", "videos" -> item.isVideo
                    else -> true
                }
            }
            if (terms.isBlank() || terms == "all" || terms == "everything" || terms == "latest" || terms == "latest photo" || terms == "latest video") return typed
            return typed.filter { item ->
                item.displayName.orEmpty().contains(terms, ignoreCase = true) ||
                    item.bucketName.contains(terms, ignoreCase = true) ||
                    item.relativePath.contains(terms, ignoreCase = true) ||
                    item.mimeType.orEmpty().contains(terms, ignoreCase = true)
            }
        }
        val results = mutableListOf<Pair<String, String>>()
        val originalPrompt = actions.first().originalUserPrompt
        actions.forEach { action ->
            val args = action.arguments
            val result = runCatching {
                when (action.tool) {
                    "navigate" -> {
                        when (args["screen"].orEmpty().lowercase(Locale.ROOT).replace(' ', '_')) {
                            "photos", "library", "gallery", "favorites", "favourites", "videos" -> {
                                destination = Destination.PHOTOS
                                filter = when (args["screen"]?.lowercase(Locale.ROOT)) {
                                    "favorites", "favourites" -> LibraryFilter.FAVORITES
                                    "videos" -> LibraryFilter.VIDEOS
                                    else -> LibraryFilter.ALL
                                }
                            }
                            "albums" -> destination = Destination.ALBUMS
                            "explore" -> destination = Destination.EXPLORE
                            "people" -> destination = Destination.PEOPLE
                            "ai_sets", "sets" -> destination = Destination.AI_SETS
                            "optimize", "duplicates" -> destination = Destination.OPTIMIZE
                            "settings" -> destination = Destination.SETTINGS
                            "recently_deleted", "trash", "recycle_bin" -> showRecentlyDeleted = true
                            "agent" -> destination = Destination.AGENT
                            else -> error("Aura doesn't have a screen named ${args["screen"].orEmpty()}.")
                        }
                        "Opened ${args["screen"] ?: "the requested Aura screen"}."
                    }
                    "search_media" -> {
                        val matches = matchingMedia(args["query"].orEmpty(), args["media_type"].orEmpty(), visibleItems)
                            .sortedByDescending(MediaItem::dateTakenEpochMillis).take(16)
                        val array = org.json.JSONArray()
                        matches.forEach { item ->
                            array.put(org.json.JSONObject()
                                .put("uri", item.contentUri)
                                .put("name", item.displayName ?: "Untitled")
                                .put("type", if (item.isVideo) "video" else "photo")
                                .put("folder", item.bucketName)
                                .put("captured_at", item.dateTakenEpochMillis))
                        }
                        org.json.JSONObject().put("ok", true).put("count", matches.size).put("items", array).toString()
                    }
                    "open_latest_media" -> {
                        val latest = visibleItems.filter { item ->
                            when (args["media_type"].orEmpty().lowercase(Locale.ROOT)) {
                                "video", "videos" -> item.isVideo
                                else -> !item.isVideo
                            }
                        }.maxByOrNull(MediaItem::dateTakenEpochMillis)
                        if (latest == null) error("No matching media is available on this device.")
                        viewing = latest
                        "Opened ${latest.displayName ?: if (latest.isVideo) "latest video" else "latest photo"}."
                    }
                    "open_ai_erase" -> {
                        val query = args["query"].orEmpty()
                        val found = matchingMedia(query, "photo", visibleItems)
                            .maxByOrNull(MediaItem::dateTakenEpochMillis)
                            ?: error("I couldn't find an accessible photo matching '${query.ifBlank { "latest photo" }}'.")
                        aiErasePrompt = args["operation"] ?: originalPrompt
                        aiErasing = found
                        editing = null
                        viewing = null
                        "Opened ${found.displayName ?: "the selected photo"} in AI Erase. Paint over the object or detect text, then review and save a separate copy."
                    }
                    "open_tone_editor" -> {
                        val query = args["query"].orEmpty()
                        val found = matchingMedia(query, "photo", visibleItems)
                            .maxByOrNull(MediaItem::dateTakenEpochMillis)
                            ?: error("I couldn't find an accessible photo to edit.")
                        editing = found
                        viewing = null
                        "Opened ${found.displayName ?: "the selected photo"} in Aura's non-destructive tone editor. Adjust the preview and save a new version."
                    }
                    "refresh_library" -> {
                        if (access == MediaAccessLevel.DENIED) error("Grant Aura media access before refreshing the device library.")
                        viewModel.refresh()
                        "Started a local refresh of your device media index."
                    }
                    "analyze_library" -> {
                        if (visibleItems.isEmpty()) error("No accessible media is indexed yet. Add media or grant access before analysis.")
                        if (recognitionProgress.isRunning) "On-device analysis is already running."
                        else { viewModel.analyzeLibrary(visibleItems); "Started on-device People and scene analysis for ${visibleItems.size} items." }
                    }
                    "scan_duplicates" -> {
                        if (visibleItems.isEmpty()) error("No accessible media is indexed yet. Add media or grant access before scanning.")
                        if (duplicateReviewState.isScanning) "A duplicate scan is already running."
                        else {
                            destination = Destination.OPTIMIZE
                            if (args["mode"].equals("similar", ignoreCase = true)) viewModel.scanSimilarPhotos() else viewModel.scanExactDuplicates()
                            "Opened Optimize and started a read-only ${args["mode"] ?: "exact"} duplicate scan."
                        }
                    }
                    "find_continuation_shots" -> {
                        continuationShotSuggestions = viewModel.findContinuationShotSuggestions()
                        val groups = org.json.JSONArray()
                        continuationShotSuggestions.forEachIndexed { index, sequence ->
                            val members = visibleItems.filter { it.contentUri in sequence.contentUris }
                            groups.put(org.json.JSONObject()
                                .put("group_index", index)
                                .put("count", sequence.contentUris.size)
                                .put("start_time", sequence.startEpochMillis)
                                .put("end_time", sequence.endEpochMillis)
                                .put("media", org.json.JSONArray().also { array ->
                                    members.forEach { array.put(org.json.JSONObject().put("name", it.displayName ?: "Untitled").put("uri", it.contentUri)) }
                                }))
                        }
                        org.json.JSONObject().put("ok", true).put("suggestions", groups).toString()
                    }
                    "save_continuation_set" -> {
                        val index = args["group_index"]?.toIntOrNull() ?: error("Choose a continuation group index from the suggestions first.")
                        val group = continuationShotSuggestions.getOrNull(index) ?: error("That continuation group is no longer available. Run the search again.")
                        val name = args["name"].orEmpty().ifBlank { "Continuation ${index + 1}" }
                        viewModel.saveAiSetForAgent(name, group.contentUris)
                        "Saved '$name' as a user-confirmed AI Set with ${group.contentUris.size} shots."
                    }
                    "move_media_to_trash" -> {
                        if (!AgentActionPolicy.authorizeTrash(originalPrompt)) error("I won't move media to Trash because your original request didn't explicitly ask to delete, trash, or move it to Recently Deleted.")
                        val query = args["query"].orEmpty()
                        val matches = if (query.equals("selected", true)) visibleItems.filter { it.contentUri in selected }
                            else matchingMedia(query, "all", visibleItems)
                        if (matches.isEmpty()) error("No matching photos or videos were found. Nothing was moved.")
                        pendingTrashUris = matches.map(MediaItem::contentUri).distinct()
                        "Found ${matches.size} matching item(s). Review the Recently Deleted confirmation in Aura to move them into the recoverable bin. Permanent deletion is not available."
                    }
                    "restore_from_trash" -> {
                        val matches = matchingMedia(args["query"].orEmpty(), "all", visibleTrashedItems)
                        if (matches.isEmpty()) error("No matching items were found in Recently Deleted.")
                        matches.forEach { viewModel.restoreFromTrash(it.contentUri) }
                        "Requested restore for ${matches.size} item(s) from Recently Deleted."
                    }
                    "open_settings" -> { destination = Destination.SETTINGS; "Opened Aura Settings." }
                    "search_web" -> {
                        val query = args["query"].orEmpty().trim()
                        if (query.isBlank()) error("Provide a topic to search on the web.")
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(query)}")))
                        "Opened a browser search for '$query'. No gallery image or private media was shared."
                    }
                    else -> error("Aura rejected an unsupported action: ${action.tool}.")
                }
            }.fold(
                onSuccess = { value -> org.json.JSONObject().put("ok", true).put("result", value).toString() },
                onFailure = { error -> org.json.JSONObject().put("ok", false).put("error", error.message ?: "The action failed.").toString() },
            )
            results += action.tool to result
        }
        agentViewModel.provideToolResults(results, originalPrompt)
    }

    exportProgress?.let { progress ->
        ExportProgressSheet(
            percent = progress.percent,
            errorMessage = progress.errorMessage,
            onDismiss = viewModel::dismissExportProgress,
        )
    }

    if (pendingTrashUris != null) {
        AlertDialog(
            onDismissRequest = { pendingTrashUris = null },
            title = { Text("Move to Recently Deleted?") },
            text = { Text("This removes the item from Aura’s main library. The original file stays in its device folder and can be restored here.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingTrashUris.orEmpty().forEach(viewModel::moveToTrash)
                    pendingTrashUris = null
                    selected = emptySet()
                    viewing = null
                }) { Text("Move to Recently Deleted") }
            },
            dismissButton = { TextButton(onClick = { pendingTrashUris = null }) { Text("Cancel") } },
        )
    }

    if (aiErasing != null) {
        BackHandler { aiErasing = null }
        ScreenErrorBoundary(screenName = "AI Erase", onRetry = { aiErasing = null }) {
            AiEraseScreen(
                item = aiErasing!!,
                modelReady = agentState.inpaintDownload.status == "Downloaded" || agentState.inpaintDownload.status == "Ready",
                viewModel = aiEraseViewModel,
                onBack = { aiErasing = null },
                onDownloadModel = agentViewModel::downloadInpaintingModel,
            )
        }
        return
    }

    if (editing != null) {
        BackHandler { editing = null }
        ScreenErrorBoundary(screenName = "Editor", onRetry = { editing = null }) {
            AuraEditorScreen(
                item = editing!!,
                editHistory = editorHistory,
                isExporting = exportProgress?.isExporting == true,
                onBack = { editing = null },
                onSavePreset = { name, params -> viewModel.saveEditPreset(editing!!.contentUri, name, params) },
                onApplyPreset = { preset -> viewModel.applyEditPreset(preset.id) },
                onBranchPreset = { preset, name -> viewModel.branchEditPreset(preset.id, name) },
                onDeletePreset = viewModel::deleteEditPreset,
                onResetHistory = { viewModel.resetEditHistory(editing!!.contentUri) },
                onSaveCopy = { matrix, params, rotation, cropRatio ->
                    viewModel.exportEditedCopy(editing!!, matrix.values, params, rotation, cropRatio)
                },
            )
        }
        return
    }

    if (viewing != null) {
        BackHandler { viewing = null }
        val sourceItems = if (viewing!!.isTrashed) visibleTrashedItems else visibleItems
        val orderedViewerItems = remember(sourceItems) { sourceItems.sortedByDescending(MediaItem::dateTakenEpochMillis) }
        val foundIndex = orderedViewerItems.indexOfFirst { it.contentUri == viewing!!.contentUri }
        val viewerItems = if (foundIndex >= 0) orderedViewerItems else listOf(viewing!!)
        val viewerIndex = foundIndex.takeIf { it >= 0 } ?: 0
        val activeItem = viewerItems[viewerIndex]
        ScreenErrorBoundary(screenName = "Media viewer", onRetry = { viewing = null }) {
            MediaViewerScreen(
                item = activeItem,
                onBack = { viewing = null },
                onShare = { shareMedia(context, listOf(activeItem)) },
                onFavorite = { viewModel.toggleFavorite(activeItem.contentUri) },
                onEdit = { editing = activeItem },
                onDelete = { pendingTrashUris = listOf(activeItem.contentUri) },
                itemPosition = viewerIndex + 1,
                itemCount = viewerItems.size,
                canNavigatePrevious = viewerIndex > 0,
                canNavigateNext = viewerIndex < viewerItems.lastIndex,
                onNavigatePrevious = {
                    MediaViewerNavigation.targetIndex(viewerIndex, viewerItems.size, -1)?.let { viewing = viewerItems[it] }
                },
                onNavigateNext = {
                    MediaViewerNavigation.targetIndex(viewerIndex, viewerItems.size, 1)?.let { viewing = viewerItems[it] }
                },
            )
        }
        return
    }

    if (showRecentlyDeleted) {
        BackHandler { showRecentlyDeleted = false }
        ScreenErrorBoundary(screenName = "Recently deleted", onRetry = { showRecentlyDeleted = false }) {
            RecentlyDeletedScreen(
            items = visibleTrashedItems,
            onBack = { showRecentlyDeleted = false },
            onRestore = viewModel::restoreFromTrash,
            onOpen = { viewing = it },
            )
        }
        return
    }

    if (activeAlbum != null) {
        BackHandler { activeAlbum = null }
        ScreenErrorBoundary(screenName = "Album detail", onRetry = { activeAlbum = null }) {
            AlbumDetailScreen(
            album = activeAlbum!!,
            onBack = { activeAlbum = null },
            onOpen = { viewing = it },
            )
        }
        return
    }

    Scaffold(
        containerColor = AuraTokens.Void,
        bottomBar = {
            AuraBottomBar(destination) {
                destination = it
                selected = emptySet()
            }
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().background(AuraTokens.Void).padding(contentPadding)) {
            when (destination) {
                Destination.PHOTOS -> ScreenErrorBoundary(screenName = "Library", onRetry = viewModel::refresh) {
                    LibraryScreen(
                    items = visibleItems,
                    access = access,
                    accessPermanentlyDenied = permissionPermanentlyDenied,
                    refreshing = refreshing,
                    error = error,
                    selectedUris = selected,
                    filter = filter,
                    onRefresh = viewModel::refresh,
                    onFilter = { filter = it },
                    onOpenViewer = { viewing = it },
                    onToggleSelected = { item ->
                        selected = if (item.contentUri in selected) selected - item.contentUri else selected + item.contentUri
                    },
                    onShareSelected = { shareMedia(context, it); selected = emptySet() },
                    onFavoriteSelected = {
                        it.forEach { item -> if (!item.isFavorite) viewModel.toggleFavorite(item.contentUri) }
                        selected = emptySet()
                    },
                    onDeleteSelected = { pendingTrashUris = it.map(MediaItem::contentUri) },
                    onChooseMedia = chooseMedia,
                    onRequestAccess = requestAccess,
                    onDismissError = viewModel::clearError,
                    onMore = { moreOpen = true },
                    )
                }
                Destination.ALBUMS -> ScreenErrorBoundary(screenName = "Albums", errorMessage = error, onRetry = viewModel::refresh) {
                    AlbumsScreen(
                    items = visibleItems,
                    onOpenAlbum = { activeAlbum = it },
                    onRecentlyDeleted = { showRecentlyDeleted = true },
                    deletedCount = visibleTrashedItems.size,
                    onMore = { moreOpen = true },
                    )
                }
                Destination.EXPLORE -> ScreenErrorBoundary(screenName = "Explore", errorMessage = error, onRetry = viewModel::refresh) {
                    ExploreScreen(
                    onAgent = { destination = Destination.AGENT },
                    onPeople = { destination = Destination.PEOPLE },
                    onAiSets = { destination = Destination.AI_SETS },
                    onOptimize = { destination = Destination.OPTIMIZE },
                    onSettings = { destination = Destination.SETTINGS },
                    )
                }
                Destination.AGENT -> ScreenErrorBoundary(screenName = "Aura Agent", onRetry = { destination = Destination.EXPLORE }) {
                    LocalAgentScreen(
                        state = agentState,
                        itemCount = visibleItems.size,
                        hasMediaAccess = access != MediaAccessLevel.DENIED,
                        recognitionRunning = recognitionProgress.isRunning,
                        duplicateScanRunning = duplicateReviewState.isScanning,
                        onSendMessage = agentViewModel::sendMessage,
                        onDownloadTextModel = agentViewModel::downloadTextModel,
                        onDownloadInpaintingModel = agentViewModel::downloadInpaintingModel,
                        onImportSkill = agentViewModel::importSkill,
                        onRunActions = { actions -> coroutineScope.launch { executeAgentActions(actions) } },
                    )
                }
                Destination.PEOPLE -> ScreenErrorBoundary(screenName = "People", errorMessage = recognitionProgress.error ?: error, onRetry = viewModel::refresh) {
                    PeopleScreen(
                    items = visibleItems,
                    faces = detectedFaces,
                    clusters = personClusters,
                    sceneClusters = sceneClusters,
                    savedPeople = savedPeople,
                    progress = recognitionProgress,
                    onAnalyze = { viewModel.analyzeLibrary(visibleItems) },
                    onOpenMedia = { viewing = it },
                    onSavePerson = viewModel::savePerson,
                    onRenamePerson = viewModel::renamePerson,
                    onMergePersons = viewModel::mergePersons,
                    onSplitPerson = viewModel::splitPerson,
                    onDeletePerson = viewModel::deletePerson,
                    )
                }
                Destination.AI_SETS -> ScreenErrorBoundary(screenName = "AI Sets", onRetry = viewModel::refresh) {
                    AiSetsScreen(
                    items = visibleItems,
                    clusters = sceneClusters,
                    savedAiSets = savedAiSets,
                    progress = recognitionProgress,
                    error = error,
                    onAnalyze = { viewModel.analyzeLibrary(visibleItems) },
                    onSaveAiSet = viewModel::saveAiSet,
                    onRenameAiSet = viewModel::renameAiSet,
                    onMergeAiSets = viewModel::mergeAiSets,
                    onRemoveMember = viewModel::removeMediaFromAiSet,
                    onDeleteAiSet = viewModel::deleteAiSet,
                    onDismissError = viewModel::clearError,
                    )
                }
                Destination.OPTIMIZE -> ScreenErrorBoundary(screenName = "Duplicate review", errorMessage = error, onRetry = viewModel::refresh) {
                    DuplicateReviewScreen(
                        state = duplicateReviewState,
                        onBack = { destination = Destination.EXPLORE },
                        onScanExact = viewModel::scanExactDuplicates,
                        onScanSimilar = viewModel::scanSimilarPhotos,
                        onMoveToTrash = viewModel::moveDuplicateCopiesToTrash,
                    )
                }
                Destination.SETTINGS -> ScreenErrorBoundary(screenName = "Settings", errorMessage = error, onRetry = viewModel::refresh) {
                    SettingsScreen(context, visibleItems, onBack = { destination = Destination.EXPLORE })
                }
            }
        }
    }

    if (moreOpen) {
        ModalBottomSheet(
            onDismissRequest = { moreOpen = false },
            containerColor = AuraTokens.Surface1,
            contentColor = AuraTokens.Paper,
        ) {
            Text("Aura", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), fontFamily = InstrumentSerif, fontSize = 25.sp)
            SheetNavigationRow("Explore", "People and AI Sets", Icons.Outlined.Explore) { destination = Destination.EXPLORE; moreOpen = false }
            SheetNavigationRow("Aura Agent", "Operate Aura with local commands", Icons.Outlined.AutoAwesome) { destination = Destination.AGENT; moreOpen = false }
            SheetNavigationRow("Optimize", "Storage and duplicate analysis", Icons.Outlined.Tune) { destination = Destination.OPTIMIZE; moreOpen = false }
            SheetNavigationRow("Settings", "Backup, privacy and app settings", Icons.Outlined.Settings) { destination = Destination.SETTINGS; moreOpen = false }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ExploreScreen(onAgent: () -> Unit, onPeople: () -> Unit, onAiSets: () -> Unit, onOptimize: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AuraTokens.Void).verticalScroll(rememberScrollState())) {
        PageHeader("Explore", "PEOPLE · AI SETS · TOOLS")
        ExploreAction("Aura Agent", "Operate Aura with private, on-device commands", Icons.Outlined.AutoAwesome, onAgent)
        ExploreAction("People", "Group faces and manage identities on-device", Icons.Outlined.PeopleOutline, onPeople)
        ExploreAction("AI Sets", "Find recurring scenes, outfits and visual setups", Icons.Outlined.AutoAwesome, onAiSets)
        ExploreAction("Optimize", "Review storage usage and duplicate scans", Icons.Outlined.Tune, onOptimize)
        ExploreAction("Settings", "Backup, permissions and privacy controls", Icons.Outlined.Settings, onSettings)
        Text("People matches are provisional review suggestions, not confirmed identities. Person similarity and scene/setup similarity are separate signals; accuracy still needs evaluation against a real library.", Modifier.padding(horizontal = 20.dp, vertical = 16.dp), color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSetsScreen(
    items: List<MediaItem>,
    clusters: List<SceneCluster>,
    savedAiSets: List<AiSet>,
    progress: RecognitionProgress,
    error: String?,
    onAnalyze: () -> Unit,
    onSaveAiSet: (String, List<String>) -> Unit,
    onRenameAiSet: (String, String) -> Unit,
    onMergeAiSets: (String, String) -> Unit,
    onRemoveMember: (String, String) -> Unit,
    onDeleteAiSet: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    val mediaByUri = remember(items) { items.associateBy(MediaItem::contentUri) }
    var pendingSaveUris by remember { mutableStateOf<List<String>?>(null) }
    var newSetName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(AuraTokens.Void)) {
        PageHeader("AI Sets", "ON-DEVICE SCENE SIMILARITY")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoRule(
                title = "Scene similarity suggestions",
                message = "Suggestions use broad visual features and are not guaranteed to describe the same room, outfit or composition. Save only the groups you confirm. Person identity is analyzed separately and is not used to create these scene suggestions.",
                modifier = Modifier,
            )
            if (error != null) ErrorRule(error, onDismissError)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${savedAiSets.size} saved AI Sets", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${savedAiSets.sumOf { it.mediaUris.size }} memberships", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 10.sp)
                }
                AmberButton(
                    if (progress.isRunning) "ANALYZING" else if (progress.total > 0) "RESCAN" else "ANALYZE LIBRARY",
                    onAnalyze,
                    enabled = !progress.isRunning && items.isNotEmpty(),
                    height = 42,
                )
            }

            if (savedAiSets.isNotEmpty()) {
                Text("CONFIRMED SETS", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp)
                savedAiSets.forEach { set ->
                    SavedAiSetCard(
                        set = set,
                        mediaByUri = mediaByUri,
                        otherSets = savedAiSets.filterNot { it.id == set.id },
                        onRename = { name -> onRenameAiSet(set.id, name) },
                        onMerge = { targetSetId -> onMergeAiSets(targetSetId, set.id) },
                        onRemoveMember = { uri -> onRemoveMember(set.id, uri) },
                        onDelete = { onDeleteAiSet(set.id) },
                    )
                }
            }

            if (progress.isRunning || progress.total > 0) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AuraTokens.Surface1).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (progress.isRunning) "ANALYZING ON THIS DEVICE" else "LAST ANALYSIS", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp)
                        Text("${progress.completed}/${progress.total}", color = AuraTokens.Paper, fontFamily = JetBrainsMono, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = AuraTokens.Safelight,
                        trackColor = AuraTokens.Surface3,
                    )
                    if (!progress.isRunning) {
                        Spacer(Modifier.height(7.dp))
                        Text("${progress.processed} analyzed · ${progress.alreadyAnalyzed} reused · ${progress.failed} failed", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp)
                    }
                    progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                }
            }

            Text("SUGGESTIONS TO REVIEW", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp)
            if (clusters.isEmpty()) {
                EmptyIndexState(
                    if (progress.total > 0 && !progress.isRunning) "No matching scene groups yet" else "AI Sets are ready to analyze",
                    if (items.isEmpty()) "Add local photos or videos first. The scene indexer runs entirely on this device."
                    else "Run analysis to find visually related scenes across different dates. Suggestions are candidates for review, not proof that two files are identical.",
                )
            } else {
                clusters.forEachIndexed { index, cluster ->
                    SceneClusterCard(
                        title = "Suggested Set ${(index + 1).toString().padStart(2, '0')}",
                        cluster = cluster,
                        mediaByUri = mediaByUri,
                        onSave = {
                            pendingSaveUris = cluster.mediaUris
                            newSetName = ""
                        },
                    )
                }
            }
            Text(
                "LOCAL ANALYSIS · NO MEDIA UPLOAD · ORIGINAL FILES UNCHANGED",
                color = AuraTokens.PaperFaint,
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }

    if (pendingSaveUris != null) {
        AlertDialog(
            onDismissRequest = { pendingSaveUris = null },
            title = { Text("Save confirmed AI Set") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Give this group a name. The selected media will be linked to a saved set; the original files will not be moved or changed.")
                    OutlinedTextField(
                        value = newSetName,
                        onValueChange = { newSetName = it.take(AiSetRules.MAX_NAME_LENGTH) },
                        label = { Text("AI Set name") },
                        placeholder = { Text("e.g. Bedroom setup") },
                        singleLine = true,
                    )
                    Text("${pendingSaveUris.orEmpty().distinct().size} different media items", color = AuraTokens.PaperMuted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newSetName.isNotBlank() && pendingSaveUris.orEmpty().distinct().size >= 2,
                    onClick = {
                        onSaveAiSet(newSetName, pendingSaveUris.orEmpty())
                        pendingSaveUris = null
                    },
                ) { Text("Save AI Set") }
            },
            dismissButton = { TextButton(onClick = { pendingSaveUris = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SavedAiSetCard(
    set: AiSet,
    mediaByUri: Map<String, MediaItem>,
    otherSets: List<AiSet>,
    onRename: (String) -> Unit,
    onMerge: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(set.id) { mutableStateOf(false) }
    var menuOpen by remember(set.id) { mutableStateOf(false) }
    var mergeMenuOpen by remember(set.id) { mutableStateOf(false) }
    var renameOpen by remember(set.id) { mutableStateOf(false) }
    var deleteOpen by remember(set.id) { mutableStateOf(false) }
    var renameValue by remember(set.id, set.name) { mutableStateOf(set.name) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AuraTokens.Surface1).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(set.name, color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${set.mediaUris.size} items · user confirmed", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "AI Set actions", tint = AuraTokens.PaperMuted) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (expanded) "Hide members" else "Manage members") }, onClick = { expanded = !expanded; menuOpen = false })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { renameValue = set.name; renameOpen = true; menuOpen = false })
                    if (otherSets.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Merge into another set") }, onClick = { mergeMenuOpen = true; menuOpen = false })
                    }
                    DropdownMenuItem(text = { Text("Delete AI Set") }, onClick = { deleteOpen = true; menuOpen = false })
                }
                DropdownMenu(expanded = mergeMenuOpen, onDismissRequest = { mergeMenuOpen = false }) {
                    otherSets.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(target.name) },
                            onClick = { onMerge(target.id); mergeMenuOpen = false },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            set.mediaUris.take(5).forEach { uri ->
                val media = mediaByUri[uri]
                if (media != null) {
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(AuraTokens.Surface2)) {
                        if (media.isVideo) VideoFrameThumbnail(media, Modifier.fillMaxSize())
                        else AsyncImage(model = uri.toUri(), contentDescription = media.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text("REMOVE AN ITEM TO SPLIT IT OUT OF THIS SET", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.5.sp)
            set.mediaUris.forEach { uri ->
                val media = mediaByUri[uri]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (media != null) {
                        AsyncImage(model = uri.toUri(), contentDescription = media.displayName, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(44.dp).background(AuraTokens.Surface2), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ImageNotSupported, null, tint = AuraTokens.PaperFaint) }
                    }
                    Text(media?.displayName ?: "Unavailable media item", Modifier.weight(1f).padding(horizontal = 9.dp), color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { onRemoveMember(uri) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Outlined.Close, "Remove from AI Set", tint = AuraTokens.PaperMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename AI Set") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(AiSetRules.MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = { TextButton(enabled = renameValue.isNotBlank(), onClick = { onRename(renameValue); renameOpen = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Delete this AI Set?") },
            text = { Text("Only the grouping and its name will be deleted. Your photos and videos will stay where they are.") },
            confirmButton = { TextButton(onClick = { onDelete(); deleteOpen = false }) { Text("Delete set") } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SceneClusterCard(
    title: String,
    cluster: SceneCluster,
    mediaByUri: Map<String, MediaItem>,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AuraTokens.Surface1).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${cluster.mediaUris.size} media items", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp)
            }
            Text("${(cluster.averageSimilarity * 100).toInt()}% avg", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            cluster.mediaUris.take(4).forEach { uri ->
                val media = mediaByUri[uri]
                if (media != null) {
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(AuraTokens.Surface2)) {
                        if (media.isVideo) VideoFrameThumbnail(media, Modifier.fillMaxSize())
                        else AsyncImage(model = uri.toUri(), contentDescription = media.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Unconfirmed scene suggestion", Modifier.weight(1f), color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 9.sp)
            TextButton(onClick = onSave, enabled = cluster.mediaUris.distinct().size >= 2) { Text("SAVE AS AI SET") }
        }
    }
}

@Composable
private fun ExploreAction(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(AuraTokens.Surface1)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AuraTokens.Safelight, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(description, color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = AuraTokens.PaperFaint)
    }
}

@Composable
private fun SheetNavigationRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AuraTokens.PaperMuted)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = AuraTokens.Paper, fontFamily = Manrope, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun FirstRunLibrary(onChoosePhotos: () -> Unit, onAllowAccess: () -> Unit, accessPermanentlyDenied: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No media yet", color = AuraTokens.Paper, fontFamily = InstrumentSerif, fontSize = 34.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (accessPermanentlyDenied) {
                "Media access is turned off in Android settings. Open app settings to allow Aura to show your device library, or choose photos directly."
            } else {
                "Choose photos, or allow Aura to show your device library. You stay in control of what the app can see."
            },
            color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 14.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(24.dp))
        AmberButton("PICK PHOTOS & VIDEOS", onChoosePhotos)
        TextButton(onClick = onAllowAccess) {
            Text(if (accessPermanentlyDenied) "OPEN APP SETTINGS" else "ALLOW LIBRARY ACCESS", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
internal fun EmptyGallery(onChoosePhotos: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No media yet", color = AuraTokens.Paper, fontFamily = InstrumentSerif, fontSize = 34.sp)
        Spacer(Modifier.height(12.dp))
        Text("Your library is empty.", color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        AmberButton("PICK PHOTOS & VIDEOS", onChoosePhotos)
    }
}

@Composable
internal fun EmptyIndexState(title: String, message: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, color = AuraTokens.Paper, fontFamily = InstrumentSerif, fontSize = 32.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

@Composable
internal fun PageHeader(title: String, metadata: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 16.dp)) {
        Text(title, color = AuraTokens.Paper, fontFamily = InstrumentSerif, fontSize = 36.sp, lineHeight = 40.sp)
        Spacer(Modifier.height(6.dp))
        Text(metadata, color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
internal fun MetricCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(AuraTokens.Surface1).border(1.dp, AuraTokens.Hairline).padding(16.dp),
    ) {
        Text(title.uppercase(), color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
internal fun SettingsGroup(title: String) {
    Text(title, Modifier.padding(top = 18.dp, bottom = 4.dp), color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.5.sp)
}

@Composable
internal fun SettingsRow(title: String, hint: String, value: String) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 58.dp).drawBehind {
            drawLine(AuraTokens.Hairline, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 1.dp.toPx())
        }.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(hint, color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Text(value, color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
internal fun InfoRule(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().background(AuraTokens.Surface1)) {
        Box(Modifier.width(2.dp).height(72.dp).background(AuraTokens.Safelight))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(3.dp))
            Text(message, color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 11.sp, lineHeight = 17.sp)
            if (action != null && onAction != null) {
                TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                    Text(action, color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.7.sp)
                }
            }
        }
    }
}

@Composable
internal fun ErrorRule(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(AuraTokens.Surface1), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(50.dp).background(AuraTokens.Safelight))
        Text(message, color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 9.dp))
        IconButton(onClick = onDismiss, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Outlined.Close, "Dismiss error", tint = AuraTokens.PaperMuted)
        }
    }
}

@Composable
internal fun AmberButton(text: String, onClick: () -> Unit, height: Int = 48, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(height.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.Safelight, contentColor = AuraTokens.InkOnAmber),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.2.sp)
    }
}

@Composable
internal fun HairlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier.height(48.dp).border(1.dp, AuraTokens.HairlineStrong.copy(alpha = if (enabled) 1f else 0.45f), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AuraTokens.Paper.copy(alpha = if (enabled) 1f else 0.38f), fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun AuraBottomBar(selected: Destination, onSelect: (Destination) -> Unit) {
    Column(Modifier.fillMaxWidth().background(AuraTokens.Void)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AuraTokens.Hairline))
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("Photos", selected == Destination.PHOTOS, Icons.Outlined.GridView) { onSelect(Destination.PHOTOS) }
            NavItem("Albums", selected == Destination.ALBUMS, Icons.Outlined.Folder) { onSelect(Destination.ALBUMS) }
            NavItem("Explore", selected == Destination.EXPLORE || selected == Destination.AGENT || selected == Destination.PEOPLE || selected == Destination.AI_SETS || selected == Destination.OPTIMIZE || selected == Destination.SETTINGS, Icons.Outlined.Explore) { onSelect(Destination.EXPLORE) }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (selected) AuraTokens.Safelight else AuraTokens.PaperFaint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (selected) AuraTokens.Safelight else AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun readMediaAccess(context: Context): MediaAccessLevel {
    val granted = MediaPermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT).filter {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }.toSet()
    return MediaPermissionPolicy.resolveAccess(Build.VERSION.SDK_INT, granted)
}

private fun shareMedia(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<Uri>().apply { items.forEach { add(it.contentUri.toUri()) } }
    val mimeTypes = items.map { it.mimeType?.takeIf(String::isNotBlank) ?: if (it.isVideo) "video/*" else "image/*" }.distinct()
    val mimeType = when {
        mimeTypes.size == 1 -> mimeTypes.first()
        items.all(MediaItem::isVideo) -> "video/*"
        items.all { !it.isVideo } -> "image/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { type = mimeType; putExtra(Intent.EXTRA_STREAM, uris.first()) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = mimeType; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    }.apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, "Aura media", uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share media")) }
}

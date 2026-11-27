package ai.sakana.aura.gallery.presentation

import android.content.ClipData
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.horizontalScroll
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
import ai.sakana.aura.gallery.design.InstrumentSerif
import ai.sakana.aura.gallery.design.JetBrainsMono
import ai.sakana.aura.gallery.design.Manrope
import ai.sakana.aura.gallery.domain.model.MediaItem
import ai.sakana.aura.gallery.domain.recognition.AiSet
import ai.sakana.aura.gallery.domain.recognition.AiSetRules
import ai.sakana.aura.gallery.domain.recognition.DetectedFace
import ai.sakana.aura.gallery.domain.recognition.PersonCluster
import ai.sakana.aura.gallery.domain.recognition.PersonClusterCandidate
import ai.sakana.aura.gallery.domain.recognition.SceneCluster
import ai.sakana.aura.gallery.platform.MediaAccessLevel
import ai.sakana.aura.gallery.platform.MediaPermissionPolicy
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import timber.log.Timber

@Composable
internal fun PeopleScreen(
    items: List<MediaItem>,
    faces: List<DetectedFace>,
    clusters: List<PersonCluster>,
    sceneClusters: List<SceneCluster>,
    savedPeople: List<PersonClusterCandidate>,
    progress: RecognitionProgress,
    onAnalyze: () -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onSavePerson: (PersonCluster) -> Unit,
    onRenamePerson: (Long, String) -> Unit,
    onMergePersons: (Long, Long) -> Unit,
    onSplitPerson: (Long, Long) -> Unit,
    onDeletePerson: (Long) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<PersonClusterCandidate?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var mergeTarget by remember { mutableStateOf<PersonClusterCandidate?>(null) }
    var deleteTarget by remember { mutableStateOf<PersonClusterCandidate?>(null) }
    val mediaByUri = remember(items) { items.associateBy(MediaItem::contentUri) }
    val detectedMedia = remember(faces) { faces.groupBy(DetectedFace::contentUri) }
    Column(Modifier.fillMaxSize().background(AuraTokens.Void)) {
        PageHeader("People", "LAYER 1 · FACE IDENTITY")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoRule(
                title = "Possible matches · review before trusting",
                message = "Aura compares local face features across your photos and videos. Similar-looking people, lighting, angles and low-quality frames can cause mistakes. These groups are suggestions only; no name or confirmed identity is assigned automatically.",
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${clusters.size} possible people", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${faces.size} face samples · ${detectedMedia.size} media items", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 9.sp)
                }
                AmberButton(
                    if (progress.isRunning) "ANALYZING" else if (progress.total > 0) "RESCAN" else "ANALYZE LIBRARY",
                    onAnalyze,
                    enabled = !progress.isRunning && items.isNotEmpty(),
                    height = 42,
                )
            }

            if (progress.total > 0) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(AuraTokens.Surface1).padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (progress.isRunning) "FACE + VISUAL INDEXING" else "LAST ANALYSIS", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp)
                        Text("${progress.completed}/${progress.total}", color = AuraTokens.Paper, fontFamily = JetBrainsMono, fontSize = 10.sp)
                    }
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = AuraTokens.Safelight,
                        trackColor = AuraTokens.Surface3,
                    )
                    if (!progress.isRunning) {
                        Text("${progress.processed} analyzed · ${progress.alreadyAnalyzed} reused · ${progress.failed} failed", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp)
                    }
                }
            }

            Text("SAVED PEOPLE", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp)
            if (savedPeople.isEmpty()) {
                Text("Review a suggestion below and save it to create a persistent person.", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp)
            } else {
                savedPeople.forEach { person ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AuraTokens.Surface1).padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(person.name ?: "Person", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("${person.mediaUris.size} media · ${person.faceIds.size} face samples", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp)
                            }
                            Text("SAVED", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 9.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                renameTarget = person
                                renameValue = person.name.orEmpty()
                            }) { Text("Rename") }
                            TextButton(onClick = { mergeTarget = person }, enabled = savedPeople.size > 1) { Text("Merge") }
                            TextButton(onClick = { deleteTarget = person }) { Text("Remove") }
                        }
                        if (person.faceIds.size > 1) {
                            Text("SPLIT A FACE INTO A NEW PERSON", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                person.faceIds.drop(1).forEach { faceId ->
                                    TextButton(onClick = { onSplitPerson(person.clusterId, faceId) }) {
                                        Text("Split #${faceId}")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text("SUGGESTED REPEATED PEOPLE", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp)
            if (clusters.isEmpty()) {
                EmptyIndexState(
                    if (progress.total > 0 && !progress.isRunning) "No repeated-person groups yet" else "People index is ready",
                    if (items.isEmpty()) "Add local photos or videos first. Face analysis stays on this device."
                    else "Run analysis to compare detected faces across different media. Only groups spanning at least two media items appear here.",
                )
            } else {
                clusters.forEachIndexed { index, cluster ->
                    PersonClusterCard(
                        index = index,
                        cluster = cluster,
                        mediaByUri = mediaByUri,
                        onSave = { onSavePerson(cluster) },
                    )
                }
            }

            Text("LAYER 2 · VISUAL SIMILARITY", Modifier.padding(top = 10.dp), color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp)
            Text(
                "Related photos and videos are grouped by visual content after the same library analysis. These groups can connect a continuing scene even when a face, expression or angle changes; they are suggestions, not proof of identical media.",
                color = AuraTokens.PaperFaint,
                fontFamily = Manrope,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
            val visibleSceneGroups = sceneClusters.mapNotNull { cluster ->
                val members = cluster.mediaUris.mapNotNull(mediaByUri::get)
                if (members.size < 2) null else cluster to members
            }
            if (visibleSceneGroups.isEmpty()) {
                EmptyIndexState(
                    if (progress.isRunning) "Visual groups are building" else "No visual-similarity groups yet",
                    if (items.isEmpty()) "Add local photos or videos first. Both analysis layers run on this device."
                    else "Run Analyze Library above. The visual layer appears here automatically as media embeddings are indexed.",
                )
            } else {
                visibleSceneGroups.forEachIndexed { index, (cluster, members) ->
                    VisualSimilarityClusterCard(
                        index = index,
                        cluster = cluster,
                        members = members,
                        onOpenMedia = onOpenMedia,
                    )
                }
            }

            if (detectedMedia.isNotEmpty()) {
                Text("MEDIA WITH FACE DETECTIONS", Modifier.padding(top = 8.dp), color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp)
                detectedMedia.entries.sortedByDescending { it.value.size }.forEach { (uri, locations) ->
                    val media = mediaByUri[uri] ?: return@forEach
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(9.dp)).background(AuraTokens.Surface1).padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (media.isVideo) VideoFrameThumbnail(media, Modifier.size(58.dp).clip(RoundedCornerShape(5.dp)))
                        else AsyncImage(model = uri.toUri(), contentDescription = null, modifier = Modifier.size(58.dp).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(media.displayName ?: if (media.isVideo) "Video" else "Photo", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${locations.size} face detections${if (media.isVideo) " · sampled frames" else ""}", color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 10.sp)
                        }
                    }
                }
            }
            Text(
                "LOCAL FACE + VISUAL FEATURES · NO UPLOAD · NO AUTOMATIC IDENTITY MERGES",
                Modifier.padding(vertical = 12.dp),
                color = AuraTokens.PaperFaint,
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 0.3.sp,
            )
        }
    }

    renameTarget?.let { person ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename person") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("Person name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenamePerson(person.clusterId, renameValue)
                    renameTarget = null
                }, enabled = renameValue.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    mergeTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { mergeTarget = null },
            title = { Text("Merge into which person?") },
            text = {
                Column {
                    savedPeople.filterNot { it.clusterId == source.clusterId }.forEach { target ->
                        TextButton(onClick = {
                            onMergePersons(source.clusterId, target.clusterId)
                            mergeTarget = null
                        }) { Text(target.name ?: "Person") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mergeTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { person ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove saved person?") },
            text = { Text("This removes the saved identity grouping only. Original photos and videos will remain untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePerson(person.clusterId)
                    deleteTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VisualSimilarityClusterCard(
    index: Int,
    cluster: SceneCluster,
    members: List<MediaItem>,
    onOpenMedia: (MediaItem) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AuraTokens.Surface1).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Related media ${(index + 1).toString().padStart(2, '0')}", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${members.size} photos/videos", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp)
            }
            Text("${(cluster.averageSimilarity * 100).toInt()}%", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 10.sp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            members.forEach { media ->
                Box(
                    Modifier.size(82.dp).clip(RoundedCornerShape(6.dp)).background(AuraTokens.Surface2)
                        .clickable { onOpenMedia(media) },
                ) {
                    if (media.isVideo) VideoFrameThumbnail(media, Modifier.fillMaxSize())
                    else AsyncImage(
                        model = media.contentUri.toUri(),
                        contentDescription = media.displayName ?: "Related photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    if (media.isVideo) {
                        Text("VIDEO", Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 2.dp), color = Color.White, fontFamily = JetBrainsMono, fontSize = 7.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonClusterCard(
    index: Int,
    cluster: PersonCluster,
    mediaByUri: Map<String, MediaItem>,
    onSave: () -> Unit,
) {
    val previews = cluster.mediaUris.mapNotNull(mediaByUri::get).take(4)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AuraTokens.Surface1).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Possible person ${(index + 1).toString().padStart(2, '0')}", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${cluster.mediaUris.size} different media · ${cluster.faceIds.size} face samples", color = AuraTokens.PaperMuted, fontFamily = JetBrainsMono, fontSize = 9.sp)
            }
            Text("REVIEW", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            previews.forEach { media ->
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp))) {
                    if (media.isVideo) {
                        VideoFrameThumbnail(media, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = media.contentUri.toUri(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
        Text(
            "A suggested match only. Review carefully: lighting, viewing angle, image quality or a lookalike can produce a false match.",
            color = AuraTokens.PaperFaint,
            fontFamily = Manrope,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("SAVE AS PERSON") }
    }
}

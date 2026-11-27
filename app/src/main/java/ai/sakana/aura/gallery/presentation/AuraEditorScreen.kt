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
import androidx.compose.ui.graphics.graphicsLayer
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
import ai.sakana.aura.gallery.data.local.EditHistoryEntity
import ai.sakana.aura.gallery.domain.editing.EditParams
import ai.sakana.aura.gallery.domain.editing.EditorCropRules
import ai.sakana.aura.gallery.ui.edit.EditHistoryPanel
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

@Composable
internal fun AuraEditorScreen(
    item: MediaItem,
    editHistory: List<EditHistoryEntity>?,
    isExporting: Boolean,
    onBack: () -> Unit,
    onSavePreset: (String, EditParams) -> Unit,
    onApplyPreset: (EditHistoryEntity) -> Unit,
    onBranchPreset: (EditHistoryEntity, String) -> Unit,
    onDeletePreset: (EditHistoryEntity) -> Unit,
    onResetHistory: () -> Unit,
    onSaveCopy: (ColorMatrix, EditParams, Int, Float?) -> Unit,
) {
    var brightness by remember(item.contentUri) { mutableFloatStateOf(0f) }
    var contrast by remember(item.contentUri) { mutableFloatStateOf(0f) }
    var saturation by remember(item.contentUri) { mutableFloatStateOf(0f) }
    var warmth by remember(item.contentUri) { mutableFloatStateOf(0f) }
    var restoredHistory by remember(item.contentUri) { mutableStateOf(false) }
    var compareOriginal by remember { mutableStateOf(false) }
    var showAiEditInfo by remember { mutableStateOf(false) }
    var editorMode by remember { mutableStateOf(EditorMode.ADJUST) }
    var rotationDegrees by remember(item.contentUri) { mutableIntStateOf(0) }
    var cropAspectRatio by remember(item.contentUri) { mutableStateOf<Float?>(null) }
    var selectedAiTool by remember { mutableStateOf("") }
    val context = LocalContext.current
    val params = remember(brightness, contrast, saturation, warmth) {
        EditParams(brightness, contrast, saturation, warmth)
    }
    LaunchedEffect(item.contentUri, editHistory) {
        val history = editHistory ?: return@LaunchedEffect
        if (!restoredHistory) {
            history.firstOrNull(EditHistoryEntity::isActive)?.let { active ->
                brightness = active.brightness
                contrast = active.contrast
                saturation = active.saturation
                warmth = active.warmth
            }
            restoredHistory = true
        }
    }
    val matrix = remember(brightness, contrast, saturation, warmth) {
        AuraToneColorMatrix.create(brightness, contrast, saturation, warmth)
    }
    Column(Modifier.fillMaxSize().background(AuraTokens.Void)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", tint = AuraTokens.Paper) }
            Column(Modifier.weight(1f)) {
                Text(item.displayName ?: "Photo", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.width.toString() + " × " + item.height + " · " + Formatter.formatShortFileSize(context, item.sizeBytes),
                    color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 10.sp,
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(0.58f).background(Color.Black)) {
            AsyncImage(
                model = item.contentUri.toUri(),
                contentDescription = "Photo preview",
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = if (compareOriginal) 0f else rotationDegrees.toFloat() },
                contentScale = if (cropAspectRatio == null) ContentScale.Fit else ContentScale.Crop,
                colorFilter = if (compareOriginal) null else ColorFilter.colorMatrix(matrix),
            )
            Text(
                if (compareOriginal) "ORIGINAL" else "PREVIEW",
                Modifier.align(Alignment.TopStart).padding(16.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                color = AuraTokens.Paper, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp,
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(0.42f).background(AuraTokens.Surface1)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(3.dp).background(AuraTokens.HairlineStrong, CircleShape))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EditorMode.values().forEach { mode ->
                    Box(Modifier.weight(1f)) {
                        HairlineButton(
                            text = if (editorMode == mode) "✓ ${mode.label}" else mode.label,
                            onClick = { editorMode = mode },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = true,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (editorMode) {
                EditorMode.ADJUST -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Tone and light", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("ORIGINAL PRESERVED", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.4.sp)
                    }
                    ToneSlider("Brightness", brightness) { brightness = it }
                    ToneSlider("Contrast", contrast) { contrast = it }
                    ToneSlider("Saturation", saturation) { saturation = it }
                    ToneSlider("Warmth", warmth) { warmth = it }
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ORIGINAL", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 10.sp)
                        Text("LIVE PREVIEW", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 10.sp)
                    }
                }
                EditorMode.CROP -> {
                    Text("Rotate and crop", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Choose a crop shape. The original file is never overwritten.", color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Original" to null, "1:1" to 1f, "4:3" to (4f / 3f), "16:9" to (16f / 9f)).forEach { (label, ratio) ->
                            Box(Modifier.weight(1f)) {
                                HairlineButton(
                                    text = if (cropAspectRatio == ratio) "✓ $label" else label,
                                    onClick = { cropAspectRatio = ratio },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            HairlineButton("↶ Rotate left", { rotationDegrees = EditorCropRules.normalizeRotation(rotationDegrees - 90) }, Modifier.fillMaxWidth())
                        }
                        Box(Modifier.weight(1f)) {
                            HairlineButton("Rotate right ↷", { rotationDegrees = EditorCropRules.normalizeRotation(rotationDegrees + 90) }, Modifier.fillMaxWidth())
                        }
                    }
                    Text(
                        "${rotationDegrees}° rotation · ${cropAspectRatio?.let { String.format(Locale.US, "%.2f:1", it) } ?: "original frame"}",
                        color = AuraTokens.PaperFaint,
                        fontFamily = JetBrainsMono,
                        fontSize = 9.sp,
                    )
                }
                EditorMode.AI -> {
                    Text("AI object tools", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Generative object removal and canvas extension need a configured image model service. No AI provider is connected in this build.", color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 11.sp, lineHeight = 16.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            HairlineButton("Remove object", { selectedAiTool = "Remove object"; showAiEditInfo = true }, Modifier.fillMaxWidth())
                        }
                        Box(Modifier.weight(1f)) {
                            HairlineButton("Extend image", { selectedAiTool = "Extend image"; showAiEditInfo = true }, Modifier.fillMaxWidth())
                        }
                    }
                    Text("Provider connection required · source image stays local until you choose a provider", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp, lineHeight = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    HoldCompareButton("Hold to compare", { compareOriginal = true }, { compareOriginal = false })
                }
                Box(Modifier.weight(1f)) {
                    HairlineButton(
                        if (isExporting) "Exporting…" else "Save edited copy",
                        { onSaveCopy(matrix, params, rotationDegrees, cropAspectRatio) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExporting,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            EditHistoryPanel(
                presets = editHistory.orEmpty(),
                onSaveCurrent = { name -> onSavePreset(name, params) },
                onApply = { preset ->
                    brightness = preset.brightness
                    contrast = preset.contrast
                    saturation = preset.saturation
                    warmth = preset.warmth
                    onApplyPreset(preset)
                },
                onBranch = onBranchPreset,
                onDelete = onDeletePreset,
                onReset = {
                    brightness = 0f
                    contrast = 0f
                    saturation = 0f
                    warmth = 0f
                    onResetHistory()
                },
            )
        }
    }
    if (showAiEditInfo) {
        AlertDialog(
            onDismissRequest = { showAiEditInfo = false },
            title = { Text("${selectedAiTool.ifBlank { "AI Edit" }} needs a provider") },
            text = { Text("Aura has no generative image model or processing endpoint configured. Connect a compatible image-editing provider before using object removal or canvas extension. No image has been uploaded or changed.") },
            confirmButton = { TextButton(onClick = { showAiEditInfo = false }) { Text("OK") } },
        )
    }
}

private enum class EditorMode(val label: String) {
    ADJUST("Adjust"), CROP("Crop"), AI("AI Tools"),
}

@Composable
internal fun ToneSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 11.sp, modifier = Modifier.width(82.dp))
        HairlineSlider(value, onValueChange, Modifier.weight(1f).height(48.dp))
        Text(
            (if (value > 0f) "+" else "") + String.format(Locale.US, "%.2f", value),
            color = AuraTokens.Paper,
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun HairlineSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun fromX(x: Float, width: Float): Float {
        val fraction = (x / width).coerceIn(0f, 1f)
        return ((if (rtl) 1f - fraction else fraction) * 2f - 1f).coerceIn(-1f, 1f)
    }
    Canvas(
        modifier.pointerInput(rtl) {
            detectTapGestures { onValueChange(fromX(it.x, size.width.toFloat())) }
        }.pointerInput(rtl) {
            detectDragGestures { change, _ ->
                change.consume()
                onValueChange(fromX(change.position.x, size.width.toFloat()))
            }
        },
    ) {
        val y = size.height / 2f
        val zero = size.width / 2f
        val visualValue = if (rtl) -value else value
        val knob = (visualValue + 1f) * 0.5f * size.width
        drawLine(AuraTokens.HairlineStrong, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        drawLine(AuraTokens.Safelight, Offset(zero, y), Offset(knob, y), 1.dp.toPx())
        drawLine(AuraTokens.PaperFaint, Offset(zero, y - 5.dp.toPx()), Offset(zero, y + 5.dp.toPx()), 1.dp.toPx())
        drawCircle(AuraTokens.Paper, 5.dp.toPx(), Offset(knob, y))
    }
}

@Composable
internal fun HoldCompareButton(text: String, onPressed: () -> Unit, onReleased: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).border(1.dp, AuraTokens.HairlineStrong, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onPressed()
                    tryAwaitRelease()
                    onReleased()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

internal fun buildToneColorMatrix(brightness: Float, contrast: Float, saturation: Float, warmth: Float): ColorMatrix {
    fun identity() = Array(5) { row -> FloatArray(5) { col -> if (row == col) 1f else 0f } }
    fun multiply(a: Array<FloatArray>, b: Array<FloatArray>) = Array(5) { row ->
        FloatArray(5) { col -> (0..4).sumOf { k -> (a[row][k] * b[k][col]).toDouble() }.toFloat() }
    }
    val sat = identity()
    val s = (1f + saturation * 0.4f).coerceAtLeast(0f)
    val r = 0.213f * (1f - s)
    val g = 0.715f * (1f - s)
    val b = 0.072f * (1f - s)
    sat[0][0] = r + s; sat[0][1] = g; sat[0][2] = b
    sat[1][0] = r; sat[1][1] = g + s; sat[1][2] = b
    sat[2][0] = r; sat[2][1] = g; sat[2][2] = b + s

    val ctr = identity()
    val c = (1f + contrast * 0.45f).coerceAtLeast(0.05f)
    for (i in 0..2) { ctr[i][i] = c; ctr[i][4] = 0.5f * (1f - c) * 255f }

    val warm = identity()
    warm[0][0] = 1f + warmth * 0.12f
    warm[2][2] = 1f - warmth * 0.12f

    val bright = identity()
    val offset = brightness * 0.16f * 255f
    for (i in 0..2) bright[i][4] = offset

    val matrix = multiply(bright, multiply(warm, multiply(ctr, sat)))
    val values = FloatArray(20)
    for (row in 0..3) for (col in 0..4) values[row * 5 + col] = matrix[row][col]
    return ColorMatrix(values)
}

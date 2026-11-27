package ai.sakana.aura.gallery.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import ai.sakana.aura.gallery.design.AuraTokens
import ai.sakana.aura.gallery.design.InstrumentSerif
import ai.sakana.aura.gallery.design.JetBrainsMono
import ai.sakana.aura.gallery.design.Manrope
import ai.sakana.aura.gallery.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
internal fun AiEraseScreen(
    item: MediaItem,
    modelReady: Boolean,
    viewModel: AiEraseViewModel,
    onBack: () -> Unit,
    onDownloadModel: () -> Unit,
) {
    val context = LocalContext.current
    val eraseState by viewModel.state.collectAsState()
    var bitmap by remember(item.contentUri) { mutableStateOf<Bitmap?>(null) }
    var loadError by remember(item.contentUri) { mutableStateOf<String?>(null) }
    var isDetectingText by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<List<androidx.compose.ui.geometry.Offset>>>(emptyList()) }
    var activeStroke by remember { mutableStateOf<List<androidx.compose.ui.geometry.Offset>>(emptyList()) }
    var textRegions by remember { mutableStateOf<List<NormalizedTextRect>>(emptyList()) }

    LaunchedEffect(item.contentUri) {
        bitmap = null
        loadError = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val uri = item.contentUri.toUri()
                val bounds = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeStream(input, null, it) }
                } ?: error("The original photo couldn't be opened.")
                val sample = max(1, max(bounds.outWidth, bounds.outHeight) / 4096)
                var decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("This photo format isn't supported for local AI erase.")
                val orientation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    ?: ExifInterface.ORIENTATION_NORMAL
                val rotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (rotation != 0f) {
                    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
                    if (rotated !== decoded) decoded.recycle()
                    decoded = rotated
                }
                decoded
            }
        }
        loaded.onSuccess { bitmap = it }.onFailure { loadError = it.message ?: "Unable to load the photo." }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AuraTokens.Void)) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = AuraTokens.Paper) }
            Column(Modifier.weight(1f)) {
                Text("AI Erase", color = AuraTokens.Paper, fontFamily = InstrumentSerif, fontSize = 23.sp)
                Text("ON-DEVICE INPAINTING", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.8.sp)
            }
            Text("ORIGINAL SAFE", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp)
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
            when {
                bitmap == null && loadError == null -> Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AuraTokens.Safelight) }
                loadError != null -> Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(18.dp))
                bitmap != null -> {
                    val source = bitmap!!
                    Text("Paint over an object, text or watermark. Aura fills only the highlighted region.", color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(source.width.toFloat() / source.height).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                    ) {
                        Image(bitmap = source.asImageBitmap(), contentDescription = item.displayName, modifier = Modifier.fillMaxSize())
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(source) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        activeStroke = listOf(androidx.compose.ui.geometry.Offset(start.x / size.width, start.y / size.height))
                                    },
                                    onDragEnd = { if (activeStroke.isNotEmpty()) { strokes = strokes + listOf(activeStroke); activeStroke = emptyList() } },
                                    onDragCancel = { activeStroke = emptyList() },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        activeStroke = activeStroke + androidx.compose.ui.geometry.Offset(change.position.x / size.width, change.position.y / size.height)
                                    },
                                )
                            },
                        ) {
                            val allStrokes = strokes + listOfNotNull(activeStroke.takeIf { it.isNotEmpty() })
                            val brush = 26.dp.toPx()
                            allStrokes.forEach { points ->
                                if (points.size == 1) drawCircle(Color(0x99FF5544), brush / 2f, androidx.compose.ui.geometry.Offset(points.first().x * size.width, points.first().y * size.height))
                                else {
                                    val path = ComposePath().apply {
                                        moveTo(points.first().x, points.first().y)
                                        points.drop(1).forEach { lineTo(it.x, it.y) }
                                    }
                                    drawPath(path, Color(0xAAFF5544), style = androidx.compose.ui.graphics.drawscope.Stroke(brush, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                                }
                            }
                            textRegions.forEach { rect ->
                                drawRect(Color(0x66E0A24A), topLeft = androidx.compose.ui.geometry.Offset(rect.left * size.width, rect.top * size.height), size = androidx.compose.ui.geometry.Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { strokes = emptyList(); activeStroke = emptyList(); textRegions = emptyList(); viewModel.clearResult() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.DeleteSweep, null, modifier = Modifier.padding(end = 4.dp))
                            Text("Clear mask", fontSize = 10.sp)
                        }
                        OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Undo", fontSize = 10.sp) }
                        OutlinedButton(
                            onClick = { isDetectingText = true; viewModel.detectText(source) { found -> textRegions = found; isDetectingText = false } },
                            enabled = !isDetectingText,
                            modifier = Modifier.weight(1.2f),
                        ) {
                            if (isDetectingText) CircularProgressIndicator(Modifier.height(14.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.TextFields, null, modifier = Modifier.padding(end = 4.dp))
                            Text(if (isDetectingText) "Scanning" else "Detect text", fontSize = 10.sp)
                        }
                    }
                    Text("${strokes.size} brush stroke(s) · ${textRegions.size} text region(s)", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp, modifier = Modifier.padding(vertical = 7.dp))
                }
            }

            if (!modelReady) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AuraTokens.Surface1).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LaMa model not installed", color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        Text("Download the 208 MB inpainting model once. Editing runs offline.", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 9.sp)
                    }
                    Button(onClick = onDownloadModel, colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.Safelight, contentColor = AuraTokens.Void)) { Text("Download", fontSize = 9.sp) }
                }
            }
            eraseState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontFamily = Manrope, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            eraseState.savedVersionUri?.let {
                Text("Edited copy saved. The original is unchanged and this new version is in Pictures/Aura AI Edits.", color = AuraTokens.Safelight, fontFamily = Manrope, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(20.dp))
        }

        val hasMask = strokes.isNotEmpty() || textRegions.isNotEmpty() || activeStroke.isNotEmpty()
        Button(
            onClick = {
                val source = bitmap ?: return@Button
                val mask = createMask(source, strokes + listOfNotNull(activeStroke.takeIf { it.isNotEmpty() }), textRegions)
                viewModel.applyErase(item.contentUri, source, mask, "Remove the highlighted object, text or watermark from the selected photo.")
                mask.recycle()
            },
            enabled = modelReady && bitmap != null && hasMask && !eraseState.isProcessing,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.Safelight, contentColor = AuraTokens.Void, disabledContainerColor = AuraTokens.Surface3, disabledContentColor = AuraTokens.PaperFaint),
        ) {
            if (eraseState.isProcessing) CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp, color = AuraTokens.Void)
            else Icon(Icons.Outlined.AutoFixHigh, null, modifier = Modifier.padding(end = 8.dp))
            Text(if (eraseState.isProcessing) "ERASING LOCALLY…" else "ERASE & SAVE NEW VERSION", fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.6.sp)
        }
    }
}

private fun createMask(
    source: Bitmap,
    strokes: List<List<androidx.compose.ui.geometry.Offset>>,
    textRegions: List<NormalizedTextRect>,
): Bitmap {
    val mask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(mask)
    canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        strokeWidth = max(20f, minOf(source.width, source.height) * 0.055f)
    }
    strokes.forEach { points ->
        if (points.isEmpty()) return@forEach
        if (points.size == 1) {
            canvas.drawCircle(points.first().x * source.width, points.first().y * source.height, paint.strokeWidth / 2f, paint.apply { style = Paint.Style.FILL })
            paint.style = Paint.Style.STROKE
        } else {
            val path = Path().apply {
                moveTo(points.first().x * source.width, points.first().y * source.height)
                points.drop(1).forEach { lineTo(it.x * source.width, it.y * source.height) }
            }
            canvas.drawPath(path, paint)
        }
    }
    paint.style = Paint.Style.FILL
    textRegions.forEach { rect ->
        canvas.drawRect(RectF(rect.left * source.width, rect.top * source.height, rect.right * source.width, rect.bottom * source.height), paint)
    }
    return mask
}

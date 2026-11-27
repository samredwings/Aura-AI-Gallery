package ai.sakana.aura.gallery.presentation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import ai.sakana.aura.gallery.design.AuraTokens
import ai.sakana.aura.gallery.design.InstrumentSerif
import ai.sakana.aura.gallery.design.JetBrainsMono
import ai.sakana.aura.gallery.design.Manrope
import ai.sakana.aura.gallery.data.agent.AgentModelCatalog

@Composable
internal fun LocalAgentScreen(
    state: LocalAgentUiState,
    itemCount: Int,
    hasMediaAccess: Boolean,
    recognitionRunning: Boolean,
    duplicateScanRunning: Boolean,
    onSendMessage: (String) -> Unit,
    onDownloadTextModel: () -> Unit,
    onDownloadInpaintingModel: () -> Unit,
    onImportSkill: (String, String, String?) -> Unit,
    onRunActions: (List<PendingAgentAction>) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skillPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Aura couldn't read that skill file.")
                        val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "imported-skill.md"
                        Triple(fileName, content, uri.toString())
                    }
                }
                imported.onSuccess { (name, content, source) -> onImportSkill(name, content, source) }
                    .onFailure { onImportSkill("", "", it.message) }
            }
        }
    }

    fun submit(raw: String) {
        val command = raw.trim()
        if (command.isNotEmpty() && !state.isThinking && state.isModelReady) {
            onSendMessage(command)
            draft = ""
        }
    }

    LaunchedEffect(state.messages.size, state.isThinking) { scrollState.animateScrollTo(scrollState.maxValue) }
    LaunchedEffect(state.pendingActions) {
        if (state.pendingActions.isNotEmpty()) onRunActions(state.pendingActions)
    }

    Column(Modifier.fillMaxSize().background(AuraTokens.Void)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Aura Agent", color = AuraTokens.Paper, fontFamily = InstrumentSerif, fontSize = 30.sp)
            Text("LOCAL MODEL · PRIVATE MEDIA · SAFE APP TOOLS", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (hasMediaAccess) "$itemCount media items" else "Media access is off", color = AuraTokens.PaperMuted, fontFamily = Manrope, fontSize = 10.sp)
                Text("${if (recognitionRunning) "ANALYSIS RUNNING" else "ANALYSIS IDLE"} · ${if (duplicateScanRunning) "SCAN RUNNING" else "SCAN IDLE"}", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp)
            }
            ModelStatusRow(
                name = AgentModelCatalog.LOCAL_LLM.displayName,
                size = AgentModelCatalog.LOCAL_LLM.approximateSizeLabel,
                status = state.llmDownload.status,
                progress = state.llmDownload.progress,
                error = state.llmDownload.error,
                ready = state.isModelReady,
                loading = state.isLoadingModel,
                actionLabel = if (state.llmDownload.status == "Failed" || state.llmDownload.status == "Cancelled") "RETRY" else "DOWNLOAD",
                onAction = onDownloadTextModel,
            )
            if (!state.isModelReady && !state.isLoadingModel) {
                Text("The text model downloads once to Aura's private storage. Prompts, images and edits stay on the device.", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 9.sp, lineHeight = 13.sp)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            state.messages.forEach { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.fillMaxWidth(if (message.fromUser) 0.9f else 0.97f)
                            .background(if (message.fromUser) AuraTokens.Surface2 else AuraTokens.Surface1, RoundedCornerShape(12.dp))
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                    ) {
                        Text(if (message.fromUser) "YOU" else "AURA · ON DEVICE", color = if (message.fromUser) AuraTokens.Safelight else AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.7.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(message.text, color = AuraTokens.Paper, fontFamily = Manrope, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            if (state.isThinking || state.isLoadingModel) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp, color = AuraTokens.Safelight)
                    Text(if (state.isLoadingModel) "Loading local model…" else "Thinking on this device…", color = AuraTokens.PaperFaint, fontFamily = Manrope, fontSize = 10.sp)
                }
            }
            state.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontFamily = Manrope, fontSize = 10.sp) }
            Spacer(Modifier.height(4.dp))
        }

        Column(Modifier.fillMaxWidth().background(AuraTokens.Surface1).padding(horizontal = 13.dp, vertical = 8.dp)) {
            Text("TRY A LOCAL ACTION", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.8.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
                AgentQuickCommand("AI erase") { submit("Open my latest photo for AI erase and text removal") }
                AgentQuickCommand("Continue shots") { submit("Find continuation shots in my library") }
                AgentQuickCommand("Favorites") { submit("Open my favorites") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1_000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.isModelReady) "Ask Aura to edit or operate your gallery…" else "Download the model to chat…", fontSize = 10.sp) },
                    singleLine = true,
                    enabled = state.isModelReady && !state.isThinking,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AuraTokens.Paper,
                        unfocusedTextColor = AuraTokens.Paper,
                        focusedBorderColor = AuraTokens.Safelight,
                        unfocusedBorderColor = AuraTokens.Hairline,
                        cursorColor = AuraTokens.Safelight,
                    ),
                )
                Button(
                    onClick = { submit(draft) },
                    enabled = state.isModelReady && !state.isThinking && draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AuraTokens.Safelight, contentColor = AuraTokens.Void, disabledContainerColor = AuraTokens.Surface3, disabledContentColor = AuraTokens.PaperFaint),
                ) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send to local model") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${state.installedSkills} learned skill(s)", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp)
                androidx.compose.material3.TextButton(onClick = { skillPicker.launch(arrayOf("text/markdown", "text/plain", "text/*")) }) {
                    Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = AuraTokens.Safelight, modifier = Modifier.padding(end = 4.dp))
                    Text("LEARN SKILL", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 8.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("IMAGE MODEL · LAMA", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp)
                TextButton(onClick = onDownloadInpaintingModel, enabled = state.inpaintDownload.status != "Downloading" && state.inpaintDownload.status != "Downloaded") {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = AuraTokens.Safelight, modifier = Modifier.padding(end = 4.dp))
                    Text(if (state.inpaintDownload.status == "Downloaded") "READY" else "${state.inpaintDownload.status.uppercase()}", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 8.sp)
                }
            }
            if (state.inpaintDownload.status == "Downloading") LinearProgressIndicator(progress = { state.inpaintDownload.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = AuraTokens.Safelight, trackColor = AuraTokens.Surface3)
            state.inpaintDownload.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 9.sp) }
        }
    }
}

@Composable
private fun ModelStatusRow(
    name: String,
    size: String,
    status: String,
    progress: Int,
    error: String?,
    ready: Boolean,
    loading: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = AuraTokens.Safelight)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(name, color = AuraTokens.Paper, fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Text("$size · ${if (ready) "LOCAL MODEL READY" else if (loading) "LOADING MODEL" else status.uppercase()}", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp)
            if (status == "Downloading") LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = AuraTokens.Safelight, trackColor = AuraTokens.Surface3)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (!ready && !loading) androidx.compose.material3.TextButton(onClick = onAction) {
            Text(actionLabel, color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 8.sp)
        }
    }
}

@Composable
private fun AgentQuickCommand(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label, fontFamily = Manrope, fontSize = 9.sp) })
}

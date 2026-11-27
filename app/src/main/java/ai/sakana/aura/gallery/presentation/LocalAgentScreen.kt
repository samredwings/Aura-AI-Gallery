package ai.sakana.aura.gallery.presentation

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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.sakana.aura.gallery.design.AuraTokens
import ai.sakana.aura.gallery.design.InstrumentSerif
import ai.sakana.aura.gallery.design.JetBrainsMono
import ai.sakana.aura.gallery.design.Manrope

private data class AgentChatMessage(val fromUser: Boolean, val text: String)

@Composable
internal fun LocalAgentScreen(
    itemCount: Int,
    hasMediaAccess: Boolean,
    recognitionRunning: Boolean,
    duplicateScanRunning: Boolean,
    onCommand: (LocalAgentCommand) -> String,
) {
    var draft by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            AgentChatMessage(
                fromUser = false,
                text = "I’m Aura’s local agent. I can navigate your gallery, refresh its on-device index, run scene/People analysis, and review duplicate candidates. Your media and commands stay on this device.",
            ),
        )
    }
    val scrollState = rememberScrollState()

    fun submit(raw: String) {
        val commandText = raw.trim()
        if (commandText.isEmpty()) return
        messages += AgentChatMessage(fromUser = true, text = commandText)
        messages += AgentChatMessage(fromUser = false, text = onCommand(LocalAgentCommandParser.parse(commandText)))
        draft = ""
    }

    LaunchedEffect(messages.size) { scrollState.animateScrollTo(scrollState.maxValue) }

    Column(Modifier.fillMaxSize().background(AuraTokens.Void)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("Aura Agent", color = AuraTokens.Paper, fontFamily = InstrumentSerif, fontSize = 30.sp)
            Text("LOCAL CONTROL · ON-DEVICE ONLY", color = AuraTokens.Safelight, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (hasMediaAccess) "$itemCount media items available" else "Media access is off",
                color = AuraTokens.PaperMuted,
                fontFamily = Manrope,
                fontSize = 11.sp,
            )
            Text(
                "${if (recognitionRunning) "Analysis running" else "Analysis idle"}  ·  ${if (duplicateScanRunning) "Duplicate scan running" else "Duplicate scan idle"}",
                color = AuraTokens.PaperFaint,
                fontFamily = JetBrainsMono,
                fontSize = 9.sp,
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            messages.forEach { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
                ) {
                    Column(
                        Modifier.fillMaxWidth(if (message.fromUser) 0.88f else 0.96f)
                            .background(
                                color = if (message.fromUser) AuraTokens.Surface2 else AuraTokens.Surface1,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                    ) {
                        Text(
                            if (message.fromUser) "YOU" else "AURA · LOCAL",
                            color = if (message.fromUser) AuraTokens.Safelight else AuraTokens.PaperFaint,
                            fontFamily = JetBrainsMono,
                            fontSize = 8.sp,
                            letterSpacing = 0.7.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(message.text, color = AuraTokens.Paper, fontFamily = Manrope, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Column(Modifier.fillMaxWidth().background(AuraTokens.Surface1).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("QUICK COMMANDS", color = AuraTokens.PaperFaint, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.8.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                AgentQuickCommand("Favorites") { submit("Open favorites") }
                AgentQuickCommand("Analyze") { submit("Analyze my library") }
                AgentQuickCommand("Duplicates") { submit("Find exact duplicates") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(160) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Tell Aura what to open or scan…", fontSize = 11.sp) },
                    singleLine = true,
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
                    enabled = draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AuraTokens.Safelight,
                        contentColor = AuraTokens.Void,
                        disabledContainerColor = AuraTokens.Surface3,
                        disabledContentColor = AuraTokens.PaperFaint,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Run command")
                }
            }
        }
    }
}

@Composable
private fun AgentQuickCommand(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontFamily = Manrope, fontSize = 10.sp) },
    )
}

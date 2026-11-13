package io.github.lordofpolls.shellwave.feature.session

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Multi-line prompt composer for drafting commands or natural-language prompts
 * for AI CLI agents (like antigravity-cli or opencode) using voice dictation,
 * on-device Gemini Nano structuring (Pixel 9), and mobile keyboard conveniences.
 *
 * Compactly sized with a scrollable body and `.imePadding()` so the Send/Cancel
 * action row stays visible above the soft keyboard while typing.
 */
@Composable
internal fun PromptComposerDialog(
    initialText: String = "",
    onSend: (prompt: String, sendEnter: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(initialText) }
    var sendEnter by remember { mutableStateOf(true) }
    var isListening by remember { mutableStateOf(false) }
    var isPolishing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var cliFlavor by remember { mutableStateOf(AgentCliPreferences.getFlavor(context)) }

    fun selectFlavor(flavor: AgentCliFlavor) {
        cliFlavor = flavor
        AgentCliPreferences.setFlavor(context, flavor)
    }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(speechRecognizer) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            statusMessage = "Speech recognition unavailable on this device"
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        val baseText = text
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                statusMessage = "Listening…"
            }

            override fun onBeginningOfSpeech() {
                isListening = true
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
                statusMessage = null
            }

            override fun onError(error: Int) {
                isListening = false
                statusMessage = null
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull() ?: ""
                if (spoken.isNotBlank()) {
                    val combined = if (baseText.isBlank()) spoken else "$baseText $spoken"
                    text = PromptRefiner.applyVoiceMacros(combined)
                }
                isListening = false
                statusMessage = null
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull() ?: ""
                if (spoken.isNotBlank()) {
                    val combined = if (baseText.isBlank()) spoken else "$baseText $spoken"
                    text = PromptRefiner.applyVoiceMacros(combined)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
        isListening = true
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        statusMessage = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
        } else {
            statusMessage = "Microphone permission is required for voice dictation"
        }
    }

    fun toggleVoice() {
        if (isListening) {
            stopListening()
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun polish() {
        if (text.isBlank() || isPolishing) return
        isPolishing = true
        statusMessage = "Refining prompt…"
        scope.launch {
            try {
                val result = PromptRefiner.refinePrompt(text) { progress ->
                    statusMessage = progress
                }
                text = result.text
                statusMessage = result.statusNotice
            } finally {
                isPolishing = false
            }
        }
    }

    val aiShortcuts = cliFlavor.slashCommands + listOf(
        "agy",
        "agy -c",
        "opencode",
    )

    val aiTemplates = listOf(
        "Fix errors" to "Please review the errors above and fix them.",
        "Proceed" to "Looks good, please proceed.",
        "Explain" to "Please explain this code and your approach in detail.",
        "Run tests" to "Please run unit tests to verify the changes.",
        "Add comments" to "Please add clear comments explaining the logic.",
        "Refactor" to "Please refactor this for clarity and performance.",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .imePadding()
            .padding(vertical = 8.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Prompt Composer",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AgentCliTogglePill(
                    selected = cliFlavor,
                    onSelect = { selectFlavor(it) },
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Agent prompt / instruction") },
                    placeholder = { Text("Speak or type prompt…") },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp, max = 116.dp),
                )

                // Voice dictation, paste clip & Gemini Nano polish controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(
                            onClick = { toggleVoice() },
                            modifier = Modifier.size(34.dp),
                            colors = if (isListening) {
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else {
                                IconButtonDefaults.filledTonalIconButtonColors()
                            },
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Filled.Mic else Icons.Outlined.Mic,
                                contentDescription = if (isListening) "Stop voice input" else "Start voice input",
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        if (clipboardManager.hasText()) {
                            CompactActionPill(
                                label = "Paste",
                                onClick = {
                                    val clip = clipboardManager.getText()?.text ?: ""
                                    if (clip.isNotBlank()) {
                                        text = if (text.isBlank()) clip else "$text\n$clip"
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.ContentPaste,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                            )
                        }
                    }

                    val polishEnabled = text.isNotBlank() && !isPolishing
                    CompactActionPill(
                        label = if (isPolishing) "Refining…" else "Nano Polish",
                        enabled = polishEnabled,
                        onClick = { polish() },
                        leadingIcon = {
                            if (isPolishing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (polishEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    },
                                )
                            }
                        },
                    )
                }

                statusMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("unavailable") || it.contains("AICore")) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }

                // Vibe prompt template chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    aiTemplates.forEach { (label, templateText) ->
                        CompactComposerChip(
                            label = label,
                            onClick = {
                                text = if (text.isBlank()) {
                                    templateText
                                } else {
                                    "$text\n$templateText"
                                }
                            },
                        )
                    }
                }

                // Quick insertion chips for AI CLI workflows
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    aiShortcuts.forEach { shortcut ->
                        CompactComposerChip(
                            label = shortcut,
                            isMonospace = true,
                            onClick = {
                                text = if (text.isEmpty()) {
                                    "$shortcut "
                                } else if (text.endsWith(" ") || text.endsWith("\n")) {
                                    "$text$shortcut "
                                } else {
                                    "$text $shortcut "
                                }
                            },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .toggleable(
                            value = sendEnter,
                            role = Role.Checkbox,
                            onValueChange = { sendEnter = it },
                        ),
                ) {
                    Checkbox(
                        checked = sendEnter,
                        onCheckedChange = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Execute immediately (send Enter)",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSend(text, sendEnter)
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            Row {
                if (text.isNotEmpty()) {
                    TextButton(onClick = { text = "" }) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun CompactActionPill(
    label: String,
    onClick: () -> Unit,
    leadingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
        },
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 0.5f else 0.25f),
        ),
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon()
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CompactComposerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (isMonospace) FontFamily.Monospace else null,
                fontWeight = if (isMonospace) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

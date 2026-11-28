package io.github.lordofpolls.shellwave.feature.session

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.lordofpolls.shellwave.core.prefs.AgentCli
import io.github.lordofpolls.shellwave.core.prefs.AgentCliPreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import kotlinx.coroutines.launch

private enum class ComposerInsertTab { TEMPLATES, COMMANDS }
private enum class ComposerUploadKind { IMAGE, FILE }

private val COMPOSER_TEMPLATES = listOf(
    "Fix errors" to "Please review the errors above and fix them.",
    "Proceed" to "Looks good, please proceed.",
    "Explain" to "Please explain this code and your approach in detail.",
    "Run tests" to "Please run unit tests to verify the changes.",
    "Add comments" to "Please add clear comments explaining the logic.",
    "Refactor" to "Please refactor this for clarity and performance.",
)

/**
 * Full-width bottom sheet composer for drafting commands or natural-language prompts
 * for configurable AI coding agent CLIs using voice dictation, on-device Gemini Nano structuring,
 * wrapping quick-insert FlowRows, and a pinned Send/Enter footer above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptComposerDialog(
    onSend: (prompt: String, sendEnter: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
    onUploadImage: (suspend (Uri) -> Result<String>)? = null,
    onUploadFile: (suspend (Uri) -> Result<String>)? = null,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var text by remember { mutableStateOf(initialText) }
    var preTransformText by remember { mutableStateOf<String?>(null) }
    var sendEnter by remember { mutableStateOf(true) }
    var isListening by remember { mutableStateOf(false) }
    var isPolishing by remember { mutableStateOf(false) }
    var isGeneratingCmd by remember { mutableStateOf(false) }
    var uploadingKind by remember { mutableStateOf<ComposerUploadKind?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var selectedInsertTab by remember { mutableStateOf(ComposerInsertTab.TEMPLATES) }
    var insertsExpanded by remember { mutableStateOf(true) }

    val enabledAgents by rememberPrefState { AgentCliPreferences.getEnabledAgents(it) }
    val activeAgent by rememberPrefState { AgentCliPreferences.getSelectedAgent(it) }

    LaunchedEffect(Unit) {
        NanoClientManager.warmupIfEnabled(context)
    }

    fun selectAgent(agent: AgentCli) {
        AgentCliPreferences.setSelectedAgent(context, agent)
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
                    text = PromptRefiner.applyVoiceMacros(combined, activeAgent)
                }
                isListening = false
                statusMessage = null
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull() ?: ""
                if (spoken.isNotBlank()) {
                    val combined = if (baseText.isBlank()) spoken else "$baseText $spoken"
                    text = PromptRefiner.applyVoiceMacros(combined, activeAgent)
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
        ActivityResultContracts.RequestPermission(),
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
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                startListening()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun appendRemotePath(remotePath: String) {
        text = when {
            text.isBlank() -> remotePath
            text.endsWith(" ") || text.endsWith("\n") -> "$text$remotePath"
            else -> "$text $remotePath"
        }
    }

    fun launchUpload(
        kind: ComposerUploadKind,
        uri: Uri?,
        uploader: (suspend (Uri) -> Result<String>)?,
    ) {
        if (uri == null || uploader == null || uploadingKind != null) return
        val kindLabel = if (kind == ComposerUploadKind.IMAGE) "image" else "file"
        val kindTitle = if (kind == ComposerUploadKind.IMAGE) "Image" else "File"
        uploadingKind = kind
        statusMessage = "Uploading $kindLabel to remote host…"
        scope.launch {
            try {
                uploader(uri).fold(
                    onSuccess = { remotePath ->
                        appendRemotePath(remotePath)
                        statusMessage = "Attached $remotePath"
                    },
                    onFailure = { err ->
                        statusMessage = "$kindTitle upload failed: ${err.message ?: "SFTP error"}"
                    },
                )
            } finally {
                uploadingKind = null
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        launchUpload(ComposerUploadKind.IMAGE, uri, onUploadImage)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        launchUpload(ComposerUploadKind.FILE, uri, onUploadFile)
    }

    fun polish() {
        if (text.isBlank() || isPolishing || isGeneratingCmd) return
        val original = text
        isPolishing = true
        statusMessage = "Refining prompt…"
        scope.launch {
            try {
                val result = PromptRefiner.refinePrompt(
                    rawInput = original,
                    activeAgent = activeAgent,
                    context = context,
                    onProgress = { progress -> statusMessage = progress },
                    onPartialText = null,
                )
                if (result.text != original) {
                    preTransformText = original
                    text = result.text
                }
                statusMessage = result.statusNotice
                    ?: if (result.text == original) "Prompt already looks clean" else null
            } finally {
                isPolishing = false
            }
        }
    }

    fun generateShellCmd() {
        if (text.isBlank() || isPolishing || isGeneratingCmd) return
        val original = text
        isGeneratingCmd = true
        statusMessage = "Translating to shell command…"
        scope.launch {
            try {
                val result = PromptRefiner.generateShellCommand(
                    rawInput = original,
                    context = context,
                    onProgress = { progress -> statusMessage = progress },
                    onPartialText = null,
                )
                if (result.text != original) {
                    preTransformText = original
                    text = result.text
                }
                statusMessage = result.statusNotice
                    ?: if (result.text == original) "No command change" else null
            } finally {
                isGeneratingCmd = false
            }
        }
    }

    val aiShortcuts = activeAgent?.let { agent ->
        (agent.slashCommands + enabledAgents.flatMap { enabled ->
            enabled.launchCommands.map { cmd -> cmd.command }
        }).distinct()
    }.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { AgentSheetDragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            AgentSheetHeader(
                icon = Icons.Outlined.EditNote,
                title = "Prompt Composer",
                enabledAgents = enabledAgents,
                activeAgent = activeAgent,
                onSelectAgent = ::selectAgent,
                onDismiss = onDismiss,
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Agent prompt / instruction") },
                    placeholder = { Text("Speak or type your prompt…") },
                    shape = AGENT_CARD_SHAPE,
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 168.dp),
                )

                ComposerActionBar(
                    text = text,
                    preTransformText = preTransformText,
                    hasClipboardText = clipboardManager.hasText(),
                    hasImageUpload = onUploadImage != null,
                    hasFileUpload = onUploadFile != null,
                    isListening = isListening,
                    isPolishing = isPolishing,
                    isGeneratingCmd = isGeneratingCmd,
                    uploadingKind = uploadingKind,
                    onToggleVoice = ::toggleVoice,
                    onPolish = ::polish,
                    onGenerateShellCmd = ::generateShellCmd,
                    onPaste = {
                        val clip = clipboardManager.getText()?.text ?: ""
                        if (clip.isNotBlank()) {
                            text = if (text.isBlank()) clip else "$text\n$clip"
                        }
                    },
                    onPickImage = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPickFile = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onUndo = {
                        preTransformText?.let { priorText ->
                            text = priorText
                            preTransformText = null
                            statusMessage = "Restored previous text"
                        }
                    },
                    onClear = {
                        text = ""
                        preTransformText = null
                        statusMessage = null
                    },
                )

                statusMessage?.let { message ->
                    val isNoticeError = message.contains("unavailable", ignoreCase = true) ||
                        message.contains("AICore", ignoreCase = true) ||
                        message.contains("filtered", ignoreCase = true) ||
                        message.contains("failed", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isNoticeError) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isNoticeError) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }

                ComposerQuickInsertPanel(
                    selectedTab = if (aiShortcuts.isEmpty()) ComposerInsertTab.TEMPLATES else selectedInsertTab,
                    expanded = insertsExpanded,
                    activeAgent = activeAgent,
                    aiShortcuts = aiShortcuts,
                    onSelectTab = { tab ->
                        selectedInsertTab = tab
                        insertsExpanded = true
                    },
                    onToggleExpanded = { insertsExpanded = !insertsExpanded },
                    onInsertTemplate = { templateText ->
                        text = if (text.isBlank()) templateText else "$text\n$templateText"
                    },
                    onInsertShortcut = { shortcut ->
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

            AgentSheetFooter(
                checkboxLabel = "Send Enter",
                checked = sendEnter,
                onCheckedChange = { sendEnter = it },
                actionContent = {
                    Button(
                        onClick = {
                            onSend(text, sendEnter)
                            onDismiss()
                        },
                        enabled = text.isNotBlank(),
                        shape = AGENT_BUTTON_SHAPE,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Send")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerActionBar(
    text: String,
    preTransformText: String?,
    hasClipboardText: Boolean,
    hasImageUpload: Boolean,
    hasFileUpload: Boolean,
    isListening: Boolean,
    isPolishing: Boolean,
    isGeneratingCmd: Boolean,
    uploadingKind: ComposerUploadKind?,
    onToggleVoice: () -> Unit,
    onPolish: () -> Unit,
    onGenerateShellCmd: () -> Unit,
    onPaste: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val aiEnabled = text.isNotBlank() && !isPolishing && !isGeneratingCmd
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onToggleVoice,
                shape = AGENT_BUTTON_SHAPE,
                modifier = Modifier.size(36.dp),
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

            FilledTonalButton(
                onClick = onPolish,
                enabled = aiEnabled,
                shape = AGENT_BUTTON_SHAPE,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                if (isPolishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isPolishing) "Refining…" else "Nano Polish",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = onGenerateShellCmd,
                enabled = aiEnabled,
                shape = AGENT_BUTTON_SHAPE,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                if (isGeneratingCmd) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isGeneratingCmd) "Command…" else "Shell cmd",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val showUtilities = hasClipboardText || hasImageUpload || hasFileUpload || preTransformText != null || text.isNotEmpty()
        if (showUtilities) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (hasClipboardText) {
                    AgentUtilityPill(
                        label = "Paste",
                        onClick = onPaste,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }

                if (hasImageUpload) {
                    UploadUtilityPill(
                        label = "Image",
                        isUploading = uploadingKind == ComposerUploadKind.IMAGE,
                        enabled = uploadingKind == null,
                        icon = Icons.Outlined.Image,
                        onClick = onPickImage,
                    )
                }

                if (hasFileUpload) {
                    UploadUtilityPill(
                        label = "File",
                        isUploading = uploadingKind == ComposerUploadKind.FILE,
                        enabled = uploadingKind == null,
                        icon = Icons.Outlined.AttachFile,
                        onClick = onPickFile,
                    )
                }

                if (preTransformText != null) {
                    AgentUtilityPill(
                        label = "Undo",
                        highlighted = true,
                        onClick = onUndo,
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Outlined.Undo,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }

                if (text.isNotEmpty()) {
                    AgentUtilityPill(
                        label = "Clear",
                        onClick = onClear,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadUtilityPill(
    label: String,
    isUploading: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    AgentUtilityPill(
        label = if (isUploading) "Uploading…" else label,
        enabled = enabled,
        onClick = onClick,
        leadingIcon = {
            if (isUploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerQuickInsertPanel(
    selectedTab: ComposerInsertTab,
    expanded: Boolean,
    activeAgent: AgentCli?,
    aiShortcuts: List<String>,
    onSelectTab: (ComposerInsertTab) -> Unit,
    onToggleExpanded: () -> Unit,
    onInsertTemplate: (String) -> Unit,
    onInsertShortcut: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgentTabPill(
                label = "Templates",
                selected = selectedTab == ComposerInsertTab.TEMPLATES,
                onClick = { onSelectTab(ComposerInsertTab.TEMPLATES) },
            )
            if (aiShortcuts.isNotEmpty()) {
                val cmdLabel = activeAgent?.shortName?.let { "/ $it" } ?: "/ Commands"
                AgentTabPill(
                    label = cmdLabel,
                    selected = selectedTab == ComposerInsertTab.COMMANDS,
                    onClick = { onSelectTab(ComposerInsertTab.COMMANDS) },
                    isMonospace = true,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse quick inserts" else "Expand quick inserts",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            when (selectedTab) {
                ComposerInsertTab.TEMPLATES -> {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        COMPOSER_TEMPLATES.forEach { (label, templateText) ->
                            AgentChip(
                                label = label,
                                onClick = { onInsertTemplate(templateText) },
                            )
                        }
                    }
                }
                ComposerInsertTab.COMMANDS -> {
                    val highlighted = activeAgent?.highlightedCommands.orEmpty()
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        aiShortcuts.forEach { shortcut ->
                            AgentChip(
                                label = shortcut,
                                isMonospace = true,
                                highlighted = shortcut in highlighted,
                                onClick = { onInsertShortcut(shortcut) },
                            )
                        }
                    }
                }
            }
        }
    }
}

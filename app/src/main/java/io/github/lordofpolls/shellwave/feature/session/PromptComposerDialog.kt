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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.lordofpolls.shellwave.core.prefs.AgentCliPreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import kotlinx.coroutines.launch

private enum class ComposerUploadKind { IMAGE, FILE }

/**
 * Compact, scrollable, and expandable inline composer docked inside the KeyBar bay when in Agent
 * Deck so the terminal above stays live and readable while drafting longer replies.
 */
@Composable
internal fun InlinePromptComposer(
    text: String,
    onTextChange: (String) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    focusTrigger: Int,
    voiceToggleTrigger: Int,
    onVoiceListeningChange: (Boolean) -> Unit,
    onSend: (prompt: String, sendEnter: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onUploadImage: (suspend (Uri) -> Result<String>)? = null,
    onUploadFile: (suspend (Uri) -> Result<String>)? = null,
    showRawTypingTip: Boolean = false,
    onDismissRawTypingTip: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var preTransformText by remember { mutableStateOf<String?>(null) }
    var sendEnter by remember { mutableStateOf(true) }
    var isListening by remember { mutableStateOf(false) }
    var isPolishing by remember { mutableStateOf(false) }
    var isGeneratingCmd by remember { mutableStateOf(false) }
    var uploadingKind by remember { mutableStateOf<ComposerUploadKind?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val activeAgent by rememberPrefState { AgentCliPreferences.getSelectedAgent(it) }

    LaunchedEffect(Unit) {
        NanoClientManager.warmupIfEnabled(context)
    }

    LaunchedEffect(focusTrigger) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    LaunchedEffect(isListening) {
        onVoiceListeningChange(isListening)
    }

    DisposableEffect(Unit) {
        onDispose { onVoiceListeningChange(false) }
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
                    onTextChange(PromptRefiner.applyVoiceMacros(combined, activeAgent))
                }
                isListening = false
                statusMessage = null
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull() ?: ""
                if (spoken.isNotBlank()) {
                    val combined = if (baseText.isBlank()) spoken else "$baseText $spoken"
                    onTextChange(PromptRefiner.applyVoiceMacros(combined, activeAgent))
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

    LaunchedEffect(voiceToggleTrigger) {
        if (voiceToggleTrigger > 0) {
            toggleVoice()
        }
    }

    fun appendRemotePath(remotePath: String) {
        val updated = when {
            text.isBlank() -> remotePath
            text.endsWith(" ") || text.endsWith("\n") -> "$text$remotePath"
            else -> "$text $remotePath"
        }
        onTextChange(updated)
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
        statusMessage = "Uploading $kindLabel…"
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
                    onTextChange(result.text)
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
                    onTextChange(result.text)
                }
                statusMessage = result.statusNotice
                    ?: if (result.text == original) "No command change" else null
            } finally {
                isGeneratingCmd = false
            }
        }
    }

    val windowHeightDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val maxFieldHeight = if (expanded) {
        (windowHeightDp * 0.42f).coerceIn(180.dp, 340.dp)
    } else {
        140.dp
    }
    val textScrollState = rememberScrollState()
    val aiEnabled = text.isNotBlank() && !isPolishing && !isGeneratingCmd

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp, max = maxFieldHeight)
                    .verticalScroll(textScrollState)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                text = "Message agent or draft a command…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    },
                )
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (showRawTypingTip && statusMessage == null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Tip: Tap ⇄ on the bar (or hold ⌨) for raw terminal typing",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (onDismissRawTypingTip != null) {
                            IconButton(
                                onClick = onDismissRawTypingTip,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Dismiss tip",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerToolIconButton(
                        icon = if (expanded) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                        contentDescription = if (expanded) "Collapse editor" else "Expand editor",
                        onClick = onToggleExpanded,
                    )

                    if (onUploadImage != null) {
                        ComposerToolIconButton(
                            icon = Icons.Outlined.Image,
                            contentDescription = "Upload image",
                            busy = uploadingKind == ComposerUploadKind.IMAGE,
                            enabled = uploadingKind == null,
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                    }

                    if (onUploadFile != null) {
                        ComposerToolIconButton(
                            icon = Icons.Outlined.AttachFile,
                            contentDescription = "Upload file",
                            busy = uploadingKind == ComposerUploadKind.FILE,
                            enabled = uploadingKind == null,
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        )
                    }

                    if (text.isNotBlank()) {
                        ComposerToolIconButton(
                            icon = Icons.Outlined.AutoAwesome,
                            contentDescription = "Nano polish prompt",
                            busy = isPolishing,
                            enabled = aiEnabled,
                            onClick = ::polish,
                        )
                        ComposerToolIconButton(
                            icon = Icons.Outlined.Terminal,
                            contentDescription = "Translate to shell command",
                            busy = isGeneratingCmd,
                            enabled = aiEnabled,
                            onClick = ::generateShellCmd,
                        )
                    }

                    if (preTransformText != null) {
                        ComposerToolIconButton(
                            icon = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = "Undo AI edit",
                            tint = MaterialTheme.colorScheme.primary,
                            onClick = {
                                preTransformText?.let { priorText ->
                                    onTextChange(priorText)
                                    preTransformText = null
                                    statusMessage = "Restored previous text"
                                }
                            },
                        )
                    }

                    if (text.isNotBlank()) {
                        ComposerToolIconButton(
                            icon = Icons.Outlined.DeleteOutline,
                            contentDescription = "Clear prompt",
                            onClick = {
                                onTextChange("")
                                preTransformText = null
                                statusMessage = null
                            },
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close composer",
                        modifier = Modifier.size(18.dp),
                    )
                }

                FilledTonalIconButton(
                    onClick = { sendEnter = !sendEnter },
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = if (sendEnter) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardReturn,
                        contentDescription = if (sendEnter) "Send with Enter enabled" else "Paste without Enter",
                        modifier = Modifier.size(16.dp),
                    )
                }

                FilledTonalIconButton(
                    onClick = { onSend(text, sendEnter) },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send prompt",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    busy: Boolean = false,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.size(32.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Fallback modal sheet wrapper retained for standalone entry points (e.g. tablet master-detail
 * Git diff review) using the lean [InlinePromptComposer] surface.
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(initialText) }
    var expanded by remember { mutableStateOf(false) }
    val focusTrigger by remember { mutableIntStateOf(1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { AgentSheetDragHandle() },
        modifier = modifier,
    ) {
        InlinePromptComposer(
            text = text,
            onTextChange = { text = it },
            expanded = expanded,
            onToggleExpanded = { expanded = !expanded },
            focusTrigger = focusTrigger,
            voiceToggleTrigger = 0,
            onVoiceListeningChange = {},
            onSend = { prompt, enter ->
                onSend(prompt, enter)
                onDismiss()
            },
            onClose = onDismiss,
            onUploadImage = onUploadImage,
            onUploadFile = onUploadFile,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding()
                .navigationBarsPadding(),
        )
    }
}

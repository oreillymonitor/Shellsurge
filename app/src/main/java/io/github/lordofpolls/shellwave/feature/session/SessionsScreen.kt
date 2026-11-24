package io.github.lordofpolls.shellwave.feature.session

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import io.github.lordofpolls.shellwave.core.db.dao.KeyBarLayoutDao
import io.github.lordofpolls.shellwave.core.db.dao.TerminalProfileDao
import io.github.lordofpolls.shellwave.core.db.entities.HostEntity
import io.github.lordofpolls.shellwave.core.db.entities.KeyBarLayoutEntity
import io.github.lordofpolls.shellwave.core.db.entities.ScriptEntity
import io.github.lordofpolls.shellwave.core.db.entities.TerminalProfileEntity
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.core.prefs.NotificationPreferences
import io.github.lordofpolls.shellwave.core.prefs.SessionLayoutPreferences
import io.github.lordofpolls.shellwave.core.prefs.TerminalInputPreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import io.github.lordofpolls.shellwave.core.ui.FoldPosture
import io.github.lordofpolls.shellwave.core.ui.rememberFoldPosture
import io.github.lordofpolls.shellwave.ssh.SessionManager
import io.github.lordofpolls.shellwave.ssh.SessionStatus
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ssh.TerminalSize
import io.github.lordofpolls.shellwave.terminal.DEFAULT_KEY_BAR_KEYS
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_PROFILE
import io.github.lordofpolls.shellwave.terminal.DEFAULT_TERMINAL_TEXT_SIZE_SP
import io.github.lordofpolls.shellwave.terminal.FONT_SIZE_SP_RANGE
import io.github.lordofpolls.shellwave.terminal.KeyBar
import io.github.lordofpolls.shellwave.terminal.LinkType
import io.github.lordofpolls.shellwave.terminal.TerminalCanvas
import io.github.lordofpolls.shellwave.terminal.TerminalFontFamily
import io.github.lordofpolls.shellwave.terminal.TerminalInputCapture
import io.github.lordofpolls.shellwave.terminal.TerminalLink
import io.github.lordofpolls.shellwave.terminal.TerminalMatch
import io.github.lordofpolls.shellwave.terminal.TerminalSelectionOverlay
import io.github.lordofpolls.shellwave.terminal.collectTerminalRows
import io.github.lordofpolls.shellwave.terminal.ctrlCode
import io.github.lordofpolls.shellwave.terminal.decodeKeyBarKeys
import io.github.lordofpolls.shellwave.terminal.externalRowAt
import io.github.lordofpolls.shellwave.terminal.findMatches
import io.github.lordofpolls.shellwave.terminal.KeyboardKey
import io.github.lordofpolls.shellwave.terminal.hardwareKeyCode
import io.github.lordofpolls.shellwave.terminal.shouldHideKeyBar
import io.github.lordofpolls.shellwave.terminal.linkAt
import io.github.lordofpolls.shellwave.terminal.rememberTerminalSelectionState
import io.github.lordofpolls.shellwave.terminal.resolveTerminalTypeface
import io.github.lordofpolls.shellwave.terminal.selectionHighlightColor
import io.github.lordofpolls.shellwave.terminal.dragRows
import io.github.lordofpolls.shellwave.terminal.TerminalScrollbar
import io.github.lordofpolls.shellwave.terminal.TerminalTextSanitizer
import io.github.lordofpolls.shellwave.terminal.terminalGestures
import io.github.lordofpolls.shellwave.terminal.wheelButton
import io.github.lordofpolls.shellwave.ui.design.EmptyState
import io.github.lordofpolls.shellwave.ui.design.MachineText
import io.github.lordofpolls.shellwave.ui.design.SessionChipModel
import io.github.lordofpolls.shellwave.ui.design.SessionChipRail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * N concurrent sessions, with a [ListDetailPaneScaffold] beside the terminal on wide or unfolded
 * windows and a dedicated split for tabletop posture. Session state lives in [sessionManager], so a
 * rotation or a fold can tear this screen down and rebuild it without touching a connection.
 *
 * Switching is by tab. A `HorizontalPager` cannot work: `terminalGestures` claims every touch in
 * the content area on [androidx.compose.ui.input.pointer.PointerEventPass.Initial], so a horizontal
 * swipe there never reaches a pager. Swipe-to-switch would mean reworking that touch ownership on
 * purpose.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SessionsScreen(
    sessionManager: SessionManager,
    // SessionSummary carries a hostId, not the host row. A session whose host can't be resolved
    // still renders, falling back to the host portion of its own identity string.
    hosts: List<HostEntity> = emptyList(),
    onNewSession: () -> Unit,
    initialSessionId: Long? = null,
    // No fixed host: typed into whichever session is focused here.
    sendToCurrentScripts: List<ScriptEntity> = emptyList(),
    // Unfiltered: the filtering that matters is per-session, and only this screen knows the
    // selected session's host. See [runnableHere].
    captureScripts: List<ScriptEntity> = emptyList(),
    onRunScript: (ScriptEntity, SshConnection) -> Unit = { _, _ -> },
    // The session id travels alongside the connection: MainActivity resolves it back to a
    // connection itself once the browser screen is pushed, since a session can close (and its
    // SshConnection with it) while that screen is open.
    onBrowseFiles: (sessionId: Long, connection: SshConnection) -> Unit = { _, _ -> },
    // Null before any profile row has ever been saved, in which case DEFAULT_TERMINAL_PROFILE
    // applies. A host with its own override uses that instead, resolved per-session in
    // `SessionTabBody` - so this default stays live while an override, once resolved, does not.
    terminalProfile: TerminalProfileEntity? = null,
    terminalProfileDao: TerminalProfileDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    modifier: Modifier = Modifier,
    onEditRemoteFile: ((sessionId: Long, path: String) -> Unit)? = null,
) {
    val summaries by sessionManager.summaries.collectAsState()
    if (summaries.isEmpty()) {
        // Only shown for the frame before MainActivity pops the terminal off the stack.
        // Copy matches SessionsListScreen's empty state.
        EmptyState(
            message = "No open sessions.",
            actionLabel = "Open Hosts",
            onAction = onNewSession,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val scope = rememberCoroutineScope()

    // Threaded down so the path-tap dialog's "Download" reaches it too, beyond the menu actions.
    val fileTransferController = rememberFileTransferController()
    val loggingController = rememberSessionLoggingController()

    // Driven by the navigator's own destination; a second piece of state could drift from it.
    // Falls back to the first session once a target is known to exist, so a freshly-opened screen
    // - or one whose selected session just closed - never shows an empty detail pane.
    val selectedId = navigator.currentDestination?.contentKey?.takeIf { id -> summaries.any { it.id == id } }
        ?: summaries.firstOrNull { it.id == initialSessionId }?.id ?: summaries.first().id

    DisposableEffect(selectedId) {
        sessionManager.setForegroundSession(selectedId)
        onDispose { sessionManager.clearForegroundSession(selectedId) }
    }

    // Keyed on the selected session, not shared screen-wide: switching tabs must not leave the
    // bar open over a session that was never searched.
    val searchController = rememberTerminalSearchController(selectedId)

    fun selectSession(id: Long) {
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }
    }

    // Keyed on [initialSessionId] instead of `Unit`: the terminal is not always torn down between
    // two requests, since `openTerminal` is idempotent when it is already on top, so a session
    // opened while one is showing changes this parameter without recomposing the screen from
    // scratch. With `Unit` that request was silently dropped and the previous session stayed put.
    LaunchedEffect(initialSessionId) {
        selectSession(
            summaries.firstOrNull { it.id == initialSessionId }?.id ?: selectedId
        )
    }

    val bellContext = LocalContext.current
    val bellMode by rememberPrefState { BellPreferences.get(it) }

    // WindowInfoTracker reports per-Activity window metrics. Degrades to Flat rather than crashing
    // without one, e.g. in a @Preview.
    val activity = LocalActivity.current
    val posture = if (activity != null) rememberFoldPosture(activity).value else FoldPosture.Flat

    val layoutContext = LocalContext.current
    val fullWidthTerminal by rememberPrefState { SessionLayoutPreferences.getFullWidthTerminal(it) }

    // On a narrow window there is no second pane to collapse, so the full-width toggle must be
    // absent rather than present-but-inert.
    val isWide = navigator.scaffoldDirective.maxHorizontalPartitions >= 2

    Column(modifier = modifier.fillMaxSize()) {
        val current = summaries.firstOrNull { it.id == selectedId }

        // Hoisted above the rail, whose overflow opens it.
        var scriptPickerOpen by remember(current?.id) { mutableStateOf(false) }
        var promptComposerOpen by remember(current?.id) { mutableStateOf(false) }
        var composerInitialText by remember(current?.id) { mutableStateOf("") }
        var quickResponseOpen by remember(current?.id) { mutableStateOf(false) }
        var gitDiffOpen by remember(current?.id) { mutableStateOf(false) }

        // A capture script pinned to another host is left out: "run it here" and "run it on that
        // other machine" are opposite instructions, and honouring the script's own target would run
        // it somewhere the user is not looking. Hostless ones qualify everywhere.
        val runnableHere =
            remember(sendToCurrentScripts, captureScripts, current?.hostId) {
                sendToCurrentScripts + captureScripts.filter { it.targetHostId == null || it.targetHostId == current?.hostId }
            }

        // The rail is the terminal's whole top strip. Three rows died here - a status header
        // restating the rail's own status word, a permanent bell toggle, and always-visible
        // Download/Upload links. One MoreVert replaced them.
        SessionChipRail(
            sessions =
                summaries.map { summary ->
                    SessionChipModel(
                        id = summary.id,
                        name = sessionDisplayName(summary.hostId, summary.label, hosts),
                        status = summary.status,
                        agentForwarding = summary.agentForwarding,
                        turnState = summary.turnState,
                    )
                },
            selectedId = selectedId,
            onSelect = ::selectSession,
            onClose = sessionManager::closeSession,
            onNewSession = onNewSession,
            overflow = {
                // Close is the exception: closing a session that never connected is exactly
                // what a user needs when one is stuck.
                val overflowConnection =
                    current?.connection?.takeIf { current.status == SessionStatus.CONNECTED }
                // current.connection, not the CONNECTED-gated overflowConnection: "Stop logging"
                // must stay reachable through a drop or reconnect, not just while the channel that
                // started it is still up. Memoized per session id, not a fresh fallback flow on
                // every recomposition.
                val loggingFlow = remember(current?.id) {
                    current?.connection?.isLogging ?: MutableStateFlow(false)
                }
                val isLogging by loggingFlow.collectAsState()
                TerminalOverflowMenu(
                    connection = overflowConnection,
                    bellMode = bellMode,
                    onBellMode = { mode -> BellPreferences.set(bellContext, mode) },
                    outputAlertsEnabled = current?.outputAlertsEnabled ?: true,
                    onToggleOutputAlerts = current?.let { s ->
                        { sessionManager.setSessionOutputAlerts(s.id, !s.outputAlertsEnabled) }
                    },
                    onDownload = { connection -> fileTransferController.requestDownload(connection) },
                    onUpload = { connection -> fileTransferController.requestUpload(connection) },
                    onBrowseFiles = { connection ->
                        current?.let { onBrowseFiles(it.id, connection) }
                    },
                    onRunScript = if (runnableHere.isEmpty()) null else ({
                        scriptPickerOpen = true
                    }),
                    onPromptComposer = {
                        composerInitialText = ""
                        promptComposerOpen = true
                    },
                    onAgentQuickResponse = { quickResponseOpen = true },
                    onClose = current?.let { { sessionManager.closeSession(it.id) } },
                    onSearch = searchController::show,
                    isLogging = isLogging,
                    onStartLogging = loggingController::requestStart,
                    onStopLogging = { current?.connection?.stopLogging() },
                    onOpenGitDiff = if (current?.status == SessionStatus.CONNECTED) ({
                        gitDiffOpen = true
                    }) else null,
                )
            },
        )

        current?.takeIf { it.status == SessionStatus.FAILED }?.error?.let { error ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { sessionManager.reconnectNow(current.id) }) { Text("Retry") }
            }
        }

        if (scriptPickerOpen && current != null) {
            ScriptPickerDialog(
                scripts = runnableHere,
                onPick = { script ->
                    scriptPickerOpen = false
                    onRunScript(script, current.connection)
                },
                onDismiss = { scriptPickerOpen = false },
            )
        }

        if (quickResponseOpen && current != null) {
            AgentQuickResponseSheet(
                onSendText = { text, sendEnter ->
                    val conn = current.connection
                    val emulator = conn.emulatorOrNull
                    if (emulator != null && text.isNotEmpty()) {
                        emulator.paste(text)
                    } else if (text.isNotEmpty()) {
                        conn.write(text.replace("\n", "\r"))
                    }
                    if (sendEnter) {
                        conn.write("\r")
                    }
                },
                onSendRaw = { control ->
                    current.connection.write(control)
                },
                onOpenComposerWithText = { initialText ->
                    quickResponseOpen = false
                    composerInitialText = initialText
                    promptComposerOpen = true
                },
                onOpenGitDiff = if (current.status == SessionStatus.CONNECTED) ({
                    quickResponseOpen = false
                    gitDiffOpen = true
                }) else null,
                onDismiss = { quickResponseOpen = false },
            )
        }

        if (gitDiffOpen && current != null) {
            val promptDirHint = current.connection.emulatorOrNull?.let { emulator ->
                extractPromptWorkingDir(TerminalTextSanitizer.extractCleanTail(emulator, maxLines = 10))
            }
            GitDiffSheet(
                connection = current.connection,
                promptDirHint = promptDirHint,
                onAskAgent = { reviewPrompt ->
                    gitDiffOpen = false
                    composerInitialText = reviewPrompt
                    promptComposerOpen = true
                },
                onDismiss = { gitDiffOpen = false },
                onEditFile = onEditRemoteFile?.let { editCb ->
                    { remotePath ->
                        gitDiffOpen = false
                        editCb(current.id, remotePath)
                    }
                },
            )
        }

        if (promptComposerOpen && current != null) {
            PromptComposerDialog(
                initialText = composerInitialText,
                onUploadImage = if (current.status == SessionStatus.CONNECTED) {
                    { uri -> current.connection.uploadImageAttachment(uri) }
                } else {
                    null
                },
                onSend = { prompt, enter ->
                    val conn = current.connection
                    val emulator = conn.emulatorOrNull
                    if (emulator != null) {
                        emulator.paste(prompt)
                    } else {
                        conn.write(prompt.replace("\n", "\r"))
                    }
                    if (enter) {
                        conn.write("\r")
                    }
                },
                onDismiss = {
                    promptComposerOpen = false
                    composerInitialText = ""
                },
            )
        }

        SessionsSplitLayout(
            posture = posture,
            isWide = isWide,
            fullWidthTerminal = fullWidthTerminal,
            onToggleFullWidth = {
                SessionLayoutPreferences.setFullWidthTerminal(layoutContext, !fullWidthTerminal)
            },
            navigator = navigator,
            selectedId = selectedId,
            onSelect = ::selectSession,
            summaries = summaries,
            hosts = hosts,
            sessionManager = sessionManager,
            terminalProfile = terminalProfile,
            terminalProfileDao = terminalProfileDao,
            keyBarLayoutDao = keyBarLayoutDao,
            fileTransferController = fileTransferController,
            searchController = searchController,
            onEditRemoteFile = onEditRemoteFile,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        )
    }

    // Once for the screen: an AlertDialog overlays everything wherever it is composed.
    FileTransferDialogs(fileTransferController)
    SessionLoggingEffects(loggingController)
}

/**
 * One session's terminal and key bar. Reads live state from [sessionManager]; owns nothing that
 * would need saving across rotation.
 *
 * [terminalHeight] and [preKeyBarGap] are tabletop posture's hook: the terminal is sized exactly
 * instead of sharing space via `weight(1f)`, and the gap reserves the hinge's occluded height. The
 * defaults reproduce the ordinary layout.
 */
// WindowInsets.isImeVisible is @ExperimentalLayoutApi - the key bar's keyboard button needs to know
// whether the IME is up to decide between showing and hiding it, and there is no stable equivalent.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionTabBody(
    sessionId: Long,
    sessionManager: SessionManager,
    terminalProfileDao: TerminalProfileDao,
    keyBarLayoutDao: KeyBarLayoutDao,
    fileTransferController: FileTransferController,
    searchController: TerminalSearchController,
    modifier: Modifier = Modifier,
    terminalProfile: TerminalProfileEntity? = null,
    terminalHeight: Dp? = null,
    preKeyBarGap: Dp = 0.dp,
    showLayoutToggle: Boolean = false,
    fullWidthTerminal: Boolean = false,
    onToggleFullWidth: () -> Unit = {},
    onEditRemoteFile: ((sessionId: Long, path: String) -> Unit)? = null,
) {
    val summaries by sessionManager.summaries.collectAsState()
    val summary = summaries.firstOrNull { it.id == sessionId } ?: return
    val focusRequester = remember { FocusRequester() }

    // The keyboard button's two halves live here, not in KeyBar, because this is what owns the
    // focus: TerminalInputCapture's shim is the only focusable thing on the screen, and an IME with
    // nothing focused has nowhere to send what it types. Showing the keyboard is focus then show,
    // in that order - requestFocus() on an already-focused field is a no-op, which is why the
    // button cannot be that call alone.
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    // Once per session and override id rather than as a live Flow: nothing edits a host's override
    // while that host's session is open, unlike the app-wide default.
    var overrideProfile by remember { mutableStateOf<TerminalProfileEntity?>(null) }
    LaunchedEffect(summary.terminalProfileId) {
        overrideProfile = summary.terminalProfileId?.let { terminalProfileDao.getById(it) }
    }
    // Unlike cursor style, blink and scrollback - baked into the connection at connect() time -
    // these touch only this composable's renderer, so they apply live with no reconnect.
    val profileOrDefault = overrideProfile ?: terminalProfile ?: DEFAULT_TERMINAL_PROFILE
    // Only the selection tint is read here; every other colour comes from emulator.mColors, kept in
    // sync by SessionManager.applyDefaultColorScheme.
    val selectionColor = summary.colorScheme.selectionHighlightColor()
    val fontFamily = TerminalFontFamily.fromStored(profileOrDefault.fontFamily)

    var overrideLayout by remember { mutableStateOf<KeyBarLayoutEntity?>(null) }
    LaunchedEffect(summary.keyBarLayoutId) {
        overrideLayout = summary.keyBarLayoutId?.let { keyBarLayoutDao.getById(it) }
    }
    val keyBarKeys = overrideLayout?.let { decodeKeyBarKeys(it.keysJson) } ?: DEFAULT_KEY_BAR_KEYS
    // From the same per-host layout override the keys come from. No override means one row.
    val keyBarRows = overrideLayout?.rows ?: 1
    val localContext = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val terminalTypeface =
        remember(fontFamily, profileOrDefault.customFontUri) {
            resolveTerminalTypeface(localContext, fontFamily, profileOrDefault.customFontUri)
        }
    val lineHeightMultiplier = profileOrDefault.lineHeightMultiplier

    val haptics = LocalHapticFeedback.current
    var isVoiceListening by remember { mutableStateOf(false) }
    var activeDigest by remember { mutableStateOf<AgentDigestResult?>(null) }
    var isAnalyzingDigest by remember { mutableStateOf(false) }
    var streamingDigestText by remember(sessionId) { mutableStateOf<String?>(null) }
    var activePromptSignature by remember(sessionId) { mutableStateOf<String?>(null) }
    var dismissedPromptSignature by remember(sessionId) { mutableStateOf<String?>(null) }
    var tabPromptComposerOpen by remember { mutableStateOf(false) }
    var tabComposerInitialText by remember { mutableStateOf("") }
    var tabGitDiffOpen by remember(sessionId) { mutableStateOf(false) }
    val autoDigestOnReturn by rememberPrefState { NotificationPreferences.isAutoDigestOnReturn(it) }

    var terminalEmulator by remember { mutableStateOf<TerminalEmulator?>(null) }

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            localContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    val voiceEngine = remember {
        AgentVoiceEngine(
            context = localContext,
            onListeningStateChange = { listening -> isVoiceListening = listening },
            onQuickReply = { replyText ->
                val emulator = terminalEmulator
                if (emulator != null && replyText.isNotEmpty()) {
                    emulator.paste(replyText)
                } else if (replyText.isNotEmpty()) {
                    summary.connection.write(replyText)
                }
                summary.connection.write("\r")
                dismissedPromptSignature = activePromptSignature
                activeDigest = null
            },
            onFreeformSpeech = { recognizedText ->
                tabComposerInitialText = recognizedText
                tabPromptComposerOpen = true
                dismissedPromptSignature = activePromptSignature
                activeDigest = null
            },
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceEngine.startListeningWindow(autoTriggered = false)
        }
    }

    DisposableEffect(voiceEngine) {
        onDispose { voiceEngine.release() }
    }

    LaunchedEffect(sessionId, summary.status) {
        if (summary.status == SessionStatus.CONNECTED) {
            NanoClientManager.warmupIfEnabled(localContext)
        }
    }

    fun triggerDigest(speakImmediately: Boolean = false) {
        val emulator = terminalEmulator ?: return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        isAnalyzingDigest = true
        streamingDigestText = null
        activePromptSignature = null
        activeDigest = null
        val tail = TerminalTextSanitizer.extractCleanTail(emulator, maxLines = 100)
        coroutineScope.launch {
            try {
                val result = AgentDigestModel.analyzeTerminalTail(
                    sanitizedOutput = tail,
                    context = localContext,
                    onPartialText = { partial ->
                        coroutineScope.launch {
                            streamingDigestText = partial
                        }
                    },
                )
                activeDigest = result
                if (speakImmediately) {
                    voiceEngine.speak(result.spokenSummary, listenAfter = hasAudioPermission())
                }
            } finally {
                streamingDigestText = null
                isAnalyzingDigest = false
            }
        }
    }
    var measuredSize by remember { mutableStateOf<TerminalSize?>(null) }
    var ctrlLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }
    val keyboardEnhancedInput by rememberPrefState { TerminalInputPreferences.getKeyboardEnhancedInput(it) }
    var tabQuickResponseOpen by remember { mutableStateOf(false) }
    var quickDrawerActive by remember(sessionId) { mutableStateOf(false) }
    val hideKeyBarPref by rememberPrefState { SessionLayoutPreferences.getHideKeyBarWithKeyboard(it) }
    val configuration = LocalConfiguration.current
    val keyBarHidden = shouldHideKeyBar(
        hideKeyBarPref,
        configuration.keyboard,
        configuration.hardKeyboardHidden,
        rememberKeyboardDevices(),
    )
    LaunchedEffect(keyBarHidden) {
        if (keyBarHidden) {
            ctrlLatched = false
            altLatched = false
        }
    }

    // topRow <= 0 is how far into the scrollback the view is; scrollDragRemainderPx carries a
    // drag's sub-cell remainder between events, so slow swipes accumulate to a full row instead of
    // rounding to zero every frame.
    var topRow by remember { mutableStateOf(0) }
    var scrollDragRemainderPx by remember { mutableStateOf(0f) }
    var transcriptRows by remember(sessionId) {
        mutableIntStateOf(summary.connection.emulatorOrNull?.screen?.activeTranscriptRows ?: 0)
    }
    var mouseScrolledLines by remember(sessionId) { mutableIntStateOf(0) }

    var searchQuery by remember(sessionId) { mutableStateOf("") }
    var searchMatches by remember(sessionId) { mutableStateOf(emptyList<TerminalMatch>()) }
    var searchMatchIndex by remember(sessionId) { mutableIntStateOf(0) }

    fun jumpToSearchMatch(index: Int) {
        val matches = searchMatches
        val emulator = terminalEmulator
        if (matches.isEmpty() || emulator == null) return
        val clamped = ((index % matches.size) + matches.size) % matches.size
        searchMatchIndex = clamped
        val maxScrollback = emulator.screen.activeTranscriptRows
        topRow = externalRowAt(emulator, matches[clamped].row).coerceIn(-maxScrollback, 0)
    }

    var fontSizeSp by remember { mutableStateOf(DEFAULT_TERMINAL_TEXT_SIZE_SP) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    // Keyed on both id and fontSizeSp, so adjustments in Settings apply immediately.
    LaunchedEffect(profileOrDefault.id, profileOrDefault.fontSizeSp) {
        fontSizeSp = profileOrDefault.fontSizeSp.sp
    }

    // Coordinates are the external row system, same as topRow.
    val selectionState = rememberTerminalSelectionState()

    var detectedLink by remember { mutableStateOf<TerminalLink?>(null) }

    val redrawRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    val frame = remember { mutableIntStateOf(0) }

    // A fresh query or opening the bar jumps to the first match - the one case where scrolling
    // the view is wanted.
    LaunchedEffect(searchController.open, searchQuery) {
        val emulator = terminalEmulator
        val matches =
            if (searchController.open && emulator != null && searchQuery.isNotEmpty()) {
                findMatches(collectTerminalRows(emulator), searchQuery)
            } else {
                emptyList()
            }
        searchMatches = matches
        if (matches.isNotEmpty()) jumpToSearchMatch(0) else searchMatchIndex = 0
    }

    // Live output re-runs the search sampled - not debounced, for the same reason screenText
    // below is: a build log with no pause would never let debounce emit. Only refreshes the
    // match list, never touches topRow, or output the user isn't scrolled back to look at would
    // keep yanking the view to a stale match. collectTerminalRows touches the emulator and must
    // stay on Main (see SshConnection's threading note); only the string search itself moves off
    // it.
    LaunchedEffect(sessionId) {
        snapshotFlow { frame.intValue }.sample(300).collect {
            if (!searchController.open || searchQuery.isEmpty()) return@collect
            val emulator = terminalEmulator ?: return@collect
            val rows = collectTerminalRows(emulator)
            val matches = withContext(Dispatchers.Default) { findMatches(rows, searchQuery) }
            searchMatches = matches
            searchMatchIndex = searchMatchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(Unit) {
        for (unused in redrawRequests) {
            withFrameNanos { }
            frame.intValue++
        }
    }

    // Rate-limited to one announcement per 400ms so a screen reader isn't flooded per tick - sample
    // over debounce because a build log with no pause would otherwise never let debounce emit.
    var screenText by remember { mutableStateOf("") }
    LaunchedEffect(summary.connection) {
        summary.connection.outputTick.sample(400).collect {
            val emulator = terminalEmulator
            screenText =
                emulator?.screen?.getSelectedText(0, 0, emulator.mColumns, emulator.mRows)
                    ?.trimEnd() ?: ""
        }
    }

    // Auto-digest when returning to a tab whose command/agent finished while away.
    LaunchedEffect(sessionId, summary.turnState, autoDigestOnReturn, terminalEmulator) {
        if (autoDigestOnReturn &&
            summary.status == SessionStatus.CONNECTED &&
            terminalEmulator != null &&
            activeDigest == null &&
            !isAnalyzingDigest &&
            sessionManager.consumePendingAutoDigest(sessionId)
        ) {
            triggerDigest(speakImmediately = false)
        }
    }

    // Ambient AI question & numbered menu prompt detection (e.g. [y/N], (y/n), or 1./2./3. menus)
    LaunchedEffect(summary.connection) {
        summary.connection.outputTick.sample(1000).collect {
            val emulator = terminalEmulator ?: return@collect
            val screen = emulator.screen ?: return@collect
            if (activeDigest != null) return@collect
            val lastRow = emulator.mRows - 1
            val startRow = (lastRow - 11).coerceAtLeast(0)
            val recentText = runCatching {
                screen.getSelectedText(0, startRow, emulator.mColumns, lastRow, true, true)
            }.getOrNull() ?: ""
            val numberedActions = AgentDigestModel.extractNumberedOptions(recentText)
            val yesNoTail = recentText.lines().takeLast(3).joinToString("\n")
            val yesNoRegex = Regex("(?i)(\\[y/n\\]|\\(y/n\\)|\\[yes/no\\]|\\(yes/no\\))")
            val signature = when {
                numberedActions.size >= 2 -> "menu:" + numberedActions.joinToString("|") { it.label }
                yesNoRegex.containsMatchIn(yesNoTail) -> yesNoTail.trim()
                else -> null
            }
            if (signature != null) {
                if (signature != dismissedPromptSignature) {
                    val tail = TerminalTextSanitizer.extractCleanTail(emulator, maxLines = 60)
                    val result = AgentDigestModel.analyzeTerminalTail(tail, context = localContext)
                    activePromptSignature = signature
                    activeDigest = result
                }
            } else {
                dismissedPromptSignature = null
            }
        }
    }

    // Re-runs on a fresh SshConnection identity: first connect, or a reconnect.
    LaunchedEffect(summary.connection) {
        summary.connection.outputTick.collect {
            redrawRequests.trySend(Unit)
            // New output while scrolled back must not yank the view to the bottom, nor silently
            // show stale content. When the live screen scrolls, row 0 in the external coordinate
            // system moves with it, so a fixed topRow would drift to point at different history
            // than the user was looking at. getScrollCounter() is that delta since it was last
            // cleared; shift topRow by it, then always clear it so it carries nothing into the next
            // scroll.
            val emulator = terminalEmulator
            if (emulator != null) {
                transcriptRows = emulator.screen.activeTranscriptRows
                val scrolled = emulator.scrollCounter
                if (scrolled != 0) {
                    if (topRow < 0) {
                        topRow =
                            (topRow - scrolled).coerceIn(-emulator.screen.activeTranscriptRows, 0)
                    }
                    // The selection points into the same buffer rows - shift it the same way, or it
                    // grabs different text than what's highlighted once the screen scrolls.
                    selectionState.shiftForScroll(scrolled)
                }
                emulator.clearScrollCounter()
            }
        }
    }

    LaunchedEffect(summary.connection, summary.status) {
        if (summary.status == SessionStatus.CONNECTED) {
            val emulator = summary.connection.emulatorOrNull
            terminalEmulator = emulator
            transcriptRows = emulator?.screen?.activeTranscriptRows ?: 0
            redrawRequests.trySend(Unit)
        } else if (summary.status == SessionStatus.CONNECTING) {
            // Fresh connection and emulator: drop the old buffer and anything pointing into it.
            terminalEmulator = null
            transcriptRows = 0
            mouseScrolledLines = 0
            topRow = 0
            selectionState.clear()
        }
    }

    // TerminalEmulator has no internal blink timer, only the boolean, so this loop is the schedule.
    // setCursorBlinkingEnabled is re-applied live rather than trusting what connect() baked in, so
    // toggling the setting reaches an open session - unlike cursor style and scrollback depth,
    // which the engine reads only at connection time.
    LaunchedEffect(terminalEmulator, profileOrDefault.cursorBlink) {
        val emulator = terminalEmulator ?: return@LaunchedEffect
        emulator.setCursorBlinkingEnabled(profileOrDefault.cursorBlink)
        if (!profileOrDefault.cursorBlink) return@LaunchedEffect
        var visible = true
        while (isActive) {
            delay(530)
            visible = !visible
            emulator.setCursorBlinkState(visible)
            redrawRequests.trySend(Unit)
        }
    }

    // beginConnectIfNeeded no-ops after the first call, so recomposition calling it again is fine.
    LaunchedEffect(sessionId) {
        val first = snapshotFlow { measuredSize }.filterNotNull().first()
        sessionManager.beginConnectIfNeeded(
            sessionId,
            first.cols,
            first.rows,
            first.cellWidthPx,
            first.cellHeightPx
        )
    }

    LaunchedEffect(summary.connection) {
        snapshotFlow { measuredSize }.filterNotNull().debounce(150).collect { size ->
            summary.connection.resize(size.cols, size.rows, size.cellWidthPx, size.cellHeightPx)
            sessionManager.updateSize(
                sessionId,
                size.cols,
                size.rows,
                size.cellWidthPx,
                size.cellHeightPx
            )
        }
    }

    fun sendText(text: String) {
        mouseScrolledLines = 0
        // The latch is a one-shot modifier for the next keypress, not the next batch: a fast burst
        // can coalesce several characters into one delta, and only the first of those was actually
        // typed while the modifier was held.
        val toSend =
            when {
                text.isNotEmpty() && ctrlLatched -> {
                    ctrlLatched = false
                    (ctrlCode(text[0]) ?: text[0]) + text.substring(1)
                }

                text.isNotEmpty() && altLatched -> {
                    altLatched = false
                    "\u001B$text"
                }

                else -> text
            }.replace('\n', '\r')
        summary.connection.write(toSend)
    }

    fun sendSpecialKey(keyCode: Int) {
        val emulator = summary.connection.emulatorOrNull
        val isMouseActive = emulator?.isMouseTrackingActive() == true
        if (!ctrlLatched && !altLatched && !isMouseActive) {
            if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                val maxScrollback = emulator?.screen?.activeTranscriptRows ?: 0
                transcriptRows = maxScrollback
                topRow = (topRow - 10).coerceIn(-maxScrollback, 0)
                return
            } else if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                val maxScrollback = emulator?.screen?.activeTranscriptRows ?: 0
                transcriptRows = maxScrollback
                topRow = (topRow + 10).coerceIn(-maxScrollback, 0)
                return
            }
        }
        val keyMode =
            (if (ctrlLatched) KeyHandler.KEYMOD_CTRL else 0) or (if (altLatched) KeyHandler.KEYMOD_ALT else 0)
        ctrlLatched = false
        altLatched = false
        val code =
            KeyHandler.getCode(
                keyCode,
                keyMode,
                emulator?.isCursorKeysApplicationMode ?: false,
                emulator?.isKeypadApplicationMode ?: false,
            )
        if (code != null) summary.connection.write(code)
    }

    // TerminalEmulator.sendMouseEvent picks SGR 1006 vs. legacy X10 internally from DECSET state;
    // this only converts a pixel offset to the 1-based column and row it expects.
    fun mouseCell(offset: Offset): Pair<Int, Int>? {
        val size = measuredSize ?: return null
        return (offset.x / size.cellWidthPx).toInt() + 1 to (offset.y / size.cellHeightPx).toInt() + 1
    }

    fun sendMouseClick(offset: Offset) {
        val emulator = terminalEmulator ?: return
        val (column, row) = mouseCell(offset) ?: return
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, true)
        emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, column, row, false)
    }

    fun sendWheelLines(notches: Int, col: Int = 1, row: Int = 1) {
        val emulator = terminalEmulator ?: return
        val button = if (notches > 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
        repeat(abs(notches)) {
            emulator.sendMouseEvent(button, col, row, true)
        }
        if (notches > 0) {
            mouseScrolledLines += notches
        } else {
            mouseScrolledLines = (mouseScrolledLines + notches).coerceAtLeast(0)
        }
    }

    fun sendMouseWheel(offset: Offset, dy: Float) {
        val emulator = terminalEmulator ?: return
        val cellHeightPx = measuredSize?.cellHeightPx ?: return
        val size = measuredSize ?: return
        val col = ((offset.x / size.cellWidthPx).toInt() + 1).coerceIn(1, emulator.mColumns)
        val row = ((offset.y / size.cellHeightPx).toInt() + 1).coerceIn(1, emulator.mRows)
        val (notches, remainder) = dragRows(scrollDragRemainderPx + dy, cellHeightPx)
        scrollDragRemainderPx = remainder
        if (notches != 0) {
            sendWheelLines(notches, col, row)
        }
    }

    fun shareSelection(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(
            Intent.EXTRA_TEXT,
            text
        )
        }
        localContext.startActivity(Intent.createChooser(send, null))
    }

    // A bare IP has no scheme, so "Open" guesses http:// the way an address bar does. A bare path
    // is a path on the SSH host, which is why LinkActionDialog never offers "Open" for
    // LinkType.PATH.
    fun openLink(link: TerminalLink) {
        val uri =
            if (link.type == LinkType.IP) Uri.parse("http://${link.text}") else Uri.parse(link.text)
        runCatching { localContext.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    val toggleKeyboard: () -> Unit = {
        if (imeVisible) {
            keyboardController?.hide()
        } else {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // imePadding lives on KeyBar or its stand-in below, not on this Column - see that call site.
    Column(modifier = modifier) {
        if (searchController.open) {
            TerminalSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = searchMatches.size,
                matchIndex = searchMatchIndex,
                onNext = { jumpToSearchMatch(searchMatchIndex + 1) },
                onPrevious = { jumpToSearchMatch(searchMatchIndex - 1) },
                onClose = { searchController.hide() },
            )
        }
        Box(
            modifier =
                if (terminalHeight != null) {
                    Modifier
                        .height(terminalHeight)
                        .fillMaxWidth()
                } else {
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // Preview rather than onKeyEvent: claims the event before
                    // TerminalInputCapture's BasicTextField, so Ctrl+C isn't first eaten by the
                    // field's own copy shortcut.
                    .onPreviewKeyEvent { event ->
                        val emulator = terminalEmulator
                        val bytes =
                            hardwareKeyCode(
                                event = event,
                                cursorApp = emulator?.isCursorKeysApplicationMode ?: false,
                                keypadApp = emulator?.isKeypadApplicationMode ?: false,
                            )
                        if (bytes != null) {
                            summary.connection.write(bytes)
                            true
                        } else {
                            false
                        }
                    }
                    .terminalGestures(
                        onDrag = { dy ->
                            val cellHeightPx = measuredSize?.cellHeightPx ?: return@terminalGestures
                            val maxScrollback = terminalEmulator?.screen?.activeTranscriptRows ?: 0
                            transcriptRows = maxScrollback
                            val (rows, remainder) = dragRows(scrollDragRemainderPx + dy, cellHeightPx)
                            scrollDragRemainderPx = remainder
                            if (rows != 0) topRow = (topRow - rows).coerceIn(-maxScrollback, 0)
                        },
                        onPinch = { scale ->
                            fontSizeSp = (fontSizeSp.value * scale).coerceIn(
                                FONT_SIZE_SP_RANGE.start,
                                FONT_SIZE_SP_RANGE.endInclusive
                            ).sp
                        },
                        onTap = { offset ->
                            val now = System.currentTimeMillis()
                            val isZoomed = abs(fontSizeSp.value - profileOrDefault.fontSizeSp) > 0.5f
                            if (now - lastTapTimeMs < 350L && isZoomed) {
                                // Double-tap resets zoom back to default font size
                                fontSizeSp = profileOrDefault.fontSizeSp.sp
                                lastTapTimeMs = 0L
                            } else {
                                lastTapTimeMs = now
                                val emulator = terminalEmulator
                                val size = measuredSize
                                val link =
                                    if (emulator != null && size != null) {
                                        val col = (offset.x / size.cellWidthPx).toInt()
                                            .coerceIn(0, emulator.mColumns - 1)
                                        val screenRow = (offset.y / size.cellHeightPx).toInt()
                                            .coerceIn(0, emulator.mRows - 1)
                                        linkAt(emulator, screenRow + topRow, col)
                                    } else {
                                        null
                                    }
                                if (link != null) {
                                    detectedLink = link
                                } else {
                                    focusRequester.requestFocus()
                                    selectionState.clear() // tapping outside a selection dismisses it, same as Android's own text selection
                                }
                            }
                        },
                        onLongPress = { offset ->
                            val emulator = terminalEmulator
                            val size = measuredSize
                            if (emulator != null && size != null) {
                                selectionState.selectWord(
                                    emulator,
                                    size.cellWidthPx,
                                    size.cellHeightPx,
                                    topRow,
                                    offset
                                )
                            }
                        },
                        isMouseReportingActive = {
                            terminalEmulator?.isMouseTrackingActive() ?: false
                        },
                        onMouseClick = ::sendMouseClick,
                        onMouseWheel = ::sendMouseWheel,
                        excludeRightEdgePx = with(LocalDensity.current) { 48.dp.toPx() },
                    ),
            ) {
                TerminalCanvas(
                    emulator = terminalEmulator,
                    frame = frame,
                    onMeasured = { cols, rows, cellW, cellH ->
                        measuredSize = TerminalSize(cols, rows, cellW, cellH)
                    },
                    modifier = Modifier.matchParentSize(),
                    fontSizeSp = fontSizeSp,
                    typeface = terminalTypeface,
                    lineHeightMultiplier = lineHeightMultiplier,
                    topRow = topRow,
                    selection = selectionState.selection,
                    selectionColor = selectionColor,
                )
                TerminalInputCapture(
                    focusRequester = focusRequester,
                    resetKey = sessionId,
                    // Lambdas, not ::sendText: the Compose compiler memoises a local function
                    // reference without keying on what it captures, so ::sendText kept writing to
                    // the previous tab's summary. Any local fun that reads summary must be passed
                    // as a lambda.
                    onText = { sendText(it) },
                    onBackspace = { n ->
                        summary.connection.write("\u007F".repeat(n))
                    },
                    // See TerminalInputCapture for why the field carries this instead of the canvas.
                    // Named per session with the label the chip rail shows, so announcement and screen
                    // agree.
                    accessibilityLabel = "Terminal, ${summary.label}",
                    screenText = screenText,
                    enhancedInput = keyboardEnhancedInput,
                    modifier = Modifier.matchParentSize(),
                )
                TerminalSelectionOverlay(
                    state = selectionState,
                    emulator = terminalEmulator,
                    cellWidthPx = measuredSize?.cellWidthPx ?: 0,
                    cellHeightPx = measuredSize?.cellHeightPx ?: 0,
                    topRow = topRow,
                    onCopy = { text -> summary.connection.onCopyTextToClipboard(text) },
                    onPaste = { summary.connection.onPasteTextFromClipboard() },
                    onShare = ::shareSelection,
                )
                detectedLink?.let { link ->
                    val resolvePathForLink: suspend () -> String = {
                        if (link.text.startsWith("/")) {
                            link.text
                        } else {
                            val hint = terminalEmulator?.let { emu ->
                                extractPromptWorkingDir(TerminalTextSanitizer.extractCleanTail(emu, maxLines = 10))
                            }
                            val base = resolveWorkingDirOnHost(summary.connection, hint).trimEnd('/')
                            if (base.isBlank() || base == ".") link.text else "$base/${link.text}"
                        }
                    }
                    LinkActionDialog(
                        link = link,
                        onOpen = { openLink(link); detectedLink = null },
                        onCopy = {
                            summary.connection.onCopyTextToClipboard(link.text); detectedLink = null
                        },
                        // The path is known from the tap, so this skips straight to the SAF destination
                        // picker (resolving relative project paths against the remote shell's cwd).
                        onDownload = {
                            detectedLink = null
                            coroutineScope.launch {
                                val resolved = resolvePathForLink()
                                fileTransferController.requestDownload(summary.connection, resolved)
                            }
                        },
                        onDismiss = { detectedLink = null },
                        onEdit = if (link.type == LinkType.PATH && onEditRemoteFile != null && summary.status == SessionStatus.CONNECTED) ({
                            detectedLink = null
                            coroutineScope.launch {
                                val resolved = resolvePathForLink()
                                onEditRemoteFile(sessionId, resolved)
                            }
                        }) else null,
                        onAskAgent = if (link.type == LinkType.PATH) ({
                            val location = link.lineNumber?.let { "${link.text}:$it" } ?: link.text
                            val contextLine = link.sourceLine?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                            tabComposerInitialText = "Look at $location and fix this issue:$contextLine"
                            detectedLink = null
                            tabPromptComposerOpen = true
                        }) else null,
                    )
                }
            }
            // Interactive scrollbar for fast scrubbing through scrollback history
            val isMouseActive = terminalEmulator?.isMouseTrackingActive() == true
            val effectiveScrollback = if (isMouseActive) maxOf(mouseScrolledLines + 100, 500) else transcriptRows
            val effectiveTopRow = if (isMouseActive) -mouseScrolledLines else topRow

            terminalEmulator?.let { emulator ->
                TerminalScrollbar(
                    topRow = effectiveTopRow,
                    maxScrollback = effectiveScrollback,
                    screenRows = emulator.mRows,
                    onScrollToRow = { newRow ->
                        if (isMouseActive) {
                            val currentLines = mouseScrolledLines
                            val targetLines = -newRow
                            val delta = targetLines - currentLines
                            if (delta != 0) {
                                sendWheelLines(delta)
                            }
                        } else {
                            topRow = newRow
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(bottom = 8.dp, top = 8.dp),
                )
            }

            // Scrollback position, line steppers, and jump-to-bottom pill:
            val isScrolledBack = if (isMouseActive) mouseScrolledLines > 0 else topRow < 0
            val linesBackCount = if (isMouseActive) mouseScrolledLines else -topRow
            val canShowJumpPill = if (isMouseActive) true else transcriptRows > 0

            if (isScrolledBack) {
                ScrollbackStepperPill(
                    linesBack = linesBackCount,
                    onStepUp = {
                        if (isMouseActive) {
                            sendWheelLines(10)
                        } else {
                            topRow = (topRow - 10).coerceIn(-transcriptRows, 0)
                        }
                    },
                    onStepDown = {
                        if (isMouseActive) {
                            sendWheelLines(-10)
                        } else {
                            topRow = (topRow + 10).coerceIn(-transcriptRows, 0)
                        }
                    },
                    onJumpToBottom = {
                        if (isMouseActive) {
                            summary.connection.write("q")
                            mouseScrolledLines = 0
                        } else {
                            topRow = 0
                        }
                    },
                    modifier = if (keyBarHidden) {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    } else {
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 56.dp, bottom = 12.dp)
                    },
                )
            } else if (canShowJumpPill) {
                ScrollbackJumpPill(
                    onJumpUp = {
                        if (isMouseActive) {
                            sendWheelLines(10)
                        } else {
                            topRow = (-10).coerceIn(-transcriptRows, 0)
                        }
                    },
                    modifier = if (keyBarHidden) {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    } else {
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 56.dp, bottom = 12.dp)
                    },
                )
            }
            if (keyBarHidden) {
                KeyboardKey(
                    visible = imeVisible,
                    onClick = toggleKeyboard,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 56.dp, bottom = 12.dp),
                )
            }
            if (showLayoutToggle) {
                LayoutToggleButton(
                    fullWidth = fullWidthTerminal,
                    onToggle = onToggleFullWidth,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
        }
        if (preKeyBarGap > 0.dp) Spacer(modifier = Modifier
            .fillMaxWidth()
            .height(preKeyBarGap))

        if (isAnalyzingDigest) {
            AgentDigestLoadingCapsule(
                onDismiss = {
                    streamingDigestText = null
                    isAnalyzingDigest = false
                },
                streamingText = streamingDigestText,
            )
        } else {
            activeDigest?.let { digest ->
                AgentDigestCapsule(
                    digest = digest,
                    isListening = isVoiceListening,
                    onListenClick = {
                        voiceEngine.speak(digest.spokenSummary, listenAfter = hasAudioPermission())
                    },
                    onMicClick = {
                        if (isVoiceListening) {
                            voiceEngine.stopListening()
                        } else if (hasAudioPermission()) {
                            voiceEngine.startListeningWindow(autoTriggered = false)
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onActionClick = { actionText ->
                        val emulator = terminalEmulator
                        if (emulator != null) {
                            emulator.paste(actionText)
                        } else {
                            summary.connection.write(actionText)
                        }
                        summary.connection.write("\r")
                        dismissedPromptSignature = activePromptSignature
                        activeDigest = null
                        voiceEngine.stopListening()
                    },
                    onDismiss = {
                        dismissedPromptSignature = activePromptSignature
                        activeDigest = null
                        voiceEngine.stopListening()
                    },
                )
            }
        }

        fun sendQuickText(text: String, sendEnter: Boolean) {
            val emulator = terminalEmulator
            if (emulator != null && text.isNotEmpty()) {
                emulator.paste(text)
            } else if (text.isNotEmpty()) {
                summary.connection.write(text.replace("\n", "\r"))
            }
            if (sendEnter) {
                summary.connection.write("\r")
            }
        }

        // `imePadding()` belongs here, on the key bar or its stand-in, never on the outer Column. With it on
        // the Column - whose other child is a `weight(1f)` Box - each frame of the IME-hide
        // animation recomputes the remaining-space split from a bottom padding that is itself still
        // animating. If one frame's inset makes the padding read larger than the Column's height,
        // `weight(1f)` clamps the terminal to zero for that frame and the key bar is placed
        // immediately after it, at the top, which reads as a second key bar flashing. Padding only
        // this one removes the weighted sibling from the equation.
        if (keyBarHidden) {
            Spacer(modifier = Modifier.imePadding())
        } else KeyBar(
            ctrlLatched = ctrlLatched,
            altLatched = altLatched,
            onCtrlToggle = { ctrlLatched = !ctrlLatched },
            onAltToggle = { altLatched = !altLatched },
            onSpecialKey = { keyCode -> sendSpecialKey(keyCode) },
            // Through the same text-input path as typed IME input, so it inherits sendText's
            // Ctrl/Alt latch behaviour.
            onMacro = { text -> sendText(text) },
            keyboardVisible = imeVisible,
            onKeyboardToggle = toggleKeyboard,
            onOpenPromptComposer = {
                tabComposerInitialText = ""
                tabPromptComposerOpen = true
            },
            onOpenQuickResponse = { tabQuickResponseOpen = true },
            onOpenDigest = { triggerDigest(speakImmediately = false) },
            quickDrawerActive = quickDrawerActive,
            onToggleQuickDrawer = { quickDrawerActive = !quickDrawerActive },
            onQuickAction = { text, sendEnter -> sendQuickText(text, sendEnter) },
            onSendRaw = { control -> summary.connection.write(control) },
            keys = keyBarKeys,
            rows = keyBarRows,
            modifier = Modifier.imePadding(),
        )

        if (tabQuickResponseOpen) {
            AgentQuickResponseSheet(
                onSendText = { text, sendEnter -> sendQuickText(text, sendEnter) },
                onSendRaw = { control ->
                    summary.connection.write(control)
                },
                onOpenComposerWithText = { initialText ->
                    tabQuickResponseOpen = false
                    tabComposerInitialText = initialText
                    tabPromptComposerOpen = true
                },
                onSummarize = {
                    triggerDigest(speakImmediately = true)
                },
                isSummarizing = isAnalyzingDigest,
                isQuickBarActive = quickDrawerActive,
                onToggleQuickBar = { quickDrawerActive = !quickDrawerActive },
                onOpenGitDiff = if (summary.status == SessionStatus.CONNECTED) ({
                    tabQuickResponseOpen = false
                    tabGitDiffOpen = true
                }) else null,
                onDismiss = {
                    tabQuickResponseOpen = false
                    focusRequester.requestFocus()
                },
            )
        }

        if (tabGitDiffOpen && summary.status == SessionStatus.CONNECTED) {
            val promptDirHint = terminalEmulator?.let { emu ->
                extractPromptWorkingDir(TerminalTextSanitizer.extractCleanTail(emu, maxLines = 10))
            }
            GitDiffSheet(
                connection = summary.connection,
                promptDirHint = promptDirHint,
                onAskAgent = { reviewPrompt ->
                    tabGitDiffOpen = false
                    tabComposerInitialText = reviewPrompt
                    tabPromptComposerOpen = true
                },
                onDismiss = {
                    tabGitDiffOpen = false
                    focusRequester.requestFocus()
                },
                onEditFile = onEditRemoteFile?.let { editCb ->
                    { remotePath ->
                        tabGitDiffOpen = false
                        editCb(sessionId, remotePath)
                    }
                },
            )
        }

        if (tabPromptComposerOpen) {
            PromptComposerDialog(
                initialText = tabComposerInitialText,
                onUploadImage = if (summary.status == SessionStatus.CONNECTED) {
                    { uri -> summary.connection.uploadImageAttachment(uri) }
                } else {
                    null
                },
                onSend = { prompt, enter -> sendQuickText(prompt, enter) },
                onDismiss = {
                    tabPromptComposerOpen = false
                    tabComposerInitialText = ""
                    focusRequester.requestFocus()
                },
            )
        }
    }

    LaunchedEffect(sessionId) { focusRequester.requestFocus() }
}

/** Scrollback position, line steppers, and jump-to-bottom in one: shown only while scrolled back. */
@Composable
private fun ScrollbackStepperPill(
    linesBack: Int,
    onStepUp: () -> Unit,
    onStepDown: () -> Unit,
    onJumpToBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = modifier.semantics {
            contentDescription = "Scrolled back $linesBack lines. Controls to step up, down, or jump to bottom."
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            FilledTonalIconButton(
                onClick = onStepUp,
                modifier = Modifier.size(34.dp),
            ) {
                Text(
                    text = "▲10",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }

            FilledTonalIconButton(
                onClick = onStepDown,
                modifier = Modifier.size(34.dp),
            ) {
                Text(
                    text = "▼10",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }

            Button(
                onClick = onJumpToBottom,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                MachineText(
                    "$linesBack ↓",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

/** Quick 1-tap jump pill to step 10 lines back into history from the live bottom. */
@Composable
private fun ScrollbackJumpPill(
    onJumpUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = modifier.semantics {
            contentDescription = "Step back 10 lines into scrollback history"
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            FilledTonalIconButton(
                onClick = onJumpUp,
                modifier = Modifier.size(34.dp),
            ) {
                Text(
                    text = "▲10",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * "Open" is omitted for [LinkType.PATH], which names a path on the remote host rather than anything
 * `Intent.ACTION_VIEW` could open here; "Download" is offered only for it.
 */
@Composable
private fun LinkActionDialog(
    link: TerminalLink,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onAskAgent: (() -> Unit)? = null,
) {
    val displayTitle = if (link.type == LinkType.PATH && link.lineNumber != null) {
        "${link.text}:${link.lineNumber}"
    } else {
        link.text
    }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(displayTitle) },
        text = {
            if (link.type == LinkType.PATH) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = link.sourceLine?.takeIf { it.isNotBlank() } ?: "Remote path",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                    )
                    if (onAskAgent != null || onEdit != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onAskAgent != null) {
                                Button(
                                    onClick = onAskAgent,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Text("Ask Agent", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (onEdit != null) {
                                TextButton(onClick = onEdit) { Text("Edit") }
                            }
                        }
                    }
                }
            } else {
                Text("Open in browser or copy?")
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = {
            Row {
                if (link.type == LinkType.PATH) TextButton(onClick = onDownload) { Text("Download") }
                if (link.type != LinkType.PATH) TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

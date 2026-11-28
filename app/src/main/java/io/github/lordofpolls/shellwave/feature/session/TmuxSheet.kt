package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ssh.SshConnection
import kotlinx.coroutines.launch

private sealed interface TmuxPendingPrompt {
    data object NewSession : TmuxPendingPrompt
    data class RenameWindow(val sessionName: String, val windowIndex: Int, val currentName: String) : TmuxPendingPrompt
    data class KillWindow(val sessionName: String, val windowIndex: Int, val windowName: String) : TmuxPendingPrompt
    data class KillSession(val sessionName: String) : TmuxPendingPrompt
}

/**
 * Bottom sheet for inspecting and controlling remote `tmux` sessions, windows, and panes over SSH
 * without requiring any host-side daemon beyond `tmux`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TmuxSheet(
    connection: SshConnection,
    onSendShellLine: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var refreshTick by remember { mutableIntStateOf(0) }
    var inspectState by remember { mutableStateOf<TmuxInspectResult?>(null) }
    var busyAction by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<TmuxPendingPrompt?>(null) }
    var promptDraft by remember { mutableStateOf("") }

    LaunchedEffect(connection, refreshTick) {
        val result = runCatching { connection.execCapture(TMUX_INSPECT_COMMAND) }
        inspectState = result.fold(
            onSuccess = { cap ->
                if (cap.error != null) {
                    TmuxInspectResult.Error(cap.error)
                } else {
                    parseTmuxInspectOutput(
                        stdout = cap.stdout,
                        stderr = cap.stderr,
                        exitCode = cap.exitStatus ?: 0,
                    )
                }
            },
            onFailure = { err ->
                TmuxInspectResult.Error(err.message ?: "Failed to inspect tmux")
            },
        )
    }

    fun runTmuxCommand(
        command: String,
        dismissOnSuccess: Boolean = false,
        fallbackAttachSession: String? = null,
    ) {
        if (busyAction) return
        busyAction = true
        actionError = null
        scope.launch {
            try {
                val res = connection.execCapture(command)
                if (res.error != null) {
                    actionError = res.error
                    return@launch
                }
                val code = res.exitStatus ?: 0
                if (code == 42 && fallbackAttachSession != null) {
                    onSendShellLine(tmuxAttachShellLine(fallbackAttachSession))
                    onDismiss()
                    return@launch
                }
                if (code != 0) {
                    actionError = res.stderr.ifBlank { res.stdout }.trim()
                        .ifBlank { "tmux command failed (exit $code)" }
                    return@launch
                }
                if (dismissOnSuccess) {
                    onDismiss()
                } else {
                    refreshTick++
                }
            } catch (e: Exception) {
                actionError = e.message ?: "SSH exec failed"
            } finally {
                busyAction = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { AgentSheetDragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            SessionSheetHeader(
                icon = Icons.AutoMirrored.Outlined.ViewQuilt,
                title = "tmux sessions",
                onDismiss = onDismiss,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                promptDraft = "dev"
                                prompt = TmuxPendingPrompt.NewSession
                            },
                            enabled = !busyAction && inspectState !is TmuxInspectResult.NotInstalled,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "New tmux session",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { refreshTick++ },
                            enabled = !busyAction,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh tmux sessions",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                actionError?.let { err ->
                    Surface(
                        shape = AGENT_BUTTON_SHAPE,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                when (val state = inspectState) {
                    null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                    TmuxInspectResult.NotInstalled -> {
                        Text(
                            text = "`tmux` is not installed on this remote host.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TmuxInspectResult.NoServer -> {
                        SessionSheetCard {
                            Text(
                                text = "No tmux sessions are running on this host.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    enabled = !busyAction,
                                    onClick = {
                                        onSendShellLine("tmux new -A -s shellsurge")
                                        onDismiss()
                                    },
                                ) {
                                    Text("Start & attach shellsurge")
                                }
                                OutlinedButton(
                                    enabled = !busyAction,
                                    onClick = {
                                        promptDraft = "dev"
                                        prompt = TmuxPendingPrompt.NewSession
                                    },
                                ) {
                                    Text("New named session")
                                }
                            }
                        }
                    }
                    is TmuxInspectResult.Error -> {
                        SessionSheetCard {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = { refreshTick++ }) {
                                Text("Retry")
                            }
                        }
                    }
                    is TmuxInspectResult.Available -> {
                        val anyAttached = state.sessions.any { it.attached }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.sessions, key = { it.name }) { session ->
                                TmuxSessionCard(
                                    session = session,
                                    anyAttached = anyAttached,
                                    busy = busyAction,
                                    onSwitchOrAttach = {
                                        if (anyAttached && !session.attached) {
                                            runTmuxCommand(
                                                command = tmuxSwitchClientCommand(session.name),
                                                dismissOnSuccess = true,
                                                fallbackAttachSession = session.name,
                                            )
                                        } else {
                                            onSendShellLine(tmuxAttachShellLine(session.name))
                                            onDismiss()
                                        }
                                    },
                                    onNewWindow = {
                                        runTmuxCommand(tmuxNewWindowCommand(session.name))
                                    },
                                    onKillSession = {
                                        prompt = TmuxPendingPrompt.KillSession(session.name)
                                    },
                                    onSelectWindow = { window ->
                                        val cmd = if (!session.attached && anyAttached) {
                                            "${tmuxSelectWindowCommand(session.name, window.index)} && ${tmuxSwitchClientCommand(session.name)}"
                                        } else {
                                            tmuxSelectWindowCommand(session.name, window.index)
                                        }
                                        runTmuxCommand(
                                            command = cmd,
                                            dismissOnSuccess = true,
                                            fallbackAttachSession = if (!session.attached) session.name else null,
                                        )
                                    },
                                    onSelectPane = { window, pane ->
                                        val selectCmd =
                                            "${tmuxSelectWindowCommand(session.name, window.index)} && ${tmuxSelectPaneCommand(session.name, window.index, pane.paneIndex)}"
                                        val cmd = if (!session.attached && anyAttached) {
                                            "$selectCmd && ${tmuxSwitchClientCommand(session.name)}"
                                        } else {
                                            selectCmd
                                        }
                                        runTmuxCommand(
                                            command = cmd,
                                            dismissOnSuccess = true,
                                            fallbackAttachSession = if (!session.attached) session.name else null,
                                        )
                                    },
                                    onRenameWindow = { window ->
                                        promptDraft = window.name
                                        prompt = TmuxPendingPrompt.RenameWindow(
                                            sessionName = session.name,
                                            windowIndex = window.index,
                                            currentName = window.name,
                                        )
                                    },
                                    onKillWindow = { window ->
                                        prompt = TmuxPendingPrompt.KillWindow(
                                            sessionName = session.name,
                                            windowIndex = window.index,
                                            windowName = window.name,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val activePrompt = prompt) {
        TmuxPendingPrompt.NewSession -> {
            AlertDialog(
                onDismissRequest = { prompt = null },
                title = { Text("New tmux session") },
                text = {
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        label = { Text("Session name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = promptDraft.isNotBlank(),
                        onClick = {
                            val name = promptDraft.trim()
                            prompt = null
                            runTmuxCommand(tmuxNewSessionCommand(name))
                        },
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { prompt = null }) { Text("Cancel") }
                },
            )
        }
        is TmuxPendingPrompt.RenameWindow -> {
            AlertDialog(
                onDismissRequest = { prompt = null },
                title = { Text("Rename window #${activePrompt.windowIndex}") },
                text = {
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        label = { Text("Window name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = promptDraft.isNotBlank(),
                        onClick = {
                            val nextName = promptDraft.trim()
                            prompt = null
                            runTmuxCommand(
                                tmuxRenameWindowCommand(
                                    activePrompt.sessionName,
                                    activePrompt.windowIndex,
                                    nextName,
                                ),
                            )
                        },
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { prompt = null }) { Text("Cancel") }
                },
            )
        }
        is TmuxPendingPrompt.KillWindow -> {
            AlertDialog(
                onDismissRequest = { prompt = null },
                title = { Text("Close tmux window?") },
                text = {
                    Text("Close #${activePrompt.windowIndex} \"${activePrompt.windowName}\" in ${activePrompt.sessionName}?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            prompt = null
                            runTmuxCommand(
                                tmuxKillWindowCommand(activePrompt.sessionName, activePrompt.windowIndex),
                            )
                        },
                    ) { Text("Close") }
                },
                dismissButton = {
                    TextButton(onClick = { prompt = null }) { Text("Cancel") }
                },
            )
        }
        is TmuxPendingPrompt.KillSession -> {
            AlertDialog(
                onDismissRequest = { prompt = null },
                title = { Text("Kill tmux session?") },
                text = {
                    Text("Terminate session \"${activePrompt.sessionName}\" and all its windows?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            prompt = null
                            runTmuxCommand(tmuxKillSessionCommand(activePrompt.sessionName))
                        },
                    ) { Text("Kill") }
                },
                dismissButton = {
                    TextButton(onClick = { prompt = null }) { Text("Cancel") }
                },
            )
        }
        null -> {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TmuxSessionCard(
    session: TmuxSessionInfo,
    anyAttached: Boolean,
    busy: Boolean,
    onSwitchOrAttach: () -> Unit,
    onNewWindow: () -> Unit,
    onKillSession: () -> Unit,
    onSelectWindow: (TmuxWindowInfo) -> Unit,
    onSelectPane: (TmuxWindowInfo, TmuxPaneInfo) -> Unit,
    onRenameWindow: (TmuxWindowInfo) -> Unit,
    onKillWindow: (TmuxWindowInfo) -> Unit,
) {
    SessionSheetCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = session.name,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.attached) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "attached",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!session.attached) {
                AgentUtilityPill(
                    label = if (anyAttached) "Switch" else "Attach",
                    enabled = !busy,
                    active = true,
                    leadingIcon = {
                        Icon(
                            imageVector = if (anyAttached) Icons.Outlined.SwapHoriz else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    onClick = onSwitchOrAttach,
                )
            }
            AgentUtilityPill(
                label = "+ Window",
                enabled = !busy,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                onClick = onNewWindow,
            )
            AgentUtilityPill(
                label = "Kill",
                enabled = !busy,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                onClick = onKillSession,
            )
        }

        session.windows.forEach { window ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (window.active) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                border = BorderStroke(
                    1.dp,
                    if (window.active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { onSelectWindow(window) },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "#${window.index} ${window.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (window.active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (window.bellFlag) {
                            Icon(
                                imageVector = Icons.Outlined.NotificationsActive,
                                contentDescription = "Window bell",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp),
                            )
                        } else if (window.activityFlag && !window.active) {
                            Text(
                                text = "activity",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = { onRenameWindow(window) },
                            enabled = !busy,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Rename window",
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        IconButton(
                            onClick = { onKillWindow(window) },
                            enabled = !busy,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Close window",
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }

                    if (window.panes.size > 1) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            window.panes.forEach { pane ->
                                val cmd = pane.currentCommand.ifBlank { "shell" }
                                AgentUtilityPill(
                                    label = "Pane ${pane.paneIndex} · $cmd",
                                    active = pane.active && window.active,
                                    enabled = !busy,
                                    isMonospace = true,
                                    onClick = { onSelectPane(window, pane) },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

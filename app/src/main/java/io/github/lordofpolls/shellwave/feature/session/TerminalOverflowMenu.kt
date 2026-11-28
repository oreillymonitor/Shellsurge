package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.ssh.SshConnection

/**
 * The terminal's overflow menu, pinned at the chip rail's right edge.
 *
 * [connection] is non-null only while the selected session is `CONNECTED`, gating actions that
 * require a live SSH channel. [onClose] stays available even while connecting or failed.
 */
@Composable
internal fun TerminalOverflowMenu(
    connection: SshConnection?,
    bellMode: BellMode,
    onBellMode: (BellMode) -> Unit,
    onDownload: (SshConnection) -> Unit,
    onUpload: (SshConnection) -> Unit,
    onBrowseFiles: (SshConnection) -> Unit,
    onRunScript: (() -> Unit)?,
    onClose: (() -> Unit)?,
    onSearch: () -> Unit,
    isLogging: Boolean,
    onStartLogging: (SshConnection) -> Unit,
    onStopLogging: () -> Unit,
    modifier: Modifier = Modifier,
    onPromptComposer: (() -> Unit)? = null,
    onAgentQuickResponse: (() -> Unit)? = null,
    outputAlertsEnabled: Boolean = true,
    onToggleOutputAlerts: (() -> Unit)? = null,
    onOpenGitDiff: (() -> Unit)? = null,
    onOpenLinks: (() -> Unit)? = null,
    onOpenReadableOutput: (() -> Unit)? = null,
    onOpenTmux: (() -> Unit)? = null,
    onOpenListeningPorts: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var showingBell by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = {
                showingBell = false
                expanded = true
            },
        ) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Session options")
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showingBell) {
                DropdownMenuItem(
                    text = { Text("Back") },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    },
                    onClick = { showingBell = false },
                )
                BellMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(BellPreferences.modeName(mode)) },
                        leadingIcon = { RadioButton(selected = mode == bellMode, onClick = null) },
                        onClick = {
                            onBellMode(mode)
                            showingBell = false
                        },
                    )
                }
            } else {
                if (connection != null) {
                    if (onPromptComposer != null) {
                        DropdownMenuItem(
                            text = { Text("Compose prompt…") },
                            leadingIcon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onPromptComposer()
                            },
                        )
                    }
                    if (onAgentQuickResponse != null) {
                        DropdownMenuItem(
                            text = { Text("Quick response…") },
                            leadingIcon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onAgentQuickResponse()
                            },
                        )
                    }
                    if (onOpenGitDiff != null) {
                        DropdownMenuItem(
                            text = { Text("Git diff & status…") },
                            leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onOpenGitDiff()
                            },
                        )
                    }
                    if (onOpenTmux != null) {
                        DropdownMenuItem(
                            text = { Text("tmux sessions…") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ViewQuilt, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onOpenTmux()
                            },
                        )
                    }
                    if (onOpenListeningPorts != null) {
                        DropdownMenuItem(
                            text = { Text("Listening ports…") },
                            leadingIcon = { Icon(Icons.Outlined.Router, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onOpenListeningPorts()
                            },
                        )
                    }
                    if (onRunScript != null) {
                        DropdownMenuItem(
                            text = { Text("Run script here") },
                            leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onRunScript()
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Browse files") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onBrowseFiles(connection)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Upload file") },
                        leadingIcon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onUpload(connection)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Download file") },
                        leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onDownload(connection)
                        },
                    )
                }

                if (onOpenReadableOutput != null) {
                    DropdownMenuItem(
                        text = { Text("Readable output…") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onOpenReadableOutput()
                        },
                    )
                }
                if (onOpenLinks != null) {
                    DropdownMenuItem(
                        text = { Text("Links…") },
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onOpenLinks()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Search") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSearch()
                    },
                )
                if (isLogging) {
                    DropdownMenuItem(
                        text = { Text("Stop logging") },
                        leadingIcon = { Icon(Icons.Outlined.StopCircle, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onStopLogging()
                        },
                    )
                } else if (connection != null) {
                    DropdownMenuItem(
                        text = { Text("Log session…") },
                        leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onStartLogging(connection)
                        },
                    )
                }
                HorizontalDivider()
                if (onToggleOutputAlerts != null) {
                    DropdownMenuItem(
                        text = { Text(if (outputAlertsEnabled) "Output alerts: On" else "Output alerts: Muted") },
                        leadingIcon = {
                            Icon(
                                if (outputAlertsEnabled) {
                                    Icons.Outlined.NotificationsActive
                                } else {
                                    Icons.Outlined.NotificationsOff
                                },
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            expanded = false
                            onToggleOutputAlerts()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Bell: ${BellPreferences.modeName(bellMode)}") },
                    leadingIcon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                    trailingIcon = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                    },
                    onClick = { showingBell = true },
                )
                if (onClose != null) {
                    HorizontalDivider()
                    val errorColor = MaterialTheme.colorScheme.error
                    DropdownMenuItem(
                        text = { Text("Close session", color = errorColor) },
                        leadingIcon = {
                            Icon(Icons.Outlined.Close, contentDescription = null, tint = errorColor)
                        },
                        onClick = {
                            expanded = false
                            onClose()
                        },
                    )
                }
            }
        }
    }
}

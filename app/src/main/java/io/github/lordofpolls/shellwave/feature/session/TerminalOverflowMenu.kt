package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.ssh.SshConnection

/**
 * The terminal's anchored top-right overflow popover, keeping the grouped pill and card styling
 * while opening directly beneath the `⋮` trigger.
 *
 * [connection] is non-null only while the selected session is `CONNECTED`, gating actions that
 * require a live SSH channel. [onClose] stays available even while connecting/failed.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Session options")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
            modifier = Modifier.width(296.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionSheetCard {
                        AgentSectionHeader(title = "Agent & View")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (connection != null && onPromptComposer != null) {
                                AgentUtilityPill(
                                    label = "Compose",
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.EditNote,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onPromptComposer()
                                    },
                                )
                            }
                            if (connection != null && onAgentQuickResponse != null) {
                                AgentUtilityPill(
                                    label = "Quick response",
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.SmartToy,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onAgentQuickResponse()
                                    },
                                )
                            }
                            if (onOpenReadableOutput != null) {
                                AgentUtilityPill(
                                    label = "Readable output",
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.Article,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onOpenReadableOutput()
                                    },
                                )
                            }
                            if (connection != null && onOpenGitDiff != null) {
                                AgentUtilityPill(
                                    label = "Git diff",
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Code,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onOpenGitDiff()
                                    },
                                )
                            }
                            if (onOpenLinks != null) {
                                AgentUtilityPill(
                                    label = "Links",
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Link,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onOpenLinks()
                                    },
                                )
                            }
                            if (connection != null && onOpenTmux != null) {
                                AgentUtilityPill(
                                    label = "tmux",
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.ViewQuilt,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onOpenTmux()
                                    },
                                )
                            }
                            if (connection != null && onOpenListeningPorts != null) {
                                AgentUtilityPill(
                                    label = "Ports",
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Router,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        onOpenListeningPorts()
                                    },
                                )
                            }
                            AgentUtilityPill(
                                label = "Search",
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onSearch()
                                },
                            )
                        }
                    }

                    if (connection != null || isLogging) {
                        SessionSheetCard {
                            AgentSectionHeader(title = "Files & Tools")
                            if (connection != null) {
                                SessionDrawerActionRow(
                                    icon = Icons.Outlined.Folder,
                                    label = "Browse files",
                                    onClick = {
                                        expanded = false
                                        onBrowseFiles(connection)
                                    },
                                )
                                SessionDrawerActionRow(
                                    icon = Icons.Outlined.Upload,
                                    label = "Upload file",
                                    onClick = {
                                        expanded = false
                                        onUpload(connection)
                                    },
                                )
                                SessionDrawerActionRow(
                                    icon = Icons.Outlined.Download,
                                    label = "Download file",
                                    onClick = {
                                        expanded = false
                                        onDownload(connection)
                                    },
                                )
                                if (onRunScript != null) {
                                    SessionDrawerActionRow(
                                        icon = Icons.Outlined.PlayArrow,
                                        label = "Run script here",
                                        onClick = {
                                            expanded = false
                                            onRunScript()
                                        },
                                    )
                                }
                            }
                            if (isLogging) {
                                SessionDrawerActionRow(
                                    icon = Icons.Outlined.StopCircle,
                                    label = "Stop logging",
                                    onClick = {
                                        expanded = false
                                        onStopLogging()
                                    },
                                )
                            } else if (connection != null) {
                                SessionDrawerActionRow(
                                    icon = Icons.Outlined.Save,
                                    label = "Log session…",
                                    onClick = {
                                        expanded = false
                                        onStartLogging(connection)
                                    },
                                )
                            }
                        }
                    }

                    SessionSheetCard {
                        AgentSectionHeader(
                            title = "Alerts & Bell",
                            icon = Icons.Outlined.Notifications,
                        )
                        if (onToggleOutputAlerts != null) {
                            SessionDrawerActionRow(
                                icon = if (outputAlertsEnabled) {
                                    Icons.Outlined.NotificationsActive
                                } else {
                                    Icons.Outlined.NotificationsOff
                                },
                                label = if (outputAlertsEnabled) "Output alerts: On" else "Output alerts: Muted",
                                onClick = {
                                    expanded = false
                                    onToggleOutputAlerts()
                                },
                            )
                        }
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BellMode.entries.forEach { mode ->
                                AgentTabPill(
                                    label = BellPreferences.modeName(mode),
                                    selected = mode == bellMode,
                                    onClick = { onBellMode(mode) },
                                )
                            }
                        }
                    }

                    if (onClose != null) {
                        SessionSheetCard {
                            SessionDrawerActionRow(
                                icon = Icons.Outlined.Close,
                                label = "Close session",
                                destructive = true,
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
}

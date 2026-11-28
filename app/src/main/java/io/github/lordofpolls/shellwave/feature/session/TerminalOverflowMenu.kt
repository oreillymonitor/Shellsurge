package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lordofpolls.shellwave.core.prefs.BellMode
import io.github.lordofpolls.shellwave.core.prefs.BellPreferences
import io.github.lordofpolls.shellwave.ssh.SshConnection
import io.github.lordofpolls.shellwave.ui.design.ActionTrayChip

/**
 * Down/up chevron toggle pinned at the right edge of the terminal session chip rail.
 */
@Composable
internal fun TerminalTrayToggleButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Hide session controls" else "Show session controls",
        )
    }
}

/**
 * Top-anchored expanding overlay tray for session tools, file actions, alerts/bell, and close.
 * Rendered as an overlay over the terminal container so toggling it never resizes the PTY.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalSessionTray(
    expanded: Boolean,
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
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    outputAlertsEnabled: Boolean = true,
    onToggleOutputAlerts: (() -> Unit)? = null,
    onOpenGitDiff: (() -> Unit)? = null,
    onOpenLinks: (() -> Unit)? = null,
    onOpenReadableOutput: (() -> Unit)? = null,
    onOpenTmux: (() -> Unit)? = null,
    onOpenListeningPorts: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Row 1: quick tool launchers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TrayQuickTool(
                            icon = Icons.Outlined.Search,
                            label = "Search",
                            onClick = {
                                onDismiss()
                                onSearch()
                            },
                        )
                        if (onOpenLinks != null) {
                            TrayQuickTool(
                                icon = Icons.Outlined.Link,
                                label = "Links",
                                onClick = {
                                    onDismiss()
                                    onOpenLinks()
                                },
                            )
                        }
                        if (onOpenReadableOutput != null) {
                            TrayQuickTool(
                                icon = Icons.AutoMirrored.Outlined.Article,
                                label = "Read",
                                onClick = {
                                    onDismiss()
                                    onOpenReadableOutput()
                                },
                            )
                        }
                        if (connection != null && onOpenTmux != null) {
                            TrayQuickTool(
                                icon = Icons.AutoMirrored.Outlined.ViewQuilt,
                                label = "tmux",
                                onClick = {
                                    onDismiss()
                                    onOpenTmux()
                                },
                            )
                        }
                        if (connection != null && onOpenListeningPorts != null) {
                            TrayQuickTool(
                                icon = Icons.Outlined.Router,
                                label = "Ports",
                                onClick = {
                                    onDismiss()
                                    onOpenListeningPorts()
                                },
                            )
                        }
                    }

                    val hasMiddleRow = connection != null || isLogging
                    if (hasMiddleRow) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (connection != null) {
                                ActionTrayChip(
                                    label = "Browse files",
                                    icon = Icons.Outlined.Folder,
                                    onClick = {
                                        onDismiss()
                                        onBrowseFiles(connection)
                                    },
                                )
                                ActionTrayChip(
                                    label = "Upload",
                                    icon = Icons.Outlined.Upload,
                                    onClick = {
                                        onDismiss()
                                        onUpload(connection)
                                    },
                                )
                                ActionTrayChip(
                                    label = "Download",
                                    icon = Icons.Outlined.Download,
                                    onClick = {
                                        onDismiss()
                                        onDownload(connection)
                                    },
                                )
                                if (onOpenGitDiff != null) {
                                    ActionTrayChip(
                                        label = "Git diff",
                                        icon = Icons.Outlined.Code,
                                        onClick = {
                                            onDismiss()
                                            onOpenGitDiff()
                                        },
                                    )
                                }
                                if (onRunScript != null) {
                                    ActionTrayChip(
                                        label = "Run script",
                                        icon = Icons.Outlined.PlayArrow,
                                        onClick = {
                                            onDismiss()
                                            onRunScript()
                                        },
                                    )
                                }
                            }
                            if (isLogging) {
                                ActionTrayChip(
                                    label = "Stop logging",
                                    icon = Icons.Outlined.StopCircle,
                                    selected = true,
                                    onClick = {
                                        onDismiss()
                                        onStopLogging()
                                    },
                                )
                            } else if (connection != null) {
                                ActionTrayChip(
                                    label = "Log session",
                                    icon = Icons.Outlined.Save,
                                    onClick = {
                                        onDismiss()
                                        onStartLogging(connection)
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (onToggleOutputAlerts != null) {
                            ActionTrayChip(
                                label = if (outputAlertsEnabled) "Alerts: On" else "Alerts: Muted",
                                icon = if (outputAlertsEnabled) {
                                    Icons.Outlined.NotificationsActive
                                } else {
                                    Icons.Outlined.NotificationsOff
                                },
                                selected = outputAlertsEnabled,
                                onClick = onToggleOutputAlerts,
                            )
                        }
                        BellMode.entries.forEach { mode ->
                            ActionTrayChip(
                                label = "Bell: ${BellPreferences.modeName(mode)}",
                                selected = mode == bellMode,
                                onClick = { onBellMode(mode) },
                            )
                        }
                        if (onClose != null) {
                            ActionTrayChip(
                                label = "Close session",
                                icon = Icons.Outlined.Close,
                                destructive = true,
                                onClick = {
                                    onDismiss()
                                    onClose()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayQuickTool(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

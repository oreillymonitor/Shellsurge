package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.core.net.Reachability
import io.github.lordofpolls.shellwave.ssh.SessionStatus

/** `ui/design` takes the fields it renders, never a persistence type. */
data class HostScriptAction(val id: Long, val name: String, val onRun: () -> Unit)

/** A null [accent] is "No group". */
data class HostMoveTarget(val name: String, val accent: Color?, val current: Boolean, val onMove: () -> Unit)

private enum class HostTraySubPanel { NONE, SCRIPTS, GROUPS }

/**
 * Name and status on row 1, `user@host` (plus `:port` when not 22) on row 2; tapping the trailing
 * chevron (or long-pressing the card) expands an inline action tray directly inside the card.
 *
 * StatusMarker appears inline only while a session for this host is live or transitioning;
 * otherwise a non-null [reachability] puts a [ReachabilityMarker] in the same slot, never both.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun HostCard(
    displayName: String,
    identity: String,
    liveStatus: SessionStatus?,
    reachability: Reachability?,
    scripts: List<HostScriptAction>,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    /** Null hides the Wake item rather than disabling it. */
    onWake: (() -> Unit)? = null,
    isWolTile: Boolean = false,
    /** Null hides the item rather than disabling it. */
    onUseForWolTile: (() -> Unit)? = null,
    /** Row accent. Null renders the icon in the default content colour. */
    accent: Color? = null,
    /** Null in every sort mode but manual. */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    /** Empty hides "Move to group". */
    moveTargets: List<HostMoveTarget> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var subPanel by remember { mutableStateOf(HostTraySubPanel.NONE) }

    fun toggleExpanded() {
        subPanel = HostTraySubPanel.NONE
        expanded = !expanded
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { toggleExpanded() },
                    onClickLabel = "Connect",
                    onLongClickLabel = "Host options",
                ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (liveStatus != null) {
                            StatusMarker(liveStatus)
                        } else if (reachability != null) {
                            ReachabilityMarker(reachability)
                        }
                    }
                    MachineText(
                        identity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { toggleExpanded() }) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Hide options for $displayName" else "Show options for $displayName",
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionTrayChip(
                            label = "Edit",
                            icon = Icons.Outlined.Edit,
                            onClick = {
                                expanded = false
                                onEdit()
                            },
                        )
                        ActionTrayChip(
                            label = "Duplicate",
                            icon = Icons.Outlined.ContentCopy,
                            onClick = {
                                expanded = false
                                onDuplicate()
                            },
                        )
                        if (onWake != null) {
                            ActionTrayChip(
                                label = "Wake",
                                icon = Icons.Outlined.PowerSettingsNew,
                                onClick = {
                                    expanded = false
                                    onWake()
                                },
                            )
                        }
                        if (scripts.isNotEmpty()) {
                            ActionTrayChip(
                                label = "Scripts",
                                icon = Icons.Outlined.PlayArrow,
                                selected = subPanel == HostTraySubPanel.SCRIPTS,
                                onClick = {
                                    subPanel = if (subPanel == HostTraySubPanel.SCRIPTS) {
                                        HostTraySubPanel.NONE
                                    } else {
                                        HostTraySubPanel.SCRIPTS
                                    }
                                },
                            )
                        }
                        if (moveTargets.isNotEmpty()) {
                            ActionTrayChip(
                                label = "Group",
                                icon = Icons.Outlined.Folder,
                                selected = subPanel == HostTraySubPanel.GROUPS,
                                onClick = {
                                    subPanel = if (subPanel == HostTraySubPanel.GROUPS) {
                                        HostTraySubPanel.NONE
                                    } else {
                                        HostTraySubPanel.GROUPS
                                    }
                                },
                            )
                        }
                        if (onUseForWolTile != null) {
                            ActionTrayChip(
                                label = if (isWolTile) "Remove WOL tile" else "WOL tile",
                                icon = Icons.Outlined.ToggleOn,
                                selected = isWolTile,
                                onClick = {
                                    expanded = false
                                    onUseForWolTile()
                                },
                            )
                        }
                        if (onMoveUp != null) {
                            ActionTrayChip(
                                label = "Move up",
                                icon = Icons.Outlined.ArrowUpward,
                                onClick = {
                                    expanded = false
                                    onMoveUp()
                                },
                            )
                        }
                        if (onMoveDown != null) {
                            ActionTrayChip(
                                label = "Move down",
                                icon = Icons.Outlined.ArrowDownward,
                                onClick = {
                                    expanded = false
                                    onMoveDown()
                                },
                            )
                        }
                        ActionTrayChip(
                            label = "Delete",
                            icon = Icons.Outlined.Delete,
                            destructive = true,
                            onClick = {
                                expanded = false
                                onDelete()
                            },
                        )
                    }

                    AnimatedVisibility(visible = subPanel == HostTraySubPanel.SCRIPTS && scripts.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                scripts.forEach { script ->
                                    ActionTrayChip(
                                        label = script.name,
                                        icon = Icons.Outlined.PlayArrow,
                                        onClick = {
                                            expanded = false
                                            subPanel = HostTraySubPanel.NONE
                                            script.onRun()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = subPanel == HostTraySubPanel.GROUPS && moveTargets.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                moveTargets.forEach { target ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (target.accent != null) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(end = 4.dp)
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(target.accent),
                                            )
                                        }
                                        ActionTrayChip(
                                            label = target.name,
                                            selected = target.current,
                                            onClick = {
                                                expanded = false
                                                subPanel = HostTraySubPanel.NONE
                                                target.onMove()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package io.github.lordofpolls.shellwave.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Run takes the leading edge as the frequent action; tapping the body edits; the trailing chevron
 * expands an inline action tray for History, Widget/QS-tile pinning, and Delete.
 *
 * The `WIDGET`/`QUICK SETTINGS` badges are words rather than pictographs: registration is machine
 * truth and belongs beside the mode and target. [onPinToWidget]/[onUseForQsTile] are nullable
 * because a background trigger refuses anything but a capture script.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScriptCard(
    name: String,
    modeAndTarget: String,
    pinnedToWidget: Boolean,
    isQsTile: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onHistory: (() -> Unit)? = null,
    onPinToWidget: (() -> Unit)? = null,
    onUseForQsTile: (() -> Unit)? = null,
    accent: Color? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                // Tighter than the other cards: the two IconButtons each bring their own 48dp box.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRun) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Run $name",
                        tint = accent ?: LocalContentColor.current,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClickLabel = "Edit") { onEdit() }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MachineText(
                            modeAndTarget,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (pinnedToWidget) TriggerBadge("WIDGET")
                        if (isQsTile) TriggerBadge("QUICK SETTINGS")
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Hide options for $name" else "Show options for $name",
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
                        if (onHistory != null) {
                            ActionTrayChip(
                                label = "History",
                                icon = Icons.Outlined.History,
                                onClick = {
                                    expanded = false
                                    onHistory()
                                },
                            )
                        }
                        if (onPinToWidget != null) {
                            ActionTrayChip(
                                label = if (pinnedToWidget) "Unpin widget" else "Pin to widget",
                                icon = Icons.Outlined.PushPin,
                                selected = pinnedToWidget,
                                onClick = {
                                    expanded = false
                                    onPinToWidget()
                                },
                            )
                        }
                        if (onUseForQsTile != null) {
                            ActionTrayChip(
                                label = if (isQsTile) "Remove tile" else "QS tile",
                                icon = Icons.Outlined.ToggleOn,
                                selected = isQsTile,
                                onClick = {
                                    expanded = false
                                    onUseForQsTile()
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
                }
            }
        }
    }
}

/** No container or tint: a chip would make these look like controls when they state facts. */
@Composable
private fun TriggerBadge(token: String) {
    MachineText(
        token,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

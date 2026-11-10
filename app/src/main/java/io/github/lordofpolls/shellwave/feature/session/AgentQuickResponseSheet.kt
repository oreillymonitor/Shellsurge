package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Compact bottom sheet menu providing immediate 1-tap responses, accessible slash commands
 * (/models, /usage, etc.), tight vibe directives, and quick controls for AI coding agents.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AgentQuickResponseSheet(
    onSendText: (text: String, sendEnter: Boolean) -> Unit,
    onSendRaw: (control: String) -> Unit,
    onOpenComposerWithText: (initialText: String) -> Unit,
    onSummarize: (() -> Unit)? = null,
    isSummarizing: Boolean = false,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var autoDismiss by remember { mutableStateOf(true) }

    fun execute(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
        if (autoDismiss) {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "Agent Quick Response",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "1-tap replies & vibe directives",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onOpenComposerWithText("")
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(
                        Icons.Outlined.EditNote,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Composer",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }

            // Summarize & Listen Card
            if (onSummarize != null) {
                Card(
                    onClick = { execute { onSummarize() } },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSummarizing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (isSummarizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSummarizing) "Gemini is summarizing terminal..." else "Summarize Terminal & Listen",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isSummarizing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = if (isSummarizing) "Analyzing terminal output with Gemini..." else "On-device digest, read aloud & voice reply",
                                style = MaterialTheme.typography.bodySmall,
                                color = (if (isSummarizing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }

            // Section 1: Immediate Decisions & Controls (Tightened)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Yes / Confirm
                        Button(
                            onClick = { execute { onSendText("y", true) } },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Yes (y)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }

                        // No / Reject
                        Button(
                            onClick = { execute { onSendText("n", true) } },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("No (n)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }

                        // Enter / Continue
                        FilledTonalButton(
                            onClick = { execute { onSendText("", true) } },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Enter", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Numbered choice row + Stop & Esc
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        (1..4).forEach { num ->
                            FilledTonalButton(
                                onClick = { execute { onSendText("$num", true) } },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text("$num", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            }
                        }

                        // Stop (Ctrl+C)
                        Button(
                            onClick = { execute { onSendRaw("\u0003") } },
                            modifier = Modifier.height(30.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Stop", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        // ESC
                        FilledTonalButton(
                            onClick = { execute { onSendRaw("\u001B") } },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("ESC", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Section 2: Standard System Prompts & Slash Commands (Prominent & Accessible)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "SYSTEM PROMPTS & SLASH COMMANDS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                val slashCommands = listOf(
                    "/models",
                    "/usage",
                    "/compact",
                    "/plan",
                    "/goal",
                    "/review",
                    "/browser",
                    "/learn",
                    "/help",
                    "/clear",
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    slashCommands.forEach { slash ->
                        CompactDirectiveChip(
                            label = slash,
                            command = slash,
                            onSend = { cmd -> execute { onSendText(cmd, true) } },
                            onEdit = { cmd ->
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                    onOpenComposerWithText(cmd)
                                }
                            },
                            isMonospace = true,
                            highlight = slash in listOf("/models", "/usage", "/plan", "/goal"),
                        )
                    }
                }
            }

            // Section 3: Vibe Directives (Tighter, Compact Unified Pills)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "VIBE DIRECTIVES",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                val directives = listOf(
                    "Proceed" to "proceed",
                    "Looks good" to "looks good, proceed",
                    "Explain" to "explain what you did and why",
                    "Run tests" to "run tests",
                    "Git diff" to "git diff",
                    "Git status" to "git status",
                    "Undo change" to "undo last change and try an alternate approach",
                    "Continue" to "continue",
                    "Fix error" to "fix this error and explain the solution",
                    "Add comments" to "add clear comments explaining this logic",
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    directives.forEach { (label, command) ->
                        CompactDirectiveChip(
                            label = label,
                            command = command,
                            onSend = { cmd -> execute { onSendText(cmd, true) } },
                            onEdit = { cmd ->
                                scope.launch {
                                    sheetState.hide()
                                    onDismiss()
                                    onOpenComposerWithText(cmd)
                                }
                            },
                            isMonospace = false,
                        )
                    }
                }
            }

            // Section 4: Launch Coding Agent CLI
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Launch:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )

                // agy
                FilledTonalButton(
                    onClick = { execute { onSendText("agy", true) } },
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("agy", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                // agy -c
                FilledTonalButton(
                    onClick = { execute { onSendText("agy -c", true) } },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(30.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("agy -c", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                // opencode
                FilledTonalButton(
                    onClick = { execute { onSendText("opencode", true) } },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(30.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("opencode", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                // clear
                FilledTonalButton(
                    onClick = { execute { onSendText("clear", true) } },
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("clear", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            // Options: Auto-dismiss toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = autoDismiss,
                    onCheckedChange = { autoDismiss = it },
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Dismiss menu after sending command",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * A tight, space-efficient unified pill for directives and slash commands.
 * Tapping the label executes the command immediately; tapping the subtle pencil icon
 * opens it in the Prompt Composer for tweaking before sending.
 */
@Composable
private fun CompactDirectiveChip(
    label: String,
    command: String,
    onSend: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
    highlight: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (highlight) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            },
        ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(30.dp),
        ) {
            Box(
                modifier = Modifier
                    .clickable { onSend(command) }
                    .padding(start = 9.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = if (isMonospace) FontFamily.Monospace else null,
                    fontWeight = if (highlight || isMonospace) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (highlight) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Box(
                modifier = Modifier
                    .clickable { onEdit(command) }
                    .padding(start = 2.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit $label in composer",
                    modifier = Modifier.size(11.dp),
                    tint = if (highlight) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
            }
        }
    }
}

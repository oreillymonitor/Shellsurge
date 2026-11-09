package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Bottom sheet menu providing immediate 1-tap responses and quick controls
 * for interacting with AI coding agents (such as Antigravity, Claude Code, Aider, OpenCode)
 * from a mobile terminal session.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AgentQuickResponseSheet(
    onSendText: (text: String, sendEnter: Boolean) -> Unit,
    onSendRaw: (control: String) -> Unit,
    onOpenComposerWithText: (initialText: String) -> Unit,
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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "Agent Quick Response",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "1-tap replies & vibe coding shortcuts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AssistChip(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            onOpenComposerWithText("")
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.EditNote,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text("Composer") },
                )
            }

            // Section 1: Immediate Decisions (Big tactile buttons)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "IMMEDIATE REPLIES",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Yes / Confirm
                        Button(
                            onClick = { execute { onSendText("y", true) } },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Yes (y)", fontWeight = FontWeight.Bold)
                        }

                        // No / Reject
                        Button(
                            onClick = { execute { onSendText("n", true) } },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("No (n)", fontWeight = FontWeight.Bold)
                        }

                        // Enter / Continue
                        FilledTonalButton(
                            onClick = { execute { onSendText("", true) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Enter")
                        }
                    }

                    // Numbered choice row for multiple choice prompts (1, 2, 3, 4)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Choice:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        (1..4).forEach { num ->
                            FilledTonalButton(
                                onClick = { execute { onSendText("$num", true) } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("$num", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // Section 2: Vibe Directives
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "VIBE DIRECTIVES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                val directives = listOf(
                    "Proceed" to "proceed",
                    "Looks good, proceed" to "looks good, proceed",
                    "Explain what you did" to "explain what you did and why",
                    "Run tests" to "run tests",
                    "Git diff" to "git diff",
                    "Git status" to "git status",
                    "Undo last change" to "undo last change and try an alternate approach",
                    "Continue" to "continue",
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    directives.forEach { (label, command) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SuggestionChip(
                                onClick = { execute { onSendText(command, true) } },
                                label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                        onOpenComposerWithText(command)
                                    }
                                },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "Edit $label in composer",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Section 3: Agent Controls & Terminal Signals
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AGENT CONTROL & SIGNALS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Ctrl+C (Interrupt)
                    Button(
                        onClick = { execute { onSendRaw("\u0003") } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ctrl+C (Stop)")
                    }

                    // Escape (ESC)
                    FilledTonalButton(
                        onClick = { execute { onSendRaw("\u001B") } },
                    ) {
                        Text("ESC")
                    }

                    // Clear
                    FilledTonalButton(
                        onClick = { execute { onSendText("clear", true) } },
                    ) {
                        Text("clear")
                    }

                    // Slash commands
                    listOf("/compact", "/plan", "/goal", "/review", "/help").forEach { slash ->
                        FilterChip(
                            selected = false,
                            onClick = { execute { onSendText(slash, true) } },
                            label = { Text(slash, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                        )
                    }
                }
            }

            // Options: Auto-dismiss toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = autoDismiss,
                    onCheckedChange = { autoDismiss = it },
                )
                Text(
                    text = "Dismiss menu after sending command",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

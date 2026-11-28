package io.github.lordofpolls.shellwave.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.lordofpolls.shellwave.ui.design.ChromeMonoFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lordofpolls.shellwave.core.prefs.AgentCli
import io.github.lordofpolls.shellwave.core.prefs.AgentCliPreferences
import io.github.lordofpolls.shellwave.core.prefs.rememberPrefState
import kotlinx.coroutines.launch

/**
 * Compact bottom sheet menu providing immediate 1-tap responses, switchable slash commands
 * for enabled AI CLI agents, quick prompts, and launch controls.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AgentQuickResponseSheet(
    onSendText: (text: String, sendEnter: Boolean) -> Unit,
    onSendRaw: (control: String) -> Unit,
    onOpenComposerWithText: (initialText: String) -> Unit,
    modifier: Modifier = Modifier,
    onSummarize: (() -> Unit)? = null,
    isSummarizing: Boolean = false,
    isQuickBarActive: Boolean = false,
    onToggleQuickBar: (() -> Unit)? = null,
    onOpenGitDiff: (() -> Unit)? = null,
    onOpenReadableOutput: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var autoDismiss by remember { mutableStateOf(true) }
    val enabledAgents by rememberPrefState { AgentCliPreferences.getEnabledAgents(it) }
    val activeAgent by rememberPrefState { AgentCliPreferences.getSelectedAgent(it) }

    fun selectAgent(agent: AgentCli) {
        AgentCliPreferences.setSelectedAgent(context, agent)
    }

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
        dragHandle = { AgentSheetDragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            AgentSheetHeader(
                icon = Icons.Outlined.SmartToy,
                title = "Quick Response",
                enabledAgents = enabledAgents,
                activeAgent = activeAgent,
                onSelectAgent = ::selectAgent,
                onDismiss = onDismiss,
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Top Action Bar matching Composer's 36dp control strip
                if (onSummarize != null || onToggleQuickBar != null || onOpenGitDiff != null || onOpenReadableOutput != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onSummarize != null) {
                            FilledTonalButton(
                                onClick = { execute { onSummarize() } },
                                shape = AGENT_BUTTON_SHAPE,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isSummarizing) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    contentColor = if (isSummarizing) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp),
                            ) {
                                if (isSummarizing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isSummarizing) "Summarizing…" else "Summarize",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (onToggleQuickBar != null) {
                            OutlinedButton(
                                onClick = { execute { onToggleQuickBar() } },
                                shape = AGENT_BUTTON_SHAPE,
                                colors = if (isQuickBarActive) {
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isQuickBarActive) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.SwapHoriz,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = if (isQuickBarActive) "Shell Keys" else "Agent Deck",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }

                        if (onOpenGitDiff != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                        onOpenGitDiff()
                                    }
                                },
                                shape = AGENT_BUTTON_SHAPE,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Diff",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }

                        if (onOpenReadableOutput != null) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                        onOpenReadableOutput()
                                    }
                                },
                                shape = AGENT_BUTTON_SHAPE,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Article,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Read",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                // Immediate Decisions & Controls
                Surface(
                    shape = AGENT_CARD_SHAPE,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
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
                            Button(
                                onClick = { execute { onSendText("y", true) } },
                                shape = AGENT_BUTTON_SHAPE,
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

                            Button(
                                onClick = { execute { onSendText("n", true) } },
                                shape = AGENT_BUTTON_SHAPE,
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

                            FilledTonalButton(
                                onClick = { execute { onSendText("", true) } },
                                shape = AGENT_BUTTON_SHAPE,
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            (1..4).forEach { num ->
                                FilledTonalButton(
                                    onClick = { execute { onSendText("$num", true) } },
                                    shape = AGENT_BUTTON_SHAPE,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp),
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text("$num", fontWeight = FontWeight.Bold, fontFamily = ChromeMonoFontFamily, fontSize = 13.sp)
                                }
                            }

                            Button(
                                onClick = { execute { onSendRaw("\u0003") } },
                                shape = AGENT_BUTTON_SHAPE,
                                modifier = Modifier.height(34.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Stop", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }

                            FilledTonalButton(
                                onClick = { execute { onSendRaw("\u001B") } },
                                shape = AGENT_BUTTON_SHAPE,
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Text("ESC", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Slash Commands
                activeAgent?.let { agent ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentSectionHeader(
                            title = "Slash commands",
                            icon = Icons.Outlined.Code,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            agent.slashCommands.forEach { slash ->
                                AgentChip(
                                    label = slash,
                                    isMonospace = true,
                                    highlighted = slash in agent.highlightedCommands,
                                    onClick = { execute { onSendText(slash, true) } },
                                    onEdit = {
                                        scope.launch {
                                            sheetState.hide()
                                            onDismiss()
                                            onOpenComposerWithText(slash)
                                        }
                                    },
                                    editContentDescription = "Edit $slash in composer",
                                )
                            }
                        }
                    }
                }

                // Quick Prompts
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentSectionHeader(
                        title = "Quick prompts",
                        icon = Icons.Outlined.AutoAwesome,
                    )

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
                            AgentChip(
                                label = label,
                                onClick = { execute { onSendText(command, true) } },
                                onEdit = {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                        onOpenComposerWithText(command)
                                    }
                                },
                                editContentDescription = "Edit $label in composer",
                            )
                        }
                    }
                }

                // Launch Coding Agent CLI
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentSectionHeader(
                        title = "Launch",
                        icon = Icons.Outlined.PlayArrow,
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        enabledAgents.forEach { agent ->
                            agent.launchCommands.forEach { launchCmd ->
                                AgentUtilityPill(
                                    label = launchCmd.label,
                                    isMonospace = true,
                                    onClick = {
                                        selectAgent(agent)
                                        execute { onSendText(launchCmd.command, true) }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (launchCmd.isResume) {
                                                Icons.Outlined.History
                                            } else {
                                                Icons.Outlined.PlayArrow
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                )
                            }
                        }

                        AgentUtilityPill(
                            label = "clear",
                            isMonospace = true,
                            onClick = { execute { onSendText("clear", true) } },
                        )
                    }

                    if (enabledAgents.isEmpty()) {
                        Text(
                            text = "Enable CLI agents in Settings → Coding Agents to show slash commands and launch shortcuts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            AgentSheetFooter(
                checkboxLabel = "Dismiss after send",
                checked = autoDismiss,
                onCheckedChange = { autoDismiss = it },
                actionContent = {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                                onOpenComposerWithText("")
                            }
                        },
                        shape = AGENT_BUTTON_SHAPE,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Composer",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        }
    }
}

package io.github.lordofpolls.shellwave.terminal

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lordofpolls.shellwave.ui.design.MachineText
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Ctrl, Alt, then [keys]. The modifiers latch on tap and are always present, not part of [keys],
 * which is what the editor edits; the next key, from the bar or the IME, consumes and clears them.
 *
 * Labels are uppercased with `Locale.ROOT` - a key names something the far end receives, and
 * mixed-case "Esc" beside a mono grid was the last chrome speaking in the app's own voice.
 * `Locale.ROOT` also keeps a Turkish device from turning "list" into "LIST" with a dotted I. Arrow
 * glyphs are left alone.
 *
 * [rows] gives two rows where one would mean a horizontal scroll hiding the rightmost keys behind
 * an unadvertised gesture. It halves the button list in order, modifiers included.
 *
 * A [KeyBarKeyType.CURSOR_CLUSTER] is a pinned column, never a member of that flow. It is the one
 * entry two rows tall, so chunking it alongside flat keys went wrong both ways: at `rows = 2` it
 * landed inside one of the rows, stacking a 48dp row and a gap on top of its own 100dp; at
 * `rows = 1` it stretched the single row to that 100dp and left every flat key floating above 52dp
 * of nothing. Lifting it out fixes both, and the height is then spent rather than wasted - with a
 * cluster present the flowing keys take two rows regardless of [rows], the second one being paid
 * for already. [MIN_BUTTONS_FOR_FREE_SECOND_ROW] is why that is not unconditional.
 *
 * The keyboard key sits outside the scroll on purpose. Every other button may be scrolled off the
 * right edge, survivable for `HOME` and fatal for this one: the soft keyboard is what the user
 * reaches for when the IME is hidden, and a control that is both the only way back and reachable
 * only by an unadvertised swipe is a dead end. It sends nothing to the far end, so drawing it as
 * one more [MachineText] key would say the opposite.
 *
 * A bare arrow read aloud says nothing about what it does, and latch state is carried by colour
 * alone: hence [keyBarKeyDescription] building descriptions from [KeyBarKey] and not the rendered
 * label, [LatchButton]'s `stateDescription`, and the explicit
 * `Modifier.minimumInteractiveComponentSize` that M3's `Button` family omits. Uppercasing changes
 * what a key looks like, never what a screen reader is told. [KeyboardKey] has no label to fall
 * back on, so its description is the only thing a reader gets, and it swaps with the state.
 */
@Composable
fun KeyBar(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSpecialKey: (keyCode: Int) -> Unit,
    onMacro: (text: String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardVisible: Boolean = false,
    onKeyboardToggle: () -> Unit = {},
    onRawKeyboardToggle: (() -> Unit)? = null,
    onOpenQuickResponse: (() -> Unit)? = null,
    agentDeckActive: Boolean = true,
    onToggleDeck: (() -> Unit)? = null,
    agentContext: AgentBarContext = AgentBarContext.Default,
    isVoiceListening: Boolean = false,
    onVoiceClick: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    composerActive: Boolean = false,
    hasComposerDraft: Boolean = false,
    composerContent: (@Composable (Modifier) -> Unit)? = null,
    onQuickAction: ((text: String, sendEnter: Boolean) -> Unit)? = null,
    onSendRaw: ((control: String) -> Unit)? = null,
    keys: List<KeyBarKey> = DEFAULT_KEY_BAR_KEYS,
    rows: Int = 1,
    hasCustomLayout: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current

    if (composerActive && composerContent != null) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KeyGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            composerContent(Modifier.weight(1f))
            Column(
                verticalArrangement = Arrangement.spacedBy(KeyGap),
                horizontalAlignment = Alignment.End,
            ) {
                if (onVoiceClick != null) {
                    VoicePinnedKey(listening = isVoiceListening, onClick = onVoiceClick)
                }
                KeyboardKey(
                    visible = keyboardVisible,
                    onClick = onKeyboardToggle,
                    onLongClick = onRawKeyboardToggle ?: { onSpecialKey(KeyEvent.KEYCODE_ENTER) },
                    modifier = Modifier.size(ClusterKeySize),
                    composeMode = true,
                    hasDraft = hasComposerDraft,
                )
            }
        }
        return
    }

    // A cluster is drawn as its own pinned column below, so it never enters the chunked flow.
    val hasCluster = keys.any { it.type == KeyBarKeyType.CURSOR_CLUSTER }
    val showAgentDeck = agentDeckActive && onQuickAction != null
    val useBuiltInShellDeck = !showAgentDeck && !hasCustomLayout

    // The flowing part of the bar for custom layouts, as one ordered list.
    val buttons: List<@Composable () -> Unit> =
        buildList {
            add { LatchButton(label = "Ctrl", latched = ctrlLatched, onClick = onCtrlToggle) }
            add { LatchButton(label = "Alt", latched = altLatched, onClick = onAltToggle) }
            keys.filter { it.type != KeyBarKeyType.CURSOR_CLUSTER }.forEach { key ->
                add {
                    val isLong = key.label.length > 4
                    FilledTonalButton(
                        onClick = {
                            haptics.keyTap()
                            when (key.type) {
                                KeyBarKeyType.SPECIAL -> onSpecialKey(key.keyCode)
                                KeyBarKeyType.MACRO -> onMacro(key.macroText)
                                KeyBarKeyType.CURSOR_CLUSTER -> Unit // filtered out above
                            }
                        },
                        modifier =
                            if (isLong) {
                                Modifier
                                    .height(ClusterKeySize)
                                    .widthIn(min = ClusterKeySize)
                                    .semantics { contentDescription = keyBarKeyDescription(key) }
                            } else {
                                Modifier
                                    .size(ClusterKeySize)
                                    .semantics { contentDescription = keyBarKeyDescription(key) }
                            },
                        contentPadding = if (isLong) KeyContentPadding else PaddingValues(0.dp),
                    ) {
                        MachineText(
                            key.label.uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

    // Stacked pinned controls make the strip two rows tall, so two rows of keys are always free.
    val hasTwoRowPinned = hasCluster || showAgentDeck || useBuiltInShellDeck || onOpenQuickResponse != null || onVoiceClick != null
    val freeSecondRow = hasTwoRowPinned && buttons.size >= MIN_BUTTONS_FOR_FREE_SECOND_ROW
    val rowCount = maxOf(rows.coerceIn(1, MAX_KEY_BAR_ROWS), if (freeSecondRow) 2 else 1)
    // Ceiling division puts the longer row first: the bar grows away from the terminal.
    val perRow = ((buttons.size + rowCount - 1) / rowCount).coerceAtLeast(1)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
        // Bottom, since the pinned columns are the tall ones: fewer flowing rows should settle
        // against the lower arm, where the thumb rests, instead of floating above a gap.
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            // Weighted, so the pinned columns measure first and the scrolling rows take what is
            // left. The other way round, a long layout pushes the pinned controls and keyboard key
            // off-screen, which is the thing being prevented.
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            when {
                showAgentDeck -> {
                    AgentDeckRows(
                        agentContext = agentContext,
                        onQuickAction = onQuickAction,
                        onSpecialKey = onSpecialKey,
                        onSendRaw = onSendRaw,
                        onMacro = onMacro,
                        onToggleDeck = onToggleDeck,
                        onKeyboardToggle = onKeyboardToggle,
                        onRawKeyboardToggle = onRawKeyboardToggle,
                        isVoiceListening = isVoiceListening,
                        onVoiceClick = onVoiceClick,
                        onPaste = onPaste,
                    )
                }

                useBuiltInShellDeck -> {
                    DefaultShellDeckRows(
                        ctrlLatched = ctrlLatched,
                        altLatched = altLatched,
                        onCtrlToggle = onCtrlToggle,
                        onAltToggle = onAltToggle,
                        onSpecialKey = onSpecialKey,
                        onMacro = onMacro,
                        onToggleDeck = onToggleDeck,
                    )
                }

                else -> {
                    val customRows = buttons.chunked(perRow)
                    customRows.forEachIndexed { rowIdx, rowButtons ->
                        val insertDeckToggle = onToggleDeck != null &&
                            ((customRows.size >= 2 && rowIdx == 1) || (customRows.size == 1 && rowIdx == 0))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(KeyGap),
                        ) {
                            if (insertDeckToggle) {
                                DeckToggleButton(inShellDeck = true, onClick = onToggleDeck)
                            }
                            rowButtons.forEach { it() }
                        }
                    }
                }
            }
        }

        if (showAgentDeck || useBuiltInShellDeck) {
            VerticalArrowColumn(onSpecialKey = onSpecialKey)
        } else if (hasCluster) {
            CursorCluster(onSpecialKey = onSpecialKey)
        }

        // Pinned controls at the right edge: 2x2 grid (Robot/Voice on top, Enter/Keyboard below)
        Column(
            verticalArrangement = Arrangement.spacedBy(KeyGap),
            horizontalAlignment = Alignment.End,
        ) {
            val topRowKeys: List<@Composable () -> Unit> = buildList {
                if (onOpenQuickResponse != null) {
                    add {
                        AgentQuickResponseKey(
                            onClick = onOpenQuickResponse,
                            onLongClick = onToggleDeck,
                            active = !agentDeckActive,
                        )
                    }
                }
                if (onVoiceClick != null) {
                    add {
                        VoicePinnedKey(
                            listening = isVoiceListening,
                            onClick = onVoiceClick,
                        )
                    }
                }
            }

            if (topRowKeys.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    topRowKeys.forEach { it() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                DedicatedEnterKey(
                    onClick = { onSpecialKey(KeyEvent.KEYCODE_ENTER) },
                )
                KeyboardKey(
                    visible = keyboardVisible,
                    onClick = onKeyboardToggle,
                    onLongClick = onRawKeyboardToggle ?: { onSpecialKey(KeyEvent.KEYCODE_ENTER) },
                    modifier = Modifier.size(ClusterKeySize),
                    composeMode = agentDeckActive && agentContext !is AgentBarContext.PasswordPrompt,
                    hasDraft = hasComposerDraft,
                )
            }
        }
    }
}

/**
 * Below this many flowing buttons, a cluster's spare row is left unused - see [KeyBar]'s doc. Four
 * is the smallest count that splits into two rows of more than one, and Ctrl/Alt already account
 * for two of it, so in practice this only holds back a layout stripped down to one or two keys.
 */
private const val MIN_BUTTONS_FOR_FREE_SECOND_ROW = 4

/**
 * Agent-first two-row deck: Row 1 adapts dynamically to the terminal state (detected numbered
 * choices, `[y/n]` confirmation, password/passphrase prompt, active run controls, or idle
 * `1 2 3 Y N`), while Row 2 holds fixed muscle-memory controls (`⇄`, `ESC`, `⇧TAB`, `^C`, `📋`).
 */
@Composable
private fun AgentDeckRows(
    agentContext: AgentBarContext,
    onQuickAction: (text: String, sendEnter: Boolean) -> Unit,
    onSpecialKey: (keyCode: Int) -> Unit,
    onSendRaw: ((control: String) -> Unit)?,
    onMacro: (text: String) -> Unit,
    onToggleDeck: (() -> Unit)?,
    onKeyboardToggle: () -> Unit,
    onRawKeyboardToggle: (() -> Unit)?,
    isVoiceListening: Boolean,
    onVoiceClick: (() -> Unit)?,
    onPaste: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        when (agentContext) {
            is AgentBarContext.NumberedChoices -> {
                agentContext.choices.forEachIndexed { index, choice ->
                    val isFirst = index == 0
                    KeyBarPillButton(
                        label = choice.label,
                        contentDescription = "Select option ${choice.label}",
                        isMonospace = false,
                        containerColor = if (isFirst) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (isFirst) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        onClick = { onQuickAction(choice.sendText, true) },
                    )
                }
            }

            is AgentBarContext.YesNoPrompt -> {
                val yesDefault = agentContext.defaultYes != false
                KeyBarPillButton(
                    label = "✓ YES",
                    contentDescription = "Answer yes",
                    containerColor = if (yesDefault) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (yesDefault) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    onClick = { onQuickAction("y", true) },
                )
                KeyBarPillButton(
                    label = "✕ NO",
                    contentDescription = "Answer no",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { onQuickAction("n", true) },
                )
                if (agentContext.defaultYes != null) {
                    KeyBarPillButton(
                        label = "↵ DEFAULT",
                        contentDescription = "Accept default response",
                        onClick = { onQuickAction("", true) },
                    )
                }
            }

            AgentBarContext.PasswordPrompt -> {
                KeyBarPillButton(
                    label = "TYPE SECRET",
                    contentDescription = "Open terminal keyboard for password entry",
                    icon = Icons.Outlined.Keyboard,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onRawKeyboardToggle ?: onKeyboardToggle,
                )
                QuickDrawerButton(
                    label = "ESC",
                    contentDescription = "Send Escape key",
                    onClick = { onSpecialKey(KeyEvent.KEYCODE_ESCAPE) },
                )
                QuickDrawerButton(
                    label = "^C",
                    contentDescription = "Send Ctrl+C interrupt",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = {
                        if (onSendRaw != null) {
                            onSendRaw("\u0003")
                        } else {
                            onQuickAction("\u0003", false)
                        }
                    },
                )
            }

            AgentBarContext.Running -> {
                KeyBarPillButton(
                    label = "ESC STOP",
                    contentDescription = "Send Escape to stop agent",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { onSpecialKey(KeyEvent.KEYCODE_ESCAPE) },
                )
                KeyBarPillButton(
                    label = "^C KILL",
                    contentDescription = "Send Ctrl+C interrupt",
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = {
                        if (onSendRaw != null) {
                            onSendRaw("\u0003")
                        } else {
                            onQuickAction("\u0003", false)
                        }
                    },
                )
                if (onVoiceClick != null) {
                    KeyBarPillButton(
                        label = if (isVoiceListening) "LISTENING…" else "STEER",
                        contentDescription = if (isVoiceListening) "Stop voice input" else "Steer agent by voice",
                        icon = if (isVoiceListening) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,
                        containerColor = if (isVoiceListening) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (isVoiceListening) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        onClick = onVoiceClick,
                    )
                }
            }

            AgentBarContext.Default -> {
                (1..3).forEach { num ->
                    QuickDrawerButton(
                        label = "$num",
                        contentDescription = "Send $num and Enter",
                        onClick = { onQuickAction("$num", true) },
                    )
                }
                QuickDrawerButton(
                    label = "Y",
                    contentDescription = "Send y and Enter",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { onQuickAction("y", true) },
                )
                QuickDrawerButton(
                    label = "N",
                    contentDescription = "Send n and Enter",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { onQuickAction("n", true) },
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        if (onToggleDeck != null) {
            DeckToggleButton(inShellDeck = false, onClick = onToggleDeck)
        }
        QuickDrawerButton(
            label = "ESC",
            contentDescription = "Send Escape key",
            onClick = { onSpecialKey(KeyEvent.KEYCODE_ESCAPE) },
        )
        KeyBarPillButton(
            label = "⇧TAB",
            contentDescription = "Send Shift+Tab",
            onClick = {
                if (onSendRaw != null) {
                    onSendRaw("\u001B[Z")
                } else {
                    onMacro("\u001B[Z")
                }
            },
        )
        QuickDrawerButton(
            label = "^C",
            contentDescription = "Send Ctrl+C interrupt",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = {
                if (onSendRaw != null) {
                    onSendRaw("\u0003")
                } else {
                    onQuickAction("\u0003", false)
                }
            },
        )
        if (onPaste != null) {
            PasteBarButton(onClick = onPaste)
        }
    }
}

/**
 * Built-in Shell deck for hosts without a custom keybar layout, trading `PgUp`/`PgDn` (already
 * covered by the floating scrollback pills & scrollbar) for direct shell punctuation and left/right.
 */
@Composable
private fun DefaultShellDeckRows(
    ctrlLatched: Boolean,
    altLatched: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSpecialKey: (keyCode: Int) -> Unit,
    onMacro: (text: String) -> Unit,
    onToggleDeck: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        LatchButton(label = "Ctrl", latched = ctrlLatched, onClick = onCtrlToggle)
        LatchButton(label = "Alt", latched = altLatched, onClick = onAltToggle)
        QuickDrawerButton(
            label = "ESC",
            contentDescription = "Send Escape key",
            onClick = { onSpecialKey(KeyEvent.KEYCODE_ESCAPE) },
        )
        QuickDrawerButton(
            label = "TAB",
            contentDescription = "Send Tab key",
            onClick = { onSpecialKey(KeyEvent.KEYCODE_TAB) },
        )
        ClusterKey(KeyEvent.KEYCODE_DPAD_LEFT, "←", onSpecialKey)
        QuickDrawerButton(
            label = "~",
            contentDescription = "Send tilde character",
            onClick = { onMacro("~") },
        )
        QuickDrawerButton(
            label = "HOME",
            contentDescription = "Move to start of line",
            onClick = { onSpecialKey(KeyEvent.KEYCODE_MOVE_HOME) },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        if (onToggleDeck != null) {
            DeckToggleButton(inShellDeck = true, onClick = onToggleDeck)
        }
        QuickDrawerButton(
            label = "/",
            contentDescription = "Send slash character",
            onClick = { onMacro("/") },
        )
        QuickDrawerButton(
            label = "-",
            contentDescription = "Send hyphen character",
            onClick = { onMacro("-") },
        )
        QuickDrawerButton(
            label = "|",
            contentDescription = "Send pipe character",
            onClick = { onMacro("|") },
        )
        ClusterKey(KeyEvent.KEYCODE_DPAD_RIGHT, "→", onSpecialKey)
        QuickDrawerButton(
            label = "\\",
            contentDescription = "Send backslash character",
            onClick = { onMacro("\\") },
        )
        QuickDrawerButton(
            label = "END",
            contentDescription = "Move to end of line",
            onClick = { onSpecialKey(KeyEvent.KEYCODE_MOVE_END) },
        )
    }
}

@Composable
private fun DeckToggleButton(
    inShellDeck: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = {
            haptics.keyTap()
            onClick()
        },
        colors = if (inShellDeck) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        modifier = Modifier
            .size(ClusterKeySize)
            .semantics {
                contentDescription = if (inShellDeck) "Switch to Agent deck" else "Switch to Shell deck"
            },
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.SwapHoriz,
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

@Composable
private fun PasteBarButton(onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = {
            haptics.keyTap()
            onClick()
        },
        modifier = Modifier
            .size(ClusterKeySize)
            .semantics {
                contentDescription = "Paste from clipboard"
            },
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentPaste,
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

@Composable
private fun KeyBarPillButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = true,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val haptics = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = {
            haptics.keyTap()
            onClick()
        },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = modifier
            .height(ClusterKeySize)
            .widthIn(min = ClusterKeySize)
            .semantics { this.contentDescription = contentDescription },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (isMonospace) {
            MachineText(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickDrawerButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val haptics = LocalHapticFeedback.current
    val isWide = label.length > 2
    FilledTonalButton(
        onClick = {
            haptics.keyTap()
            onClick()
        },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = if (isWide) {
            Modifier
                .height(ClusterKeySize)
                .widthIn(min = ClusterKeySize)
                .semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
                .size(ClusterKeySize)
                .semantics { this.contentDescription = contentDescription }
        },
        contentPadding = if (isWide) PaddingValues(horizontal = 10.dp, vertical = 0.dp) else PaddingValues(0.dp),
    ) {
        MachineText(
            label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Shared circular button in the pinned right-hand 2x2 grid supporting both tap and long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedActionKey(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .size(ClusterKeySize)
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .combinedClickable(
                onClick = {
                    haptics.keyTap()
                    onClick()
                },
                onLongClick = onLongClick?.let { longAction ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longAction()
                    }
                },
                onClickLabel = onClickLabel,
                onLongClickLabel = onLongClickLabel,
                role = Role.Button,
            )
            .semantics {
                this.contentDescription = contentDescription
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * Pinned voice quick-reply / compose key in the top-right slot of the KeyBar grid.
 */
@Composable
private fun VoicePinnedKey(
    listening: Boolean,
    onClick: () -> Unit,
) {
    PinnedActionKey(
        onClick = onClick,
        contentDescription = if (listening) "Stop listening" else "Speak reply or prompt",
        onClickLabel = if (listening) "Stop listening" else "Start voice input",
        containerColor = if (listening) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (listening) {
            MaterialTheme.colorScheme.onError
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Icon(
            imageVector = if (listening) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

/**
 * Dedicated 1-tap Enter key pinned in the bottom-right grid.
 */
@Composable
private fun DedicatedEnterKey(
    onClick: () -> Unit,
) {
    PinnedActionKey(
        onClick = onClick,
        contentDescription = "Send Enter key",
        onClickLabel = "Send Enter",
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardReturn,
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

/**
 * Opens the Quick Response menu on tap; long-press flips between Agent Deck and Shell Deck.
 */
@Composable
private fun AgentQuickResponseKey(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    active: Boolean = false,
) {
    PinnedActionKey(
        onClick = onClick,
        onLongClick = onLongClick,
        contentDescription = if (active) {
            "Open agent quick response menu, shell deck active"
        } else {
            "Open agent quick response menu, hold to switch key deck"
        },
        onClickLabel = "Open agent quick response menu",
        onLongClickLabel = "Switch key deck",
        containerColor = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Icon(
            imageVector = Icons.Outlined.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

/**
 * Shows the soft keyboard, or hides it when it is already up; long-press sends Enter.
 *
 * This exists because focus is not visibility. The terminal's IME shim ([TerminalInputCapture]) is
 * focused for the whole life of a session, so once the keyboard has been dismissed - by the back
 * gesture, or by the system - tapping the terminal calls `requestFocus()` on a field that is
 * *already* focused, which is a no-op and brings nothing back. The bar is the only chrome that
 * survives an IME dismissal, so this is where the way back belongs, floating in the bar's place
 * when the bar is hidden.
 *
 * Icon-only: it is app chrome rather than a key, and nothing it does reaches the far end, so
 * dressing it as one more `CTRL`-style label would be a claim about the wrong thing. The glyph
 * swaps with [visible] because the button's meaning does - and the `contentDescription` swaps with
 * it, since a screen reader gets no glyph to read.
 */
@Composable
internal fun KeyboardKey(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    composeMode: Boolean = false,
    hasDraft: Boolean = false,
) {
    val highlightDraft = !visible && composeMode && hasDraft
    PinnedActionKey(
        onClick = onClick,
        onLongClick = onLongClick,
        contentDescription = when {
            visible -> "Hide the keyboard"
            composeMode && hasDraft -> "Resume saved prompt draft, hold for raw keyboard"
            composeMode -> "Open prompt composer, hold for raw keyboard"
            else -> "Show the keyboard, hold to send Enter"
        },
        onClickLabel = when {
            visible -> "Hide keyboard"
            composeMode && hasDraft -> "Resume prompt draft"
            composeMode -> "Open prompt composer"
            else -> "Show keyboard"
        },
        onLongClickLabel = if (composeMode) "Open raw keyboard" else "Send Enter",
        containerColor = if (highlightDraft) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (highlightDraft) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    visible -> Icons.Outlined.KeyboardHide
                    composeMode -> Icons.Outlined.EditNote
                    else -> Icons.Outlined.Keyboard
                },
                // The button carries the description; a second one here would have TalkBack read the
                // control twice on the way past.
                contentDescription = null,
                modifier = Modifier.size(KeyIconSize),
            )
            if (hasDraft && !visible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private fun HapticFeedback.keyTap() = performHapticFeedback(HapticFeedbackType.KeyboardTap)

const val MAX_KEY_BAR_ROWS = 2

/**
 * Compact 1-column vertical stack of Up/Down arrows used by Agent Deck and the built-in Shell Deck.
 */
@Composable
private fun VerticalArrowColumn(onSpecialKey: (keyCode: Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        ClusterKey(KeyEvent.KEYCODE_DPAD_UP, "↑", onSpecialKey)
        ClusterKey(KeyEvent.KEYCODE_DPAD_DOWN, "↓", onSpecialKey)
    }
}

/**
 * The arrow keys as the inverted T for custom layouts that include [KeyBarKeyType.CURSOR_CLUSTER].
 * Redundant `PgUp`/`PgDn` cells beside `↑` stay blank since scrollback stepping and scrubbing sit
 * directly above the bar.
 */
@Composable
private fun CursorCluster(onSpecialKey: (keyCode: Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            Spacer(modifier = Modifier.size(ClusterKeySize))
            ClusterKey(KeyEvent.KEYCODE_DPAD_UP, "↑", onSpecialKey)
            Spacer(modifier = Modifier.size(ClusterKeySize))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            ClusterKey(KeyEvent.KEYCODE_DPAD_LEFT, "←", onSpecialKey)
            ClusterKey(KeyEvent.KEYCODE_DPAD_DOWN, "↓", onSpecialKey)
            ClusterKey(KeyEvent.KEYCODE_DPAD_RIGHT, "→", onSpecialKey)
        }
    }
}

/**
 * Repeat rides `clickable`'s press state rather than a `pointerInput` of its own, which would have
 * to out-consume the button to stop it clicking too. So the initiating tap lands on release, after
 * the repeats - the count is still right.
 */
@Composable
private fun ClusterKey(
    keyCode: Int,
    glyph: String,
    onSpecialKey: (keyCode: Int) -> Unit,
    icon: ImageVector? = null,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed, keyCode) {
        if (!pressed) return@LaunchedEffect
        delay(ViewConfiguration.getKeyRepeatTimeout().toLong())
        while (true) {
            onSpecialKey(keyCode)
            delay(ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

    FilledTonalButton(
        onClick = {
            haptics.keyTap()
            onSpecialKey(keyCode)
        },
        interactionSource = interactionSource,
        modifier =
            Modifier
                .size(ClusterKeySize)
                .semantics {
                    contentDescription =
                        keyBarKeyDescription(
                            KeyBarKey(
                                glyph,
                                KeyBarKeyType.SPECIAL,
                                keyCode = keyCode
                            )
                        )
                },
        contentPadding = if (icon == null) KeyContentPadding else PaddingValues(0.dp),
    ) {
        if (icon == null) {
            MachineText(glyph, style = MaterialTheme.typography.labelMedium)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(KeyIconSize))
        }
    }
}

/** Square, because a 48x40 cell makes the T's vertical arm look shorter than its horizontal one. */
private val ClusterKeySize = 48.dp

/**
 * Under the 24dp Material default: at 24dp the icon fills a 48dp cell edge to edge and reads as
 * cramped beside the mono glyphs, which have [KeyContentPadding] around them.
 */
private val KeyIconSize = 20.dp

private val KeyGap = 4.dp

/**
 * `ButtonDefaults.ContentPadding` is sized for word-length labels. A key bar's are three or four
 * characters, and the default makes each key nearly twice as wide as its glyph needs, which is how
 * a six-key bar ends up scrolling on a phone. Vertical padding stays zero so
 * `ButtonDefaults.MinHeight` sets the height.
 */
private val KeyContentPadding =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)


@Composable
private fun LatchButton(label: String, latched: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = {
            haptics.keyTap()
            onClick()
        },
        colors =
            if (latched) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
        // Latched state is a container-colour swap, invisible to TalkBack. stateDescription is
        // announced after the label ("Ctrl, active") with no role change away from Button.
        modifier =
            Modifier
                .size(ClusterKeySize)
                .semantics {
                    contentDescription = "$label modifier key"
                    stateDescription = if (latched) "active" else "inactive"
                },
        contentPadding = PaddingValues(0.dp),
    ) {
        MachineText(label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium)
    }
}

package io.github.lordofpolls.shellwave.terminal

import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    onOpenPromptComposer: (() -> Unit)? = null,
    onOpenQuickResponse: (() -> Unit)? = null,
    onOpenDigest: (() -> Unit)? = null,
    quickDrawerActive: Boolean = false,
    onToggleQuickDrawer: (() -> Unit)? = null,
    onQuickAction: ((text: String, sendEnter: Boolean) -> Unit)? = null,
    onSendRaw: ((control: String) -> Unit)? = null,
    keys: List<KeyBarKey> = DEFAULT_KEY_BAR_KEYS,
    rows: Int = 1,
) {
    val haptics = LocalHapticFeedback.current

    // A cluster is drawn as its own pinned column below, so it never enters the chunked flow.
    val hasCluster = keys.any { it.type == KeyBarKeyType.CURSOR_CLUSTER }

    // The flowing part of the bar as one ordered list, so the row split below is a plain chunking
    // of it rather than a second notion of what's on the bar.
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

    // A cluster or stacked AI controls make the strip two rows tall, so a second row of flat keys is free.
    val hasTwoRowPinned = hasCluster || (onOpenQuickResponse != null || onOpenPromptComposer != null || onOpenDigest != null)
    val freeSecondRow = hasTwoRowPinned && buttons.size >= MIN_BUTTONS_FOR_FREE_SECOND_ROW
    val rowCount = maxOf(rows.coerceIn(1, MAX_KEY_BAR_ROWS), if (freeSecondRow) 2 else 1)
    // Ceiling division puts the longer row first: the bar grows away from the terminal.
    val perRow = ((buttons.size + rowCount - 1) / rowCount).coerceAtLeast(1)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
        // Bottom, since the pinned columns are the tall ones: fewer flowing rows should settle
        // against the cluster's lower arm, where the thumb rests, instead of floating above a gap.
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            // Weighted, so the pinned columns measure first and the scrolling rows take what is
            // left. The other way round, a long layout pushes the cluster and the keyboard key
            // off-screen, which is the thing being prevented.
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            if (quickDrawerActive && onQuickAction != null) {
                QuickResponseDrawerRows(
                    onQuickAction = onQuickAction,
                    onSendRaw = onSendRaw,
                    onToggleQuickDrawer = onToggleQuickDrawer,
                )
            } else {
                buttons.chunked(perRow).forEach { rowButtons ->
                    // Two rows can still be wider than the window; scroll rather than crowd.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(KeyGap),
                    ) {
                        rowButtons.forEach { it() }
                    }
                }
            }
        }
        if (hasCluster) CursorCluster(onSpecialKey = onSpecialKey)

        // Pinned controls at the right edge: 2x2 grid of circular controls (Composer/Enter, Robot, Digest, Keyboard)
        Column(
            verticalArrangement = Arrangement.spacedBy(KeyGap),
            horizontalAlignment = Alignment.End,
        ) {
            val topRowKeys: List<@Composable () -> Unit> = buildList {
                if (onOpenPromptComposer != null) {
                    if (quickDrawerActive) {
                        add {
                            DedicatedEnterKey(
                                onClick = { onSpecialKey(KeyEvent.KEYCODE_ENTER) },
                                onLongClick = onOpenPromptComposer,
                            )
                        }
                    } else {
                        add {
                            PromptComposerKey(
                                onClick = onOpenPromptComposer,
                                onLongClick = { onSpecialKey(KeyEvent.KEYCODE_ENTER) },
                            )
                        }
                    }
                }
                if (onOpenQuickResponse != null) {
                    add {
                        AgentQuickResponseKey(
                            onClick = onOpenQuickResponse,
                            onLongClick = onToggleQuickDrawer,
                            active = quickDrawerActive,
                        )
                    }
                }
            }
            val bottomRowKeys: List<@Composable () -> Unit> = buildList {
                if (onOpenDigest != null) {
                    add {
                        AgentDigestKey(
                            onClick = onOpenDigest,
                            onLongClick = onToggleQuickDrawer,
                        )
                    }
                }
                add {
                    KeyboardKey(
                        visible = keyboardVisible,
                        onClick = onKeyboardToggle,
                        onLongClick = { onSpecialKey(KeyEvent.KEYCODE_ENTER) },
                        modifier = Modifier.size(ClusterKeySize),
                    )
                }
            }

            if (topRowKeys.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    topRowKeys.forEach { it() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                bottomRowKeys.forEach { it() }
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
 * Two-row Quick Response Drawer replacing the left-side terminal keys when flipped on,
 * giving immediate 1-tap access to Enter, y/n, numbered choices 1-4, and Ctrl+C.
 */
@Composable
private fun QuickResponseDrawerRows(
    onQuickAction: (text: String, sendEnter: Boolean) -> Unit,
    onSendRaw: ((control: String) -> Unit)?,
    onToggleQuickDrawer: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        QuickDrawerButton(
            label = "↵ ENTER",
            contentDescription = "Send Enter key",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { onQuickAction("", true) },
        )
        QuickDrawerButton(
            label = "YES (Y)",
            contentDescription = "Send y and Enter",
            onClick = { onQuickAction("y", true) },
        )
        QuickDrawerButton(
            label = "NO (N)",
            contentDescription = "Send n and Enter",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = { onQuickAction("n", true) },
        )
        QuickDrawerButton(
            label = "^C",
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
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        (1..4).forEach { num ->
            QuickDrawerButton(
                label = "$num",
                contentDescription = "Send $num and Enter",
                onClick = { onQuickAction("$num", true) },
            )
        }
        if (onToggleQuickDrawer != null) {
            QuickDrawerButton(
                label = "⇄ KEYS",
                contentDescription = "Switch back to standard keys",
                onClick = onToggleQuickDrawer,
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
 * Triggers the on-device Gemini Nano terminal output digest; long-press flips the quick response drawer.
 */
@Composable
private fun AgentDigestKey(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    PinnedActionKey(
        onClick = onClick,
        onLongClick = onLongClick,
        contentDescription = "Summarize terminal output",
        onClickLabel = "Summarize terminal output",
        onLongClickLabel = "Toggle quick response drawer",
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

/**
 * Opens the multi-line Prompt Composer dialog; long-press immediately sends Enter.
 */
@Composable
private fun PromptComposerKey(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    PinnedActionKey(
        onClick = onClick,
        onLongClick = onLongClick,
        contentDescription = "Compose prompt for AI agent, hold to send Enter",
        onClickLabel = "Open prompt composer",
        onLongClickLabel = "Send Enter",
    ) {
        Icon(
            imageVector = Icons.Outlined.EditNote,
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

/**
 * Dedicated 1-tap Enter key shown in place of [PromptComposerKey] when the Quick Response Drawer is active;
 * long-press opens the Prompt Composer.
 */
@Composable
private fun DedicatedEnterKey(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    PinnedActionKey(
        onClick = onClick,
        onLongClick = onLongClick,
        contentDescription = "Send Enter key, hold to open prompt composer",
        onClickLabel = "Send Enter",
        onLongClickLabel = "Open prompt composer",
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
 * Opens the Quick Response menu on tap; long-press flips the KeyBar between standard keys
 * and the Quick Response Drawer.
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
            "Open agent quick response menu, quick drawer active"
        } else {
            "Open agent quick response menu, hold to flip quick drawer"
        },
        onClickLabel = "Open agent quick response menu",
        onLongClickLabel = "Toggle quick response drawer",
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
) {
    PinnedActionKey(
        onClick = onClick,
        onLongClick = onLongClick,
        contentDescription = if (visible) {
            "Hide the keyboard, hold to send Enter"
        } else {
            "Show the keyboard, hold to send Enter"
        },
        onClickLabel = if (visible) "Hide keyboard" else "Show keyboard",
        onLongClickLabel = "Send Enter",
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (visible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
            // The button carries the description; a second one here would have TalkBack read the
            // control twice on the way past.
            contentDescription = null,
            modifier = Modifier.size(KeyIconSize),
        )
    }
}

private fun HapticFeedback.keyTap() = performHapticFeedback(HapticFeedbackType.KeyboardTap)

const val MAX_KEY_BAR_ROWS = 2

/**
 * The arrow keys as the inverted T every physical keyboard uses, with the page keys in the two
 * cells the T leaves empty:
 * ```
 * PgUp  ↑  PgDn
 *  ←    ↓   →
 * ```
 * Two rows tall, about 44dp of terminal height, and that was the accepted trade. A linear `↑↓←→`
 * run has no spatial meaning and every press needs reading. PgUp/PgDn here rather than as flat
 * keys because those cost bar width the flowing rows do not have, and these cells were blank.
 * Drawn as icons: "PgUp" wraps inside a 48dp cell, and the Unicode page glyphs come from a
 * fallback font that renders them at half the arrows' size.
 *
 * Fixed [ClusterKeySize] rather than [MinKeyWidth], since `↑` has to sit above `↓` and equal-width
 * cells are what guarantee it.
 */
@Composable
private fun CursorCluster(onSpecialKey: (keyCode: Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            ClusterKey(
                KeyEvent.KEYCODE_PAGE_UP,
                "PgUp",
                onSpecialKey,
                icon = Icons.Outlined.KeyboardDoubleArrowUp,
            )
            ClusterKey(KeyEvent.KEYCODE_DPAD_UP, "↑", onSpecialKey)
            ClusterKey(
                KeyEvent.KEYCODE_PAGE_DOWN,
                "PgDn",
                onSpecialKey,
                icon = Icons.Outlined.KeyboardDoubleArrowDown,
            )
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

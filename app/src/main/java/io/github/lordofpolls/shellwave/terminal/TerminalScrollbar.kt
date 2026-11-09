package io.github.lordofpolls.shellwave.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An interactive, draggable vertical scrollbar for the terminal viewport.
 *
 * Provides calibrated, comfortable scrubbing through scrollback history with
 * tactile ratchet haptics, an unconstrained horizontal tooltip badge, and
 * fine-scrubbing support when pulling away from the screen edge.
 *
 * @param topRow Current scroll offset (`<= 0`, where 0 is live screen, negative is scrollback).
 * @param maxScrollback Number of active transcript rows available in history.
 * @param screenRows Number of visible rows currently rendered on screen.
 * @param onScrollToRow Invoked when the user drags the scrollbar thumb or taps the track.
 * @param modifier Modifier applied to the outer scrollbar container.
 */
@Composable
fun TerminalScrollbar(
    topRow: Int,
    maxScrollback: Int,
    screenRows: Int,
    onScrollToRow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasScrollback = maxScrollback > 0
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    val currentTopRow by rememberUpdatedState(topRow)
    val currentMaxScrollback by rememberUpdatedState(maxScrollback)
    val currentHasScrollback by rememberUpdatedState(hasScrollback)
    val currentOnScrollToRow by rememberUpdatedState(onScrollToRow)

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubRow by remember { mutableIntStateOf(topRow) }
    var lastHapticRow by remember { mutableIntStateOf(topRow) }
    var isFineScrubbing by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .wrapContentSize(Alignment.CenterEnd),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat()
        if (trackHeightPx <= 0f) return@BoxWithConstraints

        val totalRows = if (hasScrollback) maxScrollback + screenRows else screenRows
        val visibleRatio = if (hasScrollback) (screenRows.toFloat() / totalRows.toFloat()).coerceIn(0.04f, 1f) else 1f
        val minThumbHeightPx = with(density) { 48.dp.toPx() }
        val thumbHeightPx = if (hasScrollback) {
            (trackHeightPx * visibleRatio).coerceIn(minThumbHeightPx, trackHeightPx * 0.75f)
        } else {
            trackHeightPx * 0.4f
        }
        val availableTravelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)

        val activeRow = if (isScrubbing) scrubRow else topRow
        val scrollProgress = if (hasScrollback) {
            ((activeRow + maxScrollback).toFloat() / maxScrollback.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        val thumbTopPx = scrollProgress * availableTravelPx

        // Trigger haptics on milestone rows (every 5-10 rows or boundaries) for a crisp ratchet feel
        fun checkHaptic(row: Int) {
            val maxScroll = currentMaxScrollback
            if (maxScroll <= 0) return
            val threshold = if (maxScroll > 500) 10 else 5
            if (abs(row - lastHapticRow) >= threshold || row == 0 || row == -maxScroll) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastHapticRow = row
            }
        }

        // Relative drag accumulator with controlled progressive curve
        fun updateFromRelativeDrag(initialRow: Int, totalDeltaY: Float, pullLeftPx: Float) {
            val maxScroll = currentMaxScrollback
            if (maxScroll <= 0) return
            // If the user drags their finger left (away from edge), activate ultra-fine scrubbing
            val fineMode = pullLeftPx > with(density) { 40.dp.toPx() }
            isFineScrubbing = fineMode

            val absY = abs(totalDeltaY)
            val sign = if (totalDeltaY < 0f) -1 else 1

            // Calibrated touch response:
            // ~12dp per line: steady, tactile 1-to-1 feel for the first 2 lines.
            // After 2 lines of scrolling, gentle progressive acceleration begins without wild spikes.
            val pxPerLine = with(density) { 12.dp.toPx() }.coerceAtLeast(10f)
            val twoLinesPx = pxPerLine * 2f

            val lines = when {
                fineMode -> absY / (pxPerLine * 1.5f) // Ultra-high precision
                absY <= twoLinesPx -> absY / pxPerLine // First 2 lines: linear 1-to-1 response
                else -> {
                    val extraLines = (absY - twoLinesPx) / pxPerLine
                    // Controllable progressive ramp starting after 2 lines
                    2f + extraLines + (0.35f * extraLines.pow(1.3f))
                }
            }

            val linesDelta = (sign * lines).roundToInt()
            val newRow = (initialRow + linesDelta).coerceIn(-maxScroll, 0)
            if (newRow != scrubRow) {
                scrubRow = newRow
                checkHaptic(newRow)
                currentOnScrollToRow(newRow)
            }
        }

        // Dedicated 48dp right-aligned touch track
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(48.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (!currentHasScrollback) return@awaitEachGesture
                        isScrubbing = true
                        val initialRow = currentTopRow
                        lastHapticRow = initialRow
                        scrubRow = initialRow
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                        val startY = down.position.y
                        val startX = down.position.x

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                            val totalDeltaY = change.position.y - startY
                            val pullLeftPx = (startX - change.position.x).coerceAtLeast(0f)
                            updateFromRelativeDrag(initialRow, totalDeltaY, pullLeftPx)
                        }
                        isScrubbing = false
                        isFineScrubbing = false
                    }
                }
                .semantics {
                    contentDescription = "Terminal scrollbar scrubber"
                },
        ) {
            // Track guide line along the edge
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(
                        color = if (isScrubbing) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
                        },
                        shape = CircleShape,
                    ),
            )

            // Animated thumb width and opacity
            val thumbWidth by animateDpAsState(
                targetValue = if (isScrubbing) 12.dp else 8.dp,
                label = "thumbWidth",
            )
            val thumbAlpha by animateFloatAsState(
                targetValue = when {
                    isScrubbing -> 1.0f
                    topRow < 0 -> 0.95f
                    hasScrollback -> 0.70f
                    else -> 0.40f
                },
                label = "thumbAlpha",
            )

            val thumbColor = if (isScrubbing || topRow < 0) {
                MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha)
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = thumbAlpha)
            }

            // Scrollbar Thumb
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 2.dp)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .size(
                        width = thumbWidth,
                        height = with(density) { thumbHeightPx.toDp() },
                    )
                    .background(
                        color = thumbColor,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (isScrubbing || topRow < 0) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
        }

        // Horizontal Scrub Tooltip / Badge - Placed safely to the left of the 48dp touch track
        AnimatedVisibility(
            visible = isScrubbing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 56.dp)
                .offset {
                    val badgeHeightPx = 32.dp.toPx()
                    val targetY = (thumbTopPx + thumbHeightPx / 2f - badgeHeightPx / 2f)
                        .coerceIn(8.dp.toPx(), (trackHeightPx - badgeHeightPx - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
                    IntOffset(0, targetY.roundToInt())
                },
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.wrapContentSize(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    val label = when {
                        scrubRow == 0 -> "Live (0)"
                        isFineScrubbing -> "Fine: ${-scrubRow} lines back"
                        else -> "${-scrubRow} lines back"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

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
    if (maxScrollback <= 0) return

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

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

        val totalRows = maxScrollback + screenRows
        val visibleRatio = (screenRows.toFloat() / totalRows.toFloat()).coerceIn(0.04f, 1f)
        val minThumbHeightPx = with(density) { 48.dp.toPx() }
        val thumbHeightPx = (trackHeightPx * visibleRatio).coerceIn(minThumbHeightPx, trackHeightPx * 0.75f)
        val availableTravelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)

        val activeRow = if (isScrubbing) scrubRow else topRow
        val scrollProgress = ((activeRow + maxScrollback).toFloat() / maxScrollback.toFloat()).coerceIn(0f, 1f)
        val thumbTopPx = scrollProgress * availableTravelPx

        // Trigger haptics on milestone rows (every 5-10 rows or boundaries) for a crisp ratchet feel
        fun checkHaptic(row: Int) {
            val threshold = if (maxScrollback > 500) 10 else 5
            if (abs(row - lastHapticRow) >= threshold || row == 0 || row == -maxScrollback) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastHapticRow = row
            }
        }

        // Relative drag accumulator with progressive power curve
        fun updateFromRelativeDrag(initialRow: Int, totalDeltaY: Float, pullLeftPx: Float) {
            // If the user drags their finger left (away from edge), activate ultra-fine scrubbing
            val fineMode = pullLeftPx > with(density) { 40.dp.toPx() }
            isFineScrubbing = fineMode

            val absY = abs(totalDeltaY)
            val sign = if (totalDeltaY < 0f) -1 else 1

            // Progressive power curve:
            // Under 60px of drag: calm linear response (~4px per line), giving effortless control for 10-20 lines
            // Over 60px of drag: smooth progressive acceleration to traverse large history buffers
            val lines = when {
                fineMode -> absY / 5.0f // Ultra-high precision: 5px per line
                absY <= 60f -> absY / 3.5f // Gentle deadband: 10 lines = 35px, 20 lines = 70px
                else -> {
                    val extraPx = absY - 60f
                    val baseScale = (maxScrollback / 120f).coerceIn(1.5f, 15f)
                    (60f / 3.5f) + (extraPx / 30f).pow(1.55f) * baseScale
                }
            }

            val linesDelta = (sign * lines).roundToInt()
            val newRow = (initialRow + linesDelta).coerceIn(-maxScrollback, 0)
            if (newRow != scrubRow) {
                scrubRow = newRow
                checkHaptic(newRow)
                onScrollToRow(newRow)
            }
        }

        fun updateFromDirectTap(touchY: Float) {
            val targetThumbTop = (touchY - thumbHeightPx / 2f).coerceIn(0f, availableTravelPx)
            val newProgress = targetThumbTop / availableTravelPx
            val newRow = (-maxScrollback + (newProgress * maxScrollback).roundToInt()).coerceIn(-maxScrollback, 0)
            if (newRow != scrubRow) {
                scrubRow = newRow
                checkHaptic(newRow)
                onScrollToRow(newRow)
            }
        }

        // Dedicated 48dp right-aligned touch track
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(48.dp)
                .pointerInput(maxScrollback, trackHeightPx, thumbHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isScrubbing = true
                        lastHapticRow = topRow
                        scrubRow = topRow
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                        val startY = down.position.y
                        val startX = down.position.x
                        val initialRow = topRow

                        // If touch was far from the thumb, jump to that position first
                        val touchDistanceToThumb = abs(startY - (thumbTopPx + thumbHeightPx / 2f))
                        val isNearThumb = touchDistanceToThumb <= (thumbHeightPx / 2f + 20f)
                        if (!isNearThumb) {
                            updateFromDirectTap(startY)
                        }

                        var currentInitialRow = if (!isNearThumb) scrubRow else initialRow

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
                            updateFromRelativeDrag(currentInitialRow, totalDeltaY, pullLeftPx)
                        }
                        isScrubbing = false
                        isFineScrubbing = false
                    }
                }
                .semantics {
                    contentDescription = "Terminal scrollbar scrubber"
                },
        ) {
            // Subtle track background line along the edge
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(
                        color = if (isScrubbing) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
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
                    else -> 0.70f
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

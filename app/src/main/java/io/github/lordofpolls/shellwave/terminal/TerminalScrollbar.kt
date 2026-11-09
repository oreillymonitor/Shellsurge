package io.github.lordofpolls.shellwave.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlin.math.roundToInt

/**
 * An interactive, draggable vertical scrollbar for the terminal viewport.
 *
 * Allows smooth, fast scrubbing through the scrollback history buffer.
 * Provides a tactile touch target on the right edge, real-time line indicator badge,
 * and haptic feedback as the user scrubs.
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
    var scrubRow by remember { mutableStateOf(topRow) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(44.dp), // Generous touch target
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

        fun updateFromTouch(touchY: Float) {
            val targetThumbTop = (touchY - thumbHeightPx / 2f).coerceIn(0f, availableTravelPx)
            val newProgress = targetThumbTop / availableTravelPx
            val newRow = (-maxScrollback + (newProgress * maxScrollback).roundToInt()).coerceIn(-maxScrollback, 0)
            if (newRow != scrubRow) {
                scrubRow = newRow
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onScrollToRow(newRow)
            }
        }

        // Pointer gesture detector handling tap-to-jump and continuous drag-scrubbing
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(maxScrollback, trackHeightPx, thumbHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isScrubbing = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        updateFromTouch(down.position.y)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                            updateFromTouch(change.position.y)
                        }
                        isScrubbing = false
                    }
                }
                .semantics {
                    contentDescription = "Terminal scrollbar. Drag to scrub history."
                },
        ) {
            // Track background visible while scrubbing
            if (isScrubbing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            shape = CircleShape,
                        ),
                )
            }

            // Animated thumb width and opacity
            val thumbWidth by animateDpAsState(
                targetValue = if (isScrubbing) 8.dp else 5.dp,
                label = "thumbWidth",
            )
            val thumbAlpha by animateFloatAsState(
                targetValue = when {
                    isScrubbing -> 1.0f
                    topRow < 0 -> 0.75f
                    else -> 0.35f
                },
                label = "thumbAlpha",
            )

            val thumbColor = if (isScrubbing || topRow < 0) {
                MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = thumbAlpha)
            }

            // Scrollbar Thumb
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                    .size(
                        width = thumbWidth,
                        height = with(density) { thumbHeightPx.toDp() },
                    )
                    .background(
                        color = thumbColor,
                        shape = CircleShape,
                    ),
            )

            // Scrub Tooltip / Badge showing exact line distance
            AnimatedVisibility(
                visible = isScrubbing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 28.dp)
                    .offset {
                        val badgeHeightPx = 32.dp.toPx()
                        val targetY = (thumbTopPx + thumbHeightPx / 2f - badgeHeightPx / 2f)
                            .coerceIn(8.dp.toPx(), trackHeightPx - badgeHeightPx - 8.dp.toPx())
                        IntOffset(0, targetY.roundToInt())
                    },
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 4.dp,
                ) {
                    val label = if (scrubRow == 0) {
                        "Live"
                    } else {
                        "${-scrubRow} lines back"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

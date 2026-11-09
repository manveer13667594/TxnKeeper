package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * A scroll bar for the transaction list: it shows where you are in the WHOLE list (all matching
 * transactions, not only the rows loaded so far), and you can drag it to jump far up or down.
 *
 * The list loads rows a page at a time, so the position is counted in transactions and measured
 * against the total, which does not change while more rows load. Dragging past the loaded rows
 * asks for more to be loaded first.
 *
 * The bar appears while the list moves or the bar is held, and fades away a moment after it stops,
 * so it never sits on top of the rows' menus. It only grabs touches where the thumb is.
 */

private val ThumbMinHeight = 48.dp      // the thumb never gets shorter than this, however long the list
private val TouchWidth = 24.dp          // width of the area that can grab the thumb
private val TrackTopGap = 4.dp
// Keeps the thumb above the + button and the "back to top" arrow, even at the end of the list
private val TrackBottomGap = 132.dp
private const val HIDE_AFTER_MS = 1500L

/** Everything the thumb needs, worked out from where the list is now. */
private class ScrollMetrics(
    /** 0 = at the top, 1 = at the very end. */
    val progress: Float,
    /** The share of all transactions that is on screen; sets how long the thumb is. */
    val visibleFraction: Float,
    /** How many transactions the top of the screen can travel (all of them minus those on screen). */
    val scrollRange: Int
)

/**
 * [before][i] = how many transactions come before day i, so before.last() is the number loaded.
 * Day i is list item i + 1 (item 0 is the totals card). Returns null when there is nothing to show
 * (no rows, or everything already fits on the screen).
 */
private fun scrollMetrics(state: LazyListState, before: IntArray, total: Int): ScrollMetrics? {
    val days = before.size - 1
    val loaded = before[days]
    val all = max(total, loaded)
    if (days <= 0 || all <= 0) return null
    if (!state.canScrollBackward && !state.canScrollForward) return null
    val visible = state.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null

    // Transactions on screen right now (the totals card is not one)
    var onScreen = 0
    for (item in visible) {
        val day = item.index - 1
        if (day in 0 until days) onScreen += before[day + 1] - before[day]
    }
    val range = max(1, all - onScreen)

    // Which transaction is at the top of the screen, counting the part of its day already scrolled past
    val first = visible.first()
    val firstDay = first.index - 1
    val topTransaction = if (firstDay < 0) {
        0f
    } else {
        val day = minOf(firstDay, days - 1)
        val scrolledPast = (state.firstVisibleItemScrollOffset.toFloat() / max(1, first.size)).coerceIn(0f, 1f)
        before[day] + scrolledPast * (before[day + 1] - before[day])
    }

    val progress = if (!state.canScrollForward && loaded >= all) 1f else (topTransaction / range).coerceIn(0f, 1f)
    return ScrollMetrics(
        progress = progress,
        visibleFraction = (onScreen.toFloat() / all).coerceIn(0f, 1f),
        scrollRange = range
    )
}

/** Roughly how tall one transaction is on screen (average over the visible days; a guess if none). */
private fun pixelsPerTransaction(state: LazyListState, before: IntArray): Float {
    var px = 0
    var count = 0
    for (item in state.layoutInfo.visibleItemsInfo) {
        val day = item.index - 1
        if (day in 0 until before.size - 1) {
            px += item.size
            count += before[day + 1] - before[day]
        }
    }
    return if (count > 0) px.toFloat() / count else 72f
}

/** The day (0-based) that holds transaction number [txn] (0-based), given the "before" counts. */
private fun dayAt(before: IntArray, txn: Int): Int {
    var low = 0
    var high = before.size - 2          // the last day
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (before[mid] <= txn) low = mid else high = mid - 1
    }
    return low
}

/**
 * Put this inside the Box that holds the list, after the list.
 *
 * @param dayCounts how many transactions each day holds, in list order (day i is list item i + 1)
 * @param totalCount how many transactions match in all, loaded or not
 * @param onLoadUpTo asks for at least this many transactions to be loaded
 */
@Composable
fun BoxScope.TransactionScrollbar(
    listState: LazyListState,
    dayCounts: List<Int>,
    totalCount: Int,
    onLoadUpTo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val before = remember(dayCounts) {
        IntArray(dayCounts.size + 1).also { for (i in dayCounts.indices) it[i + 1] = it[i] + dayCounts[i] }
    }
    val total by rememberUpdatedState(totalCount)
    val loadUpTo by rememberUpdatedState(onLoadUpTo)
    val beforeNow by rememberUpdatedState(before)
    // The thumb's length only changes when the number of rows on screen does, not on every scroll frame
    val thumbFraction by remember(before) {
        derivedStateOf { scrollMetrics(listState, before, total)?.visibleFraction }
    }

    var trackPx by remember { mutableIntStateOf(0) }
    // While the thumb is held: where it is (0..1) and how many transactions that spans
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var dragRange by remember { mutableIntStateOf(1) }
    val dragging = dragProgress != null

    // Visible while the list moves or the thumb is held, and for a moment after
    val moving = listState.isScrollInProgress
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(moving, dragging) {
        if (moving || dragging) {
            shown = true
        } else {
            delay(HIDE_AFTER_MS)
            shown = false
        }
    }

    // Holding the thumb: go to that place in the list. When it is past the rows loaded so far, ask for
    // more, scroll to the end of what there is, and finish the job when the rows arrive (this runs again).
    LaunchedEffect(dragProgress, before) {
        val progress = dragProgress ?: return@LaunchedEffect
        val days = before.size - 1
        if (days <= 0) return@LaunchedEffect
        val loaded = before[days]
        val target = (progress * dragRange).roundToInt()
        if (target >= loaded && loaded < total) loadUpTo(target + 100)
        if (target <= 0) {
            listState.scrollToItem(0)
        } else {
            // Land inside the day too (not only on its first row), so the list follows the thumb smoothly
            val wanted = target.coerceIn(0, loaded - 1)
            val day = dayAt(before, wanted)
            val inside = ((wanted - before[day]) * pixelsPerTransaction(listState, before)).roundToInt()
            listState.scrollToItem(day + 1, inside)
        }
    }

    val thumbWidth by animateDpAsState(
        targetValue = if (dragging) 8.dp else 4.dp,
        label = "scrollbarThumbWidth"
    )
    val thumbColor = if (dragging) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Box(
        modifier = modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(top = TrackTopGap, bottom = TrackBottomGap)
            .width(TouchWidth)
            .onSizeChanged { trackPx = it.height }
    ) {
        val fraction = thumbFraction
        if (fraction != null && trackPx > 0) {
            val minPx = with(density) { ThumbMinHeight.toPx() }
            val trackF = trackPx.toFloat()
            // Glides to its new length instead of jumping when days of different size scroll by
            val thumbPx by animateFloatAsState(
                targetValue = max(minPx, fraction * trackF).coerceAtMost(trackF),
                animationSpec = tween(250),
                label = "scrollbarThumbLength"
            )
            val room = (trackF - thumbPx).coerceAtLeast(0f)       // how far the thumb can travel

            // The drag handler is set up once, so it reads this through State to see the latest value
            val latestRoom by rememberUpdatedState(room)

            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(400)),
                // The offset moves the whole thumb, touch area included, so it can be grabbed where it is drawn.
                // It is read while the screen is laid out (not while it is composed), so scrolling
                // only moves the thumb and never rebuilds it.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val progress = dragProgress
                            ?: scrollMetrics(listState, beforeNow, total)?.progress
                            ?: 0f
                        IntOffset(0, (progress * room).roundToInt())
                    }
            ) {
                Box(
                    modifier = Modifier
                        .width(TouchWidth)
                        .height(with(density) { thumbPx.toDp() })
                        .pointerInput(Unit) {
                            var position = 0f
                            detectVerticalDragGestures(
                                onDragStart = {
                                    val now = scrollMetrics(listState, beforeNow, total)
                                    position = now?.progress ?: 0f
                                    dragRange = now?.scrollRange ?: 1
                                    dragProgress = position
                                },
                                onDragEnd = { dragProgress = null },
                                onDragCancel = { dragProgress = null },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    if (latestRoom > 0f) {
                                        position = (position + dragAmount / latestRoom).coerceIn(0f, 1f)
                                        dragProgress = position
                                    }
                                }
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 3.dp)
                            .width(thumbWidth)
                            .fillMaxHeight()
                            .background(thumbColor, RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}
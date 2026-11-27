package com.manu.txnkeeper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize

/*
 * The tutorial shown the first time one voice recording gives several entries. It looks and works
 * like the home screen tutorial (Tour.kt). To change its words, edit BatchTourStep.
 */

/** The steps, in order. Each one lights up one thing on the Add page. */
enum class BatchTourStep(val title: String, val text: String) {
    NEXT("More than one entry", "Tap → to see the next one. ← takes you back."),
    INCLUDE("Choose what to save", "Untick an entry you don't want. Try it, it gets ticked again when you continue."),
    SAVE("Save them together", "After checking each entry, Save & exit saves all the ticked ones.")
}

/**
 * Draw it last, over the whole screen. [bounds] is where the lit thing is now. On the first two steps
 * a tap on it reaches the real button; on the last one nothing can be tapped, so nothing is saved
 * by accident. [onSkip] is called for Skip and the Back button.
 */
@Composable
fun BatchTour(
    step: BatchTourStep,
    bounds: Rect?,
    onSkip: () -> Unit,
    onNext: () -> Unit
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val hole = bounds?.takeIf { it.width > 0f && it.height > 0f }?.translate(-origin)

    BackHandler(onBack = onSkip)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                overlaySize = it.size
            }
    ) {
        if (hole == null || step == BatchTourStep.SAVE) {
            Box(modifier = Modifier.fillMaxSize().blockTouches())
        } else {
            val right = overlaySize.width.toFloat()
            val bottom = overlaySize.height.toFloat()
            TouchBlocker(Rect(0f, 0f, right, hole.top))
            TouchBlocker(Rect(0f, hole.bottom, right, bottom))
            TouchBlocker(Rect(0f, hole.top, hole.left, hole.bottom))
            TouchBlocker(Rect(hole.right, hole.top, right, hole.bottom))
        }

        SpotlightDim(hole, MaterialTheme.colorScheme.surface)

        SpotlightCardLayout(hole) {
            key(step) {
                TourCard(
                    title = step.title,
                    text = step.text,
                    index = step.ordinal,
                    count = BatchTourStep.entries.size,
                    onSkip = onSkip,
                    onNext = onNext
                )
            }
        }
    }
}

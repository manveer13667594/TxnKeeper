package com.manu.txnkeeper

import androidx.activity.compose.BackHandler
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.saveable.Saver
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuTextButton

/*
 * The home screen tutorial: the screen dims, one thing stays lit, and a small card explains it.
 * The user tries each step for real: only the lit button (or the asked-for swipe) works. The tour
 * then steps aside, and the next step shows once they are back on the home screen. "Next" moves
 * on without trying.
 * To change the tutorial, edit TOUR_STEPS. To point at something new, add a TourTarget and put
 * Modifier.tourTarget(...) on that thing.
 */

/** The things the tutorial asks the user to try. SCAN and VOICE are swipes; the others are buttons. */
enum class TourTarget { ADD, SCAN, VOICE, SEARCH, MENU }

/** Where the tutorial is, and where each button is right now (filled in by Modifier.tourTarget). */
class TourTargets(startStep: Int = 0) {
    val bounds = mutableStateMapOf<TourTarget, Rect>()

    var step by mutableIntStateOf(startStep)
        private set

    /** The search box is open: set by the home screen. */
    var searchOpen by mutableStateOf(false)

    /** True after the user tried search: the tour waits until they close the search box. */
    var waitingForSearch by mutableStateOf(false)

    /** Called after the last step. */
    var onFinish: () -> Unit = {}

    /** True while the tutorial is running, so ordinary use does not move it along. */
    var active = false

    fun restart() {
        step = 0
        waitingForSearch = false
    }

    fun next() {
        if (step >= TOUR_STEPS.lastIndex) onFinish() else step++
    }

    /** The user used [target]. Moves on only if that is what the current step asks for. */
    fun tried(target: TourTarget) {
        if (!active || TOUR_STEPS.getOrNull(step)?.target != target) return
        if (target == TourTarget.SEARCH) waitingForSearch = true
        next()
    }

    companion object {
        /** Keeps the current step when the phone is rotated. */
        val Saver: Saver<TourTargets, Int> = Saver(save = { it.step }, restore = { TourTargets(it) })
    }
}

val LocalTourTargets = compositionLocalOf<TourTargets?> { null }

/** Marks something the tutorial can point at, so the light follows it on every screen size. */
@Composable
fun Modifier.tourTarget(target: TourTarget): Modifier {
    val targets = LocalTourTargets.current ?: return this
    return onGloballyPositioned { targets.bounds[target] = it.boundsInRoot() }
}

/** One step: what the user is asked to try. */
private class TourStep(
    val title: String,
    val text: String,
    val target: TourTarget
)

private val TOUR_STEPS = listOf(
    TourStep(
        title = "Add a transaction",
        text = "Tap + to try it. Use Back to return here.",
        target = TourTarget.ADD
    ),
    TourStep(
        title = "Scan a receipt",
        text = "Try it: swipe from left to right. Use Back to return here.",
        target = TourTarget.SCAN
    ),
    TourStep(
        title = "Add by voice",
        text = "Try it: swipe from right to left. Use Back to return here.",
        target = TourTarget.VOICE
    ),
    TourStep(
        title = "Search",
        text = "Tap the search icon to try it. Tap ✕ when you're done.",
        target = TourTarget.SEARCH
    ),
    TourStep(
        title = "More options",
        text = "Tap ⋮ to open Settings, Help and more.",
        target = TourTarget.MENU
    )
)

private val SCRIM = Color.Black.copy(alpha = 0.72f)

/**
 * Draw it last, over the whole screen. [onSkip] is called for Skip and the Back button.
 * On a swipe step, [onSwipeLeft] or [onSwipeRight] runs when the user makes that swipe.
 */
@Composable
fun HomeTour(
    targets: TourTargets,
    onSkip: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
) {
    val index = targets.step.coerceIn(TOUR_STEPS.indices)
    val step = TOUR_STEPS[index]
    val isSwipe = step.target == TourTarget.SCAN || step.target == TourTarget.VOICE
    // Where this overlay starts, to turn screen positions into positions inside it
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val hole: Rect? = if (isSwipe) null else targets.bounds[step.target]
        ?.takeIf { it.width > 0f && it.height > 0f }
        ?.translate(-origin)
    val cardColor = MaterialTheme.colorScheme.surface

    BackHandler(onBack = onSkip)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                overlaySize = it.size
            }
    ) {
        // What can be touched. A swipe step takes every touch and accepts only the asked-for swipe.
        // A button step blocks everything around the button, so a tap on it reaches the real button.
        when {
            isSwipe -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .swipeHorizontally(
                        onLeft = { if (step.target == TourTarget.VOICE) onSwipeLeft() },
                        onRight = { if (step.target == TourTarget.SCAN) onSwipeRight() }
                    )
            )
            hole == null -> Box(modifier = Modifier.fillMaxSize().blockTouches())
            else -> {
                val right = overlaySize.width.toFloat()
                val bottom = overlaySize.height.toFloat()
                TouchBlocker(Rect(0f, 0f, right, hole.top))
                TouchBlocker(Rect(0f, hole.bottom, right, bottom))
                TouchBlocker(Rect(0f, hole.top, hole.left, hole.bottom))
                TouchBlocker(Rect(hole.right, hole.top, right, hole.bottom))
            }
        }

        SpotlightDim(hole, cardColor)

        SpotlightCardLayout(hole) {
            // The swipe animation restarts for each step
            key(index) {
                TourCard(
                    title = step.title,
                    text = step.text,
                    swipeToLeft = if (isSwipe) step.target == TourTarget.VOICE else null,
                    index = index,
                    count = TOUR_STEPS.size,
                    onSkip = onSkip,
                    onNext = { targets.next() }
                )
            }
        }
    }
}

/** The dim layer, with a hole over [hole] and a small arrow towards the card. */
@Composable
internal fun SpotlightDim(hole: Rect?, cardColor: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    ) {
        drawRect(SCRIM)
        if (hole != null) {
            val lit = hole.inflate(4.dp.toPx())
            val radius = CornerRadius(lit.height / 2)
            drawRoundRect(Color.Transparent, lit.topLeft, lit.size, radius, blendMode = BlendMode.Clear)
            drawRoundRect(Color.White, lit.topLeft, lit.size, radius, style = Stroke(2.dp.toPx()))

            val below = hole.center.y < size.height / 2
            val cardWidth = minOf(size.width - 40.dp.toPx(), 360.dp.toPx())
            val cardLeft = (size.width - cardWidth) / 2
            val x = hole.center.x.coerceIn(cardLeft + 32.dp.toPx(), cardLeft + cardWidth - 32.dp.toPx())
            val base = if (below) hole.bottom + 18.dp.toPx() else hole.top - 18.dp.toPx()
            val tip = if (below) base - 10.dp.toPx() else base + 10.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(x, tip)
                    lineTo(x - 10.dp.toPx(), base)
                    lineTo(x + 10.dp.toPx(), base)
                    close()
                },
                cardColor
            )
        }
    }
}

/** Places the card: under a target in the top half, above one in the bottom half, else in the middle. */
@Composable
internal fun SpotlightCardLayout(hole: Rect?, card: @Composable () -> Unit) {
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = card
    ) { measurables, constraints ->
        val margin = 20.dp.roundToPx()
        val gap = 18.dp.roundToPx()
        val width = minOf(constraints.maxWidth - margin * 2, 360.dp.roundToPx()).coerceAtLeast(0)
        val placeable = measurables.first().measure(
            Constraints(
                minWidth = width,
                maxWidth = width,
                maxHeight = (constraints.maxHeight - margin * 2).coerceAtLeast(0)
            )
        )
        val wanted = when {
            hole == null -> (constraints.maxHeight - placeable.height) / 2
            hole.center.y < constraints.maxHeight / 2 -> hole.bottom.toInt() + gap
            else -> hole.top.toInt() - gap - placeable.height
        }
        val y = wanted.coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - width) / 2, y)
        }
    }
}

internal fun Modifier.blockTouches(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** An invisible area (in pixels, inside the overlay) that swallows touches. */
@Composable
internal fun TouchBlocker(area: Rect) {
    Box(
        modifier = Modifier
            .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
            .layout { measurable, _ ->
                val width = area.width.roundToInt().coerceAtLeast(0)
                val height = area.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .blockTouches()
    )
}

@Composable
internal fun TourCard(
    title: String,
    text: String,
    index: Int,
    count: Int,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    /** Shows the moving hand: true = swipe left, false = swipe right, null = none. */
    swipeToLeft: Boolean? = null
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(scheme.surface)
            // Short screens (phone on its side): the card can scroll
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (swipeToLeft != null) {
            SwipeAnimation(
                toLeft = swipeToLeft,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))

        // Progress dots; the long one is the current step
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { i ->
                Box(
                    modifier = Modifier
                        .width(if (i == index) 20.dp else 7.dp)
                        .height(7.dp)
                        .clip(CircleShape)
                        .background(if (i == index) scheme.onSurface else scheme.onSurface.copy(alpha = 0.2f))
                )
            }
        }

        val last = index >= count - 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // On the last step Done does the same, so Skip is not needed
            if (!last) ManuTextButton(onClick = onSkip) { Text("Skip tour") } else Spacer(Modifier.width(1.dp))
            ManuButton(onClick = onNext) { Text(if (last) "Done" else "Next") }
        }
    }
}

/** A moving hand (res/raw/swipe_left.json). The file swipes left; flipped, it swipes right. */
@Composable
private fun SwipeAnimation(toLeft: Boolean, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.swipe_left))
    val dynamic = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR_FILTER,
            value = SimpleColorFilter(MaterialTheme.colorScheme.onSurface.toArgb()),
            keyPath = arrayOf("**")
        )
    )
    LottieAnimation(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        dynamicProperties = dynamic,
        modifier = modifier
            .size(110.dp)
            .graphicsLayer(scaleX = if (toLeft) 1f else -1f)
    )
}

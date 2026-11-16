package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/*
 * One open / close animation for every full-screen page, so nothing feels different from the rest.
 * It is the one the Settings page always had: slide in from the right with a fade, and back out to
 * the right. To change how every page moves, change these two lines only.
 */
val ScreenEnter: EnterTransition = slideInHorizontally(initialOffsetX = { it }) + fadeIn()
val ScreenExit: ExitTransition = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()

/*
 * The camera is opened by swiping left to right and closed by swiping right to left, so it moves the
 * same way as the finger: it comes in from the LEFT edge and goes back out to the left. (Voice entry
 * opens with the opposite swipe and keeps the normal right-hand animation above.)
 */
val CameraEnter: EnterTransition = slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
val CameraExit: ExitTransition = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()

/**
 * Shows a full-screen page over what is behind it, with [enter] and [exit] (the standard
 * [ScreenEnter] / [ScreenExit] unless a page asks for its own).
 *
 * The page also catches every touch that lands on it. Without that, a tap or swipe on an empty part
 * of the page (the top bar, the gap between two cards) fell through to whatever is underneath, for
 * example the home screen's icons or its swipe-to-open-camera gesture.
 *
 * [content] runs inside the animation, so a page that needs to know when it has finished sliding in
 * can read `transition.currentState == EnterExitState.Visible`. Pages that don't care just ignore it.
 */
@Composable
fun ScreenTransition(
    visible: Boolean,
    enter: EnterTransition = ScreenEnter,
    exit: ExitTransition = ScreenExit,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit
    ) {
        val animationScope = this
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            animationScope.content()
        }
    }
}

/**
 * A page inside a page (Settings > Payment methods, and so on) opening over its list: it moves
 * exactly like a screen opening over the home screen, and the list stays where it is underneath.
 */
fun subPageOpenTransition(): ContentTransform =
    ContentTransform(
        targetContentEnter = ScreenEnter,
        initialContentExit = ExitTransition.None,
        targetContentZIndex = 1f
    )

/** Going back from a sub-page: it slides away to the right and shows the list that was underneath. */
fun subPageCloseTransition(): ContentTransform =
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ScreenExit,
        targetContentZIndex = -1f
    )
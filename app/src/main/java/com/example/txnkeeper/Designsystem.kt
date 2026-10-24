package com.example.txnkeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/*
 * Small app-level building blocks. The look (colours, shapes, sizes) comes from ManuDesignSystem:
 * colours from MaterialTheme (set by ManuDesignSystemTheme), sizes from ManuDesignTokens.
 */

/** Screen background: the lib's flat background colour. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // Plain Text() takes its colour from LocalContentColor, which is black unless something sets it.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            content = content
        )
    }
}

/**
 * Swipe from left to right anywhere on this element to call [onSwipe] (we use it to open the camera).
 * Vertical scrolling is not affected.
 */
@Composable
fun Modifier.swipeRightTo(onSwipe: () -> Unit): Modifier {
    val current by rememberUpdatedState(onSwipe)
    return pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { total = 0f },
            onDragEnd = { if (total > 100.dp.toPx()) current() },
            onHorizontalDrag = { _, amount -> total += amount }
        )
    }
}

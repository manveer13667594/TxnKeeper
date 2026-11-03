package com.manu.txnkeeper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

/**
 * Hides floating buttons while the user scrolls down (so they never cover rows or buttons) and
 * shows them again on scroll up, or when back at the top.
 * [position] is any number that grows as the content scrolls down; [onVisible] gets true / false.
 */
@Composable
fun FabAutoHide(position: () -> Int, onVisible: (Boolean) -> Unit) {
    LaunchedEffect(Unit) {
        var last = position()
        snapshotFlow { position() }.collect { now ->
            val delta = now - last
            when {
                now <= 0 || delta < -12 -> { onVisible(true); last = now }
                delta > 12 -> { onVisible(false); last = now }
            }
        }
    }
}

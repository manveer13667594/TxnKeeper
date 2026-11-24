package com.manu.txnkeeper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the debug tools are active. In a release build this is always false (BuildConfig.DEBUG is a
 * constant there, so the debug code is still removed). In a debug build it is true unless "Hide debug
 * features" is switched on in Settings, which gives the release experience while testing: no debug
 * cards, no debug logging.
 *
 * Use [on] everywhere instead of BuildConfig.DEBUG. It is a Compose state, so screens update at once.
 */
object DebugMode {
    /** Set from Settings (and read from the saved setting at start-up). */
    var hidden by mutableStateOf(false)

    /** True when debug features should show and run. */
    val on: Boolean get() = BuildConfig.DEBUG && !hidden
}

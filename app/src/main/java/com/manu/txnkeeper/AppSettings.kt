package com.manu.txnkeeper

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** How the app picks light or dark colours. */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * The app's saved settings. Each setting is a Compose state, so screens update by themselves
 * when it changes, and it is also written to the phone so it is still there after a restart.
 *
 * To add a setting later: add a key, a `var` like [themeMode], and an `update...` function.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(readThemeMode())
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    private fun readThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, null)
        return ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM
    }

    private companion object {
        const val PREFS_NAME = "txnkeeper_settings"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

@Composable
fun rememberAppSettings(): AppSettings {
    val context = LocalContext.current
    return remember { AppSettings(context) }
}
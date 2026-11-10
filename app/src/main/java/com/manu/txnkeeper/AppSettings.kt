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

    /** Whether the voice screen shows its tips (one at a time). On by default; turned off in Settings. */
    var voiceTips by mutableStateOf(prefs.getBoolean(KEY_VOICE_TIPS, true))
        private set

    fun updateVoiceTips(on: Boolean) {
        voiceTips = on
        prefs.edit().putBoolean(KEY_VOICE_TIPS, on).apply()
    }

    /**
     * The first-use hints on the home screen ("swipe left for voice", "swipe right for the camera").
     * Each is shown until it is closed or the shortcut has been used once.
     */
    var voiceHintDone by mutableStateOf(prefs.getBoolean(KEY_VOICE_HINT_DONE, false))
        private set
    var cameraHintDone by mutableStateOf(prefs.getBoolean(KEY_CAMERA_HINT_DONE, false))
        private set

    fun markVoiceHintDone() {
        if (voiceHintDone) return
        voiceHintDone = true
        prefs.edit().putBoolean(KEY_VOICE_HINT_DONE, true).apply()
    }

    fun markCameraHintDone() {
        if (cameraHintDone) return
        cameraHintDone = true
        prefs.edit().putBoolean(KEY_CAMERA_HINT_DONE, true).apply()
    }

    /**
     * The payment methods offered when adding or editing a transaction. It starts as the built-in
     * list ([PAYMENT_METHODS]); after that it is entirely yours: add new ones, remove any of them
     * (even the built-in ones, even all of them).
     */
    var paymentMethods by mutableStateOf(readPaymentMethods())
        private set

    /** Adds a payment method (the name is tidied first). Does nothing for a blank or repeated name. */
    fun addPaymentMethod(name: String) {
        val clean = cleanPaymentMethodName(name)
        if (clean.isEmpty() || clean in paymentMethods) return
        savePaymentMethods(paymentMethods + clean)
    }

    /** Removes it from the list. Transactions that already use it keep their label. */
    fun removePaymentMethod(name: String) {
        savePaymentMethods(paymentMethods - name)
    }

    /** Puts the list back to the four built-in methods (part of "delete all data"). */
    fun resetPaymentMethods() {
        paymentMethods = PAYMENT_METHODS
        prefs.edit().remove(KEY_PAYMENT_METHODS).apply()
    }

    private fun savePaymentMethods(list: List<String>) {
        paymentMethods = list
        // An empty list is saved as an empty text, which is different from "never changed"
        prefs.edit().putString(KEY_PAYMENT_METHODS, list.joinToString("\n")).apply()
    }

    private fun readPaymentMethods(): List<String> {
        val saved = prefs.getString(KEY_PAYMENT_METHODS, null) ?: return PAYMENT_METHODS
        return saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private companion object {
        const val PREFS_NAME = "txnkeeper_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_PAYMENT_METHODS = "payment_methods"
        const val KEY_VOICE_TIPS = "voice_tips"
        const val KEY_VOICE_HINT_DONE = "voice_hint_done"
        const val KEY_CAMERA_HINT_DONE = "camera_hint_done"
    }
}

@Composable
fun rememberAppSettings(): AppSettings {
    val context = LocalContext.current
    return remember { AppSettings(context) }
}
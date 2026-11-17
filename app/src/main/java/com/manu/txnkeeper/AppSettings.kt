package com.manu.txnkeeper

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.manu.txnkeeper.widget.Widgets

/** The two transaction types, as they are stored in the database. */
const val TYPE_EXPENSE = "EXPENSE"
const val TYPE_INCOME = "INCOME"

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
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(readThemeMode())
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        // The home screen widgets follow the app's Light / Dark / System choice
        Widgets.refreshAll(appContext)
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
     * Receipt images. When a scanned or picked image is on the Add page, its "Include image" box starts
     * ticked or not ticked according to this. (You can always change the box for one transaction.)
     */
    var includeImageByDefault by mutableStateOf(prefs.getBoolean(KEY_INCLUDE_IMAGE, true))
        private set

    fun updateIncludeImageByDefault(on: Boolean) {
        includeImageByDefault = on
        prefs.edit().putBoolean(KEY_INCLUDE_IMAGE, on).apply()
    }

    /**
     * Whether the Export screen starts with "Include images" switched on (it can still be changed
     * for one export). Only matters once some transactions have images.
     */
    var exportWithImagesByDefault by mutableStateOf(prefs.getBoolean(KEY_EXPORT_IMAGES, true))
        private set

    fun updateExportWithImagesByDefault(on: Boolean) {
        exportWithImagesByDefault = on
        prefs.edit().putBoolean(KEY_EXPORT_IMAGES, on).apply()
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
        // A removed method can't stay the default
        if (chosenDefaultPayment == name) updateDefaultPayment(null)
    }

    /** Puts the list back to the built-in methods (part of "delete all data"). */
    fun resetPaymentMethods() {
        paymentMethods = PAYMENT_METHODS
        prefs.edit().remove(KEY_PAYMENT_METHODS).apply()
        updateDefaultPayment(null)
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

    // ------------------------------------------------------------------ defaults for a new transaction
    // What the Add page starts with. All of it can still be changed on the page for one entry.

    /** The type a new transaction starts as: [TYPE_EXPENSE] or [TYPE_INCOME]. Expense by default. */
    var defaultType by mutableStateOf(readDefaultType())
        private set

    fun updateDefaultType(type: String) {
        if (type != TYPE_EXPENSE && type != TYPE_INCOME) return
        defaultType = type
        prefs.edit().putString(KEY_DEFAULT_TYPE, type).apply()
    }

    private fun readDefaultType(): String {
        val saved = prefs.getString(KEY_DEFAULT_TYPE, null)
        return if (saved == TYPE_INCOME) TYPE_INCOME else TYPE_EXPENSE
    }

    /**
     * The account a new transaction starts in, or null for "last used" (the account you are
     * looking at, or the one you used last). It may point to an account that was deleted since,
     * so callers check it against the real list (see [validDefaultAccountId]).
     */
    var defaultAccountId by mutableStateOf(readDefaultAccountId())
        private set

    fun updateDefaultAccountId(id: Long?) {
        defaultAccountId = id
        prefs.edit().apply {
            if (id == null) remove(KEY_DEFAULT_ACCOUNT) else putLong(KEY_DEFAULT_ACCOUNT, id)
        }.apply()
    }

    private fun readDefaultAccountId(): Long? =
        if (prefs.contains(KEY_DEFAULT_ACCOUNT)) prefs.getLong(KEY_DEFAULT_ACCOUNT, 0L) else null

    /** The default account if it still exists in [accountIds], otherwise null (= "last used"). */
    fun validDefaultAccountId(accountIds: Collection<Long>): Long? =
        defaultAccountId?.takeIf { it in accountIds }

    /** The method picked in Settings, which may no longer be in the list. See [defaultPayment]. */
    private var chosenDefaultPayment by mutableStateOf<String?>(prefs.getString(KEY_DEFAULT_PAYMENT, null))

    /** Pass null to go back to the automatic choice. */
    fun updateDefaultPayment(method: String?) {
        chosenDefaultPayment = method
        prefs.edit().apply {
            if (method == null) remove(KEY_DEFAULT_PAYMENT) else putString(KEY_DEFAULT_PAYMENT, method)
        }.apply()
    }

    /**
     * The payment method a new transaction starts with. Your choice from Settings while it is still
     * in the list; otherwise CASH if you have it, otherwise the first one, otherwise blank (no methods).
     */
    val defaultPayment: String
        get() = chosenDefaultPayment?.takeIf { it in paymentMethods }
            ?: paymentMethods.firstOrNull { it == "CASH" }
            ?: paymentMethods.firstOrNull()
            ?: ""

    private companion object {
        const val PREFS_NAME = "txnkeeper_settings"
        const val KEY_DEFAULT_TYPE = "default_type"
        const val KEY_DEFAULT_ACCOUNT = "default_account_id"
        const val KEY_DEFAULT_PAYMENT = "default_payment"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_PAYMENT_METHODS = "payment_methods"
        const val KEY_VOICE_TIPS = "voice_tips"
        const val KEY_INCLUDE_IMAGE = "include_image_default"
        const val KEY_EXPORT_IMAGES = "export_with_images_default"
        const val KEY_VOICE_HINT_DONE = "voice_hint_done"
        const val KEY_CAMERA_HINT_DONE = "camera_hint_done"
    }
}

@Composable
fun rememberAppSettings(): AppSettings {
    val context = LocalContext.current
    return remember { AppSettings(context) }
}
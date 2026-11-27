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

    /**
     * The button of the home screen "Add / Voice / Scan" widget that was tapped last (one of the
     * WidgetActions), or null before the first tap. The widget highlights it.
     */
    val lastQuickAction: String?
        get() = prefs.getString(KEY_LAST_QUICK_ACTION, null)

    fun markQuickAction(action: String) {
        prefs.edit().putString(KEY_LAST_QUICK_ACTION, action).apply()
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

    /** Voice screen starts listening by itself when it opens, and stops when you stop speaking. */
    var voiceAutoListen by mutableStateOf(prefs.getBoolean(KEY_VOICE_AUTO, true))
        private set

    fun updateVoiceAutoListen(on: Boolean) {
        voiceAutoListen = on
        prefs.edit().putBoolean(KEY_VOICE_AUTO, on).apply()
    }

    /** The little start / stop sounds of voice entry. */
    var voiceSounds by mutableStateOf(prefs.getBoolean(KEY_VOICE_SOUNDS, true))
        private set

    fun updateVoiceSounds(on: Boolean) {
        voiceSounds = on
        prefs.edit().putBoolean(KEY_VOICE_SOUNDS, on).apply()
    }

    /** Whether voice entry uses the phone's Google speech recogniser first (fast, online), with Whisper as the fallback. */
    var fastVoice by mutableStateOf(prefs.getBoolean(KEY_FAST_VOICE, true))
        private set

    fun updateFastVoice(on: Boolean) {
        fastVoice = on
        prefs.edit().putBoolean(KEY_FAST_VOICE, on).apply()
    }

    /** Whether Whisper also listens to every recording in the background and offers its wording when it differs. */
    var doubleCheckVoice by mutableStateOf(prefs.getBoolean(KEY_DOUBLE_CHECK, true))
        private set

    fun updateDoubleCheckVoice(on: Boolean) {
        doubleCheckVoice = on
        prefs.edit().putBoolean(KEY_DOUBLE_CHECK, on).apply()
    }

    /** Debug builds only: hides every debug feature (see DebugMode) to try the release experience. */
    var hideDebug by mutableStateOf(prefs.getBoolean(KEY_HIDE_DEBUG, false))
        private set

    init {
        DebugMode.hidden = hideDebug
    }

    fun updateHideDebug(on: Boolean) {
        hideDebug = on
        DebugMode.hidden = on
        prefs.edit().putBoolean(KEY_HIDE_DEBUG, on).apply()
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
     * Whether a scanned or attached image fills the form by itself. It is the starting state of the
     * "Fill from image" box on the Add page (which can be changed for one transaction).
     */
    var fillFromImageByDefault by mutableStateOf(prefs.getBoolean(KEY_FILL_FROM_IMAGE, true))
        private set

    fun updateFillFromImageByDefault(on: Boolean) {
        fillFromImageByDefault = on
        prefs.edit().putBoolean(KEY_FILL_FROM_IMAGE, on).apply()
    }

    /**
     * Whether the Export screen starts with "Include images" switched on (it can still be changed
     * for one export). Only matters once some transactions have images.
     */
    var exportWithImagesByDefault by mutableStateOf(prefs.getBoolean(KEY_EXPORT_IMAGES, false))
        private set

    fun updateExportWithImagesByDefault(on: Boolean) {
        exportWithImagesByDefault = on
        prefs.edit().putBoolean(KEY_EXPORT_IMAGES, on).apply()
    }

    /**
     * The home screen tutorial (Tour.kt): true while it still has to be shown. It starts as true only
     * on a new install; people who update the app only see it if they ask for it in Help.
     */
    var tourPending by mutableStateOf(readTourPending())
        private set

    private fun readTourPending(): Boolean {
        if (prefs.contains(KEY_TOUR_PENDING)) return prefs.getBoolean(KEY_TOUR_PENDING, false)
        // Decided once, then remembered. A new user has no transaction database yet (it is made
        // after this runs). This also covers "Clear data", while people updating the app keep theirs.
        val newInstall = !appContext.getDatabasePath("txnkeeper_database").exists()
        prefs.edit().putBoolean(KEY_TOUR_PENDING, newInstall).apply()
        return newInstall
    }

    /**
     * The tutorial for a recording with several entries (BatchTour.kt): true until it has been seen
     * or skipped. It waits until that first happens, so people who update the app get it too.
     */
    var batchTourPending by mutableStateOf(prefs.getBoolean(KEY_BATCH_TOUR_PENDING, true))
        private set

    fun finishBatchTour() {
        batchTourPending = false
        prefs.edit().putBoolean(KEY_BATCH_TOUR_PENDING, false).apply()
    }

    /** "Show tutorial" in Help. */
    fun restartTour() {
        batchTourPending = true
        prefs.edit().putBoolean(KEY_BATCH_TOUR_PENDING, true).apply()
        tourPending = true
        prefs.edit().putBoolean(KEY_TOUR_PENDING, true).apply()
    }

    /** Done or Skip. */
    fun finishTour() {
        tourPending = false
        prefs.edit().putBoolean(KEY_TOUR_PENDING, false).apply()
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
        const val KEY_LAST_QUICK_ACTION = "last_quick_widget_action"
        const val KEY_PAYMENT_METHODS = "payment_methods"
        const val KEY_VOICE_TIPS = "voice_tips"
        const val KEY_VOICE_AUTO = "voice_auto_listen"
        const val KEY_VOICE_SOUNDS = "voice_sounds"
        const val KEY_FAST_VOICE = "fast_voice_google"
        const val KEY_DOUBLE_CHECK = "double_check_whisper"
        const val KEY_INCLUDE_IMAGE = "include_image_default"
        const val KEY_HIDE_DEBUG = "hide_debug_features"
        const val KEY_FILL_FROM_IMAGE = "fill_from_image_default"
        const val KEY_EXPORT_IMAGES = "export_with_images_default"
        const val KEY_TOUR_PENDING = "home_tour_pending"
        const val KEY_BATCH_TOUR_PENDING = "batch_tour_pending"
    }
}

@Composable
fun rememberAppSettings(): AppSettings {
    val context = LocalContext.current
    return remember { AppSettings(context) }
}
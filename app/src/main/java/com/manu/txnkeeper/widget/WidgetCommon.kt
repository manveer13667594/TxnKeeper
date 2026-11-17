package com.manu.txnkeeper.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.unit.ColorProvider
import com.manu.txnkeeper.MainActivity
import com.manu.txnkeeper.ThemeMode
import com.manu.txnkeeper.ui.theme.DarkInk
import com.manu.txnkeeper.ui.theme.DarkOnSurface
import com.manu.txnkeeper.ui.theme.DarkOnSurfaceVariant
import com.manu.txnkeeper.ui.theme.DarkSurface
import com.manu.txnkeeper.ui.theme.DarkSurfaceVariant
import com.manu.txnkeeper.ui.theme.LightInk
import com.manu.txnkeeper.ui.theme.LightOnSurface
import com.manu.txnkeeper.ui.theme.LightOnSurfaceVariant
import com.manu.txnkeeper.ui.theme.LightSurface
import com.manu.txnkeeper.ui.theme.LightSurfaceVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/*
 * Things both home screen widgets share: the buttons' intent actions, the app's colours and a
 * way to redraw them all. The two widgets are QuickActionsWidget (the small "Add / Voice / Scan" pill) and
 * SearchBarWidget (a bar like Google's, with the app logo and four icons).
 */

/** Intent actions MainActivity listens for. */
object WidgetActions {
    const val ADD_TRANSACTION = "com.manu.txnkeeper.action.ADD_TRANSACTION"
    const val VOICE_ENTRY = "com.manu.txnkeeper.action.VOICE_ENTRY"
    const val OPEN_CAMERA = "com.manu.txnkeeper.action.OPEN_CAMERA"
    const val OPEN_SEARCH = "com.manu.txnkeeper.action.OPEN_SEARCH"

    /** Taps of the Add / Voice / Scan widget carry this address, so the app can tell them from the search bar's. */
    const val SCHEME = "txnkeeper-widget"
    const val SOURCE_QUICK = "quick"
}

/** Opens MainActivity, optionally with one of the [WidgetActions]. No action just opens the app. */
internal fun widgetLaunchIntent(context: Context, action: String?, source: String? = null): Intent =
    Intent(context, MainActivity::class.java).apply {
        this.action = action ?: Intent.ACTION_MAIN
        // Part of the address on purpose: it keeps this button's PendingIntent apart from the other widget's
        // button with the same action (extras would not)
        if (source != null) data = Uri.parse("${WidgetActions.SCHEME}://$source")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

object Widgets {
    /**
     * Redraws every placed widget (used when the app's theme choice changes). Safe to call from anywhere.
     */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            // No widget placed, or the launcher is busy: nothing to do, it redraws the next time anyway
            runCatching { QuickActionsWidget().updateAll(appContext) }
            runCatching { SearchBarWidget().updateAll(appContext) }
        }
    }
}

/** The app's colours (ui/theme/Color.kt) for the widgets. Light and dark come from the app's setting; "System" follows the phone. */
internal class WidgetPalette(mode: ThemeMode) {
    // The "Add / Voice" card
    val card = pick(mode, LightSurface, DarkSurface)
    val ink = pick(mode, LightInk, DarkInk)
    val onInk = pick(mode, Color.White, Color.Black)
    val soft = pick(mode, LightSurfaceVariant, DarkSurfaceVariant)
    val onSoft = pick(mode, LightOnSurface, DarkOnSurface)

    // The search bar
    val bar = pick(mode, LightSurfaceVariant, DarkSurfaceVariant)
    val icon = pick(mode, LightOnSurface, DarkOnSurface)
    val hint = pick(mode, LightOnSurfaceVariant, DarkOnSurfaceVariant)

    private fun pick(mode: ThemeMode, light: Color, dark: Color): ColorProvider = when (mode) {
        ThemeMode.LIGHT -> ColorProvider(light)
        ThemeMode.DARK -> ColorProvider(dark)
        ThemeMode.SYSTEM -> DayNightColorProvider(day = light, night = dark)
    }
}

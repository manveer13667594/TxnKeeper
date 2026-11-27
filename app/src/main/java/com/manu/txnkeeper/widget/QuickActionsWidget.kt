package com.manu.txnkeeper.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.manu.txnkeeper.AppSettings

/** The short home screen widget: add a transaction, voice entry and scan a receipt. See WidgetBar.kt. */
class QuickActionsWidget : GlanceAppWidget() {

    // The real size, so the bar can turn into a column when the widget is tall
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read when the widget is drawn; the app redraws it when the theme or the last tapped button changes
        val settings = AppSettings(context)
        val palette = WidgetPalette(settings.themeMode)
        val last = settings.lastQuickAction
        provideContent {
            ShortcutBar(
                context = context,
                buttons = listOf(ADD_BUTTON, VOICE_BUTTON, SCAN_BUTTON),
                lastAction = last,
                palette = palette,
            )
        }
    }
}

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}

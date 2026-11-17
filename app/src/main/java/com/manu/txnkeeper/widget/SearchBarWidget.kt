package com.manu.txnkeeper.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.manu.txnkeeper.AppSettings
import com.manu.txnkeeper.R

/**
 * The second home screen widget, laid out like the Google search bar: a rounded bar with the app logo on
 * the left (tap it to open the app) and three icons on the right: + (add a transaction), the mic (voice
 * entry) and the camera (scan a receipt).
 *
 * Same app colours and theme choice as [QuickActionsWidget]. Narrow: logo and icons. Wider: the app name
 * shows next to the logo as well.
 *
 * The bar is always one row high ([BAR_HEIGHT]) and sits in the middle of whatever space the launcher
 * gives it, so it never turns into a tall block. Its width limits are in res/xml/search_bar_widget_info.xml.
 */
class SearchBarWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read when the widget is drawn; AppSettings.updateThemeMode redraws it when the choice changes
        val mode = AppSettings(context).themeMode
        val palette = WidgetPalette(mode)
        provideContent {
            val showName = LocalSize.current.width >= WIDE.width
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(BAR_HEIGHT)
                        .background(palette.bar)
                        .cornerRadius(BAR_HEIGHT / 2)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left: logo (and name), opens the app
                    Row(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .clickable(actionStartActivity(widgetLaunchIntent(context, null))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(GlanceModifier.width(4.dp))
                        Image(
                            provider = ImageProvider(R.mipmap.txn_keeper_round),
                            contentDescription = context.getString(R.string.widget_open_app),
                            modifier = GlanceModifier.size(34.dp),
                        )
                        if (showName) {
                            Spacer(GlanceModifier.width(10.dp))
                            Text(
                                text = context.getString(R.string.widget_hint),
                                maxLines = 1,
                                style = TextStyle(
                                    color = palette.hint,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                    }
                    // Right: + , mic, camera
                    IconAction(
                        R.drawable.ic_widget_add, context.getString(R.string.widget_add_transaction),
                        palette.icon, widgetLaunchIntent(context, WidgetActions.ADD_TRANSACTION),
                    )
                    IconAction(
                        R.drawable.ic_widget_mic, context.getString(R.string.widget_voice_entry),
                        palette.icon, widgetLaunchIntent(context, WidgetActions.VOICE_ENTRY),
                    )
                    IconAction(
                        R.drawable.ic_widget_camera, context.getString(R.string.widget_scan_receipt),
                        palette.icon, widgetLaunchIntent(context, WidgetActions.OPEN_CAMERA),
                    )
                }
            }
        }
    }

    private companion object {
        val BAR_HEIGHT = 56.dp
        val COMPACT = DpSize(200.dp, 55.dp)
        val WIDE = DpSize(260.dp, 55.dp)
    }
}

/** A bare icon with a roomy tap area, like the icons on the Google search bar. */
@Composable
private fun IconAction(iconRes: Int, description: String, tint: ColorProvider, intent: Intent) {
    Box(
        modifier = GlanceModifier
            .size(44.dp)
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

class SearchBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SearchBarWidget()
}

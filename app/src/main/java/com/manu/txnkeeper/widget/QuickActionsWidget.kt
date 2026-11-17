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
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
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
 * The first home screen widget: a card with two buttons, "+" (add a transaction) and the mic (voice entry).
 *
 * It looks like the app: the same black-and-white palette (ui/theme/Color.kt), a white / charcoal card,
 * the main button in "ink" (near black in light, near white in dark) like the app's buttons and selected
 * pills, and the second one in the soft grey of the app's secondary surfaces. It follows the app's
 * Light / Dark / System choice from Settings, not the wallpaper colours.
 *
 * Narrow (2 cells): icons only. Wider: icon and a short label. How far it can be stretched is set in
 * res/xml/quick_actions_widget_info.xml (maxResizeWidth / maxResizeHeight).
 */
class QuickActionsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read when the widget is drawn; AppSettings.updateThemeMode redraws it when the choice changes
        val mode = AppSettings(context).themeMode
        val palette = WidgetPalette(mode)
        provideContent {
            val showLabels = LocalSize.current.width >= WIDE.width
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(palette.card)
                    .cornerRadius(28.dp)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    iconRes = R.drawable.ic_widget_add,
                    label = context.getString(R.string.widget_add_label),
                    description = context.getString(R.string.widget_add_transaction),
                    showLabel = showLabels,
                    background = palette.ink,
                    content = palette.onInk,
                    intent = widgetLaunchIntent(context, WidgetActions.ADD_TRANSACTION),
                )
                Spacer(GlanceModifier.width(8.dp))
                ActionButton(
                    iconRes = R.drawable.ic_widget_mic,
                    label = context.getString(R.string.widget_voice_label),
                    description = context.getString(R.string.widget_voice_entry),
                    showLabel = showLabels,
                    background = palette.soft,
                    content = palette.onSoft,
                    intent = widgetLaunchIntent(context, WidgetActions.VOICE_ENTRY),
                )
            }
        }
    }

    private companion object {
        val COMPACT = DpSize(110.dp, 55.dp)
        val WIDE = DpSize(200.dp, 55.dp)
    }
}

@Composable
private fun RowScope.ActionButton(
    iconRes: Int,
    label: String,
    description: String,
    showLabel: Boolean,
    background: ColorProvider,
    content: ColorProvider,
    intent: Intent,
) {
    Box(
        modifier = GlanceModifier
            .defaultWeight()
            .fillMaxHeight()
            .background(background)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(iconRes),
                // With a visible label the icon needs no description of its own
                contentDescription = if (showLabel) null else description,
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(content),
            )
            if (showLabel) {
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = label,
                    maxLines = 1,
                    style = TextStyle(
                        color = content,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}

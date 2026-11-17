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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.manu.txnkeeper.AppSettings
import com.manu.txnkeeper.R

/**
 * The first home screen widget: a short, rounded bar with three pill buttons, "+" (add a transaction),
 * the mic (voice entry) and the camera (scan a receipt).
 *
 * The button you used last is the highlighted one, in "ink" (near black in light, near white in dark, like
 * the app's buttons); the others sit in the card colour. Before the first tap none is highlighted. The app
 * remembers the last tap (AppSettings.lastQuickAction, set when MainActivity opens from this widget) and
 * redraws the widget.
 *
 * Every button's fill and icon colour are set on every draw, highlighted or not. The launcher redraws a
 * widget onto the old one, so a colour that is only set for the highlighted button would stay on it after
 * another button is tapped.
 *
 * Same palette and Light / Dark / System choice as the app and [SearchBarWidget]. Narrow: icons only. Wider:
 * icon and a short label. The bar is always [BAR_HEIGHT] high, in the middle of the space the launcher gives
 * it, and only its width can be changed (res/xml/quick_actions_widget_info.xml).
 */
class QuickActionsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read when the widget is drawn; the app redraws it when the theme or the last tapped button changes
        val settings = AppSettings(context)
        val palette = WidgetPalette(settings.themeMode)
        val last = settings.lastQuickAction
        provideContent {
            val showLabels = LocalSize.current.width >= WIDE.width
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .height(BAR_HEIGHT)
                        .background(palette.bar)
                        .cornerRadius(BAR_HEIGHT / 2)
                        .padding(BAR_PADDING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuickButton(
                        iconRes = R.drawable.ic_widget_add,
                        label = context.getString(R.string.widget_add_label),
                        description = context.getString(R.string.widget_add_transaction),
                        showLabel = showLabels,
                        focused = last == WidgetActions.ADD_TRANSACTION,
                        palette = palette,
                        intent = widgetLaunchIntent(context, WidgetActions.ADD_TRANSACTION, WidgetActions.SOURCE_QUICK),
                    )
                    Spacer(GlanceModifier.width(BAR_PADDING))
                    QuickButton(
                        iconRes = R.drawable.ic_widget_mic,
                        label = context.getString(R.string.widget_voice_label),
                        description = context.getString(R.string.widget_voice_entry),
                        showLabel = showLabels,
                        focused = last == WidgetActions.VOICE_ENTRY,
                        palette = palette,
                        intent = widgetLaunchIntent(context, WidgetActions.VOICE_ENTRY, WidgetActions.SOURCE_QUICK),
                    )
                    Spacer(GlanceModifier.width(BAR_PADDING))
                    QuickButton(
                        iconRes = R.drawable.ic_widget_camera,
                        label = context.getString(R.string.widget_scan_label),
                        description = context.getString(R.string.widget_scan_receipt),
                        showLabel = showLabels,
                        focused = last == WidgetActions.OPEN_CAMERA,
                        palette = palette,
                        intent = widgetLaunchIntent(context, WidgetActions.OPEN_CAMERA, WidgetActions.SOURCE_QUICK),
                    )
                }
            }
        }
    }

    private companion object {
        /** How tall the bar is. Change this one number to make the widget taller or shorter. */
        val BAR_HEIGHT = 64.dp
        val BAR_PADDING = 8.dp
        val COMPACT = DpSize(150.dp, 40.dp)
        val WIDE = DpSize(240.dp, 40.dp)
    }
}

@Composable
private fun RowScope.QuickButton(
    iconRes: Int,
    label: String,
    description: String,
    showLabel: Boolean,
    focused: Boolean,
    palette: WidgetPalette,
    intent: Intent,
) {
    // Always both colours, see the note on QuickActionsWidget
    val fill = if (focused) palette.ink else palette.card
    val content = if (focused) palette.onInk else palette.onSoft
    Box(
        modifier = GlanceModifier
            .defaultWeight()
            .fillMaxHeight()
            .background(fill)
            .cornerRadius((BUTTON_HEIGHT) / 2)
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(iconRes),
                // With a visible label the icon needs no description of its own
                contentDescription = if (showLabel) null else description,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(content),
            )
            if (showLabel) {
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = label,
                    maxLines = 1,
                    style = TextStyle(
                        color = content,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/** A button fills the bar's height minus the padding above and below it. */
private val BUTTON_HEIGHT = 40.dp

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}
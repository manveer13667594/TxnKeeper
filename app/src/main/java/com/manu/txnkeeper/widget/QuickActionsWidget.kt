package com.manu.txnkeeper.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import com.manu.txnkeeper.MainActivity
import com.manu.txnkeeper.R

/** Intent actions MainActivity listens for. */
object WidgetActions {
    const val ADD_TRANSACTION = "com.manu.txnkeeper.action.ADD_TRANSACTION"
    const val VOICE_ENTRY = "com.manu.txnkeeper.action.VOICE_ENTRY"
}

class QuickActionsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(28.dp)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionButton(
                        iconRes = R.drawable.ic_widget_add,
                        description = context.getString(R.string.widget_add_transaction),
                        intent = launchIntent(context, WidgetActions.ADD_TRANSACTION),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    ActionButton(
                        iconRes = R.drawable.ic_widget_mic,
                        description = context.getString(R.string.widget_voice_entry),
                        intent = launchIntent(context, WidgetActions.VOICE_ENTRY),
                    )
                }
            }
        }
    }

    private fun launchIntent(context: Context, action: String) =
        Intent(context, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
}

@Composable
private fun RowScope.ActionButton(iconRes: Int, description: String, intent: Intent) {
    Box(
        modifier = GlanceModifier
            .defaultWeight()
            .fillMaxHeight()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            modifier = GlanceModifier.size(28.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
        )
    }
}

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}

package com.manu.txnkeeper.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.manu.txnkeeper.R

/*
 * The design both widgets share: a rounded bar of buttons. The button used last is highlighted in
 * "ink", the others sit in the card colour. Every button's colours are set on every draw, because
 * the launcher draws a widget onto the old one and a highlight would otherwise stay behind.
 *
 * There are three layouts, picked from the widget's size:
 *   1. a row of pills with icon and text (wide)
 *   2. a row of round icon buttons (narrower)
 *   3. a column of round icon buttons (taller than wide)
 * Buttons have the same size in both widgets and the bar is only as long as its buttons need, in the
 * middle of the space the launcher gives it. They get a little smaller when space is tight, but never
 * below MIN_ICON_BUTTON: a widget too small for all of them shows fewer (see buttonsThatFit).
 */

/** One button of the bar. */
internal data class WidgetButton(
    val action: String,
    val iconRes: Int,
    val labelRes: Int,
    val descriptionRes: Int,
)

internal val SEARCH_BUTTON = WidgetButton(
    WidgetActions.OPEN_SEARCH, R.drawable.ic_widget_search,
    R.string.widget_search_label, R.string.widget_search_transactions,
)
internal val ADD_BUTTON = WidgetButton(
    WidgetActions.ADD_TRANSACTION, R.drawable.ic_widget_add,
    R.string.widget_add_label, R.string.widget_add_transaction,
)
internal val VOICE_BUTTON = WidgetButton(
    WidgetActions.VOICE_ENTRY, R.drawable.ic_widget_mic,
    R.string.widget_voice_label, R.string.widget_voice_entry,
)
internal val SCAN_BUTTON = WidgetButton(
    WidgetActions.OPEN_CAMERA, R.drawable.ic_widget_camera,
    R.string.widget_scan_label, R.string.widget_scan_receipt,
)

/** How thick the bar is: its height as a row, its width as a column. */
private val BAR_THICKNESS = 64.dp
/** Space around the buttons and between them. */
private val BAR_PADDING = 8.dp

/** A round icon button: the bar's thickness minus the padding on both sides. */
private val ICON_BUTTON = 48.dp
/** Round buttons never get smaller than this, so icons stay clear and easy to tap. */
private val MIN_ICON_BUTTON = 40.dp
/** A pill with text. It may shrink to the minimum; below that the text is dropped. */
private val LABEL_BUTTON = 88.dp
private val MIN_LABEL_BUTTON = 80.dp

/** When there is no room for every button, the ones listed first here are kept. */
private val KEEP_ORDER = listOf(ADD_BUTTON, VOICE_BUTTON, SCAN_BUTTON, SEARCH_BUTTON)

/** The buttons that fit along [length] without getting too small, in their usual order. At least one. */
private fun buttonsThatFit(buttons: List<WidgetButton>, length: Dp): List<WidgetButton> {
    val count = ((length - BAR_PADDING) / (MIN_ICON_BUTTON + BAR_PADDING)).toInt().coerceIn(1, buttons.size)
    val kept = KEEP_ORDER.filter { it in buttons }.take(count)
    return buttons.filter { it in kept }
}

/** Needs SizeMode.Exact on the widget, so that LocalSize is the widget's real size. */
@Composable
internal fun ShortcutBar(
    context: Context,
    buttons: List<WidgetButton>,
    lastAction: String?,
    palette: WidgetPalette,
) {
    val size = LocalSize.current
    val vertical = size.height > size.width
    val longSide = if (vertical) size.height else size.width
    val shortSide = if (vertical) size.width else size.height

    val thickness = minOf(BAR_THICKNESS, shortSide)
    val shown = buttonsThatFit(buttons, longSide)
    // What one button can take along the bar
    val room = (longSide - BAR_PADDING * (shown.size + 1)) / shown.size
    val showLabels = !vertical && room >= MIN_LABEL_BUTTON

    val iconSize = minOf(ICON_BUTTON, thickness - BAR_PADDING * 2, room)
    val buttonWidth = if (showLabels) minOf(LABEL_BUTTON, room) else iconSize
    val buttonHeight = if (showLabels) thickness - BAR_PADDING * 2 else iconSize
    val buttonModifier = GlanceModifier.width(buttonWidth).height(buttonHeight)

    val bar = GlanceModifier
        .background(palette.bar)
        .cornerRadius(thickness / 2)
        .padding(BAR_PADDING)

    @Composable
    fun Item(button: WidgetButton) = ShortcutButton(
        context, button, button.action == lastAction, showLabels, palette,
        buttonModifier, buttonHeight / 2,
    )

    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (vertical) {
            Column(modifier = bar.width(thickness), horizontalAlignment = Alignment.CenterHorizontally) {
                shown.forEachIndexed { index, button ->
                    if (index > 0) Spacer(GlanceModifier.height(BAR_PADDING))
                    Item(button)
                }
            }
        } else {
            Row(modifier = bar.height(thickness), verticalAlignment = Alignment.CenterVertically) {
                shown.forEachIndexed { index, button ->
                    if (index > 0) Spacer(GlanceModifier.width(BAR_PADDING))
                    Item(button)
                }
            }
        }
    }
}

@Composable
private fun ShortcutButton(
    context: Context,
    button: WidgetButton,
    focused: Boolean,
    showLabel: Boolean,
    palette: WidgetPalette,
    modifier: GlanceModifier,
    radius: Dp,
) {
    val fill = if (focused) palette.ink else palette.card
    val content = if (focused) palette.onInk else palette.onSoft
    val intent = widgetLaunchIntent(context, button.action, WidgetActions.SOURCE_QUICK)
    Box(
        modifier = modifier
            .background(fill)
            .cornerRadius(radius)
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(button.iconRes),
                // With a visible label the icon needs no description of its own
                contentDescription = if (showLabel) null else context.getString(button.descriptionRes),
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(content),
            )
            if (showLabel) {
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = context.getString(button.labelRes),
                    maxLines = 1,
                    style = TextStyle(color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}
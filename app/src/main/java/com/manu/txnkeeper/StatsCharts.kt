package com.manu.txnkeeper

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.theme.ManuDesignSystem
import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.TransactionEntity
import com.manu.txnkeeper.ui.theme.ChartCategoryColors
import com.manu.txnkeeper.ui.theme.ChartOtherColor
import com.manu.txnkeeper.ui.theme.ExpenseColor
import com.manu.txnkeeper.ui.theme.IncomeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The extra charts of the Stats screen (everything except the balance line, which is in StatsScreen.kt):
 *   In vs Out   grouped bars, income next to expense per day / week / month (drag sideways, tap a bar)
 *   Breakdown   donut of expenses by payment method or by account, with a legend that shows each share
 *   Top spends  ranked rows with a share bar: the titles you spent the most on
 *   By weekday  seven columns, the busiest day in full colour (tap a day for its amount)
 *
 * Top spends and By weekday are plain Compose (no chart library): text always fits, nothing is
 * clipped at large font sizes, and they sit fine inside the scrolling Stats page.
 *
 * Every panel has a fixed height (or is just a list), because the Stats page scrolls. Nothing here uses
 * weight(1f) or a nested vertical scroll.
 *
 * Colours: income / expense and the pie slice colours come from ui/theme/Color.kt; text and grid
 * colours from MaterialTheme, so light and dark follow the app.
 * The numbers come from the pure functions at the bottom of ChartData.kt. They run off the main thread
 * (see rememberComputed), so a big history does not freeze the screen.
 */

private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val FULL_WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** Income-vs-expense bars: this many groups on screen, scroll sideways for the rest. */
private const val VISIBLE_BAR_GROUPS = 8

/** Fixed chart heights (the page scrolls, so charts do not stretch to fill what is left). */
private val BAR_CHART_HEIGHT = 250.dp
private val DONUT_HEIGHT = 200.dp
private val WEEKDAY_HEIGHT = 170.dp

/** The tallest weekday column, in dp. */
private const val WEEKDAY_BAR_MAX_DP = 110f

// ------------------------------------------------------------------ chips and the panel

/** The row of chips that picks which chart is shown. */
private fun StatsChartType.icon(): ImageVector = when (this) {
    StatsChartType.BALANCE -> Icons.AutoMirrored.Filled.ShowChart
    StatsChartType.INCOME_EXPENSE -> Icons.Default.BarChart
    StatsChartType.BREAKDOWN -> Icons.Default.PieChart
    StatsChartType.TOP_SPENDS -> Icons.Default.Leaderboard
    StatsChartType.WEEKDAY -> Icons.Default.DateRange
}

/** Short text that fits five to a row under an icon. */
private fun StatsChartType.shortLabel(): String = when (this) {
    StatsChartType.BALANCE -> "Balance"
    StatsChartType.INCOME_EXPENSE -> "In/Out"
    StatsChartType.BREAKDOWN -> "Split"
    StatsChartType.TOP_SPENDS -> "Top"
    StatsChartType.WEEKDAY -> "Weekday"
}

/** One line under the chart tabs saying what the chart shows. */
internal fun StatsChartType.description(): String = when (this) {
    StatsChartType.BALANCE -> "Your balance after every transaction"
    StatsChartType.INCOME_EXPENSE -> "Money in and out, period by period"
    StatsChartType.BREAKDOWN -> "Where the money went or came from"
    StatsChartType.TOP_SPENDS -> "The biggest titles, ranked"
    StatsChartType.WEEKDAY -> "Which days of the week add up the most"
}

/** The five chart types as equal tabs (icon over a short label). They always fit, so nothing scrolls sideways. */
@Composable
internal fun ChartTypeChips(
    selected: StatsChartType,
    onSelect: (StatsChartType) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        StatsChartType.entries.forEach { type ->
            val on = type == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) scheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(type) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    type.icon(), contentDescription = null,
                    tint = if (on) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = type.shortLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** A row of equal segments, one selected (used for the time window and Line / Step). */
@Composable
internal fun SegmentedRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** True when there are too many segments to share the width: they keep their own size and the row scrolls. */
    scrollable: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                modifier = (if (scrollable) Modifier else Modifier.weight(1f))
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) scheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp, horizontal = if (scrollable) 14.dp else 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Whatever sits under the chips for the charts other than Balance. [txns] are all the transactions
 * of the open Stats page; [rangeDays] is the chosen 1W / 1M / ... window (0 = all).
 */
@Composable
internal fun MoreChartsPanel(
    type: StatsChartType,
    txns: List<TransactionEntity>,
    rangeDays: Int,
    accountNames: Map<Long, String>,
    breakdownBy: BreakdownBy,
    onBreakdownBy: (BreakdownBy) -> Unit,
    tagsOf: Map<Int, List<TagEntity>> = emptyMap(),
    /** "EXPENSE" or "INCOME": what the breakdown, top titles and weekday charts add up. */
    kind: String = "EXPENSE",
    onKind: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (type) {
        StatsChartType.BALANCE -> Unit   // drawn by StatsScreen itself
        StatsChartType.INCOME_EXPENSE -> IncomeExpensePanel(txns, rangeDays, modifier)
        StatsChartType.BREAKDOWN, StatsChartType.TOP_SPENDS, StatsChartType.WEEKDAY -> Column(modifier = modifier) {
            // These three charts add up one kind of transaction at a time
            SegmentedRow(
                labels = listOf("Expense", "Income"),
                selectedIndex = if (kind == "INCOME") 1 else 0,
                onSelect = { onKind(if (it == 1) "INCOME" else "EXPENSE") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            when (type) {
                StatsChartType.BREAKDOWN -> BreakdownPanel(txns, rangeDays, accountNames, breakdownBy, onBreakdownBy, tagsOf, kind, Modifier)
                StatsChartType.TOP_SPENDS -> TopSpendsPanel(txns, rangeDays, kind, Modifier)
                else -> WeekdayPanel(txns, rangeDays, kind, Modifier)
            }
        }
    }
}

/**
 * Works out [block] on a background thread whenever one of [keys] changes. null until the first
 * result; after that the old result stays on screen while a new one is being worked out.
 */
@Composable
private fun <T> rememberComputed(vararg keys: Any?, block: () -> T): T? {
    val state = produceState<T?>(initialValue = null, *keys) {
        value = withContext(Dispatchers.Default) { block() }
    }
    return state.value
}

/**
 * One or two lines above a chart. It always reserves two lines, so the chart below does not jump up
 * and down when the text changes between one line and two.
 */
@Composable
internal fun ChartCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 2,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A thin bar showing [fraction] (0..1) of its full width. Used under legend and ranking rows. */
@Composable
private fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

private fun percentText(part: Long, total: Long): String {
    if (total <= 0L) return "0%"
    val share = part * 100.0 / total
    return if (share < 1.0) "<1%" else "${share.roundToInt()}%"
}

// ------------------------------------------------------------------ In vs Out

private class PeriodChart(val bucket: Bucket, val periods: List<PeriodTotals>)

private val Bucket.noun: String
    get() = when (this) {
        Bucket.DAY -> "day"
        Bucket.WEEK -> "week"
        Bucket.MONTH -> "month"
    }

private fun formatDate(pattern: String, millis: Long): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

private fun periodAxisLabel(startMillis: Long, bucket: Bucket): String =
    formatDate(if (bucket == Bucket.MONTH) "MMM yy" else "d MMM", startMillis)

private fun periodFullLabel(startMillis: Long, bucket: Bucket): String = when (bucket) {
    Bucket.DAY -> formatDate("EEE, d MMM yyyy", startMillis)
    Bucket.WEEK -> "Week of " + formatDate("d MMM yyyy", startMillis)
    Bucket.MONTH -> formatDate("MMMM yyyy", startMillis)
}

@Composable
private fun IncomeExpensePanel(txns: List<TransactionEntity>, rangeDays: Int, modifier: Modifier) {
    val chart = rememberComputed(txns, rangeDays) {
        val inRange = withinDays(txns, rangeDays)
        val bucket = bucketFor(inRange)
        PeriodChart(bucket, incomeExpenseByPeriod(inRange, bucket))
    }
    // The bar that was tapped (cleared whenever the data changes)
    var picked by remember(chart) { mutableStateOf<PeriodTotals?>(null) }

    Column(modifier = modifier) {
        val c = chart
        val p = picked
        ChartCaption(
            when {
                c == null -> " "
                p != null -> {
                    val net = p.incomePaise - p.expensePaise
                    "${periodFullLabel(p.startMillis, c.bucket)}\n" +
                            "In ${formatMoney(p.incomePaise)} · Out ${formatMoney(p.expensePaise)} · Net ${formatSignedMoney(net)}"
                }
                else -> {
                    val income = c.periods.sumOf { it.incomePaise }
                    val expense = c.periods.sumOf { it.expensePaise }
                    "In ${formatMoney(income)} · Out ${formatMoney(expense)} · Net ${formatSignedMoney(income - expense)}\n" +
                            "Tap a bar to see one ${c.bucket.noun}"
                }
            }
        )
        when {
            c == null -> Spacer(Modifier.height(BAR_CHART_HEIGHT))
            c.periods.isEmpty() -> EmptyNote("No transactions in this range.", Modifier.height(BAR_CHART_HEIGHT))
            else -> IncomeExpenseBars(
                periods = c.periods,
                bucket = c.bucket,
                onPick = { picked = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_CHART_HEIGHT)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot("Income", IncomeColor)
            LegendDot("Expense", ExpenseColor)
        }
    }
}

/** What the bar chart was last filled with, so a tap (which recomposes) does not reset the scroll. */
private class BarState(val source: Any, val textColor: Int)

@Composable
private fun IncomeExpenseBars(
    periods: List<PeriodTotals>,
    bucket: Bucket,
    onPick: (PeriodTotals?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurfaceVariant.toArgb()
    val gridColor = scheme.outlineVariant.toArgb()
    val highlightColor = scheme.onSurface.toArgb()
    val income = IncomeColor.toArgb()
    val expense = ExpenseColor.toArgb()
    val latestOnPick by rememberUpdatedState(onPick)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BarChart(ctx).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("")
                setDrawGridBackground(false)
                setExtraOffsets(4f, 6f, 12f, 4f)

                // Drag sideways to scroll; no zoom. Vertical drags are left to the page, which scrolls.
                setTouchEnabled(true)
                isDragEnabled = true
                setDragYEnabled(false)
                setScaleXEnabled(false)
                setScaleYEnabled(false)
                setPinchZoom(false)
                isDoubleTapToZoomEnabled = false

                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisLeft.setDrawAxisLine(false)
                axisLeft.setLabelCount(5, false)
                axisLeft.gridLineWidth = 0.6f
                axisLeft.valueFormatter = MoneyAxisFormatter()
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.setDrawAxisLine(false)
                xAxis.granularity = 1f
                xAxis.isGranularityEnabled = true
                xAxis.setCenterAxisLabels(true)
                xAxis.setAvoidFirstLastClipping(true)

                setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        latestOnPick(e?.data as? PeriodTotals)
                    }

                    override fun onNothingSelected() {
                        latestOnPick(null)
                    }
                })
            }
        },
        update = { chart ->
            chart.xAxis.textColor = textColor
            chart.axisLeft.textColor = textColor
            chart.axisLeft.gridColor = gridColor

            val old = chart.tag as? BarState
            if (old != null && old.source === periods && old.textColor == textColor) return@AndroidView
            chart.tag = BarState(periods, textColor)

            val n = periods.size
            val labels = periods.map { periodAxisLabel(it.startMillis, bucket) }
            chart.xAxis.valueFormatter = IndexLabels(labels)
            // About five date labels at most; more would run into each other
            chart.xAxis.setLabelCount(5, false)

            // No value text on the bars: with two bars per group it overlapped. Tap a bar for the numbers.
            fun barSet(label: String, color: Int, y: (PeriodTotals) -> Long) = BarDataSet(
                periods.mapIndexed { i, p -> BarEntry(i.toFloat(), y(p) / 100f, p) }, label
            ).apply {
                this.color = color
                setDrawValues(false)
                // The library's default tap highlight is orange; use the text colour, lightly
                highLightColor = highlightColor
                highLightAlpha = 50
            }

            val data = BarData(barSet("Income", income) { it.incomePaise }, barSet("Expense", expense) { it.expensePaise })
            // Two bars plus a gap make one group of width 1:  (0.38 + 0.04) * 2 + 0.16 = 1
            data.barWidth = 0.38f
            chart.data = data
            chart.xAxis.axisMinimum = 0f
            chart.xAxis.axisMaximum = n.toFloat()
            data.groupBars(0f, 0.16f, 0.04f)

            chart.highlightValue(null as Highlight?, false)
            chart.fitScreen()
            if (n > VISIBLE_BAR_GROUPS) {
                chart.setVisibleXRangeMaximum(VISIBLE_BAR_GROUPS.toFloat())
                chart.moveViewToX((n - VISIBLE_BAR_GROUPS).toFloat())   // start on the newest
            }
            chart.notifyDataSetChanged()
            chart.invalidate()
        }
    )
}

// ------------------------------------------------------------------ Breakdown (donut)

private class BreakdownResult(val slices: List<Slice>, val totalPaise: Long)

@Composable
private fun BreakdownPanel(
    txns: List<TransactionEntity>,
    rangeDays: Int,
    accountNames: Map<Long, String>,
    breakdownBy: BreakdownBy,
    onBreakdownBy: (BreakdownBy) -> Unit,
    tagsOf: Map<Int, List<TagEntity>>,
    kind: String,
    modifier: Modifier
) {
    val noun = if (kind == "INCOME") "Income" else "Expenses"
    val result = rememberComputed(txns, rangeDays, breakdownBy, accountNames, tagsOf, kind) {
        val slices = spendBreakdown(withinDays(txns, rangeDays), breakdownBy, accountNames, tagsOf = tagsOf, kind = kind)
        BreakdownResult(slices, slices.sumOf { it.paise })
    }

    Column(modifier = modifier) {
        // "Group by": payment / account / tag as one row of equal segments
        SegmentedRow(
            labels = BreakdownBy.entries.map { it.label },
            selectedIndex = BreakdownBy.entries.indexOf(breakdownBy),
            onSelect = { onBreakdownBy(BreakdownBy.entries[it]) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )
        val r = result
        val what = breakdownBy.label.lowercase()
        ChartCaption(
            if (r == null || r.totalPaise == 0L) "$noun by $what"
            else "$noun by $what\n${formatMoney(r.totalPaise)} in total"
        )
        if (r != null) {
            if (r.slices.isEmpty()) {
                EmptyNote("No ${noun.lowercase()} in this range.", Modifier.padding(vertical = 32.dp))
            } else {
                val colors = remember(r) {
                    // Every slice gets its own colour: a tag keeps the colour you gave it, unless an earlier
                    // slice already uses that one (two tags can share a colour); then the next free one is used.
                    val used = HashSet<Color>()
                    r.slices.mapIndexed { i, s ->
                        val greyed = s.isOther ||
                                (s.label == UNTAGGED_LABEL && s.colorIndex == null && breakdownBy == BreakdownBy.TAG)
                        val colour = when {
                            greyed -> ChartOtherColor
                            s.colorIndex != null -> tagColor(s.colorIndex).takeIf { it !in used }
                                ?: TAG_COLORS.firstOrNull { it !in used }
                                ?: ChartCategoryColors.firstOrNull { it !in used }
                                ?: tagColor(s.colorIndex)
                            else -> ChartCategoryColors[i % ChartCategoryColors.size]
                        }
                        used.add(colour)
                        colour
                    }
                }
                DonutChart(
                    slices = r.slices,
                    colors = colors,
                    totalPaise = r.totalPaise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DONUT_HEIGHT)
                )
                SliceLegend(r.slices, colors, r.totalPaise)
            }
        }
    }
}

@Composable
private fun DonutChart(slices: List<Slice>, colors: List<Color>, totalPaise: Long, modifier: Modifier = Modifier) {
    val centerTextColor = MaterialTheme.colorScheme.onSurface.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PieChart(ctx).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("")
                setTouchEnabled(false)          // the legend below has the numbers; keeps the page scrollable
                setDrawEntryLabels(false)
                setHoleRadius(64f)
                setTransparentCircleRadius(0f)
                setHoleColor(AndroidColor.TRANSPARENT)
                setDrawCenterText(true)
                setCenterTextSize(18f)
            }
        },
        update = { chart ->
            chart.setCenterTextColor(centerTextColor)
            chart.setCenterText(compactMoney(totalPaise))
            val set = PieDataSet(slices.map { PieEntry(it.paise / 100f, it.label) }, "")
            set.setColors(colors.map { it.toArgb() })
            set.sliceSpace = 2f
            set.setDrawValues(false)
            chart.data = PieData(set)
            chart.invalidate()
        }
    )
}

/** One row per slice: colour dot, name, amount, share of the total, and a share bar underneath. */
@Composable
private fun SliceLegend(slices: List<Slice>, colors: List<Color>, totalPaise: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        slices.forEachIndexed { i, s ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors[i])
                    )
                    Text(
                        text = s.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, end = 8.dp)
                    )
                    Text(
                        text = formatMoney(s.paise),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = percentText(s.paise, totalPaise),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(40.dp)
                    )
                }
                ShareBar(
                    fraction = if (totalPaise > 0) s.paise.toFloat() / totalPaise else 0f,
                    color = colors[i],
                    modifier = Modifier.padding(start = 18.dp, top = 5.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Top spends

private class TopSpendsResult(val slices: List<Slice>, val totalExpensePaise: Long)

@Composable
private fun TopSpendsPanel(txns: List<TransactionEntity>, rangeDays: Int, kind: String, modifier: Modifier) {
    val income = kind == "INCOME"
    val result = rememberComputed(txns, rangeDays, kind) {
        val inRange = withinDays(txns, rangeDays)
        TopSpendsResult(
            slices = topSpendTitles(inRange, kind = kind),
            totalExpensePaise = inRange.filter { it.type == kind }.sumOf { it.amountPaise }
        )
    }

    Column(modifier = modifier) {
        val r = result
        ChartCaption(
            if (r == null || r.slices.isEmpty() || r.totalExpensePaise <= 0L) {
                if (income) "Titles you earned the most from, added up" else "Titles you spent the most on, added up"
            }
            else {
                val top = r.slices.sumOf { it.paise }
                "Top ${r.slices.size} titles are ${percentText(top, r.totalExpensePaise)} of your ${if (income) "income" else "spending"}\n" +
                        "${formatMoney(top)} of ${formatMoney(r.totalExpensePaise)}"
            }
        )
        when {
            r == null -> Spacer(Modifier.height(120.dp))
            r.slices.isEmpty() -> EmptyNote(if (income) "No income in this range." else "No expenses in this range.", Modifier.padding(vertical = 32.dp))
            else -> {
                val biggest = r.slices.first().paise.coerceAtLeast(1L)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    r.slices.forEachIndexed { i, s ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${i + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(22.dp)
                                )
                                Text(
                                    text = s.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                )
                                Text(
                                    text = formatMoney(s.paise),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            // Bars are measured against the biggest title, so the ranking is easy to compare
                            ShareBar(
                                fraction = s.paise.toFloat() / biggest,
                                color = if (income) IncomeColor else ExpenseColor,
                                modifier = Modifier.padding(start = 22.dp, top = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ By weekday

@Composable
private fun WeekdayPanel(txns: List<TransactionEntity>, rangeDays: Int, kind: String, modifier: Modifier) {
    val income = kind == "INCOME"
    val totals = rememberComputed(txns, rangeDays, kind) { spendByWeekday(withinDays(txns, rangeDays), kind) }
    // The day that was tapped (cleared whenever the data changes); tapping it again clears it
    var picked by remember(totals) { mutableStateOf<Int?>(null) }

    Column(modifier = modifier) {
        val sum = totals?.sum() ?: 0L
        val busiest = totals?.let { busiestWeekday(it) }
        val shown = picked ?: busiest
        ChartCaption(
            when {
                totals == null -> " "
                shown == null -> if (income) "Income by day of week" else "Expenses by day of week"
                else -> {
                    val v = totals[shown]
                    (if (picked == null) "Busiest day: " else "") +
                            "${FULL_WEEKDAYS[shown]}\n${formatMoney(v)} · ${percentText(v, sum)} of your ${if (income) "income" else "spending"}"
                }
            }
        )
        when {
            totals == null -> Spacer(Modifier.height(WEEKDAY_HEIGHT))
            sum == 0L -> EmptyNote(if (income) "No income in this range." else "No expenses in this range.", Modifier.height(WEEKDAY_HEIGHT))
            else -> WeekdayBars(
                totals = totals,
                emphasised = shown,
                color = if (income) IncomeColor else ExpenseColor,
                onPick = { picked = if (picked == it) null else it }
            )
        }
    }
}

/** Seven columns, Monday to Sunday. The [emphasised] day is drawn in full colour and labelled. */
@Composable
private fun WeekdayBars(totals: List<Long>, emphasised: Int?, color: Color, onPick: (Int) -> Unit) {
    val biggest = totals.max().coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WEEKDAY_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        totals.forEachIndexed { i, v ->
            val strong = i == emphasised
            val barDp = if (v <= 0L) 0f else maxOf(4f, WEEKDAY_BAR_MAX_DP * v / biggest)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = if (strong && v > 0L) compactMoney(v) else " ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .fillMaxWidth(0.7f)
                        .height(barDp.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (strong) color else color.copy(alpha = 0.35f))
                )
                Text(
                    text = WEEKDAY_LABELS[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ small shared pieces

/** x axis text taken from a list: the label for x = 0, 1, 2 ... */
private class IndexLabels(private val labels: List<String>) : ValueFormatter() {
    override fun getFormattedValue(value: Float): String = labels.getOrNull(value.roundToInt()) ?: ""
}
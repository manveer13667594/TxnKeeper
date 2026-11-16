package com.manu.txnkeeper

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
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
 *   In vs Out   grouped bars, income next to expense per day / week / month
 *   Breakdown   donut of expenses by payment method or by account, with a legend list
 *   Top spends  horizontal bars of the titles you spent the most on
 *   By weekday  bars of expense per day of the week, the busiest day in full colour
 *
 * Colours: income / expense and the pie slice colours come from ui/theme/Color.kt; text and grid
 * colours from MaterialTheme, so light and dark follow the app.
 * The numbers come from the pure functions at the bottom of ChartData.kt. They run off the main thread
 * (see rememberComputed), so a big history does not freeze the screen.
 */

private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Value labels on top of bars only when there are this few bars; more would overlap. */
private const val MAX_BAR_VALUE_LABELS = 6

/** Income-vs-expense bars: this many groups on screen, scroll sideways for the rest. */
private const val VISIBLE_BAR_GROUPS = 8

/** Weekday bars that are not the busiest day are drawn at this opacity. */
private const val DIMMED_BAR_ALPHA = 0.55f

/** Top-spends titles are cut to this many characters on the axis. */
private const val MAX_TITLE_CHARS = 16

// ------------------------------------------------------------------ chips and the panel

/** The row of chips that picks which chart is shown. */
@Composable
internal fun ChartTypeChips(
    selected: StatsChartType,
    onSelect: (StatsChartType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatsChartType.entries.forEach { type ->
            ManuChip(label = type.label, selected = type == selected, onClick = { onSelect(type) })
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
    modifier: Modifier = Modifier
) {
    when (type) {
        StatsChartType.BALANCE -> Unit   // drawn by StatsScreen itself
        StatsChartType.INCOME_EXPENSE -> IncomeExpensePanel(txns, rangeDays, modifier)
        StatsChartType.BREAKDOWN -> BreakdownPanel(txns, rangeDays, accountNames, breakdownBy, onBreakdownBy, modifier)
        StatsChartType.TOP_SPENDS -> TopSpendsPanel(txns, rangeDays, modifier)
        StatsChartType.WEEKDAY -> WeekdayPanel(txns, rangeDays, modifier)
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

@Composable
private fun ChartCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
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
                p != null -> "${periodFullLabel(p.startMillis, c.bucket)} · In ${formatMoney(p.incomePaise)} · Out ${formatMoney(p.expensePaise)}"
                else -> "Income and expense per ${c.bucket.noun} · tap a bar"
            }
        )
        if (c == null) {
            Box(Modifier.weight(1f))
        } else {
            IncomeExpenseBars(
                periods = c.periods,
                bucket = c.bucket,
                onPick = { picked = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
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

                // Drag sideways to scroll; no zoom
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleXEnabled(false)
                setScaleYEnabled(false)
                setPinchZoom(false)
                isDoubleTapToZoomEnabled = false

                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisLeft.setDrawAxisLine(false)
                axisLeft.setLabelCount(5, false)
                axisLeft.valueFormatter = MoneyAxisFormatter()
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.setDrawAxisLine(false)
                xAxis.granularity = 1f
                xAxis.isGranularityEnabled = true
                xAxis.setCenterAxisLabels(true)

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
            chart.xAxis.setLabelCount(if (n <= 7) n else 6, false)

            val showValues = n <= MAX_BAR_VALUE_LABELS
            fun set(label: String, color: Int, y: (PeriodTotals) -> Long) = BarDataSet(
                periods.mapIndexed { i, p -> BarEntry(i.toFloat(), y(p) / 100f, p) }, label
            ).apply {
                this.color = color
                setDrawValues(showValues)
                valueTextSize = 9f
                valueTextColor = textColor
                valueFormatter = CompactBarLabels()
            }

            val data = BarData(set("Income", income) { it.incomePaise }, set("Expense", expense) { it.expensePaise })
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
    modifier: Modifier
) {
    val result = rememberComputed(txns, rangeDays, breakdownBy, accountNames) {
        val slices = spendBreakdown(withinDays(txns, rangeDays), breakdownBy, accountNames)
        BreakdownResult(slices, slices.sumOf { it.paise })
    }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
        ) {
            BreakdownBy.entries.forEach { by ->
                ManuChip(label = by.label, selected = by == breakdownBy, onClick = { onBreakdownBy(by) })
            }
        }
        val r = result
        val what = breakdownBy.label.lowercase()
        ChartCaption(
            if (r == null || r.totalPaise == 0L) "Expenses by $what"
            else "Expenses by $what · ${formatMoney(r.totalPaise)} in total"
        )
        if (r != null) {
            if (r.slices.isEmpty()) {
                EmptyNote("No expenses in this range.", Modifier.padding(top = 32.dp))
            } else {
                val colors = remember(r) {
                    r.slices.mapIndexed { i, s ->
                        if (s.isOther) ChartOtherColor else ChartCategoryColors[i % ChartCategoryColors.size]
                    }
                }
                DonutChart(
                    slices = r.slices,
                    colors = colors,
                    totalPaise = r.totalPaise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
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

/** One row per slice: colour dot, name, amount, share of the total. */
@Composable
private fun SliceLegend(slices: List<Slice>, colors: List<Color>, totalPaise: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        slices.forEachIndexed { i, s ->
            val share = if (totalPaise > 0) s.paise * 100.0 / totalPaise else 0.0
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
                    text = if (share < 1.0) "<1%" else "${share.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Top spends

@Composable
private fun TopSpendsPanel(txns: List<TransactionEntity>, rangeDays: Int, modifier: Modifier) {
    val slices = rememberComputed(txns, rangeDays) { topSpendTitles(withinDays(txns, rangeDays)) }

    Column(modifier = modifier) {
        ChartCaption("Titles you spent the most on, added up")
        when {
            slices == null -> Box(Modifier.weight(1f))
            slices.isEmpty() -> EmptyNote("No expenses in this range.", Modifier.weight(1f))
            else -> TopSpendBars(
                slices = slices,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun TopSpendBars(slices: List<Slice>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val labelColor = scheme.onSurface.toArgb()
    val valueColor = scheme.onSurfaceVariant.toArgb()
    val expense = ExpenseColor.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            HorizontalBarChart(ctx).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("")
                setTouchEnabled(false)
                setDrawGridBackground(false)
                // Value labels sit on the bars, so the money axes are not needed. They still set the scale.
                axisLeft.isEnabled = false
                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisRight.axisMinimum = 0f
                xAxis.position = XAxis.XAxisPosition.BOTTOM   // titles, on the left in a horizontal chart
                xAxis.setDrawGridLines(false)
                xAxis.setDrawAxisLine(false)
                xAxis.granularity = 1f
                xAxis.isGranularityEnabled = true
                setExtraOffsets(0f, 4f, 56f, 4f)              // room on the right for the amount
            }
        },
        update = { chart ->
            // A horizontal chart draws the first entry at the bottom, so feed it smallest first
            val ordered = slices.asReversed()
            chart.xAxis.textColor = labelColor
            chart.xAxis.valueFormatter = IndexLabels(ordered.map { ellipsize(it.label, MAX_TITLE_CHARS) })
            chart.xAxis.setLabelCount(ordered.size, false)

            val set = BarDataSet(ordered.mapIndexed { i, s -> BarEntry(i.toFloat(), s.paise / 100f, s) }, "Top spends")
            set.color = expense
            set.valueTextColor = valueColor
            set.valueTextSize = 10f
            set.valueFormatter = CompactBarLabels()
            val data = BarData(set)
            data.barWidth = 0.6f
            chart.data = data
            chart.notifyDataSetChanged()
            chart.invalidate()
        }
    )
}

// ------------------------------------------------------------------ By weekday

@Composable
private fun WeekdayPanel(txns: List<TransactionEntity>, rangeDays: Int, modifier: Modifier) {
    val totals = rememberComputed(txns, rangeDays) { spendByWeekday(withinDays(txns, rangeDays)) }

    Column(modifier = modifier) {
        val busiest = totals?.withIndex()?.maxByOrNull { it.value }?.takeIf { it.value > 0L }
        ChartCaption(
            if (busiest != null) "Expenses by day of week · most on ${WEEKDAY_LABELS[busiest.index]}"
            else "Expenses by day of week"
        )
        when {
            totals == null -> Box(Modifier.weight(1f))
            totals.all { it == 0L } -> EmptyNote("No expenses in this range.", Modifier.weight(1f))
            else -> WeekdayBars(
                totals = totals,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun WeekdayBars(totals: List<Long>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurfaceVariant.toArgb()
    val gridColor = scheme.outlineVariant.toArgb()
    val strong = ExpenseColor.toArgb()
    val dimmed = ExpenseColor.copy(alpha = DIMMED_BAR_ALPHA).toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BarChart(ctx).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("")
                setTouchEnabled(false)
                setDrawGridBackground(false)
                setFitBars(true)
                setExtraOffsets(4f, 14f, 12f, 4f)
                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
                axisLeft.setDrawAxisLine(false)
                axisLeft.setLabelCount(5, false)
                axisLeft.valueFormatter = MoneyAxisFormatter()
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.setDrawAxisLine(false)
                xAxis.granularity = 1f
                xAxis.isGranularityEnabled = true
                xAxis.valueFormatter = IndexLabels(WEEKDAY_LABELS)
                xAxis.setLabelCount(WEEKDAY_LABELS.size, false)
            }
        },
        update = { chart ->
            chart.xAxis.textColor = textColor
            chart.axisLeft.textColor = textColor
            chart.axisLeft.gridColor = gridColor

            val busiest = totals.max()
            val set = BarDataSet(totals.mapIndexed { i, v -> BarEntry(i.toFloat(), v / 100f) }, "Expense")
            set.setColors(totals.map { if (it == busiest) strong else dimmed })
            set.valueTextColor = textColor
            set.valueTextSize = 9f
            set.valueFormatter = CompactBarLabels()
            val data = BarData(set)
            data.barWidth = 0.6f
            chart.data = data
            chart.notifyDataSetChanged()
            chart.invalidate()
        }
    )
}

// ------------------------------------------------------------------ small shared pieces

/** x axis text taken from a list: the label for x = 0, 1, 2 ... */
private class IndexLabels(private val labels: List<String>) : ValueFormatter() {
    override fun getFormattedValue(value: Float): String = labels.getOrNull(value.roundToInt()) ?: ""
}

/** Bar tops: short money (₹1.5K), and nothing at all for an empty bar. */
private class CompactBarLabels : ValueFormatter() {
    override fun getBarLabel(barEntry: BarEntry?): String {
        val y = barEntry?.y ?: return ""
        return if (y <= 0f) "" else compactMoney((y * 100.0).toLong())
    }
}

private fun ellipsize(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

package com.manu.txnkeeper

import android.graphics.Canvas
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.MotionEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.style.TextOverflow
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.MPPointD
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.view.doOnLayout
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.IMarker
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IFillFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.theme.ExpenseColor
import com.manu.manudesignsystem.theme.IncomeColor
import com.manu.manudesignsystem.theme.ManuDesignSystem
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.TransactionDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the stats screen starts: whatever the home list had (filters, search, account). The screen
 * keeps its own copy from there, so changing filters inside Stats does not change the home list.
 */
data class StatsRequest(
    val filters: TxnFilters,
    val search: String,
    val accountId: Long?
)

// Saving the open Stats page across a screen rotation (the request and its filters are plain values)
private fun TxnFilters.toSaveList(): ArrayList<Any?> =
    arrayListOf(newestFirst, type, payment, minAmount, maxAmount, period.name, offset, customFrom, customTo)

private fun filtersFromSaveList(l: List<*>) = TxnFilters(
    newestFirst = l[0] as Boolean,
    type = l[1] as String?,
    payment = l[2] as String?,
    minAmount = l[3] as Double?,
    maxAmount = l[4] as Double?,
    period = PeriodKind.valueOf(l[5] as String),
    offset = l[6] as Int,
    customFrom = l[7] as Long?,
    customTo = l[8] as Long?
)

private val FiltersSaver: Saver<TxnFilters, java.io.Serializable> = Saver(
    save = { it.toSaveList() },
    restore = { filtersFromSaveList(it as List<*>) }
)

internal val StatsRequestSaver: Saver<StatsRequest, java.io.Serializable> = Saver(
    save = { arrayListOf<Any?>(it.filters.toSaveList(), it.search, it.accountId) },
    restore = {
        val l = it as List<*>
        StatsRequest(filtersFromSaveList(l[0] as List<*>), l[1] as String, l[2] as Long?)
    }
)

/** Quick time windows: label and days (0 = everything). */
private val RANGES = listOf("1W" to 7, "1M" to 30, "3M" to 90, "6M" to 180, "1Y" to 365, "All" to 0)

/** Show the last this-many points at first when there are more; zoom out or scroll for the rest. */
private const val INITIAL_VISIBLE_POINTS = 40

/** Above this many points the dots get smaller so a long history stays readable. */
private const val SMALL_DOT_THRESHOLD = 120

/**
 * Opened by tapping the balance card on the home screen. One chart point per transaction (exact
 * date and time, not averaged per day): an income lifts the line, an expense drops it. Pinch to
 * zoom, drag to scroll, double-tap to zoom in, tap a point for its details.
 */
@Composable
fun StatsScreen(
    dao: TransactionDao,
    request: StatsRequest,
    settings: AppSettings,
    accounts: List<AccountSummary>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Filters live here while Stats is open (they start as the home screen's)
    var filters by rememberSaveable(request, stateSaver = FiltersSaver) { mutableStateOf(request.filters) }
    var accountId by rememberSaveable(request) { mutableStateOf(request.accountId) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    val search = request.search

    val query = remember(filters, search, accountId) { filters.toQuery(search, accountId) }
    val txns by remember(query) { dao.observeAllForChart(query) }
        .collectAsState(initial = null)

    // With no filters the line starts at the real opening balance; with filters it is a running net from 0
    val anchored = filters.activeCount == 0 && search.isEmpty()
    val startPaise = if (anchored) {
        accounts.filter { accountId == null || it.id == accountId }.sumOf { it.openingBalancePaise }
    } else 0L
    val title = accounts.firstOrNull { it.id == accountId }?.name ?: "All"

    val usedMethods by remember { dao.observePaymentMethodsInUse() }.collectAsState(initial = emptyList())
    val filterMethods = remember(settings.paymentMethods, usedMethods) {
        (settings.paymentMethods + usedMethods).distinct()
    }
    val series = remember(txns, startPaise) { txns?.let { buildChartSeries(it, startPaise) } }

    var stepped by rememberSaveable { mutableStateOf(false) }
    // The chosen quick window (null = the default "latest points" view); the tick makes a repeat tap re-apply it
    var rangeDays by rememberSaveable { mutableStateOf<Int?>(null) }
    var rangeTick by rememberSaveable { mutableIntStateOf(0) }
    // The point being touched on the chart (shown in the card at the top)
    var selected by remember { mutableStateOf<ChartPoint?>(null) }
    LaunchedEffect(series) { selected = null }
    val insights = remember(series) { series?.let { computeInsights(it) } }
    // Until a chip is tapped, open on the smallest window with enough points, and show that chip as selected
    val effectiveDays = rangeDays ?: remember(series) { series?.let { defaultRangeDays(it) } ?: 0 }

    ManuScaffold(
        // The home screen sits underneath this page. Without a touch handler here, a tap on any empty
        // part of this page (the top bar, between cards) falls through to the home icons below it.
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } },
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "Stats",
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.Default.ArrowBack,
                onNavigationClick = onClose,
                actions = {
                    IconButton(onClick = { filterSheetOpen = true }) {
                        if (filters.activeCount > 0) {
                            BadgedBox(badge = { Badge { Text("${filters.activeCount}") } }) {
                                Icon(
                                    AppIcons.Sliders,
                                    contentDescription = "Filters applied: ${filters.activeCount}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Icon(AppIcons.Sliders, contentDescription = "Filters")
                        }
                    }
                }
            )
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            AccountChips(accounts = accounts, selectedId = accountId, onSelect = { accountId = it })
            PeriodNavigator(filters = filters, onChange = { filters = it })

            val s = series
            if (s == null) {
                // Still loading
                Box(Modifier.fillMaxSize())
            } else {
                SummaryCard(s, anchored, title, selected)

                if (s.isEmpty) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No transactions to chart yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RANGES.forEach { (label, days) ->
                            ManuChip(
                                label = label,
                                selected = effectiveDays == days,
                                onClick = { rangeDays = days; rangeTick++ }
                            )
                        }
                        Box(Modifier.width(8.dp))
                        ManuChip(label = "Line", selected = !stepped, onClick = { stepped = false })
                        ManuChip(label = "Step", selected = stepped, onClick = { stepped = true })
                    }

                    TxnChart(
                        series = s,
                        stepped = stepped,
                        rangeDays = effectiveDays,
                        rangeTick = rangeTick,
                        onSelected = { selected = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LegendDot("Income", IncomeColor)
                        LegendDot("Expense", ExpenseColor)
                        Text(
                            text = "Pinch · drag · tap a point",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    insights?.let { InsightsRow(it, modifier = Modifier.padding(bottom = 12.dp)) }
                }
            }
        }
    }

    if (filterSheetOpen) {
        FilterSheet(
            filters = filters,
            onChange = { filters = it },
            onDismiss = { filterSheetOpen = false },
            paymentMethods = filterMethods
        )
    }
}

@Composable
private fun SummaryCard(series: ChartSeries, anchored: Boolean, title: String, selected: ChartPoint?) {
    // Touching a point on the chart shows the balance right after that transaction
    val value = selected?.balancePaise ?: if (anchored) series.endPaise else series.netPaise
    val label = when {
        selected != null -> {
            val t = selected.txn
            (if (anchored) "Balance after" else "Net after") +
                    " · ${t.title.ifBlank { "transaction" }} · ${formatDateLong(t.dateTime)}, ${formatTime(t.dateTime)}"
        }
        !anchored -> "Net change · filtered transactions"
        title.equals("All", ignoreCase = true) -> "Balance"
        else -> "Balance · $title"
    }
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (anchored) formatBalance(value) else formatSignedMoney(value),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (value < 0) ExpenseColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Row(modifier = Modifier.padding(top = 10.dp)) {
                Cell("Income", formatMoney(series.incomePaise), IncomeColor, Alignment.Start)
                Cell("Expense", formatMoney(series.expensePaise), ExpenseColor, Alignment.CenterHorizontally)
                Cell(
                    "Net", formatSignedMoney(series.netPaise),
                    if (series.netPaise >= 0) IncomeColor else ExpenseColor, Alignment.End
                )
            }
        }
    }
}

/** Scrollable strip of small facts about the plotted transactions. */
@Composable
private fun InsightsRow(i: ChartInsights, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
    ) {
        InsightTile("Avg spend / day", formatMoney(i.avgDailyExpensePaise), null, ExpenseColor)
        i.biggestExpense?.let {
            InsightTile("Biggest expense", formatMoney(it.txn.amountPaise), it.txn.title.ifBlank { formatDateLong(it.txn.dateTime) }, ExpenseColor)
        }
        i.biggestIncome?.let {
            InsightTile("Biggest income", formatMoney(it.txn.amountPaise), it.txn.title.ifBlank { formatDateLong(it.txn.dateTime) }, IncomeColor)
        }
        i.highest?.let { InsightTile("Peak balance", formatBalance(it.paise), formatDateLong(it.millis), null) }
        i.lowest?.let { InsightTile("Lowest balance", formatBalance(it.paise), formatDateLong(it.millis), null) }
    }
}

@Composable
private fun InsightTile(label: String, value: String, sub: String?, color: Color?) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = sub ?: " ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp)
        )
    }
}

@Composable
private fun RowScope.Cell(label: String, value: String, color: Color, align: Alignment.Horizontal) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = align) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = " $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------ the chart

/** What the chart currently shows, kept on the view so zoom survives recomposition. */
private class ChartState(val series: ChartSeries, val rangeTick: Int)

/** Draws the line with a green / red shader, but leaves the dots and labels in their own colours. */
private class TxnLineRenderer(chart: LineChart) : LineChartRenderer(chart, chart.animator, chart.viewPortHandler) {
    var lineShader: Shader? = null

    override fun drawData(c: Canvas) {
        paintRender.shader = lineShader
        super.drawData(c)
        paintRender.shader = null   // the dots (drawn after the line) use paintRender too
    }
}

/** A line chart whose line is green above the zero line and red below it. */
private class TxnLineChart(context: Context) : LineChart(context) {
    var positiveColor: Int = android.graphics.Color.GREEN
    var negativeColor: Int = android.graphics.Color.RED
    private val lineRenderer = TxnLineRenderer(this)

    init {
        renderer = lineRenderer
    }

    override fun onDraw(canvas: Canvas) {
        val top = viewPortHandler.contentTop()
        val bottom = viewPortHandler.contentBottom()
        if (bottom > top) {
            // Where y = 0 is on screen, as a fraction of the plot height: the colour switches exactly there
            val zero = getTransformer(YAxis.AxisDependency.LEFT).getPixelForValues(0f, 0f)
            val z = ((zero.y.toFloat() - top) / (bottom - top)).coerceIn(0f, 1f)
            MPPointD.recycleInstance(zero)
            lineRenderer.lineShader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(positiveColor, positiveColor, negativeColor, negativeColor),
                floatArrayOf(0f, z, z, 1f),
                Shader.TileMode.CLAMP
            )
        }
        super.onDraw(canvas)
    }
}

/**
 * X axis labels, on one line each (the library does not draw line breaks). The format follows the
 * spacing between labels: close together they show the time (the first of each day also carries its
 * date), under a day apart they show date and hour, further apart just the date, then month and year.
 */
private class TimeAxisFormatter(var series: ChartSeries?, private val chart: LineChart) : ValueFormatter() {
    override fun getFormattedValue(value: Float): String {
        val s = series ?: return ""
        val axis = chart.xAxis
        val step = if (axis.mEntryCount > 1) axis.mEntries[1] - axis.mEntries[0] else chart.visibleXRange / 3f
        val millis = s.millisAt(value)
        val locale = Locale.getDefault()
        val pattern = when {
            step < 3 * 60 -> {
                val firstOfDay = startOfDay(s.millisAt(value - step)) != startOfDay(millis)
                if (firstOfDay) "d MMM h:mm a" else "h:mm a"
            }
            step < 24 * 60 -> "d MMM, h a"
            step < 60 * 24 * 60 -> "d MMM"
            else -> "MMM yy"
        }
        return SimpleDateFormat(pattern, locale).format(Date(millis))
    }
}

private class MoneyAxisFormatter : ValueFormatter() {
    // Chart y values are rupees (Float); back to paise for the label
    override fun getFormattedValue(value: Float): String = compactMoney((value * 100.0).toLong())
}

/** The popup shown when a point is tapped. Drawn by hand, so no layout file is needed. */
private class PointMarker(private val chart: LineChart, density: Float) : IMarker {
    private val d = density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * d; typeface = Typeface.DEFAULT_BOLD }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d }
    private var textColor = 0
    private var incomeColor = 0
    private var expenseColor = 0
    private var lines: List<String> = emptyList()
    private var amountColor = 0
    private val offset = MPPointF(0f, 0f)
    private val box = RectF()

    fun setColors(background: Int, text: Int, income: Int, expense: Int) {
        boxPaint.color = background
        titlePaint.color = text
        bodyPaint.color = text
        textColor = text
        incomeColor = income
        expenseColor = expense
    }

    /** Is this touch inside the popup as it was last drawn? (used to close it) */
    fun contains(x: Float, y: Float): Boolean = !box.isEmpty && box.contains(x, y)

    override fun getOffset(): MPPointF = offset
    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF = offset

    override fun refreshContent(e: Entry, highlight: Highlight) {
        val p = e.data as? ChartPoint ?: return
        val t = p.txn
        val signed = if (p.isIncome) "+" + formatMoney(t.amountPaise) else "−" + formatMoney(t.amountPaise)
        amountColor = if (p.isIncome) incomeColor else expenseColor
        lines = listOf(
            t.title.ifBlank { if (p.isIncome) "Income" else "Expense" },
            "$signed · ${t.paymentMethod}",
            "${formatDateLong(t.dateTime)}, ${formatTime(t.dateTime)}",
            "Balance after: ${formatBalance(p.balancePaise)}"
        )
    }

    override fun draw(canvas: Canvas, posX: Float, posY: Float) {
        if (lines.isEmpty()) return
        val pad = 10f * d
        val lineGap = 4f * d
        val titleLine = titlePaint.fontSpacing
        val bodyLine = bodyPaint.fontSpacing
        val width = lines.indices.maxOf { if (it == 0) titlePaint.measureText(lines[it]) else bodyPaint.measureText(lines[it]) } + pad * 2
        val height = pad * 2 + titleLine + bodyLine * (lines.size - 1) + lineGap * (lines.size - 1)

        // Above the point if there is room, otherwise below; kept inside the chart horizontally
        val gap = 12f * d
        var top = posY - gap - height
        if (top < 0f) top = posY + gap
        var left = posX - width / 2f
        left = left.coerceIn(0f, (chart.width - width).coerceAtLeast(0f))

        box.set(left, top, left + width, top + height)
        canvas.drawRoundRect(box, 10f * d, 10f * d, boxPaint)
        var y = top + pad - titlePaint.ascent()
        canvas.drawText(lines[0], left + pad, y, titlePaint)
        for (i in 1 until lines.size) {
            y += (if (i == 1) titleLine else bodyLine) + lineGap
            bodyPaint.color = if (i == 1) amountColor else textColor
            canvas.drawText(lines[i], left + pad, y, bodyPaint)
        }
        bodyPaint.color = textColor
    }
}

/**
 * Taps: a tap on a point opens its popup; a tap on the same point, on the popup itself, or anywhere
 * with no point close by closes it. Dragging or zooming closes it too.
 */
private class TapHandler(private val chart: LineChart) : OnChartGestureListener {
    private fun clear() {
        if (!chart.highlighted.isNullOrEmpty()) chart.highlightValue(null as Highlight?, true)
    }

    override fun onChartSingleTapped(me: MotionEvent?) {
        me ?: return
        val current = chart.highlighted?.firstOrNull()
        val marker = chart.marker as? PointMarker
        if (current != null && marker != null && marker.contains(me.x, me.y)) {
            clear()
            return
        }
        val hit = chart.getHighlightByTouchPoint(me.x, me.y)
        if (hit == null || (current != null && current.x == hit.x && current.dataSetIndex == hit.dataSetIndex)) {
            clear()
        } else {
            chart.highlightValue(hit, true)
        }
    }

    override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) = clear()
    override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) = clear()
    override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
    override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
    override fun onChartLongPressed(me: MotionEvent?) {}
    override fun onChartDoubleTapped(me: MotionEvent?) {}
    override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
}

@Composable
private fun TxnChart(
    series: ChartSeries,
    stepped: Boolean,
    rangeDays: Int?,
    rangeTick: Int,
    onSelected: (ChartPoint?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val scheme = MaterialTheme.colorScheme
    val lineColor = scheme.primary.toArgb()
    val textColor = scheme.onSurfaceVariant.toArgb()
    val gridColor = scheme.outlineVariant.toArgb()
    val markerBg = scheme.inverseSurface.toArgb()
    val markerText = scheme.inverseOnSurface.toArgb()
    val income = IncomeColor.toArgb()
    val expense = ExpenseColor.toArgb()
    val latestOnSelected by rememberUpdatedState(onSelected)
    // The one biggest transaction gets a label on the chart
    val largest = remember(series) { series.points.maxByOrNull { it.txn.amountPaise } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TxnLineChart(ctx).apply {
                description.isEnabled = false
                legend.isEnabled = false
                setNoDataText("")
                setDrawGridBackground(false)
                setExtraOffsets(4f, 6f, 12f, 4f)

                // Touch: drag to scroll, pinch / double-tap to zoom along time only
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleXEnabled(true)
                setScaleYEnabled(false)
                setPinchZoom(false)
                isDoubleTapToZoomEnabled = true
                isHighlightPerDragEnabled = false
                isDragDecelerationEnabled = true
                dragDecelerationFrictionCoef = 0.88f

                // Taps are handled by TapHandler (open / close the popup). Only a point within ~30dp counts.
                isHighlightPerTapEnabled = false
                maxHighlightDistance = 30f
                onChartGestureListener = TapHandler(this)
                setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                    override fun onValueSelected(e: Entry?, h: Highlight?) {
                        latestOnSelected(e?.data as? ChartPoint)
                    }

                    override fun onNothingSelected() {
                        latestOnSelected(null)
                    }
                })
                marker = PointMarker(this, density)

                axisRight.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.setLabelCount(3, false)
                xAxis.granularity = 1f
                xAxis.isGranularityEnabled = true
                xAxis.valueFormatter = TimeAxisFormatter(null, this)

                axisLeft.setDrawGridLines(true)
                axisLeft.setLabelCount(5, false)
                axisLeft.setDrawZeroLine(true)
                axisLeft.zeroLineWidth = 1.2f
                axisLeft.valueFormatter = MoneyAxisFormatter()
                axisLeft.setDrawAxisLine(false)
                xAxis.setDrawAxisLine(false)
            }
        },
        update = { chart ->
            // Colours follow the theme every time (light / dark can change while open)
            chart.positiveColor = income
            chart.negativeColor = expense
            chart.xAxis.textColor = textColor
            chart.axisLeft.textColor = textColor
            chart.axisLeft.gridColor = gridColor
            chart.axisLeft.gridLineWidth = 0.6f
            chart.axisLeft.zeroLineColor = textColor
            (chart.marker as? PointMarker)?.setColors(markerBg, markerText, income, expense)
            (chart.xAxis.valueFormatter as? TimeAxisFormatter)?.series = series

            val old = chart.tag as? ChartState
            val dataChanged = old == null || old.series !== series
            val rangeAsked = old != null && old.rangeTick != rangeTick

            if (dataChanged) {
                val entries = series.points.map { Entry(it.x, it.balancePaise / 100f, it) }
                val set = LineDataSet(entries, "Balance")
                chart.data = LineData(set)

                // Y range always includes 0 (the baseline the colours split on), with some air around
                val ys = series.points.map { it.balancePaise / 100f }
                val lo = minOf(0f, ys.min())
                val hi = maxOf(0f, ys.max())
                val margin = ((hi - lo) * 0.08f).coerceAtLeast(1f)
                chart.axisLeft.axisMinimum = lo - margin
                chart.axisLeft.axisMaximum = hi + margin

                val minX = series.points.first().x
                val maxX = series.points.last().x
                val pad = maxOf((maxX - minX) * 0.04f, 1f)
                chart.xAxis.axisMinimum = minX - pad
                chart.xAxis.axisMaximum = maxX + pad
                chart.tag = ChartState(series, rangeTick)
                showWindow(chart, series, rangeDays)
            } else if (rangeAsked) {
                chart.tag = ChartState(series, rangeTick)
                showWindow(chart, series, rangeDays)
            }

            // Look of the line: refreshed on every update, so Line / Step and theme changes apply at once
            val set = chart.data?.getDataSetByIndex(0) as? LineDataSet
            if (set != null) {
                val n = series.points.size
                set.color = lineColor            // the real colour comes from the green / red shader in TxnLineChart
                set.lineWidth = 2f
                set.mode = if (stepped) LineDataSet.Mode.STEPPED else LineDataSet.Mode.LINEAR
                set.setDrawCircles(true)
                set.setCircleColors(series.points.map { if (it.isIncome) income else expense })
                set.circleRadius = if (n > SMALL_DOT_THRESHOLD) 2f else 4.5f
                set.setDrawCircleHole(false)

                // Only the biggest transaction gets a text label
                set.setDrawValues(true)
                set.valueTextSize = 10f
                set.valueTextColor = textColor
                set.valueFormatter = object : ValueFormatter() {
                    override fun getPointLabel(entry: Entry?): String {
                        val p = entry?.data as? ChartPoint ?: return ""
                        if (p !== largest) return ""
                        return "Largest " + (if (p.isIncome) "+" else "−") + formatMoney(p.txn.amountPaise)
                    }
                }

                // Fill between the line and the zero line: green where the balance is above 0, red below.
                // One vertical gradient with a hard colour change exactly at 0, fading towards the zero line.
                val top = chart.axisLeft.axisMaximum
                val bottom = chart.axisLeft.axisMinimum
                val zero = (top / (top - bottom)).coerceIn(0.01f, 0.99f)
                fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
                set.setDrawFilled(true)
                set.fillFormatter = IFillFormatter { _, _ -> 0f }
                set.fillDrawable = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setColors(
                        intArrayOf(alpha(income, 0x70), alpha(income, 0x0C), alpha(expense, 0x0C), alpha(expense, 0x70)),
                        floatArrayOf(0f, zero, zero, 1f)
                    )
                }
                set.highLightColor = textColor
                set.highlightLineWidth = 1f
                set.setDrawHorizontalHighlightIndicator(false)
                set.isHighlightEnabled = true
            }
            chart.notifyDataSetChanged()
            chart.invalidate()
        }
    )
}

/**
 * Picks what part of the time axis is on screen: [days] = null shows the latest points, 0 shows
 * everything, otherwise the last that-many days. Runs only once the chart has a size.
 *
 * The window is placed by setting the chart's zoom / scroll matrix directly, anchored on the newest
 * transaction (not on the end of the axis), so it always lands on real data.
 */
private fun showWindow(chart: LineChart, series: ChartSeries, days: Int?) {
    val apply = {
        chart.highlightValue(null as Highlight?, true)
        chart.fitScreen()
        val pts = series.points
        val n = pts.size
        val axisMin = chart.xAxis.axisMinimum
        val axisMax = chart.xAxis.axisMaximum
        val total = axisMax - axisMin
        val vp = chart.viewPortHandler
        val w = vp.contentWidth()
        val span: Float? = when {
            days == 0 -> null
            days != null -> days * 24f * 60f
            n > INITIAL_VISIBLE_POINTS ->
                // The last N points, never narrower than ~2 hours so a burst stays readable
                (pts.last().x - pts[n - INITIAL_VISIBLE_POINTS].x).coerceAtLeast(120f)
            else -> null
        }
        // Pinch limits: fully zoomed out up to ~10 minutes across
        vp.setMinimumScaleX(1f)
        vp.setMaximumScaleX(maxOf(total / 10f, 1f))
        if (pts.isNotEmpty() && span != null && total.isFinite() && total > span && w > 0f) {
            // Right edge: just after the newest point; left edge: one window earlier
            val right = minOf(pts.last().x + span * 0.03f, axisMax)
            val left = (right - span).coerceAtLeast(axisMin)
            val scale = total / span
            val m = Matrix()
            m.setScale(scale, 1f)
            // value -> pixel is (x - axisMin) * w / total, then scaled; shift so that `left` sits at the left edge
            m.postTranslate(-(left - axisMin) * w / span, 0f)
            vp.refresh(m, chart, true)
        }
        chart.invalidate()
    }
    // Wait until the chart view has been measured
    if (chart.width > 0 && chart.height > 0) chart.post(apply)
    else chart.doOnLayout { it.post(apply) }
}

/**
 * Tiny line chart for the home card (no axes, no touch). Green when the balance ended higher than it
 * started, red when lower, with a soft fill under the line.
 */
@Composable
internal fun Sparkline(values: List<Long>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val up = values.last() >= values.first()
    val color = if (up) IncomeColor else ExpenseColor
    Canvas(modifier = modifier) {
        val lo = values.min().toDouble()
        val hi = values.max().toDouble()
        val range = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val pad = 3.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun x(i: Int) = pad + w * i / (values.size - 1)
        fun y(v: Long) = (pad + h * (1.0 - (v - lo) / range)).toFloat().let { if (hi == lo) size.height / 2 else it }

        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(values.size - 1), size.height)
            lineTo(x(0), size.height)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)))
        )
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(x(values.size - 1), y(values.last())))
    }
}

package com.manu.txnkeeper

import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.TransactionEntity
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Turns transactions into chart points. Pure Kotlin (no Android, no chart library) so it can be
 * unit-tested. The chart screen is in StatsScreen.kt.
 *
 * One point per transaction, in time order. An income raises the line, an expense lowers it.
 * Nothing is averaged per day: two transactions on the same day are two points.
 */

/** One transaction on the chart. [balancePaise] is the running balance right after it. */
data class ChartPoint(
    val x: Float,                 // minutes since [ChartSeries.baseMillis]; strictly increasing
    val balancePaise: Long,
    val txn: TransactionEntity
) {
    val isIncome: Boolean get() = txn.type == "INCOME"
}

data class ChartSeries(
    val points: List<ChartPoint>,
    val baseMillis: Long,         // time of the first transaction (x = 0)
    val startPaise: Long,         // balance before the first transaction
    val incomePaise: Long,
    val expensePaise: Long
) {
    val endPaise: Long get() = points.lastOrNull()?.balancePaise ?: startPaise
    val netPaise: Long get() = incomePaise - expensePaise
    val isEmpty: Boolean get() = points.isEmpty()
}

/** Smallest gap between two points on the x axis, so same-minute transactions stay in order. */
private const val MIN_X_GAP = 0.1f
private const val MILLIS_PER_MINUTE = 60_000.0

/**
 * [txns] in any order; [startPaise] is the balance before the first one (opening balance, or 0 when
 * only a running net is wanted). Sorted by date, then id, so same-time entries keep their order.
 */
fun buildChartSeries(txns: List<TransactionEntity>, startPaise: Long): ChartSeries {
    val sorted = txns.sortedWith(compareBy<TransactionEntity> { it.dateTime }.thenBy { it.id })
    if (sorted.isEmpty()) return ChartSeries(emptyList(), 0L, startPaise, 0L, 0L)

    val base = sorted.first().dateTime
    var balance = startPaise
    var income = 0L
    var expense = 0L
    var lastX = -MIN_X_GAP
    val points = ArrayList<ChartPoint>(sorted.size)
    for (t in sorted) {
        when (t.type) {
            "INCOME" -> { balance += t.amountPaise; income += t.amountPaise }
            "EXPENSE" -> { balance -= t.amountPaise; expense += t.amountPaise }
        }
        val rawX = ((t.dateTime - base) / MILLIS_PER_MINUTE).toFloat()
        // A Float loses precision as x grows (after about 2 years of minutes a step of 0.1 is smaller
        // than the gap between two Floats), so the minimum gap grows with x. This keeps every x
        // strictly greater than the one before, however long the history is.
        val gap = maxOf(MIN_X_GAP, Math.ulp(lastX) * 2f)
        val x = if (rawX > lastX + gap) rawX else lastX + gap
        lastX = x
        points += ChartPoint(x, balance, t)
    }
    return ChartSeries(points, base, startPaise, income, expense)
}

/** Time on the x axis (minutes since the first transaction) back to a real time. */
fun ChartSeries.millisAt(x: Float): Long = baseMillis + (x * MILLIS_PER_MINUTE).toLong()

/** Short money for axis labels: 950, 1.2K, 50K, 1.5L, 2.3Cr (Indian units), with a minus sign if negative. */
fun compactMoney(paise: Long): String {
    val rupees = abs(paise) / 100.0
    val text = when {
        rupees >= 1e7 -> trim(rupees / 1e7) + "Cr"
        rupees >= 1e5 -> trim(rupees / 1e5) + "L"
        rupees >= 1e3 -> trim(rupees / 1e3) + "K"
        else -> trim(rupees)
    }
    return (if (paise < 0) "−" else "") + "₹" + text
}

private fun trim(v: Double): String {
    val s = String.format(java.util.Locale.US, "%.1f", v)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/**
 * Balances for the small trend line in the home card: the starting balance, then one value per
 * transaction, thinned to at most [maxPoints] (always keeping the first and the last).
 */
fun sparklineValues(series: ChartSeries, startPaise: Long, maxPoints: Int = 60): List<Long> {
    if (series.isEmpty) return emptyList()
    val all = ArrayList<Long>(series.points.size + 1)
    all += startPaise
    series.points.forEach { all += it.balancePaise }
    if (all.size <= maxPoints) return all
    val last = all.size - 1
    return List(maxPoints) { i -> all[(i.toLong() * last / (maxPoints - 1)).toInt()] }
}

/**
 * The same small trend line as [sparklineValues], built from the database's time slices
 * (see observeSparkBuckets): the starting balance, then the running balance after each slice.
 */
fun sparklineFromBuckets(buckets: List<com.manu.txnkeeper.data.local.SparkBucket>, startPaise: Long): List<Long> {
    if (buckets.isEmpty()) return emptyList()
    val out = ArrayList<Long>(buckets.size + 1)
    var running = startPaise
    out += running
    for (b in buckets) {
        running += b.netPaise
        out += running
    }
    return out
}

/** A balance at a moment (for "peak" and "lowest"). */
data class BalanceMark(val paise: Long, val millis: Long)

/** The numbers shown in the insight tiles under the chart. */
data class ChartInsights(
    val biggestIncome: ChartPoint?,
    val biggestExpense: ChartPoint?,
    /** Total expense divided by the days the transactions span (at least one day). */
    val avgDailyExpensePaise: Long,
    val highest: BalanceMark?,
    val lowest: BalanceMark?,
    /** Share of the income that was kept, in whole percent (negative when more was spent than earned). Null when there is no income. */
    val savingsRatePercent: Int? = null,
    val txnCount: Int = 0
)

/** (income − expense) / income as a whole percent, or null when there is no income to compare with. */
fun savingsRatePercent(incomePaise: Long, expensePaise: Long): Int? {
    if (incomePaise <= 0L) return null
    return ((incomePaise - expensePaise) * 100.0 / incomePaise).roundToInt()
}

fun computeInsights(series: ChartSeries): ChartInsights {
    if (series.isEmpty) return ChartInsights(null, null, 0L, null, null)
    val pts = series.points
    val income = pts.filter { it.txn.type == "INCOME" }.maxByOrNull { it.txn.amountPaise }
    val expense = pts.filter { it.txn.type == "EXPENSE" }.maxByOrNull { it.txn.amountPaise }
    val spanMillis = pts.last().txn.dateTime - pts.first().txn.dateTime
    val days = maxOf(1.0, spanMillis / 86_400_000.0)
    val hi = pts.maxByOrNull { it.balancePaise }
    val lo = pts.minByOrNull { it.balancePaise }
    return ChartInsights(
        biggestIncome = income,
        biggestExpense = expense,
        avgDailyExpensePaise = (series.expensePaise / days).toLong(),
        highest = hi?.let { BalanceMark(it.balancePaise, it.txn.dateTime) },
        lowest = lo?.let { BalanceMark(it.balancePaise, it.txn.dateTime) },
        savingsRatePercent = savingsRatePercent(series.incomePaise, series.expensePaise),
        txnCount = pts.size
    )
}

/**
 * The quick window to open on: the smallest of 1W / 1M / 3M / 6M / 1Y that holds at least
 * [minPoints] transactions ending at the newest one, otherwise 0 (everything).
 */
fun defaultRangeDays(series: ChartSeries, minPoints: Int = 25): Int {
    val pts = series.points
    if (pts.size < minPoints) return 0
    val lastX = pts.last().x
    for (days in listOf(7, 30, 90, 180, 365)) {
        val from = lastX - days * 24f * 60f
        if (pts.count { it.x >= from } >= minPoints) return days
    }
    return 0
}

// ------------------------------------------------------------------ more chart types
//
// Everything below is for the extra Stats charts (bars, pie, top spends, weekday). Pure Kotlin like the
// rest of this file. All of it works on a list of transactions, already cut to the time window.

/** The charts the Stats screen can show. [label] is the chip text. */
enum class StatsChartType(val label: String) {
    BALANCE("Balance"),
    INCOME_EXPENSE("In vs Out"),
    BREAKDOWN("Breakdown"),
    TOP_SPENDS("Top spends"),
    WEEKDAY("By weekday")
}

/** What the breakdown (pie) chart splits spending by. */
enum class BreakdownBy(val label: String) {
    PAYMENT("Payment method"),
    ACCOUNT("Account"),
    TAG("Tag")
}

/** How wide one bar of the income-vs-expense chart is. */
enum class Bucket { DAY, WEEK, MONTH }

/** Income and expense of one [Bucket], [startMillis] is local midnight of its first day. */
data class PeriodTotals(val startMillis: Long, val incomePaise: Long, val expensePaise: Long)

/** One labelled amount: a pie slice or a bar. [isOther] marks the "everything else" slice. */
data class Slice(
    val label: String,
    val paise: Long,
    val isOther: Boolean = false,
    /** For a tag slice: the tag's colour number (see TAG_COLORS), so the chart uses the tag's own colour. */
    val colorIndex: Int? = null
)

private const val MILLIS_PER_DAY = 86_400_000L

/** Up to this span the bars are days, up to the next one weeks, beyond that months. */
private const val DAY_BARS_MAX_DAYS = 14
private const val WEEK_BARS_MAX_DAYS = 100

/** Pie / bar charts show this many slices at most; the rest are added up into one "Other". */
private const val MAX_SLICES = 7

/**
 * Only the transactions from the last [days] days, counted back from the newest one (the same window
 * the balance chart's 1W / 1M / ... chips show). 0 = everything.
 */
fun withinDays(txns: List<TransactionEntity>, days: Int): List<TransactionEntity> {
    if (days <= 0 || txns.isEmpty()) return txns
    val from = txns.maxOf { it.dateTime } - days * MILLIS_PER_DAY
    return txns.filter { it.dateTime >= from }
}

/** Bar width that suits how much time [txns] cover, so there are roughly 4 to 30 bars. */
fun bucketFor(txns: List<TransactionEntity>): Bucket {
    if (txns.isEmpty()) return Bucket.DAY
    val span = txns.maxOf { it.dateTime } - txns.minOf { it.dateTime }
    return when {
        span <= DAY_BARS_MAX_DAYS * MILLIS_PER_DAY -> Bucket.DAY
        span <= WEEK_BARS_MAX_DAYS * MILLIS_PER_DAY -> Bucket.WEEK
        else -> Bucket.MONTH
    }
}

/** Local midnight of the first day of the bucket that holds [millis]; weeks start on Monday. */
private fun Calendar.bucketStart(millis: Long, bucket: Bucket): Long {
    firstDayOfWeek = Calendar.MONDAY
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    when (bucket) {
        Bucket.DAY -> Unit
        Bucket.WEEK -> set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        Bucket.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
    }
    return timeInMillis
}

private fun Calendar.nextBucketStart(start: Long, bucket: Bucket): Long {
    timeInMillis = start
    when (bucket) {
        Bucket.DAY -> add(Calendar.DAY_OF_MONTH, 1)
        Bucket.WEEK -> add(Calendar.DAY_OF_MONTH, 7)
        Bucket.MONTH -> add(Calendar.MONTH, 1)
    }
    return timeInMillis
}

/**
 * Income and expense added up per [bucket], oldest first. Buckets with nothing in them are still
 * included (as zeros) so a quiet week shows as a gap instead of being skipped.
 */
fun incomeExpenseByPeriod(txns: List<TransactionEntity>, bucket: Bucket): List<PeriodTotals> {
    if (txns.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    val income = HashMap<Long, Long>()
    val expense = HashMap<Long, Long>()
    var first = Long.MAX_VALUE
    var last = Long.MIN_VALUE
    for (t in txns) {
        val key = cal.bucketStart(t.dateTime, bucket)
        if (key < first) first = key
        if (key > last) last = key
        when (t.type) {
            "INCOME" -> income.merge(key, t.amountPaise) { a, b -> a + b }
            "EXPENSE" -> expense.merge(key, t.amountPaise) { a, b -> a + b }
        }
    }
    val out = ArrayList<PeriodTotals>()
    var start = first
    while (start <= last) {
        out += PeriodTotals(start, income[start] ?: 0L, expense[start] ?: 0L)
        val next = cal.nextBucketStart(start, bucket)
        if (next <= start) break
        start = next
    }
    return out
}

/** Biggest first; anything past [maxSlices] is folded into one "Other" slice at the end. */
private fun topSlices(totals: Map<String, Long>, maxSlices: Int): List<Slice> {
    val sorted = totals.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .map { Slice(it.key, it.value) }
    if (sorted.size <= maxSlices) return sorted
    val head = sorted.take(maxSlices - 1)
    val rest = sorted.drop(maxSlices - 1).sumOf { it.paise }
    return head + Slice("Other", rest, isOther = true)
}

/**
 * Expenses (income is ignored) added up per payment method or per account, for the pie chart.
 * [accountNames] maps an account id to its name.
 */
fun spendBreakdown(
    txns: List<TransactionEntity>,
    by: BreakdownBy,
    accountNames: Map<Long, String>,
    maxSlices: Int = MAX_SLICES,
    /** Transaction id -> its tags; only used when [by] is TAG. */
    tagsOf: Map<Int, List<TagEntity>> = emptyMap(),
    /** "EXPENSE" (default) or "INCOME": which kind of transaction is added up. */
    kind: String = "EXPENSE"
): List<Slice> {
    if (by == BreakdownBy.TAG) return tagBreakdown(txns, tagsOf, maxSlices, kind)
    val totals = LinkedHashMap<String, Long>()
    for (t in txns) {
        if (t.type != kind) continue
        val key = when (by) {
            BreakdownBy.PAYMENT -> t.paymentMethod.ifBlank { "Unspecified" }
            BreakdownBy.ACCOUNT -> accountNames[t.accountId] ?: "Account ${t.accountId}"
            BreakdownBy.TAG -> "Untagged"   // not reached: TAG returns above
        }
        totals.merge(key, t.amountPaise) { a, b -> a + b }
    }
    return topSlices(totals, maxSlices)
}

/** Name of the slice for expenses that have no tag. */
const val UNTAGGED_LABEL = "Untagged"

/**
 * Expenses added up per tag. A transaction with several tags is shared equally between them (the odd
 * paise go to the first), so the slices always add up to the real total; one without tags is "Untagged".
 */
fun tagBreakdown(
    txns: List<TransactionEntity>,
    tagsOf: Map<Int, List<TagEntity>>,
    maxSlices: Int = MAX_SLICES,
    kind: String = "EXPENSE"
): List<Slice> {
    val totals = LinkedHashMap<String, Long>()
    val colorOf = HashMap<String, Int>()
    for (t in txns) {
        if (t.type != kind) continue
        val tags = tagsOf[t.id].orEmpty()
        if (tags.isEmpty()) {
            totals.merge(UNTAGGED_LABEL, t.amountPaise) { a, b -> a + b }
            continue
        }
        val share = t.amountPaise / tags.size
        val rest = t.amountPaise - share * tags.size
        tags.forEachIndexed { i, tag ->
            colorOf.putIfAbsent(tag.name, tag.colorIndex)
            totals.merge(tag.name, share + if (i == 0) rest else 0L) { a, b -> a + b }
        }
    }
    return topSlices(totals, maxSlices).map { if (it.isOther) it else it.copy(colorIndex = colorOf[it.label]) }
}

/**
 * The titles you spent the most on, biggest first. "Coffee" and " coffee " count as the same title;
 * a blank title shows as "No title".
 */
fun topSpendTitles(txns: List<TransactionEntity>, limit: Int = 8, kind: String = "EXPENSE"): List<Slice> {
    val totals = LinkedHashMap<String, Long>()
    val shownAs = HashMap<String, String>()
    for (t in txns) {
        if (t.type != kind) continue
        val shown = t.title.trim().ifEmpty { "No title" }
        val key = shown.lowercase()
        shownAs.putIfAbsent(key, shown)
        totals.merge(key, t.amountPaise) { a, b -> a + b }
    }
    return totals.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { Slice(shownAs.getValue(it.key), it.value) }
}

/** Total expense for each day of the week, Monday first (index 0) to Sunday (index 6). */
fun spendByWeekday(txns: List<TransactionEntity>, kind: String = "EXPENSE"): List<Long> {
    val totals = LongArray(7)
    val cal = Calendar.getInstance()
    for (t in txns) {
        if (t.type != kind) continue
        cal.timeInMillis = t.dateTime
        // Calendar: Sunday = 1, Monday = 2 ... Saturday = 7  ->  Monday = 0 ... Sunday = 6
        totals[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] += t.amountPaise
    }
    return totals.toList()
}

/** Index (Monday = 0) of the day with the most spending, or null when nothing was spent. A tie goes to the earlier day. */
fun busiestWeekday(totals: List<Long>): Int? {
    var best = -1
    var bestValue = 0L
    for (i in totals.indices) {
        if (totals[i] > bestValue) {
            best = i
            bestValue = totals[i]
        }
    }
    return if (best >= 0) best else null
}
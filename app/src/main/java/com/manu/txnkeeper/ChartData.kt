package com.manu.txnkeeper

import com.manu.txnkeeper.data.local.TransactionEntity
import kotlin.math.abs

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
        val x = if (rawX > lastX + MIN_X_GAP) rawX else lastX + MIN_X_GAP
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

/** A balance at a moment (for "peak" and "lowest"). */
data class BalanceMark(val paise: Long, val millis: Long)

/** The numbers shown in the strip under the chart. */
data class ChartInsights(
    val biggestIncome: ChartPoint?,
    val biggestExpense: ChartPoint?,
    /** Total expense divided by the days the transactions span (at least one day). */
    val avgDailyExpensePaise: Long,
    val highest: BalanceMark?,
    val lowest: BalanceMark?
)

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
        lowest = lo?.let { BalanceMark(it.balancePaise, it.txn.dateTime) }
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

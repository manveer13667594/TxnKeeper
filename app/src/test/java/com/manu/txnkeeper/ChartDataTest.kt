package com.manu.txnkeeper

import com.manu.txnkeeper.data.local.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDataTest {

    private fun txn(id: Int, type: String, rupees: Long, minute: Long) = TransactionEntity(
        id = id, title = "t$id", amountPaise = rupees * 100, type = type,
        dateTime = 1_700_000_000_000L + minute * 60_000L
    )

    @Test
    fun runningBalanceRisesAndFalls() {
        val s = buildChartSeries(
            listOf(txn(1, "INCOME", 1000, 0), txn(2, "EXPENSE", 300, 10), txn(3, "EXPENSE", 900, 20)),
            startPaise = 0L
        )
        assertEquals(listOf(100_000L, 70_000L, -20_000L), s.points.map { it.balancePaise })
        assertEquals(100_000L, s.incomePaise)
        assertEquals(120_000L, s.expensePaise)
        assertEquals(-20_000L, s.endPaise)
    }

    @Test
    fun startingBalanceIsIncluded() {
        val s = buildChartSeries(listOf(txn(1, "EXPENSE", 100, 0)), startPaise = 50_000L)
        assertEquals(40_000L, s.points.single().balancePaise)
    }

    @Test
    fun everyTransactionIsItsOwnPoint_evenOnTheSameMinute() {
        val s = buildChartSeries(
            listOf(txn(1, "INCOME", 10, 5), txn(2, "INCOME", 10, 5), txn(3, "EXPENSE", 5, 5)),
            startPaise = 0L
        )
        assertEquals(3, s.points.size)
        val xs = s.points.map { it.x }
        assertTrue(xs.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun unsortedInputIsOrderedByTime() {
        val s = buildChartSeries(listOf(txn(2, "EXPENSE", 100, 30), txn(1, "INCOME", 500, 0)), 0L)
        assertEquals(listOf(1, 2), s.points.map { it.txn.id })
        assertEquals(0f, s.points.first().x, 0f)
        assertEquals(30f, s.points.last().x, 0.2f)
    }

    @Test
    fun emptyInput() {
        val s = buildChartSeries(emptyList(), 1_000L)
        assertTrue(s.isEmpty)
        assertEquals(1_000L, s.endPaise)
    }

    @Test
    fun compactMoneyUsesIndianUnits() {
        assertEquals("₹950", compactMoney(95_000))
        assertEquals("₹1.5K", compactMoney(150_000))
        assertEquals("₹50K", compactMoney(5_000_000))
        assertEquals("₹1.5L", compactMoney(15_000_000))
        assertEquals("₹2Cr", compactMoney(2_000_000_000))
        assertEquals("−₹300", compactMoney(-30_000))
    }

    @Test
    fun sparklineStartsAtStartingBalanceAndKeepsLast() {
        val s = buildChartSeries(listOf(txn(1, "INCOME", 100, 0), txn(2, "EXPENSE", 40, 10)), 5_000L)
        assertEquals(listOf(5_000L, 15_000L, 11_000L), sparklineValues(s, 5_000L))
    }

    @Test
    fun sparklineIsThinnedButKeepsEnds() {
        val txns = (1..500).map { txn(it, "INCOME", 1, it.toLong()) }
        val s = buildChartSeries(txns, 0L)
        val v = sparklineValues(s, 0L, maxPoints = 60)
        assertEquals(60, v.size)
        assertEquals(0L, v.first())
        assertEquals(s.endPaise, v.last())
    }

    @Test
    fun insights() {
        val s = buildChartSeries(
            listOf(txn(1, "INCOME", 1000, 0), txn(2, "EXPENSE", 300, 60), txn(3, "EXPENSE", 100, 24 * 60 * 2)),
            startPaise = 0L
        )
        val i = computeInsights(s)
        assertEquals(1, i.biggestIncome!!.txn.id)
        assertEquals(2, i.biggestExpense!!.txn.id)
        // 400 rupees of expense over 2 days = 200 per day
        assertEquals(20_000L, i.avgDailyExpensePaise)
        assertEquals(100_000L, i.highest!!.paise)
        assertEquals(60_000L, i.lowest!!.paise)
    }

    @Test
    fun insightsOfNothing() {
        val i = computeInsights(buildChartSeries(emptyList(), 0L))
        assertEquals(0L, i.avgDailyExpensePaise)
        assertTrue(i.biggestIncome == null && i.highest == null)
    }

    @Test
    fun defaultRangePicksSmallestWindowWithEnoughPoints() {
        // 30 transactions in the last 3 days, then 30 older ones spread over months
        val recent = (1..30).map { txn(1000 + it, "INCOME", 1, 60L * 24 * 100 + it * 60L) }
        val old = (1..30).map { txn(it, "INCOME", 1, it * 60L * 24 * 3) }
        val s = buildChartSeries(old + recent, 0L)
        assertEquals(7, defaultRangeDays(s))
        assertEquals(0, defaultRangeDays(buildChartSeries(recent.take(5), 0L)))
    }
}

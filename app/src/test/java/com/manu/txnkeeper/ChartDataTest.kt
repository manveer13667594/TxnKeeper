package com.manu.txnkeeper

import com.manu.txnkeeper.data.local.TransactionEntity
import java.util.Calendar
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
    fun pointsStayStrictlyOrderedOnALongHistory() {
        // One transaction, then three in the very same minute about 5.7 years later. At that distance
        // two Floats are 0.25 apart, so a fixed gap of 0.1 would collapse them onto the same x.
        val s = buildChartSeries(
            listOf(txn(1, "EXPENSE", 1, 0), txn(2, "EXPENSE", 1, 3_000_000), txn(3, "EXPENSE", 1, 3_000_000), txn(4, "EXPENSE", 1, 3_000_000)),
            startPaise = 0L
        )
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
        // 1000 earned, 400 spent: 60% kept
        assertEquals(60, i.savingsRatePercent)
        assertEquals(3, i.txnCount)
    }

    @Test
    fun insightsOfNothing() {
        val i = computeInsights(buildChartSeries(emptyList(), 0L))
        assertEquals(0L, i.avgDailyExpensePaise)
        assertTrue(i.biggestIncome == null && i.highest == null)
        assertTrue(i.savingsRatePercent == null)
        assertEquals(0, i.txnCount)
    }

    @Test
    fun savingsRate() {
        assertEquals(75, savingsRatePercent(100_000L, 25_000L))
        assertEquals(0, savingsRatePercent(100_000L, 100_000L))
        // Spent more than earned: negative
        assertEquals(-50, savingsRatePercent(10_000L, 15_000L))
        // No income to compare with
        assertTrue(savingsRatePercent(0L, 5_000L) == null)
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

    // ------------------------------------------------------------ more chart types

    /** A moment on a given day in the phone's own time zone, like the real data. Months: Calendar.NOVEMBER ... */
    private fun day(year: Int, month: Int, dayOfMonth: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(year, month, dayOfMonth, hour, 0, 0) }.timeInMillis

    private fun e(
        id: Int, type: String, rupees: Long, time: Long,
        title: String = "t$id", method: String = "CASH", account: Long = 1L
    ) = TransactionEntity(
        id = id, title = title, amountPaise = rupees * 100, type = type, dateTime = time,
        paymentMethod = method, accountId = account
    )

    @Test
    fun withinDaysCountsBackFromTheNewestTransaction() {
        val list = listOf(
            e(1, "EXPENSE", 1, day(2023, Calendar.NOVEMBER, 1)),
            e(2, "EXPENSE", 1, day(2023, Calendar.NOVEMBER, 20)),
            e(3, "EXPENSE", 1, day(2023, Calendar.NOVEMBER, 30))
        )
        assertEquals(listOf(2, 3), withinDays(list, 10).map { it.id })
        assertEquals(3, withinDays(list, 0).size)
        assertTrue(withinDays(emptyList(), 7).isEmpty())
    }

    @Test
    fun barWidthFollowsTheTimeSpan() {
        fun span(days: Int) = listOf(
            e(1, "INCOME", 1, day(2023, Calendar.JANUARY, 1)),
            e(2, "INCOME", 1, day(2023, Calendar.JANUARY, 1) + days * 86_400_000L)
        )
        assertEquals(Bucket.DAY, bucketFor(span(7)))
        assertEquals(Bucket.WEEK, bucketFor(span(30)))
        assertEquals(Bucket.MONTH, bucketFor(span(200)))
    }

    @Test
    fun dayBarsKeepQuietDaysAsZeros() {
        val p = incomeExpenseByPeriod(
            listOf(
                e(1, "INCOME", 100, day(2023, Calendar.NOVEMBER, 13, 9)),
                e(2, "EXPENSE", 40, day(2023, Calendar.NOVEMBER, 15, 18)),
                e(3, "EXPENSE", 10, day(2023, Calendar.NOVEMBER, 15, 8))
            ),
            Bucket.DAY
        )
        assertEquals(listOf(10_000L, 0L, 0L), p.map { it.incomePaise })
        assertEquals(listOf(0L, 0L, 5_000L), p.map { it.expensePaise })
        assertEquals(day(2023, Calendar.NOVEMBER, 13, 0), p.first().startMillis)
    }

    @Test
    fun weeksStartOnMonday() {
        // Sunday 12 Nov belongs to the week of Monday 6 Nov; Monday 13 Nov starts a new week
        val p = incomeExpenseByPeriod(
            listOf(
                e(1, "EXPENSE", 5, day(2023, Calendar.NOVEMBER, 12)),
                e(2, "EXPENSE", 7, day(2023, Calendar.NOVEMBER, 13))
            ),
            Bucket.WEEK
        )
        assertEquals(
            listOf(day(2023, Calendar.NOVEMBER, 6, 0), day(2023, Calendar.NOVEMBER, 13, 0)),
            p.map { it.startMillis }
        )
        assertEquals(listOf(500L, 700L), p.map { it.expensePaise })
    }

    @Test
    fun monthBarsCrossTheYearEnd() {
        val p = incomeExpenseByPeriod(
            listOf(
                e(1, "INCOME", 1, day(2023, Calendar.DECEMBER, 31)),
                e(2, "INCOME", 1, day(2024, Calendar.JANUARY, 1))
            ),
            Bucket.MONTH
        )
        assertEquals(2, p.size)
        assertEquals(day(2024, Calendar.JANUARY, 1, 0), p.last().startMillis)
    }

    @Test
    fun periodsOfNothing() {
        assertTrue(incomeExpenseByPeriod(emptyList(), Bucket.WEEK).isEmpty())
    }

    @Test
    fun breakdownIgnoresIncomeAndFoldsTheTailIntoOther() {
        val t = day(2023, Calendar.NOVEMBER, 13)
        val list = listOf(
            e(1, "EXPENSE", 500, t, method = "UPI"),
            e(2, "EXPENSE", 300, t, method = "CASH"),
            e(3, "EXPENSE", 100, t, method = "CARD"),
            e(4, "EXPENSE", 50, t, method = "WALLET"),
            e(5, "INCOME", 9_999, t, method = "UPI")
        )
        val s = spendBreakdown(list, BreakdownBy.PAYMENT, emptyMap(), maxSlices = 3)
        assertEquals(listOf("UPI", "CASH", "Other"), s.map { it.label })
        assertEquals(listOf(50_000L, 30_000L, 15_000L), s.map { it.paise })
        assertTrue(s.last().isOther && !s.first().isOther)
        // Fewer slices than the limit: nothing is folded
        assertEquals(4, spendBreakdown(list, BreakdownBy.PAYMENT, emptyMap()).size)
    }

    @Test
    fun breakdownByAccountUsesTheNames() {
        val t = day(2023, Calendar.NOVEMBER, 13)
        val list = listOf(
            e(1, "EXPENSE", 200, t, account = 1),
            e(2, "EXPENSE", 100, t, account = 2),
            e(3, "EXPENSE", 50, t, account = 9)
        )
        val s = spendBreakdown(list, BreakdownBy.ACCOUNT, mapOf(1L to "Bank", 2L to "Wallet"))
        assertEquals(listOf("Bank", "Wallet", "Account 9"), s.map { it.label })
    }

    @Test
    fun topTitlesAddUpTheSameTitle() {
        val t = day(2023, Calendar.NOVEMBER, 13)
        val list = listOf(
            e(1, "EXPENSE", 100, t, title = "Coffee"),
            e(2, "EXPENSE", 50, t, title = " coffee "),
            e(3, "EXPENSE", 120, t, title = "Rent"),
            e(4, "EXPENSE", 10, t, title = ""),
            e(5, "INCOME", 999, t, title = "Salary")
        )
        val top = topSpendTitles(list, limit = 2)
        assertEquals(listOf("Coffee", "Rent"), top.map { it.label })
        assertEquals(listOf(15_000L, 12_000L), top.map { it.paise })
        assertEquals("No title", topSpendTitles(list).last().label)
    }

    @Test
    fun weekdayTotalsRunMondayToSunday() {
        val list = listOf(
            e(1, "EXPENSE", 10, day(2023, Calendar.NOVEMBER, 13)),   // Monday
            e(2, "EXPENSE", 7, day(2023, Calendar.NOVEMBER, 12)),    // Sunday
            e(3, "EXPENSE", 5, day(2023, Calendar.NOVEMBER, 20)),    // Monday
            e(4, "INCOME", 99, day(2023, Calendar.NOVEMBER, 13))
        )
        val w = spendByWeekday(list)
        assertEquals(7, w.size)
        assertEquals(1_500L, w[0])
        assertEquals(700L, w[6])
        assertEquals(0L, w[3])
    }

    @Test
    fun busiestWeekdayIsTheBiggestTotal() {
        assertEquals(2, busiestWeekday(listOf(0L, 500L, 900L, 300L, 0L, 0L, 100L)))
        // A tie goes to the earlier day
        assertEquals(1, busiestWeekday(listOf(0L, 900L, 900L, 0L, 0L, 0L, 0L)))
        // Nothing spent: no busiest day
        assertTrue(busiestWeekday(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L)) == null)
        assertTrue(busiestWeekday(emptyList()) == null)
    }
}
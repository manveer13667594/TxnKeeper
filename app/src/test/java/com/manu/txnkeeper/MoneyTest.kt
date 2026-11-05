package com.manu.txnkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the paise helpers (Money.kt) and how paise are shown (Txnformat.kt). */
class MoneyTest {

    // ---------------------------------------------------------------- text -> paise

    @Test fun parsePaise_plainNumbers() {
        assertEquals(94500L, parsePaise("945"))
        assertEquals(1250L, parsePaise("12.5"))
        assertEquals(1250L, parsePaise("12.50"))
        assertEquals(7L, parsePaise("0.07"))
        assertEquals(125000L, parsePaise("  1250 "))      // spaces around are ignored
        assertEquals(-50025L, parsePaise("-500.25"))       // opening balances can be negative
        assertEquals(0L, parsePaise("0"))
    }

    @Test fun parsePaise_moreThanTwoDecimals_roundsHalfUp() {
        assertEquals(1235L, parsePaise("12.345"))
        assertEquals(1234L, parsePaise("12.344"))
        assertEquals(1001L, parsePaise("10.005"))          // exact decimal, no double rounding error
    }

    @Test fun parsePaise_rejectsWhatIsNotAClearNumber() {
        assertNull(parsePaise(""))
        assertNull(parsePaise("   "))
        assertNull(parsePaise("abc"))
        assertNull(parsePaise("NaN"))
        assertNull(parsePaise("Infinity"))
        assertNull(parsePaise("1,250"))                    // callers remove commas first
        assertNull(parsePaise("12,50"))
    }

    @Test fun parsePaise_rejectsAbsurdlyLargeValues_withoutHanging() {
        assertNull(parsePaise("99999999999999999999"))
        assertNull(parsePaise("1e999999999"))              // must return at once, not try to build 10^999999999
        assertNull(parsePaise("-1e999999999"))
        assertEquals(MAX_PAISE, parsePaise("10000000000000"))   // Rs 10 trillion is the limit
        assertNull(parsePaise("10000000000000.01"))
    }

    @Test fun parsePaise_rejectsTinyExponents_withoutHanging() {
        // Before the guard, "1e-99999999" made the rounding step run for minutes and froze the app
        assertNull(parsePaise("1e-99999999"))
        assertNull(parsePaise("1e-31"))
        assertNull(parsePaise("0e-99999999"))
        assertEquals(0L, parsePaise("0.000000000001"))      // a normal tiny number still rounds to 0
    }

    // ---------------------------------------------------------------- Double -> paise

    @Test fun doubleToPaise_roundsToTheNearestPaisa() {
        assertEquals(1235L, 12.35.toPaise())
        assertEquals(30L, (0.1 + 0.2).toPaise())           // 0.30000000000000004
        assertEquals(125050L, 1250.5.toPaise())
        assertEquals(0L, 0.0.toPaise())
    }

    @Test fun doubleToPaise_clampsHugeAndIgnoresNaN() {
        assertEquals(MAX_PAISE, 1e30.toPaise())
        assertEquals(-MAX_PAISE, (-1e30).toPaise())
        assertEquals(0L, Double.NaN.toPaise())
        assertEquals(MAX_PAISE, Double.POSITIVE_INFINITY.toPaise())
    }

    // ---------------------------------------------------------------- paise -> text

    @Test fun formatAmountPaise_isTheTextForAnEditBox() {
        assertEquals("945.5", formatAmountPaise(94550))
        assertEquals("1", formatAmountPaise(100))
        assertEquals("0", formatAmountPaise(0))
        assertEquals("0.05", formatAmountPaise(5))
        assertEquals("10000000", formatAmountPaise(1_000_000_000))   // never "1E+7"
        assertEquals("-500.25", formatAmountPaise(-50025))
    }

    @Test fun formatAmountPaise_thenParsePaise_isALosslessRoundTrip() {
        for (p in listOf(0L, 1L, 5L, 99L, 100L, 101L, 1235L, 125050L, 9_999_999_999L, -50025L)) {
            assertEquals(p, parsePaise(formatAmountPaise(p)))
        }
    }

    @Test fun formatMoney_showsPaiseOnlyWhenPresent() {
        assertEquals("₹250", formatMoney(25000))
        assertEquals("₹250.50", formatMoney(25050))
        assertEquals("₹0.05", formatMoney(5))
        assertEquals("₹0", formatMoney(0))
    }

    @Test fun formatBalance_andSignedMoney_useAMinusSign() {
        assertEquals("−₹300", formatBalance(-30000))
        assertEquals("₹300", formatBalance(30000))
        assertEquals("+₹300", formatSignedMoney(30000))
        assertEquals("−₹300", formatSignedMoney(-30000))
        assertEquals("₹0", formatSignedMoney(0))
        assertEquals("−₹0.05", formatSignedMoney(-5))
    }

    // ---------------------------------------------------------------- why paise

    @Test fun addingManyAmounts_neverDrifts() {
        // Ten payments of Rs 0.10 must be exactly Rs 1.00 (as Doubles it is 0.9999999999999999)
        val paise = (1..10).sumOf { parsePaise("0.10")!! }
        assertEquals(100L, paise)
        assertEquals("₹1", formatMoney(paise))
    }
}

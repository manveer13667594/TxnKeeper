package com.manu.txnkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptNamesTest {

    @Test fun newNames_areSafe_uniqueAndReadable() {
        val a = newReceiptName(1_700_000_000_000L)
        val b = newReceiptName(1_700_000_000_000L)
        assertTrue(a, Regex("r_\\d{8}-\\d{6}_[0-9a-f]{8}\\.jpg").matches(a))
        assertTrue(isSafeReceiptName(a))
        assertNotEquals(a, b)
    }

    @Test fun anythingThatCouldLeaveTheReceiptsFolder_isRefused() {
        val bad = listOf(
            null, "", ".jpg", "../x.jpg", "..\\x.jpg", "a/b.jpg", "a\\b.jpg", "/etc/passwd",
            "x..jpg", "x.png", "x.jpg.exe", "has space.jpg", "tab\t.jpg", "x.jpg\n", "é.jpg",
            "a".repeat(81) + ".jpg"
        )
        for (name in bad) assertFalse("should be refused: $name", isSafeReceiptName(name))
    }

    @Test fun ordinaryNames_areAccepted() {
        assertTrue(isSafeReceiptName("r_20261115-100530_a1b2c3d4.jpg"))
        assertTrue(isSafeReceiptName("receipt-1.jpg"))
        assertTrue(isSafeReceiptName("A.jpg"))
        // up to 80 characters before the ".jpg"
        assertTrue(isSafeReceiptName("a".repeat(80) + ".jpg"))
    }

    @Test fun sampleSize_shrinksInWholeStepsButNeverBelowTheTarget() {
        assertEquals(1, sampleSizeFor(1600, 900, 1600))
        assertEquals(1, sampleSizeFor(900, 1600, 1600))
        assertEquals(1, sampleSizeFor(100, 100, 160))
        assertEquals(2, sampleSizeFor(3200, 1800, 1600))
        assertEquals(2, sampleSizeFor(4000, 3000, 1600))   // 4000 / 4 = 1000 would be too small
        assertEquals(4, sampleSizeFor(6400, 4800, 1600))
        assertEquals(8, sampleSizeFor(1600, 900, 160))     // a list thumbnail
    }
}

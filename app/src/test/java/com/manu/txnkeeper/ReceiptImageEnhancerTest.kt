package com.manu.txnkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptImageEnhancerTest {
    private fun grey(argb: IntArray, w: Int, x: Int, y: Int) = argb[y * w + x] and 0xFF
    private fun pixel(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** A page with a shadow (dark on the left, light on the right) and two lines of faded ink. */
    private fun shadowedPage(w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val paper = 120 + x * 120 / (w - 1)
            val ink = (y in 40..43 || y in 70..73) && x in 20..220
            px[y * w + x] = pixel(if (ink) paper * 55 / 100 else paper)
        }
        return px
    }

    @Test fun shadow_isRemoved_andInkStaysDark() {
        val w = 240; val h = 120
        val out = ReceiptImageEnhancer.enhance(shadowedPage(w, h), w, h)
        // paper is white on the dark side and on the light side alike
        assertTrue(grey(out, w, 5, 5) >= 235)
        assertTrue(grey(out, w, 235, 5) >= 235)
        // ink is dark on both sides
        assertTrue(grey(out, w, 40, 41) <= 110)
        assertTrue(grey(out, w, 200, 71) <= 110)
    }

    @Test fun evenPage_staysWhite_withoutErrors() {
        val w = 50; val h = 40
        val out = ReceiptImageEnhancer.enhance(IntArray(w * h) { pixel(200) }, w, h)
        assertTrue(out.all { it == pixel(255) })
    }

    @Test fun inputIsNotChanged_andOutputIsOpaqueGrey() {
        val w = 240; val h = 120
        val input = shadowedPage(w, h)
        val copy = input.copyOf()
        val out = ReceiptImageEnhancer.enhance(input, w, h)
        assertTrue(input.contentEquals(copy))
        assertEquals(w * h, out.size)
        assertTrue(out.all { (it ushr 24) == 0xFF && ((it shr 16) and 0xFF) == (it and 0xFF) && ((it shr 8) and 0xFF) == (it and 0xFF) })
    }

    @Test(expected = IllegalArgumentException::class) fun tooFewPixels_isRejected() {
        ReceiptImageEnhancer.enhance(IntArray(10), 20, 20)
    }
}

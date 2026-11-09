package com.manu.txnkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReceiptRowsTest {
    private fun at(text: String, top: Int, left: Int, h: Int = 24) = OcrLine(text, h, top, top + h, left)

    @Test fun labelsAndFigures_readInSeparateColumns_areJoinedByRow() {
        val ocr = listOf(
            at("Sub Total", 200, 10), at("TOTAL", 240, 10),
            at("640.00", 200, 400), at("672.00", 240, 400)
        )
        assertEquals(listOf("Sub Total 640.00", "TOTAL 672.00"), ReceiptRows.merge(ocr).map { it.text })
    }

    @Test fun inARow_textIsOrderedLeftToRight() {
        val ocr = listOf(at("672.00", 240, 400), at("TOTAL", 241, 10))
        assertEquals(listOf("TOTAL 672.00"), ReceiptRows.merge(ocr).map { it.text })
    }

    @Test fun joinedRow_keepsTheTallestHeight() {
        val ocr = listOf(at("TOTAL", 240, 10, h = 20), at("672.00", 238, 400, h = 34))
        assertEquals(34, ReceiptRows.merge(ocr).single().height)
    }

    @Test fun nothingSharingARow_givesBackTheSameList() {
        val ocr = listOf(at("SHOP", 10, 10), at("Bill No 5", 60, 10), at("TOTAL 90.00", 110, 10))
        assertSame(ocr, ReceiptRows.merge(ocr))
    }

    @Test fun unknownPositions_giveBackTheSameList() {
        val ocr = listOf(OcrLine("TOTAL", 30), OcrLine("672.00", 30))
        assertSame(ocr, ReceiptRows.merge(ocr))
    }

    @Test fun singleLine_isLeftAlone() {
        val ocr = listOf(at("TOTAL 672.00", 10, 10))
        assertSame(ocr, ReceiptRows.merge(ocr))
    }

    @Test fun linesJustTouching_arePartOfDifferentRows() {
        // 24 px lines that overlap by only 4 px are two rows, not one
        val ocr = listOf(at("Milk", 100, 10), at("Bread", 120, 10))
        assertSame(ocr, ReceiptRows.merge(ocr))
    }
}

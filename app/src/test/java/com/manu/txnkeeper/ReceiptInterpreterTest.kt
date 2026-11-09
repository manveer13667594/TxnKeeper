package com.manu.txnkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReceiptInterpreterTest {
    private fun at(text: String, top: Int, left: Int, h: Int = 24) = OcrLine(text, h, top, top + h, left)

    // OCR read the bill column by column: all labels first, then all figures
    private val columnOrder = listOf(
        at("SPICE GARDEN RESTAURANT", 10, 10, h = 44),
        at("Bill No: 1182", 70, 10),
        at("Date: 03/10/2026", 100, 10),
        at("Sub Total", 200, 10), at("CGST 2.5%", 230, 10), at("SGST 2.5%", 260, 10), at("TOTAL", 290, 10),
        at("640.00", 200, 400), at("16.00", 230, 400), at("16.00", 260, 400), at("672.00", 290, 400)
    )

    @Test fun columnOrderedOcr_getsTheTotalNextToItsLabel() {
        val first = ReceiptInterpreter.interpretLines(columnOrder)
        val done = ReceiptInterpreter.fillFromRows(first, columnOrder)
        assertEquals(672.0, done.amount!!, 0.001)
        assertEquals("Spice Garden Restaurant", done.title)
    }

    @Test fun fillGaps_neverReplacesAValueThatWasFound() {
        val base = ParsedTransaction(title = "Rahul", amount = 10.0)
        val extra = ParsedTransaction(title = "Other", amount = 20.0, type = "EXPENSE", paymentMethod = "UPI")
        val out = ReceiptInterpreter.fillGaps(base, extra)
        assertEquals("Rahul", out.title)
        assertEquals(10.0, out.amount!!, 0.0)
        assertEquals("EXPENSE", out.type)
        assertEquals("UPI", out.paymentMethod)
        assertNull(out.dateMillis)
    }

    @Test fun noPositions_meansTheFirstReadingIsReturnedUntouched() {
        val ocr = listOf(OcrLine("To RAHUL SHARMA", 40), OcrLine("₹45", 100), OcrLine("Completed", 30))
        val first = ReceiptInterpreter.interpretLines(ocr)
        assertSame(first, ReceiptInterpreter.fillFromRows(first, ocr))
    }

    @Test fun goodFirstReading_isNotChangedByRowsThatAgree() {
        val ocr = listOf(
            at("To RAHUL SHARMA", 10, 10, h = 40), at("₹45", 80, 10, h = 100),
            at("Completed", 200, 10, h = 30), at("26/09/2026", 240, 10)
        )
        val first = ReceiptInterpreter.interpretLines(ocr)
        assertEquals(first, ReceiptInterpreter.fillFromRows(first, ocr))
        assertNotNull(first.amount)
    }

    @Test fun amountInWords_stillWinsWhenFiguresGiveNothing() {
        val ocr = listOf(OcrLine("Order Successful", 30), OcrLine("Rupees Four Hundred Only", 24))
        assertEquals(400.0, ReceiptInterpreter.interpretLines(ocr).amount!!, 0.0)
    }
}

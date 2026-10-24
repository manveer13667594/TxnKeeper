package com.example.txnkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Put in app/src/test/java/com/example/txnkeeper/ . Run: right-click > Run 'OcrCorrectorTest'. */
class OcrCorrectorTest {
    private fun lines(vararg t: String) = t.map { OcrLine(it, 30) }
    private fun fixed(vararg t: String) = OcrCorrector.correct(lines(*t)).lines.map { it.text }

    @Test fun paytm_rupeeSignReadAsSeven_isFixedUsingWords() {
        val out = fixed("Paytm", "Order Successful", "7902", "Rupees Nine Hundred Two Only")
        assertEquals("902", out[2])
    }

    @Test fun paytm_largeAmount_withComma() {
        assertEquals("1051", fixed("Paid Successfully", "1,051", "Rupees One Thousand Fifty One Only")[1].replace(",", ""))
    }

    @Test fun paytm_splitOnlyLine_isJoined() {
        val out = fixed("974", "Rupees Nine Hundred Seventy Four", "Only", "To: Indian Oil")
        assertEquals("Rupees Nine Hundred Seventy Four Only", out[1])
        assertEquals(3, out.size)
    }

    @Test fun gpay_paymentOf_withExtraDigit_isFixed() {
        val out = fixed("To IOCL INDANE CREDIT CARD", "945", "Completed", "Payment of 2945 completed")
        assertEquals("Payment of 945 completed", out[3])
    }

    @Test fun gpay_singleDigit_paymentOf() {
        assertEquals("Payment of 9 completed", fixed("9", "Payment of 29 completed")[1])
    }

    @Test fun gpay_agreeingAmounts_areLeftAlone() {
        val input = listOf("2", "Payment of 2 completed")
        assertEquals(input, fixed(*input.toTypedArray()))
    }

    @Test fun reference_withSpacesAndLetters_isRepaired() {
        assertEquals("UPI Ref No: 842697973883", fixed("UPI Ref No: 8426 9797 3883")[0])
        assertEquals("606097245513", fixed("6O6O97245513")[0])      // letter O read instead of zero
        assertEquals("606097245513", fixed("6060 9724 5513")[0])    // spaces inside a standalone reference
    }

    @Test fun keywordTypo_isFixed_butNamesAreNotTouched() {
        assertEquals("Completed", fixed("Compieted")[0])
        assertEquals("Kuldip Kaur", fixed("Kuldip Kaur")[0])
        assertEquals("Hostinger Pte Ltd", fixed("Hostinger Pte Ltd")[0])
    }

    @Test fun cleanReceipt_producesNoChanges() {
        val r = OcrCorrector.correct(lines("Paytm", "Paid Successfully", "400", "Rupees Four Hundred Only", "UPI Ref No: 314491869994"))
        assertTrue(r.changes.isEmpty())
    }

    @Test fun unreadableWords_areNeverTrusted() {
        // garbled words must not rewrite the amount
        assertEquals("7902", fixed("7902", "Rupees Nlne Hundrad Two Only")[0])
    }

    @Test fun emptyInput_doesNotCrash() {
        assertTrue(OcrCorrector.correct(emptyList()).lines.isEmpty())
    }

    @Test fun lineSizes_arePreserved() {
        val r = OcrCorrector.correct(listOf(OcrLine("7902", 120), OcrLine("Rupees Nine Hundred Two Only", 30)))
        assertEquals(120, r.lines[0].height)
    }
}
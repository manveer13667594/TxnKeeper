package com.example.txnkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** Put in app/src/test/java/com/example/txnkeeper/ . Run: right-click > Run 'TxnCsvTest'. */
class TxnCsvTest {

    private fun millis(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(text)!!.time

    private fun row(
        title: String = "Chai",
        amount: Double = 20.0,
        type: String = "EXPENSE",
        description: String = "",
        account: String = "Main account"
    ) = TxnRow(millis("2026-11-02 12:15:00"), title, amount, type, "UPI", account, description)

    @Test fun roundTrip_keepsEveryField_includingCommasQuotesAndNewlines() {
        val rows = listOf(
            row(),
            row(title = "Dal, rice \"thali\"", amount = 1250.5, type = "INCOME", description = "line one\nline two, with comma"),
            row(title = "₹ symbol ok", account = "HDFC; savings")
        )
        val back = TxnCsv.read(TxnCsv.write(rows))
        assertNull(back.fileError)
        assertTrue(back.errors.isEmpty())
        assertEquals(rows, back.rows)
    }

    @Test fun text_startingLikeAFormula_isGuardedInTheFileAndRestoredOnImport() {
        val csv = TxnCsv.write(listOf(row(title = "=SUM(A1)", description = "-5 refund")))
        assertTrue(csv.contains("'=SUM(A1)"))
        val back = TxnCsv.read(csv).rows.single()
        assertEquals("=SUM(A1)", back.title)
        assertEquals("-5 refund", back.description)
    }

    @Test fun read_acceptsIndianDates_semicolons_andReorderedColumns() {
        val csv = "Amount;Type;Title;Date\n1,25,000;credit;Salary;02/11/2026 09:30\n"
        val r = TxnCsv.read(csv).rows.single()
        assertEquals(125000.0, r.amount, 0.0)
        assertEquals("INCOME", r.type)
        assertEquals(millis("2026-11-02 09:30:00"), r.dateTime)
        assertEquals("CASH", r.paymentMethod)   // column missing -> CASH
    }

    @Test fun read_reportsBadRows_andKeepsGoodOnes() {
        val csv = "Date,Title,Amount,Type\n" +
                "2026-11-02,Good,10,EXPENSE\n" +
                "not a date,Bad date,10,EXPENSE\n" +
                "2026-11-02,Bad amount,abc,EXPENSE\n" +
                "2026-11-02,Bad type,10,maybe\n" +
                "2026-11-02,,10,EXPENSE\n"
        val result = TxnCsv.read(csv)
        assertEquals(listOf("Good"), result.rows.map { it.title })
        assertEquals(listOf(3, 4, 5, 6), result.errors.map { it.row })
    }

    @Test fun read_missingRequiredColumn_isAFileError() {
        val result = TxnCsv.read("Date,Title,Amount\n2026-11-02,Chai,20\n")
        assertNotNull(result.fileError)
        assertTrue(result.rows.isEmpty())
    }

    @Test fun read_emptyFile_isAFileError() {
        assertNotNull(TxnCsv.read("").fileError)
        assertNotNull(TxnCsv.read("\n\n").fileError)
    }

    @Test fun amount_decimalCommaIsNotMistakenForThousands() {
        assertEquals(12.5, TxnCsv.parseAmount("12,50")!!, 0.0)
        assertEquals(1250.0, TxnCsv.parseAmount("1,250")!!, 0.0)
        assertEquals(1250.5, TxnCsv.parseAmount("₹1,250.50")!!, 0.0)
        assertNull(TxnCsv.parseAmount("1.250,50"))   // ambiguous: rejected instead of guessed
        assertNull(TxnCsv.parseAmount("NaN"))
    }

    @Test fun date_needsTheWholeTextToMatch_andASaneYear() {
        val parsers = listOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false })
        assertNotNull(TxnCsv.parseDate("2026-11-02", parsers))
        assertNull(TxnCsv.parseDate("2026-11-02 and more", parsers))
        assertNull(TxnCsv.parseDate("0026-11-02", parsers))
        assertNull(TxnCsv.parseDate("2026-13-45", parsers))
    }
}
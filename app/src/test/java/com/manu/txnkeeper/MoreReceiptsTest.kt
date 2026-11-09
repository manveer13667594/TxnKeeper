package com.manu.txnkeeper

import java.util.Calendar
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Receipt layouts beyond payment apps, SMS and simple shop bills: restaurant and GST bills, supermarket
 * invoices, fuel, pharmacy, hotel, cafe, ride, ticket and utility receipts, US style totals and dates.
 * They are written by hand in the field layout of the open receipt datasets (SROIE: company, date, total;
 * CORD and WildReceipt: subtotal, tax, tip, total), see tools/gen_ocr_more.py.
 * Same file format as ocr_cases.tsv. Every case must pass: these are hand-checked, not random.
 */
class MoreReceiptsTest {
    private fun lines(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).readLines()

    @Test fun moreReceiptLayouts() {
        var pass = 0; var total = 0
        val failures = ArrayList<String>()
        for (line in lines("ocr_cases_more.tsv")) {
            val c = line.split("\t"); if (c.size < 3) continue
            val ocr = c[2].split("||").map { val (h, t) = it.split("@@", limit = 2); OcrLine(t, h.toInt()) }
            val e = (c.getOrNull(3) ?: "").split("~")
            val g = ReceiptInterpreter.interpretLines(ocr)
            total++
            var ok = true
            if (e.size < 6) ok = g.amount == null          // not a transaction
            else {
                if (e[0].isNotEmpty() && g.title?.lowercase() != e[0].lowercase()) ok = false
                if (g.amount == null || Math.abs(g.amount - e[1].toDouble()) > 0.01) ok = false
                if (e[2].isNotEmpty() && g.type != e[2]) ok = false
                if ((g.paymentMethod ?: "") != e[3]) ok = false
                val day = g.dateMillis?.let {
                    val cal = Calendar.getInstance().apply { timeInMillis = it }
                    "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                } ?: ""
                if (day != e[4]) ok = false
                if (e[5].isNotEmpty() && g.description != e[5]) ok = false
            }
            if (ok) pass++ else failures.add(c[1] + ": " + ocr.joinToString(" / ") { it.text } + "  =>  " + g)
        }
        println("more receipts: $pass/$total\n" + failures.joinToString("\n"))
        assertTrue("more receipts $pass/$total", pass == total)
    }
}

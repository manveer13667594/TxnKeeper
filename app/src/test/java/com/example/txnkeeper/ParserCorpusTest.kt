package com.example.txnkeeper

import java.util.Calendar
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the parsers over the synthetic corpora in app/src/test/resources/
 * (spoken_cases.tsv, ocr_cases.tsv; made by tools/gen_spoken.py and tools/gen_ocr.py).
 * Each case is scored on every field it labels. The bars are set a little under today's results
 * so a rule change that breaks something fails here, and the failing lines are printed.
 */
class ParserCorpusTest {
    private fun lines(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).readLines()

    private fun noonDaysAgo(now: Long, d: Int) = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, -d)
    }.timeInMillis

    @Test fun spokenCorpus() {
        val now = Calendar.getInstance().apply { clear(); set(2026, 9, 29, 12, 0, 0) }.timeInMillis
        var pass = 0; var total = 0
        val failures = ArrayList<String>()
        for (line in lines("spoken_cases.tsv")) {
            val c = line.split("\t"); if (c.size < 3) continue
            val exp = if (c.getOrNull(3).isNullOrEmpty()) emptyList() else c[3].split(";;")
            val got = SpokenTransactionParser.parseAll(c[2], now)
            total++
            var ok = got.size == exp.size
            for (i in exp.indices) {
                val e = exp[i].split("~"); val g = got.getOrNull(i) ?: run { ok = false; null } ?: continue
                if (e[0].isNotEmpty() && g.title?.lowercase() != e[0].lowercase()) ok = false
                if (g.amount == null || Math.abs(g.amount - e[1].toDouble()) > 0.01) ok = false
                if (e[2].isNotEmpty() && g.type != e[2]) ok = false
                if ((g.paymentMethod ?: "") != e[3]) ok = false
                val dateOk = if (e[4].isEmpty()) g.dateMillis == null
                else g.dateMillis != null && Math.abs(g.dateMillis - noonDaysAgo(now, e[4].toInt())) < 7_200_000
                if (!dateOk) ok = false
                if (e.getOrElse(5) { "" }.isNotEmpty() && g.description?.lowercase() != e[5].lowercase()) ok = false
            }
            if (ok) pass++ else failures.add(c[2])
        }
        println("spoken corpus: $pass/$total\n" + failures.take(20).joinToString("\n"))
        assertTrue("spoken corpus $pass/$total", pass >= total * 0.97)
    }

    @Test fun ocrCorpus() {
        var pass = 0; var total = 0
        val failures = ArrayList<String>()
        for (line in lines("ocr_cases.tsv")) {
            val c = line.split("\t"); if (c.size < 3) continue
            val ocr = c[2].split("||").map { val (h, t) = it.split("@@", limit = 2); OcrLine(t, h.toInt()) }
            val e = (c.getOrNull(3) ?: "").split("~")
            val g = TransactionParser.parse(OcrCorrector.correct(ocr).lines)
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
            if (ok) pass++ else failures.add(ocr.joinToString(" / ") { it.text })
        }
        println("ocr corpus: $pass/$total\n" + failures.take(20).joinToString("\n"))
        assertTrue("ocr corpus $pass/$total", pass >= total * 0.97)
    }
}

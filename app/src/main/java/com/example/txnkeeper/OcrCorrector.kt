package com.example.txnkeeper

/** Result of cleaning OCR lines: the fixed lines (same sizes) plus a log of what changed. */
data class CorrectionResult(val lines: List<OcrLine>, val changes: List<String>)

/**
 * Fixes OCR mistakes that can be VERIFIED from the receipt itself, before TransactionParser runs.
 * It never invents names or IDs: anything it can't double-check is left exactly as read.
 *
 *  1. "Rupees Nine Hundred Two" + "Only" split over two lines  -> joined into one line
 *  2. Amount in figures vs amount in words  (7902 vs "Nine Hundred Two" -> 902)
 *  3. "Payment of 2945" vs the big standalone amount 945        -> 945
 *  4. 12-digit UPI Ref / UTR with spaces or letters (O, l, I...) -> clean digits
 *  5. Typos in fixed phrases ("Compieted" -> "Completed")
 */
object OcrCorrector {

    private val keywords = listOf(
        "Completed", "Successful", "Payment Successful", "Paid Successfully", "Order Successful",
        "Money Received", "Transaction Successful", "UPI transaction ID", "Google transaction ID",
        "UPI Ref No", "Received from", "Paid to", "Banking Name", "Transfer Details",
        "Credited to", "Debited from", "Failed", "Pending", "Transaction Failed"
    )

    private val rupeesWords = Regex("""Rupees (.+?) Only""", RegexOption.IGNORE_CASE)
    private val rupeesStart = Regex("""^\s*Rupees\b""", RegexOption.IGNORE_CASE)
    private val hasOnly = Regex("""\bOnly\b""", RegexOption.IGNORE_CASE)
    private val numberOnlyLine = Regex("""^\s*[\d,]+\s*$""")
    private val paymentOf = Regex("""Payment of ([\d,]+)""")
    private val refAfterLabel = Regex("""(UPI Ref No|UTR)\s*:?\s*([0-9OolISBZ ]{12,16})\s*$""")
    private val bareRef = Regex("""^[0-9OolISBZ ]{12,15}$""")
    private val digitFix = mapOf('O' to '0', 'o' to '0', 'l' to '1', 'I' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')

    fun correct(input: List<OcrLine>): CorrectionResult {
        val changes = mutableListOf<String>()
        var lines = input.toMutableList()

        // 1. Join "Rupees ... " + "Only" that OCR split over two lines
        val joined = mutableListOf<OcrLine>()
        var i = 0
        while (i < lines.size) {
            val cur = lines[i]
            val next = lines.getOrNull(i + 1)
            if (rupeesStart.containsMatchIn(cur.text) && !hasOnly.containsMatchIn(cur.text) &&
                next != null && next.text.trim().equals("only", ignoreCase = true)
            ) {
                joined += cur.copy(text = cur.text.trimEnd() + " Only")
                changes += "Joined 'Rupees ... Only' lines"
                i += 2
            } else {
                joined += cur
                i++
            }
        }
        lines = joined

        // Amount in words, e.g. "Rupees One Thousand Fifty One Only" -> 1051
        val flat = lines.joinToString(" ") { it.text }.trim().replace(Regex("""\s+"""), " ")
        val wordsValue = rupeesWords.find(flat)?.let { wordsToNumber(it.groupValues[1]) }?.takeIf { it > 0 }

        // Lines that are only a number, e.g. "7902" or "1,051"
        val numberLines = lines.mapIndexedNotNull { k, l ->
            if (numberOnlyLine.matches(l.text)) {
                l.text.replace(",", "").trim().toLongOrNull()?.let { k to it }
            } else null
        }

        // 2. Figure disagrees with words and ends with the same digits -> rupee sign was read as a digit
        if (wordsValue != null) {
            val w = wordsValue.toString()
            for ((k, v) in numberLines) {
                val s = v.toString()
                if (v != wordsValue && s.endsWith(w) && s.length <= w.length + 1) {
                    changes += "Amount $s -> $w (figure disagreed with the words; rupee sign read as a digit)"
                    lines[k] = lines[k].copy(text = w)
                }
            }
        }

        // 3. "Payment of 2945" while the big amount line says 945
        val standalone = numberLines.map { it.second }.toSet()
        lines.forEachIndexed { k, l ->
            val m = paymentOf.find(l.text) ?: return@forEachIndexed
            val p = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@forEachIndexed
            val ps = p.toString()
            val matches = standalone.filter { it != p && ps.endsWith(it.toString()) && ps.length == it.toString().length + 1 }
            if (wordsValue == null && matches.size == 1) {
                lines[k] = l.copy(text = l.text.replaceRange(m.groups[1]!!.range, matches[0].toString()))
                changes += "'Payment of $ps' -> ${matches[0]} (matches the standalone amount)"
            }
        }

        // 4. 12-digit reference numbers broken by spaces or letters-for-digits
        lines.forEachIndexed { k, l ->
            val m = refAfterLabel.find(l.text)
            if (m != null) {
                val g = m.groups[2]!!
                val fixed = fixDigits(g.value)
                if (fixed.length == 12 && fixed != g.value) {
                    lines[k] = l.copy(text = l.text.substring(0, g.range.first) + fixed)
                    changes += "Reference '${g.value.trim()}' -> $fixed"
                }
            } else if (bareRef.matches(l.text.trim()) && l.text.count { it.isDigit() } >= 8) {
                val fixed = fixDigits(l.text)
                if (fixed.length == 12 && fixed != l.text.trim()) {
                    lines[k] = l.copy(text = fixed)
                    changes += "Reference '${l.text.trim()}' -> $fixed"
                }
            }
        }

        // 5. Typos in fixed phrases (whole line must be very close to a known phrase)
        lines.forEachIndexed { k, l ->
            val s = l.text.trim()
            if (s.length in 5..25 && keywords.none { it.equals(s, ignoreCase = true) }) {
                val best = keywords.map { it to similarity(s, it) }.maxByOrNull { it.second }
                if (best != null && best.second >= 0.85) {
                    lines[k] = l.copy(text = best.first)
                    changes += "'$s' -> '${best.first}'"
                }
            }
        }

        return CorrectionResult(lines, changes)
    }

    private fun fixDigits(raw: String): String =
        raw.filter { !it.isWhitespace() }.map { digitFix[it] ?: it }.joinToString("")

    // ---- number words ("One Thousand Fifty One" -> 1051) ----
    private val wordValues: Map<String, Long> = buildMap {
        "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen"
            .split(" ").forEachIndexed { n, w -> put(w, n.toLong()) }
        "twenty thirty forty fifty sixty seventy eighty ninety"
            .split(" ").forEachIndexed { n, w -> put(w, (n + 2) * 10L) }
    }

    /** Returns null if any word isn't a number word (so a garbled line is never trusted). */
    internal fun wordsToNumber(text: String): Long? {
        var total = 0L
        var cur = 0L
        for (w in text.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }) {
            val v = wordValues[w]
            when {
                v != null -> cur += v
                w == "hundred" -> cur *= 100
                w == "thousand" -> { total += cur * 1000; cur = 0 }
                w == "lakh" -> { total += cur * 100_000; cur = 0 }
                else -> return null
            }
        }
        return total + cur
    }

    // ---- fuzzy match: 1.0 = identical, based on edit distance ----
    private fun similarity(a: String, b: String): Double {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (x in 0..a.length) d[x][0] = x
        for (y in 0..b.length) d[0][y] = y
        for (x in 1..a.length) for (y in 1..b.length) {
            val cost = if (a[x - 1] == b[y - 1]) 0 else 1
            d[x][y] = minOf(d[x - 1][y] + 1, d[x][y - 1] + 1, d[x - 1][y - 1] + cost)
        }
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }
}
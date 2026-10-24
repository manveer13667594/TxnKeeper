package com.example.txnkeeper

/** Result of cleaning OCR lines: the fixed lines (same sizes) plus a log of what changed. */
data class CorrectionResult(
    val lines: List<OcrLine>,
    val changes: List<String>,
    /** Amount read from the words ("Five Hundred Only"), or null if the receipt has none. */
    val wordsAmount: Long? = null
)

/**
 * Fixes OCR mistakes that can be VERIFIED from the receipt itself, before TransactionParser runs.
 * It never invents names or IDs: anything it can't double-check is left exactly as read.
 *
 *  1. "Rupees Nine Hundred Two" + "Only" split over two lines  -> joined into one line
 *  2. Amount in figures vs amount in words  (7902 vs "Nine Hundred Two" -> 902).
 *     Words may read "Rupees ... Only", "... Rupees Only", "... Only" or "500 Only".
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

        // Amount in words (or "500 Only"), e.g. "Rupees One Thousand Fifty One Only" -> 1051
        // "nl" marks where a new line starts, so an amount in words may begin at the start of any line
        val wordsValue = findWordsAmount(lines.joinToString(" nl ") { it.text })

        // Lines that are only a number, e.g. "7902" or "1,051"
        val numberLines = lines.mapIndexedNotNull { k, l ->
            if (numberOnlyLine.matches(l.text)) {
                l.text.replace(",", "").trim().toLongOrNull()?.let { k to it }
            } else null
        }

        // 2. Figure has ONE extra digit in front of the words amount -> the rupee sign was read as a digit
        //    (5500 / 1500 vs "Five Hundred": dropping the first digit gives exactly the words amount)
        if (wordsValue != null) {
            for (k in lines.indices) {
                val fixed = fixFigures(lines[k].text, wordsValue)
                if (fixed != lines[k].text) {
                    changes += "'${lines[k].text.trim()}' -> '${fixed.trim()}' (figure disagreed with the words; rupee sign read as a digit)"
                    lines[k] = lines[k].copy(text = fixed)
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

        return CorrectionResult(lines, changes, wordsValue)
    }

    private fun fixDigits(raw: String): String =
        raw.filter { !it.isWhitespace() }.map { digitFix[it] ?: it }.joinToString("")

    // ---- amount in words ----

    private val onlyWord = Regex("""\bonly\b""", RegexOption.IGNORE_CASE)
    private val digitsOnly = Regex("""(\d[\d,]*)(?:\.\d{1,2})?\s*(?:/-)?\s*(?:rupees?\s+)?only\b""", RegexOption.IGNORE_CASE)
    private val fillerWords = setOf("rupees", "rupee", "rs", "inr", "and", "paise", "paisa", "nl")
    // words that may sit right before the amount in words ("Amount in words: Five Hundred Only")
    private val boundaryWords = setOf("words", "word", "in", "amount", "total", "paid", "only", "of", "is",
        "rupees", "rupee", "rs", "inr", "nl")

    /** Value written in words before "Only"; falls back to "500 Only". Null if nothing trustworthy. */
    internal fun findWordsAmount(flat: String): Long? {
        for (m in onlyWord.findAll(flat)) {
            val tokens = flat.substring(0, m.range.first).lowercase()
                .replace(Regex("[^a-z]+"), " ").trim().split(" ")
                .filter { it.isNotEmpty() }
                .takeLast(14)
                .map { fixNumberWord(it) }
            // longest tail made only of number words; it must start at a clean boundary, otherwise a
            // garbled word ("thousnad") could silently cut the amount short ("... five hundred" = 500, not 2500)
            for (start in tokens.indices) {
                if (start > 0 && tokens[start - 1] !in boundaryWords) continue
                val v = wordsToNumber(tokens.drop(start).joinToString(" "))
                if (v != null && v > 0) return v
            }
        }
        return digitsOnly.find(flat)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 }
    }

    /** "hundrad" -> "hundred", "thousnad" -> "thousand" (only when exactly one number word is very close). */
    private fun fixNumberWord(w: String): String {
        if (w.length < 5 || w in wordValues || w in bigWords || w in fillerWords) return w
        val best = (wordValues.keys + bigWords)
            .filter { it.length >= 5 }
            .map { it to similarity(w, it) }
            .filter { it.second >= 0.8 }
        return if (best.size == 1) best[0].first else w
    }

    // ---- figures vs words ----

    // a figure alone on its line, maybe with a misread rupee sign in front: "?7500", "Z500"
    private val bareFigure = Regex("""^(\s*)([^\d\s]{0,2})(\s*)(\d[\d,]*)(\.\d{1,2})?(\s*)$""")
    // a figure after an amount label: "Payment of 2945", "Total ?1500"
    private val labeledFigure = Regex(
        """(payment of|amount|total|paid|rs\.?|inr|₹|\?)(\s*)([^\d\s]{0,2})(\s*)(\d[\d,]*)(\.\d{1,2})?""",
        RegexOption.IGNORE_CASE
    )

    /** One extra digit in front of the words amount -> the words amount. Otherwise null. */
    private fun dropJunkDigit(figure: String, words: Long): String? {
        val f = figure.replace(",", "")
        val w = words.toString()
        return if (f.length == w.length + 1 && f.endsWith(w)) w else null
    }

    private fun fixFigures(text: String, words: Long): String {
        // very small amounts are only touched when a junk symbol proves there was a rupee sign
        fun allowed(junk: String) = words >= 100 || junk.isNotEmpty()

        bareFigure.matchEntire(text)?.let { m ->
            val junk = m.groupValues[2]
            val fixed = dropJunkDigit(m.groupValues[4], words)
            return if (fixed != null && allowed(junk))
                m.groupValues[1] + junk + m.groupValues[3] + fixed + m.groupValues[5] + m.groupValues[6]
            else text
        }
        var out = text
        for (m in labeledFigure.findAll(text).toList().asReversed()) {
            val fixed = dropJunkDigit(m.groupValues[5], words) ?: continue
            if (!allowed(m.groupValues[3])) continue
            val range = m.groups[5]!!.range
            out = out.replaceRange(range, fixed)
        }
        return out
    }

    // ---- number words ("One Thousand Fifty One" -> 1051) ----
    private val bigWords = setOf("hundred", "thousand", "lakh", "lakhs", "lac", "lacs", "crore", "crores")
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
        var any = false
        for (w in text.lowercase().split(Regex("""[\s-]+""")).filter { it.isNotEmpty() }) {
            val v = wordValues[w]
            when {
                w in fillerWords -> continue
                v != null -> cur += v
                w == "hundred" -> cur = if (cur == 0L) 100 else cur * 100
                w == "thousand" -> { total += maxOf(cur, 1L) * 1000; cur = 0 }
                w == "lakh" || w == "lakhs" || w == "lac" || w == "lacs" -> { total += maxOf(cur, 1L) * 100_000; cur = 0 }
                w == "crore" || w == "crores" -> { total += maxOf(cur, 1L) * 10_000_000; cur = 0 }
                else -> return null
            }
            any = true
        }
        return if (any) total + cur else null
    }

    // ---- fuzzy match: 1.0 = identical, based on edit distance ----
    private fun similarity(a: String, b: String): Double {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (x in 0..a.length) d[x][0] = x
        for (y in 0..b.length) d[0][y] = y
        for (x in 1..a.length) for (y in 1..b.length) {
            val cost = if (a[x - 1] == b[y - 1]) 0 else 1
            d[x][y] = minOf(d[x - 1][y] + 1, d[x][y - 1] + 1, d[x - 1][y - 1] + cost)
            if (x > 1 && y > 1 && a[x - 1] == b[y - 2] && a[x - 2] == b[y - 1]) d[x][y] = minOf(d[x][y], d[x - 2][y - 2] + 1)
        }
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }
}
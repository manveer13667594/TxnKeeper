package com.example.txnkeeper

import java.util.Calendar
import java.util.Locale

/**
 * Offline, rule-based parser for what you SAY: "paid five hundred rupees to Rahul for groceries".
 * It returns the same [ParsedTransaction] as the receipt parser, so the same form fill is used.
 * Anything it isn't sure about stays null and the form keeps its value.
 *
 * Understood: number words ("two thousand three hundred", "1.5 lakh", "5k"), paid/received style
 * verbs, "to / from / at / on / for" phrases, payment method, and dates like "yesterday",
 * "two days ago", "on Monday", "12th October".
 */
object SpokenTransactionParser {

    private class Tok(val text: String, val low: String, val num: Double? = null)

    fun parse(text: String, now: Long = System.currentTimeMillis()): ParsedTransaction {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return ParsedTransaction()

        val type = findType(tokens)
        val (title, description) = findTitle(tokens, type)
        return ParsedTransaction(
            title = title,
            amount = findAmount(tokens),
            type = type,
            paymentMethod = findPaymentMethod(tokens.joinToString(" ") { it.low }),
            dateMillis = findDate(tokens, now),
            description = description
        )
    }

    // ------------------------------------------------------------- tokens

    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )
    private val scales = mapOf(
        "thousand" to 1_000.0, "lakh" to 100_000.0, "lakhs" to 100_000.0,
        "crore" to 10_000_000.0, "crores" to 10_000_000.0
    )
    private val numericToken = Regex("""^\d+(?:\.\d+)?$""")
    private val kToken = Regex("""^(\d+(?:\.\d+)?)k$""")

    private fun tokenize(raw: String): List<Tok> {
        val cleaned = raw
            .replace("₹", " rs ")
            .replace(Regex("""(?<=\d),(?=\d{3})"""), "")   // 2,300 -> 2300
            .replace('-', ' ')
        val words = cleaned.split(Regex("""\s+""")).mapNotNull { w ->
            // keep inner dots (2.5) but drop punctuation around the word
            val t = w.trim { !it.isLetterOrDigit() }
            if (t.isEmpty()) null else t
        }

        val out = ArrayList<Tok>()
        var i = 0
        while (i < words.size) {
            val low = words[i].lowercase(Locale.ROOT)
            kToken.find(low)?.let {
                val v = it.groupValues[1].toDouble() * 1000
                out.add(Tok(formatNumber(v), formatNumber(v), v)); i++; continue
            }
            val run = readNumber(words, i)
            if (run != null) {
                val (value, next) = run
                out.add(Tok(formatNumber(value), formatNumber(value), value))
                i = next
            } else {
                out.add(Tok(words[i], low))
                i++
            }
        }
        return out
    }

    private fun formatNumber(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /** Reads "two thousand three hundred and fifty", "2 lakh", "a hundred"... Returns value and next index. */
    private fun readNumber(words: List<String>, start: Int): Pair<Double, Int>? {
        var total = 0.0
        var current = 0.0
        var consumed = false
        var lastWasScale = false
        var j = start
        while (j < words.size) {
            val w = words[j].lowercase(Locale.ROOT)
            val next = words.getOrNull(j + 1)?.lowercase(Locale.ROOT)
            when {
                numericToken.matches(w) && (!consumed || lastWasScale) -> {
                    current += w.toDouble(); lastWasScale = false
                }
                w == "a" && !consumed && next != null && (next == "hundred" || next in scales) -> {
                    current = 1.0
                }
                w in units -> { current += units.getValue(w); lastWasScale = false }
                w == "hundred" && (consumed || current > 0) -> {
                    current = (if (current == 0.0) 1.0 else current) * 100; lastWasScale = false
                }
                w in scales && consumed -> {
                    total += (if (current == 0.0) 1.0 else current) * scales.getValue(w)
                    current = 0.0; lastWasScale = true
                }
                w == "and" && consumed && next != null &&
                        (next in units || numericToken.matches(next)) -> { j++; continue }
                else -> break
            }
            consumed = true
            j++
        }
        // "five" alone is fine, but a lone "a" is not a number
        return if (consumed && (total + current) > 0 || (consumed && words[start].lowercase(Locale.ROOT) == "zero"))
            Pair(total + current, j) else null
    }

    // ------------------------------------------------------------- amount

    private val currencyWords = setOf("rupees", "rupee", "rs", "inr", "rupaye")
    private val timeUnits = setOf(
        "day", "days", "hour", "hours", "minute", "minutes", "week", "weeks",
        "month", "months", "year", "years", "am", "pm", "percent"
    )
    private val monthNames = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9, "october" to 10,
        "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12
    )
    private val weekdays = listOf(
        "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"
    )

    private fun findAmount(tokens: List<Tok>): Double? {
        var best: Double? = null
        var bestScore = 0
        tokens.forEachIndexed { i, t ->
            val value = t.num ?: return@forEachIndexed
            if (value <= 0 || value >= 1_000_000_000) return@forEachIndexed
            val next = tokens.getOrNull(i + 1)?.low
            val prev = tokens.getOrNull(i - 1)?.low
            // part of a date or "2 days ago", not money
            if (next in timeUnits || next in monthNames || prev in monthNames) return@forEachIndexed
            var score = 1
            if (next in currencyWords || prev in currencyWords) score = 3
            if (score > bestScore) { bestScore = score; best = value }
        }
        return best
    }

    // --------------------------------------------------------------- type

    private val expenseWords = setOf(
        "paid", "pay", "spent", "spend", "bought", "buy", "purchased", "sent", "send", "gave",
        "give", "transferred", "debited", "withdrew", "withdrawn", "charged", "ordered", "cost"
    )
    private val incomeWords = setOf(
        "received", "earned", "credited", "deposited", "refund", "refunded", "salary", "income",
        "cashback"
    )
    private val toMeVerbs = setOf("sent", "send", "gave", "give", "paid", "pay", "transferred")

    private fun findType(tokens: List<Tok>): String? {
        val words = tokens.map { it.low }
        tokens.forEachIndexed { i, t ->
            val w = t.low
            val next = words.getOrNull(i + 1)
            when {
                w in toMeVerbs && (next == "me" || next == "us") -> return "INCOME"
                w in expenseWords -> return "EXPENSE"
                w in incomeWords -> return "INCOME"
                // "got" is money in only with "from" ("got 500 from Amit"), not "got petrol for 200"
                (w == "got" || w == "get") && "from" in words -> return "INCOME"
            }
        }
        if ("from" in words) return "INCOME"
        if ("to" in words) return "EXPENSE"
        return null
    }

    // ----------------------------------------------------- payment method

    private val upiRegex = Regex("""\b(upi|gpay|g pay|google pay|phonepe|phone pe|phone pay|paytm|pay tm|bhim|amazon pay)\b""")
    private val bankRegex = Regex("""\b(neft|imps|rtgs|bank transfer|net banking|netbanking)\b""")
    private val cardRegex = Regex("""\b(card|credit card|debit card|visa|mastercard|rupay)\b""")
    private val cashRegex = Regex("""\bcash\b""")

    private fun findPaymentMethod(lower: String): String? = when {
        upiRegex.containsMatchIn(lower) -> "UPI"
        bankRegex.containsMatchIn(lower) -> "BANK TRANSFER"
        cardRegex.containsMatchIn(lower) -> "CARD"
        cashRegex.containsMatchIn(lower) -> "CASH"
        else -> null
    }

    // --------------------------------------------------------------- date

    private fun noonDaysAgo(now: Long, days: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -days)
        }.timeInMillis

    private fun findDate(tokens: List<Tok>, now: Long): Long? {
        val words = tokens.map { it.low }
        val text = words.joinToString(" ")

        if ("day before yesterday" in text) return noonDaysAgo(now, 2)
        if ("yesterday" in words || "last night" in text) return noonDaysAgo(now, 1)
        if ("today" in words || "tonight" in words) return null

        // "2 days ago" (number words were already turned into digits)
        tokens.forEachIndexed { i, t ->
            val n = t.num
            if (n != null && words.getOrNull(i + 1).let { it == "day" || it == "days" } &&
                words.getOrNull(i + 2) == "ago" && n in 1.0..60.0
            ) return noonDaysAgo(now, n.toInt())
        }

        // "12th October" / "12 October" / "October 12" (this year, or last year if that would be future)
        tokens.forEachIndexed { i, t ->
            val month = monthNames[t.low] ?: return@forEachIndexed
            // "may" is also a normal word; only trust it next to a day number
            val before = tokens.getOrNull(i - 1)?.low?.removeOrdinal()?.toIntOrNull()
            val after = tokens.getOrNull(i + 1)?.low?.removeOrdinal()?.toIntOrNull()
            val day = before ?: after ?: return@forEachIndexed
            if (day !in 1..31) return@forEachIndexed
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            var year = cal.get(Calendar.YEAR)
            fun build(y: Long): Long? = try {
                Calendar.getInstance().apply {
                    isLenient = false; clear(); set(y.toInt(), month - 1, day, 12, 0, 0)
                }.timeInMillis
            } catch (e: IllegalArgumentException) { null }
            var millis = build(year.toLong()) ?: return@forEachIndexed
            if (millis > now + 86_400_000L) millis = build(year - 1L) ?: return@forEachIndexed
            return millis
        }

        // "on Monday" / "last Monday": the most recent such day
        weekdays.forEachIndexed { index, name ->
            val at = words.indexOf(name)
            if (at >= 0) {
                val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) - 1
                var back = (today - index + 7) % 7
                if (back == 0 && words.getOrNull(at - 1) == "last") back = 7
                return noonDaysAgo(now, back)
            }
        }
        return null
    }

    private fun String.removeOrdinal(): String =
        replace(Regex("""(?<=\d)(st|nd|rd|th)$"""), "")

    // -------------------------------------------------------------- title

    private val skipWords = setOf("the", "a", "an", "my", "his", "her", "our", "their", "mr", "mrs", "ms", "shri")
    private val stopWords = setOf(
        "for", "on", "using", "via", "by", "with", "through", "yesterday", "today", "tomorrow",
        "in", "at", "from", "to", "and", "because", "as", "ago", "last", "this", "paid", "pay",
        "buy", "send", "give", "rupees", "rupee", "rs", "inr", "cash", "upi", "gpay", "card",
        "morning", "evening", "night", "please", "thanks", "thank", "before", "after", "day"
    )
    private val buyVerbs = setOf("bought", "purchased", "ordered", "ate", "had")

    private fun phraseAfter(tokens: List<Tok>, start: Int, max: Int): List<Tok> {
        var i = start
        while (i < tokens.size && tokens[i].low in skipWords) i++
        val out = ArrayList<Tok>()
        while (i < tokens.size && out.size < max) {
            val t = tokens[i]
            if (t.low in stopWords || t.num != null || t.low in monthNames || t.low in weekdays ||
                t.low.removeOrdinal().toIntOrNull() != null ||
                (out.isNotEmpty() && t.low in skipWords)   // "Ramesh the day before yesterday"
            ) break
            out.add(t); i++
        }
        return out
    }

    private fun neat(parts: List<Tok>): String? {
        if (parts.isEmpty()) return null
        return parts.joinToString(" ") { t ->
            if (t.text == t.text.lowercase(Locale.ROOT)) t.text.replaceFirstChar { it.uppercase() } else t.text
        }
    }

    private fun findTitle(tokens: List<Tok>, type: String?): Pair<String?, String?> {
        val partyMarkers = when (type) {
            "EXPENSE" -> setOf("to", "at")
            "INCOME" -> setOf("from")
            else -> setOf("to", "from", "at")
        }

        // 1. The other party: "to Rahul", "from Amit", "at Dominos"
        var partyAt = -1
        var party: List<Tok> = emptyList()
        for (i in tokens.indices) {
            if (tokens[i].low in partyMarkers) {
                val p = phraseAfter(tokens, i + 1, 4)
                if (p.isNotEmpty()) { party = p; partyAt = i + 1 + p.size; break }
            }
        }

        // 1b. "Rahul sent me 200"
        if (party.isEmpty() && type == "INCOME") {
            for (i in 1 until tokens.size - 1) {
                if (tokens[i].low in toMeVerbs && tokens[i + 1].low in setOf("me", "us")) {
                    val subject = tokens[i - 1]
                    if (subject.low != "i" && subject.text.first().isUpperCase()) {
                        party = listOf(subject); partyAt = i + 2
                    }
                    break
                }
            }
        }

        // The purpose: "for groceries", "on lunch"
        fun purpose(from: Int): List<Tok> {
            for (i in from until tokens.size) {
                if (tokens[i].low == "for" || tokens[i].low == "on") {
                    val p = phraseAfter(tokens, i + 1, 5)
                    if (p.isNotEmpty()) return p
                }
            }
            return emptyList()
        }

        if (party.isNotEmpty()) return Pair(neat(party), neat(purpose(maxOf(partyAt, 0))))

        // 2. No other party: the thing bought / spent on ("bought vegetables", "spent 300 on lunch")
        val buyAt = tokens.indexOfFirst { it.low in buyVerbs }
        if (buyAt >= 0) {
            val item = phraseAfter(tokens, buyAt + 1, 4)
            if (item.isNotEmpty()) return Pair(neat(item), null)
        }
        val why = purpose(0)
        if (why.isNotEmpty()) return Pair(neat(why), null)

        // 3. Well-known income words
        val known = tokens.firstOrNull { it.low in setOf("salary", "refund", "refunded", "cashback", "interest", "rent") }
        if (known != null) {
            return Pair(if (known.low == "refunded") "Refund" else known.low.replaceFirstChar { it.uppercase() }, null)
        }

        // 4. Short notes with no verb: "petrol 400 rupees cash", "400 rupees chai"
        val amountAt = tokens.indexOfFirst { it.num != null }
        if (amountAt >= 0) {
            val before = phraseAfter(tokens, 0, 3)
            if (amountAt > 0 && before.isNotEmpty()) return Pair(neat(before), null)
            var k = amountAt + 1
            while (k < tokens.size && tokens[k].low in currencyWords) k++
            val after = phraseAfter(tokens, k, 3)
            if (after.isNotEmpty()) return Pair(neat(after), null)
        }
        return Pair(null, null)
    }
}

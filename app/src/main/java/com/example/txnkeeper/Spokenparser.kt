package com.example.txnkeeper

import java.util.Calendar
import java.util.Locale

/**
 * Offline, rule-based parser for what you SAY: "paid five hundred rupees to Rahul for groceries".
 * It returns the same [ParsedTransaction] as the receipt parser, so the same form fill is used.
 * Anything it isn't sure about stays null and the form keeps its value.
 *
 * One recording can hold several transactions ("paid 500 to Rahul and then received 2000 from
 * Amit"): use [parseAll]. [parse] returns just the first one.
 *
 * Understood: number words ("two thousand three hundred", "two fifty", "1.5 lakh", "5k"),
 * paid/received style verbs, "to / from / at / on / for" phrases, payment method, filler words
 * ("uh", "um"), Whisper tags like [BLANK_AUDIO], and dates like "yesterday", "two days ago",
 * "on Monday", "12th October".
 *
 * Sharing rules for recordings with several transactions:
 *  - a date or payment method belongs to the clause it is said in;
 *  - but a date that is the ONLY one in the recording and is said at the very start or very end
 *    applies to every transaction ("Yesterday I paid ... and ..."), and a payment method said
 *    last as "both / all using UPI" covers all of them.
 *  - a transaction with no clear paid/received wording takes the direction of the one before it.
 */
object SpokenTransactionParser {

    // brk: 0 = no break after this word, 1 = comma-like pause, 2 = sentence end
    private class Tok(val text: String, val low: String, val num: Double? = null, val brk: Int = 0)

    private class Seg(
        val tx: ParsedTransaction,
        val amountIdx: Int,
        val size: Int,
        val dateStart: Int,
        val dateEnd: Int,
        val payAfterAmount: Boolean
    )

    private class Raw(val lead: List<Tok>, val tokens: List<Tok>, val strongBefore: Boolean)

    private class DateHit(val millis: Long, val start: Int, val end: Int)

    // ------------------------------------------------------------- entry points

    /** The first transaction in the text (or an empty one). */
    fun parse(text: String, now: Long = System.currentTimeMillis()): ParsedTransaction {
        parseAll(text, now).firstOrNull()?.let { return it }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return ParsedTransaction()
        return analyze(tokens, now, hasMoneyContext(tokens)).tx
    }

    /** Every transaction found in the text, in the order spoken. Empty if none. */
    fun parseAll(text: String, now: Long = System.currentTimeMillis()): List<ParsedTransaction> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val ctx = hasMoneyContext(tokens)

        val segs = merge(splitRaw(tokens), ctx)
            .map { analyze(it, now, ctx) }
            .filter { it.tx.amount != null }
        if (segs.isEmpty()) return emptyList()

        var dates = segs.map { it.tx.dateMillis }
        var pays = segs.map { it.tx.paymentMethod }

        // A single date at the very start / end of the recording belongs to all of them
        val datedAt = dates.indices.filter { dates[it] != null }
        if (datedAt.size == 1 && segs.size > 1) {
            val i = datedAt[0]
            val s = segs[i]
            val atStart = i == 0 && s.dateStart < s.amountIdx
            val atEnd = i == segs.lastIndex && s.dateEnd >= s.size - 1
            if (atStart || atEnd) { val d = dates[i]; dates = dates.map { d } }
        }
        // Same for the payment method, but only when said at the very end
        val paidAt = pays.indices.filter { pays[it] != null }
        if (paidAt.size == 1 && segs.size > 1 && paidAt[0] == segs.lastIndex && segs.last().payAfterAmount) {
            val p = pays[paidAt[0]]; pays = pays.map { p }
        }
        // Direction: from the transaction before, or the next one if it is the first
        val types = segs.map { it.tx.type }.toMutableList()
        for (i in types.indices) if (types[i] == null && i > 0) types[i] = types[i - 1]
        for (i in types.indices.reversed()) if (types[i] == null && i < types.lastIndex) types[i] = types[i + 1]

        return segs.mapIndexed { i, s ->
            s.tx.copy(type = types[i], paymentMethod = pays[i], dateMillis = dates[i])
        }
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

    // "uh", "um"... carry no meaning
    private val fillers = setOf("uh", "um", "umm", "uhh", "uhm", "hmm", "hm", "mm", "er", "erm", "ah", "aah", "eh")
    // Whisper tags: [BLANK_AUDIO], (music), *sigh*
    private val noiseTags = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")
    // Words people say at the very end: "thank you", "that's it"
    private val closers = listOf(
        listOf("thank", "you", "so", "much"), listOf("thank", "you"), listOf("thanks"),
        listOf("that's", "it"), listOf("thats", "it"), listOf("that", "is", "it"),
        listOf("yeah"), listOf("okay"), listOf("ok"), listOf("bye"), listOf("right")
    )
    // Words people say before the actual sentence
    private val openers = listOf(
        listOf("i", "want", "to", "add", "a", "transaction", "that", "i"),
        listOf("i", "want", "to", "add", "that", "i"), listOf("i", "want", "to", "add", "that"),
        listOf("i", "want", "to", "add"), listOf("note", "down", "that", "i"),
        listOf("note", "down", "that"), listOf("note", "down"), listOf("add", "a", "transaction", "that", "i"),
        listOf("add", "a", "transaction"), listOf("add", "transaction"), listOf("add", "that", "i"),
        listOf("please", "add"), listOf("please", "note"), listOf("okay", "so"), listOf("so", "basically"),
        listOf("so"), listOf("alright"), listOf("hey"), listOf("okay"), listOf("ok"), listOf("well")
    )
    // A full stop after these is not the end of a sentence
    private val abbreviations = setOf("rs", "mr", "mrs", "ms", "dr", "no", "st", "inr")

    private fun tokenize(raw: String): List<Tok> {
        val cleaned = raw
            .replace(noiseTags, " ")
            .replace("₹", " rs ")
            .replace(Regex("""(?<=\d),(?=\d{3})"""), "")   // 2,300 -> 2300
            .replace('-', ' ')
            .replace("/ ", " ")
        val words = ArrayList<String>()
        val brks = ArrayList<Int>()
        cleaned.split(Regex("""\s+""")).forEach { w ->
            // keep inner dots (2.5) but drop punctuation around the word
            val t = w.trim { !it.isLetterOrDigit() }
            if (t.isEmpty()) {
                val c = w.lastOrNull()
                if (c != null && brks.isNotEmpty()) brks[brks.lastIndex] = maxOf(brks.last(), breakOf(c))
                return@forEach
            }
            if (t.lowercase(Locale.ROOT) in fillers) return@forEach
            var b = w.lastOrNull()?.let { breakOf(it) } ?: 0
            if (b == 2 && t.lowercase(Locale.ROOT) in abbreviations) {
                // "2000 rs." ends a sentence; "Rs. 2000" does not
                val prev = words.lastOrNull()?.lowercase(Locale.ROOT)
                val afterNumber = prev != null && (prev.any { it.isDigit() } || prev in units || prev in scales || prev == "hundred")
                if (!afterNumber) b = 0
            }
            words.add(t); brks.add(b)
        }

        val out = ArrayList<Tok>()
        var i = 0
        while (i < words.size) {
            val low = words[i].lowercase(Locale.ROOT)
            val kMatch = kToken.find(low)
            if (kMatch != null) {
                val v = kMatch.groupValues[1].toDouble() * 1000
                out.add(Tok(formatNumber(v), formatNumber(v), v, brks[i])); i++
                continue
            }
            val run = readNumber(words, brks, i)
            if (run != null) {
                val (value, next) = run
                out.add(Tok(formatNumber(value), formatNumber(value), value, brks[next - 1]))
                i = next
            } else {
                out.add(Tok(words[i], low, null, brks[i]))
                i++
            }
        }
        // drop lead-ins at the start: "okay so", "please add", "I want to add that I"
        var lead = true
        while (lead && out.isNotEmpty()) {
            lead = false
            for (c in openers) {
                if (out.size > c.size && out.take(c.size).map { it.low } == c) {
                    repeat(c.size) { out.removeAt(0) }
                    lead = true
                    break
                }
            }
        }
        // drop "thank you" / "that's it" at the end
        var changed = true
        while (changed && out.isNotEmpty()) {
            changed = false
            for (c in closers) {
                if (out.size >= c.size && out.takeLast(c.size).map { it.low } == c) {
                    repeat(c.size) { out.removeAt(out.lastIndex) }
                    changed = true
                    break
                }
            }
        }
        return out
    }

    private fun breakOf(c: Char): Int = when (c) {
        '.', '!', '?', ';' -> 2
        ',', ':' -> 1
        else -> 0
    }

    private fun formatNumber(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /** Reads "two thousand three hundred and fifty", "2 lakh", "a hundred", "two fifty"... Returns value and next index. */
    private fun readNumber(words: List<String>, brks: List<Int>, start: Int): Pair<Double, Int>? {
        var total = 0.0
        var current = 0.0
        var consumed = false
        var lastWasScale = false
        var lastWasSmallUnit = false
        var lastWasDigits = false
        var j = start
        while (j < words.size) {
            if (j > start && brks[j - 1] > 0) break          // a pause ends the number
            val w = words[j].lowercase(Locale.ROOT)
            val next = words.getOrNull(j + 1)?.lowercase(Locale.ROOT)
            val afterNext = words.getOrNull(j + 2)?.lowercase(Locale.ROOT)
            when {
                numericToken.matches(w) && (!consumed || lastWasScale) -> {
                    current += w.toDouble(); lastWasScale = false; lastWasSmallUnit = false
                    lastWasDigits = true; consumed = true; j++
                    continue
                }
                w == "a" && !consumed && next != null && (next == "hundred" || next in scales) -> {
                    current = 1.0
                }
                // "two fifty" = 250, "five twenty" = 520
                w in units && units.getValue(w) >= 20 && consumed && lastWasSmallUnit && current in 1.0..9.0 -> {
                    current = current * 100 + units.getValue(w); lastWasScale = false; lastWasSmallUnit = false
                }
                w in units -> {
                    // "fifty thousand three days ago": the "three" belongs to the date
                    if (consumed && next in timeUnits && !(next == "day" && afterNext == "before")) break
                    // "3000 three days ago": digits followed by a word is two things
                    if (lastWasDigits) break
                    // "five hundred three hundred" is two numbers
                    if (consumed && current >= 100 && next == "hundred") break
                    current += units.getValue(w); lastWasScale = false
                    lastWasSmallUnit = units.getValue(w) in 1..9 && current < 10
                }
                w == "hundred" && (consumed || current > 0) -> {
                    current = (if (current == 0.0) 1.0 else current) * 100; lastWasScale = false; lastWasSmallUnit = false
                }
                // "hundred rupees"
                w == "hundred" && !consumed && next in currencyWords -> {
                    current = 100.0
                }
                w in scales && consumed -> {
                    total += (if (current == 0.0) 1.0 else current) * scales.getValue(w)
                    current = 0.0; lastWasScale = true; lastWasSmallUnit = false
                }
                w == "and" && consumed && next != null &&
                        (next in units || numericToken.matches(next)) &&
                        !(current >= 100 && afterNext == "hundred") &&
                        brks[j] == 0 -> { j++; continue }
                else -> break
            }
            lastWasDigits = false
            consumed = true
            j++
        }
        // "five" alone is fine, but a lone "a" is not a number
        return if (consumed && (total + current) > 0 || (consumed && words[start].lowercase(Locale.ROOT) == "zero"))
            Pair(total + current, j) else null
    }

    // ------------------------------------------------------------- splitting

    private val connectors = setOf("and", "then", "also", "plus", "another")

    /** How many words a connector at [i] takes (0 = not a connector). */
    private fun connectorAt(tokens: List<Tok>, i: Int, startOfPiece: Boolean): Int {
        val w = tokens[i].low
        val next = tokens.getOrNull(i + 1)?.low
        return when {
            w in connectors -> 1
            w == "after" && next == "that" -> 2
            w == "next" && next !in weekdays &&
                    (startOfPiece || next in expenseWords || next in incomeWords || next == "got") -> 1
            else -> 0
        }
    }

    /** Cuts the words at pauses and at "and / then / also / plus / after that". */
    private fun splitRaw(tokens: List<Tok>): List<Raw> {
        val raw = ArrayList<Raw>()
        var cur = ArrayList<Tok>()
        var lead: List<Tok> = emptyList()
        var strongBefore = false
        var pendingStrong = false
        fun flush() {
            if (cur.isNotEmpty()) raw.add(Raw(lead, cur, strongBefore))
            cur = ArrayList(); lead = emptyList()
        }
        var i = 0
        while (i < tokens.size) {
            if (connectorAt(tokens, i, cur.isEmpty()) > 0) {
                flush()
                val conn = ArrayList<Tok>()
                while (i < tokens.size) {
                    val s = connectorAt(tokens, i, true)
                    if (s == 0) break
                    for (k in 0 until s) conn.add(tokens[i + k])
                    i += s
                }
                lead = conn
                strongBefore = pendingStrong
                pendingStrong = false
                continue
            }
            if (cur.isEmpty() && lead.isEmpty()) { strongBefore = pendingStrong; pendingStrong = false }
            cur.add(tokens[i])
            if (tokens[i].brk > 0) {
                val strong = tokens[i].brk == 2
                flush()
                pendingStrong = strong
            }
            i++
        }
        flush()
        return raw
    }

    private val splitVerbs = setOf(
        "paid", "spent", "bought", "sent", "gave", "transferred", "debited", "received",
        "credited", "deposited", "earned", "purchased", "got", "withdrew"
    )

    /** "paid 300 to Amit bought tea for 20": a second verb after an amount starts a new transaction. */
    private fun splitAtVerbs(piece: List<Tok>, ctx: Boolean): List<List<Tok>> {
        val out = ArrayList<List<Tok>>()
        var start = 0
        for (i in 1 until piece.size) {
            if (piece[i].low !in splitVerbs) continue
            // "Priya paid me 500": the name before the verb goes with it
            var cut = if (piece.getOrNull(i + 1)?.low.let { it == "me" || it == "us" }) i - 1 else i
            // "salary credited 1500": the noun before credited / debited goes with it
            if (piece[i].low in setOf("credited", "debited", "deposited")) {
                val p = piece.getOrNull(i - 1)
                if (p != null && p.num == null && p.low !in stopWords && p.low !in skipWords && p.low !in currencyWords) cut = i - 1
            }
            if (cut <= start) continue
            val before = piece.subList(start, cut)
            val after = piece.subList(cut, piece.size)
            if (findAmount(before, ctx) != null && findAmount(after, ctx) != null) {
                out.add(ArrayList(before)); start = cut
            }
        }
        out.add(ArrayList(piece.subList(start, piece.size)))
        return out
    }

    /** Joins pieces that have no amount onto a neighbour, so every group has one. */
    private fun merge(raw: List<Raw>, ctx: Boolean): List<List<Tok>> {
        val merged = ArrayList<MutableList<Tok>>()
        var carry: MutableList<Tok>? = null
        for (r in raw) {
            if (findAmount(r.tokens, ctx) != null) {
                val piece = ArrayList<Tok>()
                val c = carry
                if (c != null) { piece.addAll(c); piece.addAll(r.lead) }
                carry = null
                piece.addAll(r.tokens)
                merged.add(piece)
            } else if (merged.isNotEmpty() && carry == null && !r.strongBefore &&
                r.tokens.first().low !in splitVerbs && r.tokens.first().low !in buyVerbs) {
                merged.last().addAll(r.lead); merged.last().addAll(r.tokens)
            } else {
                val c = carry ?: ArrayList()
                c.addAll(r.lead); c.addAll(r.tokens)
                carry = c
            }
        }
        // leftovers at the very end belong to the last transaction
        val left = carry
        if (left != null && merged.isNotEmpty()) merged.last().addAll(left)
        return merged.flatMap { splitAtVerbs(it, ctx) }
    }

    // ------------------------------------------------------------- one transaction

    private fun analyze(tokens: List<Tok>, now: Long, ctx: Boolean): Seg {
        val type = findType(tokens)
        val (title, description) = findTitle(tokens, type)
        val amount = findAmount(tokens, ctx)
        val date = findDate(tokens, now)
        val afterAmount = if (amount != null) tokens.drop(amount.second + 1) else emptyList()
        return Seg(
            tx = ParsedTransaction(
                title = title,
                amount = amount?.first,
                type = type,
                paymentMethod = findPaymentMethod(tokens.joinToString(" ") { it.low }),
                dateMillis = date?.millis,
                description = description
            ),
            amountIdx = amount?.second ?: Int.MAX_VALUE,
            size = tokens.size,
            dateStart = date?.start ?: -1,
            dateEnd = date?.end ?: -1,
            // only "both / all using UPI" is taken to cover the earlier transactions too
            payAfterAmount = afterAmount.any { it.low == "both" || it.low == "all" } &&
                    findPaymentMethod(afterAmount.joinToString(" ") { it.low }) != null
        )
    }

    // ------------------------------------------------------------- amount

    private val currencyWords = setOf("rupees", "rupee", "rs", "inr", "rupaye")
    private val timeUnits = setOf(
        "day", "days", "hour", "hours", "minute", "minutes", "week", "weeks",
        "month", "months", "year", "years", "am", "pm", "percent", "min", "mins", "sec", "seconds"
    )
    // Counts and measures, not money
    private val measureUnits = setOf(
        "steps", "kg", "kgs", "km", "kms", "kilometers", "kilometres", "gram", "grams", "litre",
        "litres", "liter", "liters", "piece", "pieces", "people", "times", "kids", "items",
        "degrees", "calories", "points", "persons", "members", "miles", "meters", "metres"
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

    /** Any paid / received / rupees wording in the text? Without it a small bare number is not money. */
    private fun hasMoneyContext(tokens: List<Tok>): Boolean = tokens.any {
        it.low in expenseWords || it.low in incomeWords || it.low in currencyWords ||
                it.low == "got" || it.low == "get"
    }

    /** The amount and the index of its token. */
    private fun findAmount(tokens: List<Tok>, ctx: Boolean): Pair<Double, Int>? {
        var best: Pair<Double, Int>? = null
        var bestScore = 0
        tokens.forEachIndexed { i, t ->
            val value = t.num ?: return@forEachIndexed
            if (value <= 0 || value >= 1_000_000_000) return@forEachIndexed
            val next = tokens.getOrNull(i + 1)?.low
            val prev = tokens.getOrNull(i - 1)?.low
            // part of a date, "2 days ago", "5 kg": not money
            // "950 day before yesterday" is money followed by a date, not "950 days"
            val dayBefore = next == "day" && tokens.getOrNull(i + 2)?.low == "before"
            if ((next in timeUnits && !dayBefore) || next in measureUnits || next in monthNames || prev in monthNames) {
                return@forEachIndexed
            }
            val withCurrency = next in currencyWords || prev in currencyWords
            if (!withCurrency && value < 10 && !ctx) return@forEachIndexed
            val score = if (withCurrency) 3 else 1
            // on a tie the bigger number wins ("3 shirts for 1500")
            if (score > bestScore || (score == bestScore && value > (best?.first ?: 0.0))) {
                bestScore = score; best = Pair(value, i)
            }
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

    private fun findDate(tokens: List<Tok>, now: Long): DateHit? {
        val words = tokens.map { it.low }

        // "day before yesterday"
        for (i in 0 until words.size - 2) {
            if (words[i] == "day" && words[i + 1] == "before" && words[i + 2] == "yesterday") {
                return DateHit(noonDaysAgo(now, 2), i, i + 2)
            }
        }
        val y = words.indexOf("yesterday")
        if (y >= 0) return DateHit(noonDaysAgo(now, 1), y, y)
        for (i in 0 until words.size - 1) {
            if (words[i] == "last" && words[i + 1] == "night") return DateHit(noonDaysAgo(now, 1), i, i + 1)
        }
        if ("today" in words || "tonight" in words) return null

        // "2 days ago" (number words were already turned into digits)
        tokens.forEachIndexed { i, t ->
            val n = t.num
            if (n != null && words.getOrNull(i + 1).let { it == "day" || it == "days" } &&
                words.getOrNull(i + 2) == "ago" && n in 1.0..60.0
            ) return DateHit(noonDaysAgo(now, n.toInt()), i, i + 2)
        }

        // "12th October" / "12 October" / "October 12" (this year, or last year if that would be future)
        tokens.forEachIndexed { i, t ->
            val month = monthNames[t.low] ?: return@forEachIndexed
            // "may" is also a normal word; only trust it next to a day number
            val before = tokens.getOrNull(i - 1)?.low?.removeOrdinal()?.toIntOrNull()
            val after = tokens.getOrNull(i + 1)?.low?.removeOrdinal()?.toIntOrNull()
            val day = before ?: after ?: return@forEachIndexed
            if (day !in 1..31) return@forEachIndexed
            val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            fun build(yr: Int): Long? = try {
                Calendar.getInstance().apply {
                    isLenient = false; clear(); set(yr, month - 1, day, 12, 0, 0)
                }.timeInMillis
            } catch (e: IllegalArgumentException) { null }
            var millis = build(year) ?: return@forEachIndexed
            if (millis > now + 86_400_000L) millis = build(year - 1) ?: return@forEachIndexed
            val start = if (before != null) i - 1 else i
            val end = if (before != null) i else i + 1
            return DateHit(millis, start, end)
        }

        // "on Monday" / "last Monday": the most recent such day
        weekdays.forEachIndexed { index, name ->
            val at = words.indexOf(name)
            if (at >= 0) {
                val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) - 1
                var back = (today - index + 7) % 7
                val last = words.getOrNull(at - 1) == "last"
                if (back == 0 && last) back = 7
                return DateHit(noonDaysAgo(now, back), if (last) at - 1 else at, at)
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
        "days", "weeks", "months", "year", "years", "week", "month", "morning", "evening", "night", "please", "thanks", "thank", "before", "after", "day",
        "both", "all", "each", "then", "also", "plus", "neft", "imps", "rtgs",
        "credited", "debited", "received", "got", "spent", "sent", "gave", "bought", "transferred",
        "deposited", "earned", "withdrew", "ordered", "purchased", "me", "us", "i", "we",
        "returned", "lent", "borrowed", "repaid"
    )
    private val buyVerbs = setOf("bought", "purchased", "ordered", "ate", "had")
    // "on GPay", "on credit card": the thing after "on" is how it was paid, not what for
    private val payStarts = setOf(
        "gpay", "phonepe", "paytm", "google", "phone", "bhim", "upi", "cash", "credit", "debit",
        "card", "net", "bank", "neft", "imps", "rtgs"
    )
    private val notAName = setOf("i", "we", "he", "she", "they", "you", "it", "someone", "who", "somebody", "everyone")

    private fun phraseAfter(tokens: List<Tok>, start: Int, max: Int, joinAnd: Boolean = false): List<Tok> {
        var i = start
        while (i < tokens.size && tokens[i].low in skipWords) i++
        val out = ArrayList<Tok>()
        while (i < tokens.size && out.size < max) {
            val t = tokens[i]
            if (joinAnd && t.low == "and" && out.isNotEmpty()) {
                val n = tokens.getOrNull(i + 1)
                if (n != null && n.num == null && n.low !in stopWords && n.low !in skipWords) {
                    out.add(t); i++
                    continue
                }
            }
            if (t.low in stopWords || t.num != null || t.low in monthNames || t.low in weekdays ||
                t.low.removeOrdinal().toIntOrNull() != null ||
                (out.isNotEmpty() && t.low in skipWords)   // "Ramesh the day before yesterday"
            ) break
            out.add(t); i++
        }
        // never end on a dangling "and"
        while (out.isNotEmpty() && out.last().low == "and") out.removeAt(out.lastIndex)
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

        // 1b. "Rahul sent me 200", "Priya paid me 500"
        if (party.isEmpty() && type == "INCOME") {
            for (i in 1 until tokens.size - 1) {
                if (tokens[i].low in toMeVerbs && tokens[i + 1].low in setOf("me", "us")) {
                    val subject = tokens[i - 1]
                    if (subject.low !in notAName && subject.low !in stopWords && subject.num == null) {
                        party = listOf(subject); partyAt = i + 2
                    }
                    break
                }
            }
        }

        // 1c. "paid Rahul 500 for groceries", "gave Mom 2000"
        if (party.isEmpty() && type == "EXPENSE") {
            val giveVerbs = setOf("paid", "pay", "gave", "give", "sent", "send", "transferred")
            for (i in tokens.indices) {
                if (tokens[i].low in giveVerbs) {
                    val p = phraseAfter(tokens, i + 1, 3)
                    if (p.isNotEmpty()) { party = p; partyAt = i + 1 + p.size }
                    break
                }
            }
        }

        // The purpose: "for groceries", "on lunch"
        fun purpose(from: Int): List<Tok> {
            for (i in from until tokens.size) {
                if (tokens[i].low == "for" || tokens[i].low == "on") {
                    if (tokens.getOrNull(i + 1)?.low in payStarts) continue
                    val p = phraseAfter(tokens, i + 1, 5)
                    if (p.isNotEmpty()) return p
                }
            }
            return emptyList()
        }

        if (party.isNotEmpty()) {
            val why = purpose(maxOf(partyAt, 0)).ifEmpty { purpose(0) }
            return Pair(neat(party), neat(why))
        }

        // 2. No other party: the thing bought / spent on ("bought vegetables", "spent 300 on lunch")
        val buyAt = tokens.indexOfFirst { it.low in buyVerbs }
        if (buyAt >= 0) {
            // "bought 2 kg of onions" -> onions
            var st = buyAt + 1
            if (tokens.getOrNull(st)?.num != null) {
                st++
                if (tokens.getOrNull(st)?.low in measureUnits) st++
                if (tokens.getOrNull(st)?.low == "of") st++
            }
            val item = phraseAfter(tokens, st, 4, joinAnd = true)
            if (item.isNotEmpty()) return Pair(neat(item), null)
        }
        val why = purpose(0)
        if (why.isNotEmpty()) return Pair(neat(why), null)

        if (tokens.any { it.low == "withdrew" || it.low == "withdrawn" || it.low == "withdraw" }) {
            return Pair("Cash withdrawal", null)
        }

        // 3. Well-known income words
        val known = tokens.firstOrNull { it.low in setOf("salary", "refund", "refunded", "cashback", "interest", "rent") }
        if (known != null) {
            return Pair(if (known.low == "refunded") "Refund" else known.low.replaceFirstChar { it.uppercase() }, null)
        }

        // 4. Short notes with no verb: "petrol 400 rupees cash", "400 rupees chai"
        val amountAt = findAmount(tokens, true)?.second ?: -1
        if (amountAt >= 0) {
            for (s in 0 until amountAt) {
                val before = phraseAfter(tokens, s, 3)
                if (before.isNotEmpty()) return Pair(neat(before), null)
            }
            var k = amountAt + 1
            while (k < tokens.size && tokens[k].low in currencyWords) k++
            val after = phraseAfter(tokens, k, 3)
            if (after.isNotEmpty()) return Pair(neat(after), null)
        }
        return Pair(null, null)
    }
}

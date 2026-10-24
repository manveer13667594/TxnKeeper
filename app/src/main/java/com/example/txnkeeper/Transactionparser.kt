package com.example.txnkeeper

import java.util.Calendar
import java.util.Locale

/** One line of recognized text with its on-screen height (bigger text = more important). */
data class OcrLine(val text: String, val height: Int)

/** Everything is nullable: a field the parser isn't sure about stays null and the form keeps its value. */
data class ParsedTransaction(
    val title: String? = null,
    val amount: Double? = null,
    val type: String? = null,           // "EXPENSE" or "INCOME"
    val paymentMethod: String? = null,  // "CASH", "UPI", "CARD" or "BANK TRANSFER"
    val dateMillis: Long? = null,
    val description: String? = null
) {
    val isEmpty: Boolean
        get() = title == null && amount == null && type == null &&
                paymentMethod == null && dateMillis == null && description == null
}

/** Offline, rule-based parser for payment screenshots, receipts and bank messages. */
object TransactionParser {

    fun parse(lines: List<OcrLine>): ParsedTransaction {
        val clean = lines
            .map { OcrLine(it.text.trim(), it.height) }
            .filter { it.text.isNotEmpty() }
        if (clean.isEmpty()) return ParsedTransaction()

        val fullText = clean.joinToString("\n") { it.text }
        val lower = fullText.lowercase(Locale.ROOT)

        val type = findType(clean, lower)
        return ParsedTransaction(
            title = findTitle(clean, type),
            amount = findAmount(clean),
            type = type,
            paymentMethod = findPaymentMethod(lower),
            dateMillis = findDate(fullText),
            description = findReference(fullText)
        )
    }

    // ---------------------------------------------------------------- amount

    private val currencyAmount = Regex(
        """(?:₹|rs\.?|inr|rupees?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // A line that is only a number, maybe with a misread currency symbol in front
    private val bareAmountLine = Regex("""^\s*[^\d\s]{0,2}\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*$""")

    private val amountWords = listOf(
        "paid", "amount", "total", "debited", "credited", "sent", "received",
        "payment", "paying", "transferred", "spent", "purchase", "payable"
    )
    private val ignoreWords = Regex(
        """\b(balance|bal|avl|available|limit|cashback|reward|offer|discount|saved|saving|mrp)\b"""
    )

    // Words that mark the final payable figure on bills ("Net Amount 2622.00")
    private val strongAmountWords = listOf(
        "net amount", "grand total", "total amount", "net payable",
        "amount payable", "amount paid", "total paid", "net total"
    )

    private fun toAmount(raw: String): Double? {
        val value = raw.replace(",", "").toDoubleOrNull() ?: return null
        return if (value > 0 && value < 1_000_000_000) value else null
    }

    private fun findAmount(lines: List<OcrLine>): Double? {
        val maxHeight = lines.maxOf { it.height }.coerceAtLeast(1)
        var best: Double? = null
        var bestScore = 3.5   // anything weaker than this is not trusted

        lines.forEachIndexed { i, line ->
            val lowerLine = line.text.lowercase(Locale.ROOT)
            if (ignoreWords.containsMatchIn(lowerLine)) return@forEachIndexed

            val nearby = listOfNotNull(
                lines.getOrNull(i - 1)?.text, line.text, lines.getOrNull(i + 1)?.text
            ).joinToString(" ").lowercase(Locale.ROOT)
            val keywordBonus =
                (if (amountWords.any { nearby.contains(it) }) 2.0 else 0.0) +
                        (if (strongAmountWords.any { nearby.contains(it) }) 4.0 else 0.0)
            val sizeBonus = 3.0 * line.height / maxHeight

            // Candidates with a currency marker
            currencyAmount.findAll(line.text).forEach { match ->
                val value = toAmount(match.groupValues[1]) ?: return@forEach
                val score = 4.0 + keywordBonus + sizeBonus
                if (score > bestScore) { bestScore = score; best = value }
            }

            // Candidates that are just a big number on their own line
            bareAmountLine.find(line.text)?.let { match ->
                val value = toAmount(match.groupValues[1]) ?: return@let
                val score = 1.0 + keywordBonus + sizeBonus
                if (score > bestScore) { bestScore = score; best = value }
            }

            // "Net Amount 2622.00" with the number at the end of the same line
            if (strongAmountWords.any { lowerLine.contains(it) }) {
                Regex("""([0-9][0-9,]*\.[0-9]{1,2})\s*$""").find(line.text)?.let { match ->
                    val value = toAmount(match.groupValues[1]) ?: return@let
                    val score = 5.0 + keywordBonus + sizeBonus
                    if (score > bestScore) { bestScore = score; best = value }
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------ type

    private val expenseRegex = Regex(
        """\b(paid(?: to)?|payment to|debited|debit|sent to|transferred to|purchase|spent|withdrawn|withdrawal|dr)\b"""
    )
    private val incomeRegex = Regex(
        """\b(received(?: from)?|credited|credit|deposited|refund(?:ed)?|cr)\b"""
    )

    private val headerDirection = Regex("""(?i)^\s*(to|from)\s+(?!:)\S""")
    private val outgoingHints = Regex("""requested money|payment of .* completed|autopay""")
    private val billHints = Regex("""\b(invoice|counter sale|bill no|receipt)\b""")
    private val firstToFor = Regex("""(?i)^\s*(to|for)\s*:""")
    private val firstFrom = Regex("""(?i)^\s*from\s*:""")

    private fun findType(lines: List<OcrLine>, lower: String): String? {
        // 1. Google Pay header near the top: "To NAME" = paid, "From NAME" = received
        lines.take(4).forEach { line ->
            headerDirection.find(line.text)?.let {
                return if (it.groupValues[1].equals("to", ignoreCase = true)) "EXPENSE" else "INCOME"
            }
        }
        // 2. Hints that only appear on payments you made
        if (outgoingHints.containsMatchIn(lower)) return "EXPENSE"

        // 3. Direction words. "credit card" / "debit card" describe the method, not the direction
        val text = lower.replace("credit card", "card").replace("debit card", "card")
        val expense = expenseRegex.findAll(text).count()
        val income = incomeRegex.findAll(text).count()
        if (expense > income) return "EXPENSE"
        if (income > expense) return "INCOME"

        // 4. Paytm style: whichever of "To:/For:" or "From:" comes first
        for (line in lines) {
            if (firstToFor.containsMatchIn(line.text)) return "EXPENSE"
            if (firstFrom.containsMatchIn(line.text)) return "INCOME"
        }

        // 5. Shop bills are purchases
        return if (billHints.containsMatchIn(lower)) "EXPENSE" else null
    }

    // -------------------------------------------------------- payment method

    private val upiRegex = Regex("""\b(upi|gpay|google pay|phonepe|paytm|bhim|amazon pay)\b""")
    private val vpaRegex = Regex("""[\w.\-]{2,}@[a-z]{2,}""")
    private val bankRegex = Regex("""\b(neft|imps|rtgs|bank transfer|net banking|netbanking)\b""")
    private val cardRegex = Regex("""\b(card|visa|mastercard|rupay|pos)\b|x{2,}\s?\d{4}""")
    private val cashRegex = Regex("""\bcash\b""")

    private fun findPaymentMethod(lower: String): String? = when {
        upiRegex.containsMatchIn(lower) || vpaRegex.containsMatchIn(lower) -> "UPI"
        bankRegex.containsMatchIn(lower) -> "BANK TRANSFER"
        cardRegex.containsMatchIn(lower) -> "CARD"
        cashRegex.containsMatchIn(lower) -> "CASH"
        else -> null
    }

    // ------------------------------------------------------------------ date

    private val months = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )

    private val numericDate = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})\b""")
    private val dayMonthYear = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?[\s\-,]+([A-Za-z]{3,9})\.?[\s\-,]+(\d{4}|\d{2})\b"""
    )
    private val monthDayYear = Regex(
        """\b([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b"""
    )
    private val isoDate = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    private fun monthIndex(name: String): Int? {
        val i = months.indexOf(name.take(3).lowercase(Locale.ROOT))
        return if (i >= 0) i + 1 else null
    }

    /** Local noon of the given day, so the app's date picker shows the same calendar day. */
    private fun buildDate(day: Int, month: Int, year: Int): Long? {
        val y = if (year < 100) 2000 + year else year
        if (y !in 2000..2100 || month !in 1..12 || day !in 1..31) return null
        return try {
            val cal = Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(y, month - 1, day, 12, 0, 0)
            }
            val millis = cal.timeInMillis
            // A receipt can't be from the future
            if (millis > System.currentTimeMillis() + 86_400_000L) null else millis
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun findDate(text: String): Long? {
        // Indian formats are day first: 16/10/2026
        numericDate.findAll(text).forEach { m ->
            buildDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                ?.let { return it }
        }
        dayMonthYear.findAll(text).forEach { m ->
            val month = monthIndex(m.groupValues[2]) ?: return@forEach
            buildDate(m.groupValues[1].toInt(), month, m.groupValues[3].toInt())?.let { return it }
        }
        monthDayYear.findAll(text).forEach { m ->
            val month = monthIndex(m.groupValues[1]) ?: return@forEach
            buildDate(m.groupValues[2].toInt(), month, m.groupValues[3].toInt())?.let { return it }
        }
        isoDate.findAll(text).forEach { m ->
            buildDate(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                ?.let { return it }
        }
        return null
    }

    // ----------------------------------------------------------------- title

    // Phrases that introduce the other party: "paid to X" / "received from X"
    private val toKeyword = Regex(
        """(?i)\b(?:paid to|payment to|sent to|transferred to|debited to|paid at|spent at|paying|merchant|payee|beneficiary)\b\s*[:\-]?\s*(.*)$"""
    )
    private val fromKeyword = Regex(
        """(?i)\b(?:received from|credited by)\b\s*[:\-]?\s*(.*)$"""
    )
    // Labels at the start of a line: "To: X", "For: X", "To X", "From: X", "From X"
    private val toLabel = Regex("""(?i)^\s*(?:to|for)\b\s*:?\s*(.*)$""")
    private val fromLabel = Regex("""(?i)^\s*from\b\s*:?\s*(.*)$""")

    private fun cleanTitle(raw: String): String? {
        var s = raw
            .replace(currencyAmount, " ")
            .replace(Regex("""\S+@\S+"""), " ")      // UPI ids
            .replace(Regex("""\d{6,}"""), " ")        // phone numbers, references
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ':', '-', ',', '.', '|')
        if (s.length < 2 || s.none { it.isLetter() }) return null
        if (s.length > 50) s = s.take(50).trim()
        // ALL CAPS names read better as Title Case
        if (s == s.uppercase(Locale.ROOT)) {
            s = s.lowercase(Locale.ROOT).split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
        }
        return s
    }

    /** The name after a label: same line, next line, or wrapped onto a second line in the same size. */
    private fun nameAfterLabel(lines: List<OcrLine>, index: Int, rest: String): String? {
        if (rest.isBlank()) return cleanTitle(lines.getOrNull(index + 1)?.text.orEmpty())
        var name = rest
        val base = lines[index].height
        for (j in index + 1..index + 2) {
            val next = lines.getOrNull(j) ?: break
            val sameSize = base > 0 && kotlin.math.abs(next.height - base) <= base * 0.25
            val looksLikeName = !next.text.contains(Regex("""[:@]|\d{4}""")) &&
                    !next.text.contains(Regex("""(?i)^\s*(upi|from|to|for|ref|order)\b"""))
            if (sameSize && looksLikeName) name += " " + next.text else break
        }
        return cleanTitle(name)
    }

    /** Picks the other party: the "To" name for money you paid, the "From" name for money you got. */
    private fun findTitle(lines: List<OcrLine>, type: String?): String? {
        var toName: String? = null
        var fromName: String? = null
        lines.forEachIndexed { i, line ->
            if (toName == null) {
                val rest = toKeyword.find(line.text)?.groupValues?.get(1)
                    ?: toLabel.find(line.text)?.groupValues?.get(1)
                if (rest != null) toName = nameAfterLabel(lines, i, rest)
            }
            if (fromName == null) {
                val rest = fromKeyword.find(line.text)?.groupValues?.get(1)
                    ?: fromLabel.find(line.text)?.groupValues?.get(1)
                if (rest != null) fromName = nameAfterLabel(lines, i, rest)
            }
        }
        return when (type) {
            "INCOME" -> fromName ?: toName
            else -> toName ?: fromName
        }
    }

    // ------------------------------------------------------------- reference

    private val referenceRegex = Regex(
        """(?i)\b(?:upi\s*(?:ref(?:erence)?|transaction)\s*(?:no|id)?|transaction\s*id|txn\s*id|utr(?:\s*no)?|ref(?:erence)?\s*(?:no|id|number)?)\b\.?\s*[:#\-]?\s*([A-Za-z0-9]{8,})"""
    )

    private fun findReference(text: String): String? =
        referenceRegex.find(text)?.groupValues?.get(1)?.let { "Ref: $it" }
}
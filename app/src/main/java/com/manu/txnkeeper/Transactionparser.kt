package com.manu.txnkeeper

import java.util.Calendar
import java.util.Locale

/**
 * One line of recognized text with its on-screen height (bigger text = more important).
 * top / bottom / left are the line's position in the photo; they are 0 when unknown and only
 * ReceiptRows uses them (to put a label and its figure back on one row).
 */
data class OcrLine(
    val text: String,
    val height: Int,
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0
)

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
            title = findTitle(clean, type) ?: findShopName(clean, lower),
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

    // A line that ends with "Avl Bal Rs": the number on the next line is the balance
    private val balanceTail = Regex("""(?:balance|bal|avl|available|limit)\W*(?:rs|inr|₹)?\W*$""")

    // A line that ends with "Rs" / "INR": the number is on the next line
    private val currencyTail = Regex("""(?:₹|\brs\.?|\binr|\brupees?)\s*$""")

    // Words that mark the final payable figure on bills ("Net Amount 2622.00")
    private val strongAmountWords = listOf(
        "net amount", "grand total", "total amount", "net payable",
        "amount payable", "amount paid", "total paid", "net total",
        // other bill layouts: restaurant, fare, invoice and utility receipts
        "total due", "amount due", "balance due", "bill amount", "total fare", "total bill",
        "invoice total", "invoice value", "amount to pay", "total payable", "grand amount"
    )

    // "TOTAL 672.00", "Total $11.63", "AMOUNT: 1250", "Total Rs. 540/-": a total label with its figure
    // at the end of the line. A weak rule (see findAmount), so bills that already worked are unaffected.
    private val totalLabelFigure = Regex(
        """(?i)\b(?:grand\s*total|total(?:\s*(?:amount|due|payable|bill|fare|invoice\s*(?:value|amount)))?|amount(?:\s*(?:due|payable|to\s*pay))?|bill\s*amount|net\s*(?:amount|payable|total)|balance\s*due|invoice\s*(?:total|value)|sale\s*amount|you\s*pay)\b\s*[:\-=]?\s*(?:₹|rs\.?|inr|usd|rm|[$€£?])?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:/-|only)?\s*$"""
    )

    // Lines that look like a total but are not the amount to pay
    private val notATotal = Regex(
        """\b(sub\s*-?\s*total|qty|quantity|items?|savings?|saved|discount|taxable|tax|gst|cgst|sgst|igst|vat|cess|round|rounding|tendered|change|tip|gratuity|service charge|points|weight|volume|rate|mrp|pcs|nos)\b"""
    )

    private val endsWithDecimal = Regex("""([0-9][0-9,]*\.[0-9]{1,2})\s*$""")

    private fun toAmount(raw: String): Double? {
        val value = raw.replace(",", "").toDoubleOrNull() ?: return null
        return if (value > 0 && value < 1_000_000_000) value else null
    }

    private fun findAmount(lines: List<OcrLine>): Double? = findAmountScored(lines)?.value

    /** How strongly the lines back up the amount (higher = more sure); null if there is no amount. */
    fun amountScore(lines: List<OcrLine>): Double? {
        val clean = lines.map { OcrLine(it.text.trim(), it.height) }.filter { it.text.isNotEmpty() }
        return if (clean.isEmpty()) null else findAmountScored(clean)?.score
    }

    private class AmountGuess(val value: Double, val score: Double)

    private fun findAmountScored(lines: List<OcrLine>): AmountGuess? {
        val maxHeight = lines.maxOf { it.height }.coerceAtLeast(1)
        var best: Double? = null
        var bestScore = 3.5   // anything weaker than this is not trusted

        lines.forEachIndexed { i, line ->
            val lowerLine = line.text.lowercase(Locale.ROOT)
            // SMS put the amount and the balance on one line: "Rs 500 debited ... Avl Bal Rs 9000".
            // Only look at the part before the first balance / limit word.
            // "Balance Due" on a bill is the amount to pay, not an account balance (same length, so the
            // positions still line up with the original text).
            val cut = ignoreWords.find(lowerLine.replace("balance due", "xxxxxxx due"))?.range?.first
            if (cut == 0) return@forEachIndexed
            val scan = if (cut == null) line.text else line.text.substring(0, cut)
            // A wrapped line: "Avl Bal Rs" / "20632.17"
            val prevEndsInBalance = lines.getOrNull(i - 1)?.text?.lowercase(Locale.ROOT)
                ?.let { balanceTail.containsMatchIn(it) } == true
            // A wrapped line: "credited with Rs" / "2,622.00 on 18/09/2026"
            val currencyFromAbove = !prevEndsInBalance && lines.getOrNull(i - 1)?.text?.lowercase(Locale.ROOT)
                ?.let { currencyTail.containsMatchIn(it) } == true
            val withCurrencyFromAbove = if (currencyFromAbove) "Rs " + scan else scan

            val nearby = listOfNotNull(
                lines.getOrNull(i - 1)?.text, line.text, lines.getOrNull(i + 1)?.text
            ).joinToString(" ").lowercase(Locale.ROOT)
            val keywordBonus =
                (if (amountWords.any { nearby.contains(it) }) 2.0 else 0.0) +
                        (if (strongAmountWords.any { nearby.contains(it) }) 4.0 else 0.0)
            val sizeBonus = 3.0 * line.height / maxHeight
            // The label sits on this very line ("Total Fare Rs 768.60"), so it beats a fee line next to it
            val ownBonus = if (strongAmountWords.any { lowerLine.contains(it) }) 1.5 else 0.0

            // Candidates with a currency marker
            currencyAmount.findAll(withCurrencyFromAbove).forEach { match ->
                val value = toAmount(match.groupValues[1]) ?: return@forEach
                val score = 4.0 + keywordBonus + sizeBonus + ownBonus
                if (score > bestScore) { bestScore = score; best = value }
            }

            // Candidates that are just a big number on their own line
            (if (prevEndsInBalance) null else bareAmountLine.find(scan))?.let { match ->
                val value = toAmount(match.groupValues[1]) ?: return@let
                val score = 1.0 + keywordBonus + sizeBonus
                if (score > bestScore) { bestScore = score; best = value }
            }

            // "Net Amount 2622.00" with the number at the end of the same line
            if (strongAmountWords.any { lowerLine.contains(it) }) {
                endsWithDecimal.find(line.text)?.let { match ->
                    val value = toAmount(match.groupValues[1]) ?: return@let
                    val score = 5.0 + keywordBonus + sizeBonus
                    if (score > bestScore) { bestScore = score; best = value }
                }
            }

            // "TOTAL 672.00" / "Total Amount: 450" with no currency sign. Scored low on purpose: it can
            // only win on bills where none of the rules above found anything better.
            if (!notATotal.containsMatchIn(lowerLine)) {
                totalLabelFigure.find(scan)?.let { match ->
                    val value = toAmount(match.groupValues[1]) ?: return@let
                    val score = 1.5 + keywordBonus + sizeBonus
                    if (score > bestScore) { bestScore = score; best = value }
                }
            }
        }
        return best?.let { AmountGuess(it, bestScore) }
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
    private val billHints = Regex(
        """\b(invoice|counter sale|bill no|receipt|cash memo|gstin|cgst|sgst|igst|sub\s*-?total|grand total|net payable|amount due|total due|balance due|bill amount)\b"""
    )
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
        val text = lower.replace(Regex("""(credit|debit)\s+card"""), "card")
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

    private val upiRegex = Regex("""\b(upi|gpay|google pay|phonepe|paytm|bhim|amazon pay|bharatpe|mobikwik|freecharge)\b""")
    private val vpaRegex = Regex("""[\w.\-]{2,}@[a-z]{2,}""")
    private val bankRegex = Regex("""\b(neft|imps|rtgs|bank transfer|net banking|netbanking)\b""")
    private val cardRegex = Regex("""\b(card|visa|mastercard|rupay|pos|amex|american express|maestro|diners)\b|(?<!a/c\s)(?<!ac\s)(?<!acct\s)(?:x{2,}|\*{2,})\s?\d{4}""")
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
    private val isoDate = Regex("""\b(\d{4})[-/.](\d{2})[-/.](\d{2})\b""")

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

    /** Like findAll, but a failed match doesn't swallow the start of the real date ("99 Completed 25 Jul 2026"). */
    private fun Regex.allMatches(text: String): Sequence<MatchResult> =
        generateSequence(find(text)) { find(text, it.range.first + 1) }

    private fun findDate(text: String): Long? {
        // Indian formats are day first: 16/10/2026
        numericDate.allMatches(text).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            buildDate(a, b, year)?.let { return it }
            // 10/25/2026 can only be month first (there is no 25th month), as US receipts print it
            if (b > 12 && a in 1..12) buildDate(b, a, year)?.let { return it }
        }
        dayMonthYear.allMatches(text).forEach { m ->
            val month = monthIndex(m.groupValues[2]) ?: return@forEach
            buildDate(m.groupValues[1].toInt(), month, m.groupValues[3].toInt())?.let { return it }
        }
        monthDayYear.allMatches(text).forEach { m ->
            val month = monthIndex(m.groupValues[1]) ?: return@forEach
            buildDate(m.groupValues[2].toInt(), month, m.groupValues[3].toInt())?.let { return it }
        }
        isoDate.allMatches(text).forEach { m ->
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

    // ------------------------------------------------------- shop name (bills)

    // Only bills and invoices carry the shop's name at the top; payment screenshots don't
    private val shopBillHints = Regex(
        """\b(invoice|bill no|receipt|cash memo|gstin|cgst|sgst|igst|sub\s*-?total|grand total|net payable|net amount|amount due|total due|bill amount|counter sale)\b"""
    )
    private val notAShopName = Regex(
        """(?i)\b(tax invoice|invoice|receipt|cash memo|cash bill|bill|gstin|gst|vat|welcome|thank|thanks|customer copy|duplicate|original|tel|phone|ph|mob|mobile|date|time|order|guest|folio|e-ticket|ticket|copy)\b"""
    )

    private fun looksLikeShopName(s: String): Boolean {
        if (s.length !in 3..40 || s.contains('@') || s.contains(':')) return false
        val letters = s.count { it.isLetter() }
        // mostly letters: "Store #1234" or "Rice 5kg 300/-" are not names
        if (letters < 3 || letters < s.count { !it.isWhitespace() } * 0.7) return false
        return !notAShopName.containsMatchIn(s)
    }

    /** The shop's name on a bill: the biggest text in the first few lines (the first one if sizes tie). */
    private fun findShopName(lines: List<OcrLine>, lower: String): String? {
        if (!shopBillHints.containsMatchIn(lower)) return null
        val best = lines.take(6).withIndex()
            .filter { looksLikeShopName(it.value.text) }
            .maxWithOrNull(compareBy({ it.value.height }, { -it.index }))
        return best?.let { cleanTitle(it.value.text) }
    }

    // ------------------------------------------------------------- reference

    private val referenceRegex = Regex(
        """(?i)\b(?:upi\s*(?:ref(?:erence)?|transaction)\s*(?:no|id)?|transaction\s*id|txn\s*id|utr(?:\s*no)?|ref(?:erence)?\s*(?:no|id|number)?)\b\.?\s*[:#\-]?\s*([A-Za-z0-9]{8,})"""
    )

    private fun findReference(text: String): String? =
        referenceRegex.find(text)?.groupValues?.get(1)?.let { "Ref: $it" }
}

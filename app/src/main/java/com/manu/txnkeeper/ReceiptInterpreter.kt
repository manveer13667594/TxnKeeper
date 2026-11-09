package com.manu.txnkeeper

/**
 * Extra reading passes on top of the normal scan. Every helper here only FILLS fields the first
 * reading left empty; a value that was already found is never replaced, so receipts that scan
 * correctly today give exactly the same result.
 */
object ReceiptInterpreter {

    /** The same steps a scan always runs: fix OCR slips, parse, and use the amount in words if figures gave none. */
    fun interpretLines(lines: List<OcrLine>): ParsedTransaction {
        val fixed = OcrCorrector.correct(lines)
        val base = TransactionParser.parse(fixed.lines)
        return if (base.amount == null && fixed.wordsAmount != null)
            base.copy(amount = fixed.wordsAmount.toDouble()) else base
    }

    /** [base] with every empty field taken from [extra]. */
    fun fillGaps(base: ParsedTransaction, extra: ParsedTransaction): ParsedTransaction = ParsedTransaction(
        title = base.title ?: extra.title,
        amount = base.amount ?: extra.amount,
        type = base.type ?: extra.type,
        paymentMethod = base.paymentMethod ?: extra.paymentMethod,
        dateMillis = base.dateMillis ?: extra.dateMillis,
        description = base.description ?: extra.description
    )

    /** The row reading's amount must beat the first one's evidence by this much to replace it (a tie keeps the first). */
    private const val AMOUNT_MARGIN = 0.25

    /**
     * Re-reads the same lines with labels and figures that share a row joined ("TOTAL" + "672.00"),
     * and fills what the first reading missed. Does nothing when no lines share a row.
     *
     * One exception to "only fill": OCR that returns the labels in one column and the figures in another
     * can pair "TOTAL" with the wrong figure. If both readings found an amount and the row reading is
     * clearly better supported (it saw the figure beside its label), its amount is used.
     */
    fun fillFromRows(first: ParsedTransaction, lines: List<OcrLine>): ParsedTransaction {
        val rows = ReceiptRows.merge(lines)
        if (rows === lines) return first
        val fromRows = interpretLines(rows)
        val filled = fillGaps(first, fromRows)
        if (first.amount != null && fromRows.amount != null && first.amount != fromRows.amount) {
            val before = TransactionParser.amountScore(OcrCorrector.correct(lines).lines)
            val after = TransactionParser.amountScore(OcrCorrector.correct(rows).lines)
            if (before != null && after != null && after > before + AMOUNT_MARGIN) {
                return filled.copy(amount = fromRows.amount)
            }
        }
        return filled
    }

    /** A second, independent reading (e.g. of a cleaned-up copy of the photo) read the same way. */
    fun interpret(lines: List<OcrLine>): ParsedTransaction = fillFromRows(interpretLines(lines), lines)
}

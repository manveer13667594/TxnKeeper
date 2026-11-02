package com.example.txnkeeper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import java.io.OutputStream

/**
 * Writes transactions as an A4 PDF using Android's built-in PdfDocument (no extra library):
 * a title, a small income / expense / net summary, then a table that continues over as many
 * pages as needed (the header row repeats on every page).
 *
 * The description is left out to keep rows to one line; the CSV export has everything.
 */
object TxnPdf {

    private const val PAGE_W = 595          // A4 in points (1 pt = 1/72 inch)
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val ROW_H = 20f
    private const val FOOTER_H = 28f

    // Column left edges; the amount column is right-aligned to RIGHT_EDGE
    private const val X_DATE = MARGIN
    private const val X_TITLE = MARGIN + 105f
    private const val X_ACCOUNT = MARGIN + 105f + 175f
    private const val X_METHOD = MARGIN + 105f + 175f + 75f
    private const val RIGHT_EDGE = PAGE_W - MARGIN
    private const val CELL_PAD = 3f

    private const val INK = 0xFF1C1B1F.toInt()
    private const val MUTED = 0xFF625B71.toInt()
    private const val GREEN = 0xFF1B7F3B.toInt()
    private const val RED = 0xFFB3261E.toInt()
    private const val RULE = 0xFFCAC4D0.toInt()

    private fun text(size: Float, color: Int, bold: Boolean = false, right: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (right) textAlign = Paint.Align.RIGHT
        }

    /**
     * Writes the PDF to [out]. [generatedAt] is shown under the title, followed by [scopeLabel]
     * (for example the date range) when the PDF holds only some of the transactions.
     */
    fun write(
        rows: List<TxnRow>,
        out: OutputStream,
        generatedAt: Long = System.currentTimeMillis(),
        scopeLabel: String? = null
    ) {
        val titlePaint = text(18f, INK, bold = true)
        val metaPaint = text(9f, MUTED)
        val summaryPaint = text(10.5f, INK, bold = true)
        val headPaint = text(8.5f, MUTED, bold = true)
        val headRight = text(8.5f, MUTED, bold = true, right = true)
        val cellPaint = text(8.5f, INK)
        val mutedCell = text(8.5f, MUTED)
        val incomeRight = text(8.5f, GREEN, bold = true, right = true)
        val expenseRight = text(8.5f, RED, bold = true, right = true)
        val footerPaint = text(8f, MUTED, right = true)
        val rulePaint = Paint().apply { color = RULE; strokeWidth = 0.6f }

        val income = rows.filter { it.type == "INCOME" }.sumOf { it.amount }
        val expense = rows.filter { it.type == "EXPENSE" }.sumOf { it.amount }

        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var pageNumber = 0
        var y = MARGIN

        fun finishPage() {
            val p = page ?: return
            canvas?.drawText("Page $pageNumber", RIGHT_EDGE, PAGE_H - MARGIN + 6f, footerPaint)
            doc.finishPage(p)
            page = null
        }

        fun drawTableHeader() {
            val c = canvas ?: return
            val base = y + 12f
            c.drawText("DATE", X_DATE, base, headPaint)
            c.drawText("TITLE", X_TITLE, base, headPaint)
            c.drawText("ACCOUNT", X_ACCOUNT, base, headPaint)
            c.drawText("METHOD", X_METHOD, base, headPaint)
            c.drawText("AMOUNT", RIGHT_EDGE, base, headRight)
            c.drawLine(MARGIN, y + ROW_H, RIGHT_EDGE, y + ROW_H, rulePaint)
            y += ROW_H
        }

        fun startPage(withTableHeader: Boolean) {
            finishPage()
            pageNumber++
            val newPage = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            page = newPage
            canvas = newPage.canvas
            y = MARGIN
            if (withTableHeader) drawTableHeader()
        }

        fun fit(value: String, paint: TextPaint, width: Float): String =
            TextUtils.ellipsize(value.replace(Regex("\\s+"), " "), paint, width - CELL_PAD * 2, TextUtils.TruncateAt.END)
                .toString()

        try {
            // ---------------- first page: title and summary
            startPage(withTableHeader = false)
            canvas!!.drawText("TxnKeeper transactions", MARGIN, y + 18f, titlePaint)
            y += 30f
            canvas!!.drawText(
                "Generated ${formatDateLong(generatedAt)}, ${formatTime(generatedAt)}  ·  ${rows.size} transactions" +
                        (scopeLabel?.let { "  ·  $it" } ?: ""),
                MARGIN, y + 9f, metaPaint
            )
            y += 24f
            canvas!!.drawText(
                "Income ${formatMoney(income)}     Expense ${formatMoney(expense)}     Net ${formatBalance(income - expense)}",
                MARGIN, y + 11f, summaryPaint
            )
            y += 30f
            drawTableHeader()

            if (rows.isEmpty()) {
                canvas!!.drawText("No transactions.", MARGIN, y + 14f, mutedCell)
            }

            // ---------------- table rows
            for (r in rows) {
                if (y + ROW_H > PAGE_H - MARGIN - FOOTER_H) startPage(withTableHeader = true)
                val c = canvas!!
                val base = y + 13.5f
                val isIncome = r.type == "INCOME"

                c.drawText(fit("${formatDateLong(r.dateTime)}, ${formatTime(r.dateTime)}", cellPaint, 105f), X_DATE, base, cellPaint)
                c.drawText(fit(r.title, cellPaint, 175f), X_TITLE, base, cellPaint)
                c.drawText(fit(r.accountName, mutedCell, 75f), X_ACCOUNT, base, mutedCell)
                c.drawText(fit(r.paymentMethod, mutedCell, 80f), X_METHOD, base, mutedCell)
                c.drawText(
                    formatSignedMoney(if (isIncome) r.amount else -r.amount),
                    RIGHT_EDGE, base, if (isIncome) incomeRight else expenseRight
                )
                c.drawLine(MARGIN, y + ROW_H, RIGHT_EDGE, y + ROW_H, rulePaint)
                y += ROW_H
            }

            finishPage()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
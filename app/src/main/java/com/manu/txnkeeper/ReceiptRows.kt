package com.manu.txnkeeper

/**
 * Puts text that sits on the same row of the photo back on one line.
 *
 * OCR often reads a receipt in columns: the labels come first ("Sub Total", "CGST", "TOTAL") and the
 * figures after them ("640.00", "16.00", "672.00"). The parser needs "TOTAL 672.00" side by side, so
 * lines whose vertical positions overlap are joined left to right.
 *
 * Needs the position of every line (OcrLine.top / bottom). With unknown positions, or when nothing shares
 * a row, the same list comes back unchanged.
 */
object ReceiptRows {

    fun merge(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2 || lines.any { it.bottom <= it.top }) return lines

        val rows = ArrayList<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.top + it.bottom }) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row[0], line)) row += line else rows += mutableListOf(line)
        }
        if (rows.all { it.size == 1 }) return lines

        return rows.map { row ->
            if (row.size == 1) row[0] else {
                val byLeft = row.sortedBy { it.left }
                OcrLine(
                    text = byLeft.joinToString(" ") { it.text.trim() },
                    height = row.maxOf { it.height },
                    top = row.minOf { it.top },
                    bottom = row.maxOf { it.bottom },
                    left = byLeft[0].left
                )
            }
        }
    }

    /** Same row = the two lines overlap vertically by at least half of the shorter one. */
    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        val shorter = minOf(a.bottom - a.top, b.bottom - b.top)
        return overlap > 0 && overlap * 2 >= shorter
    }
}

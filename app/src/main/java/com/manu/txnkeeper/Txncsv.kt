package com.manu.txnkeeper

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One transaction as it appears in a CSV / PDF file. It has no database ids: the account is
 * written by name, so a file can be imported on another phone (or after a reinstall).
 */
data class TxnRow(
    val dateTime: Long,
    val title: String,
    val amountPaise: Long,       // whole paise: 1250 = 12.50
    val type: String,            // "INCOME" or "EXPENSE"
    val paymentMethod: String,   // one of PAYMENT_METHODS
    val accountName: String,
    val description: String
)

/** A CSV row that could not be turned into a transaction. [row] counts the header as row 1. */
data class CsvRowError(val row: Int, val reason: String)

/** What reading a CSV file produced. If [fileError] is set the whole file is unusable. */
data class CsvReadResult(
    val rows: List<TxnRow> = emptyList(),
    val errors: List<CsvRowError> = emptyList(),
    val fileError: String? = null
)

/**
 * CSV writing and reading, with no Android or database code so it can be unit-tested.
 *
 * File format (header row, then one transaction per row):
 *   Date,Title,Amount,Type,Payment Method,Account,Description
 *   2026-11-02 12:15:00,Chai,20,EXPENSE,UPI,Main account,
 *
 * - Written as UTF-8 with a BOM, so Excel shows ₹ and other characters correctly.
 * - Reading is forgiving: columns can be in any order, `;` is accepted instead of `,`,
 *   and dates may be yyyy-MM-dd or dd/MM/yyyy (with or without a time).
 */
object TxnCsv {

    private val HEADER = listOf("Date", "Title", "Amount", "Type", "Payment Method", "Account", "Description")
    private val REQUIRED = listOf("date", "title", "amount", "type")

    private const val DATE_OUT = "yyyy-MM-dd HH:mm:ss"
    private val DATE_IN = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd",
        "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy",
        "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd-MM-yyyy"
    )
    private const val MIN_MILLIS = 0L                 // 1 Jan 1970
    private const val MAX_MILLIS = 4_102_444_800_000L // 1 Jan 2100: anything outside is a typo

    // 1,250  1,250.50  1,25,000 (Indian grouping): the last group is always exactly 3 digits
    private val THOUSANDS = Regex("""^\d{1,3}(,\d{2,3})*,\d{3}(\.\d+)?$""")

    // ------------------------------------------------------------------ writing

    fun write(rows: List<TxnRow>): String {
        val dateFormat = SimpleDateFormat(DATE_OUT, Locale.US)
        val sb = StringBuilder("﻿")
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (r in rows) {
            val cells = listOf(
                dateFormat.format(Date(r.dateTime)),
                guard(r.title),
                formatAmountPaise(r.amountPaise),
                r.type,
                r.paymentMethod,
                guard(r.accountName),
                guard(r.description)
            )
            sb.append(cells.joinToString(",") { quote(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s

    /**
     * Excel and Sheets run text that starts with = + - @ as a formula. Receipt text can start
     * with anything, so such text gets a leading ' (removed again by [unguard] on import).
     */
    private fun guard(s: String): String =
        if (s.isNotEmpty() && s[0] in FORMULA_STARTS) "'$s" else s

    private fun unguard(s: String): String =
        if (s.length > 1 && s[0] == '\'' && s[1] in FORMULA_STARTS) s.substring(1) else s

    private const val FORMULA_STARTS = "=+-@\t\r"

    // ------------------------------------------------------------------ reading

    fun read(text: String): CsvReadResult {
        val table = parseTable(text)
        val headerIndex = table.indexOfFirst { row -> row.any { it.isNotBlank() } }
        if (headerIndex < 0) return CsvReadResult(fileError = "The file is empty.")

        val columns = HashMap<String, Int>()
        table[headerIndex].forEachIndexed { i, name -> canonicalColumn(name)?.let { columns.putIfAbsent(it, i) } }
        val missing = REQUIRED.filter { it !in columns }
        if (missing.isNotEmpty()) {
            return CsvReadResult(
                fileError = "Missing column(s): ${missing.joinToString(", ")}. " +
                        "The first row must be a header like: ${HEADER.joinToString(", ")}"
            )
        }

        val dateParsers = DATE_IN.map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } }
        val rows = ArrayList<TxnRow>()
        val errors = ArrayList<CsvRowError>()

        for (i in headerIndex + 1 until table.size) {
            val cells = table[i]
            if (cells.all { it.isBlank() }) continue
            val rowNumber = i + 1
            fun cell(key: String): String = columns[key]?.let { cells.getOrNull(it) }?.trim().orEmpty()

            val dateText = cell("date")
            val dateTime = parseDate(dateText, dateParsers)
            if (dateTime == null) {
                errors += CsvRowError(rowNumber, "Unreadable date \"$dateText\"")
                continue
            }
            val title = unguard(cell("title"))
            if (title.isBlank()) {
                errors += CsvRowError(rowNumber, "Title is empty")
                continue
            }
            val amountPaise = parseAmountPaise(cell("amount"))
            if (amountPaise == null || amountPaise <= 0L) {
                errors += CsvRowError(rowNumber, "Amount \"${cell("amount")}\" is not a number above 0")
                continue
            }
            val type = parseType(cell("type"))
            if (type == null) {
                errors += CsvRowError(rowNumber, "Type \"${cell("type")}\" must be INCOME or EXPENSE")
                continue
            }
            rows += TxnRow(
                dateTime = dateTime,
                title = title,
                amountPaise = amountPaise,
                type = type,
                paymentMethod = parsePayment(cell("payment")),
                accountName = unguard(cell("account")),
                description = unguard(cell("description"))
            )
        }
        return CsvReadResult(rows = rows, errors = errors)
    }

    /** "Payment Method", "payment_method", "PAYMENT" ... all map to one key. */
    private fun canonicalColumn(header: String): String? =
        when (header.lowercase().filter { it in 'a'..'z' }) {
            "date", "datetime" -> "date"
            "title", "name" -> "title"
            "amount" -> "amount"
            "type" -> "type"
            "paymentmethod", "payment", "method", "mode" -> "payment"
            "account", "accountname" -> "account"
            "description", "note", "notes", "details" -> "description"
            else -> null
        }

    internal fun parseDate(text: String, parsers: List<SimpleDateFormat>): Long? {
        val s = text.trim()
        if (s.isEmpty()) return null
        for (parser in parsers) {
            val pos = ParsePosition(0)
            val date = parser.parse(s, pos)
            // The whole text must match, not just its beginning
            if (date != null && pos.index == s.length && date.time in MIN_MILLIS..MAX_MILLIS) return date.time
        }
        return null
    }

    /** The amount cell as whole paise, or null if it is not a clear number. */
    internal fun parseAmountPaise(text: String): Long? {
        var s = text.replace("₹", "").replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return null
        s = when {
            THOUSANDS.matches(s) -> s.replace(",", "")                              // 1,250.50 or 1,25,000
            s.count { it == ',' } == 1 && '.' !in s -> s.replace(',', '.')          // 12,50 (decimal comma)
            else -> s                                                               // anything else must parse as is
        }
        return parsePaise(s)
    }

    internal fun parseType(text: String): String? =
        when (text.trim().lowercase()) {
            "income", "credit", "cr" -> "INCOME"
            "expense", "debit", "dr" -> "EXPENSE"
            else -> null
        }

    /** Blank or unknown methods become CASH (the app only knows the methods in PAYMENT_METHODS). */
    internal fun parsePayment(text: String): String {
        val s = text.trim().uppercase().replace('_', ' ')
        return PAYMENT_METHODS.firstOrNull { it == s } ?: "CASH"
    }

    /**
     * Splits CSV text into rows of cells. Handles "quoted, cells", "" for a quote inside a
     * quoted cell, line breaks inside quotes, and a leading BOM. The separator is `;` when the
     * first line has more semicolons than commas (Excel does that in some countries).
     */
    internal fun parseTable(raw: String): List<List<String>> {
        val text = raw.removePrefix("﻿")
        val firstLine = text.substringBefore('\n')
        val separator = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','

        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    separator -> {
                        row.add(cell.toString())
                        cell.setLength(0)
                    }
                    '\n' -> {
                        row.add(cell.toString())
                        cell.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                    }
                    '\r' -> Unit   // the \n that follows ends the row
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {   // last row had no line break after it
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }
}
package com.example.txnkeeper

import java.util.Calendar
import java.util.TimeZone

/*
 * Turns the filters and the search box into SQL for the transactions table.
 * No Android or Compose code in here, so it can be tested on a plain JVM.
 *
 * Search works word by word. EVERY word must match something (AND), and a word may match in
 * several ways (OR). Examples:
 *
 *   599                 amount 599 (or amounts starting 599)
 *   >500   <=200        amount above / at most
 *   income  expense     type (also: credit, debit, received, spent, paid ...)
 *   morning afternoon evening night     time of day
 *   3pm   3:24pm   15:30                clock time
 *   today  yesterday  monday  october  2026
 *   15 oct   15 oct 2026   15/10   15-10-2026   2026-10-15
 *   upi  cash  card                     payment method
 *   pnb                                 account name
 *   anything else                       title / description
 *
 * So "paid evening upi >500" means: paid, in the evening, by UPI, over 500.
 */

/** A piece of SQL plus the values for its ? marks. */
class SqlPart(val sql: String, val args: List<Any>)

/** Plain values for the database. Built once per filter / search change. */
data class TxnQuery(
    val txType: String?,
    val payMethod: String?,
    val minAmount: Double?,
    val maxAmount: Double?,
    val dateFrom: Long?,
    val dateTo: Long?,
    val accountId: Long?,
    val search: String,
    val newestFirst: Boolean
)

object TxnSql {
    /** The WHERE clause (without the word WHERE) shared by the list query and the totals query. */
    fun where(
        q: TxnQuery,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): SqlPart {
        val parts = mutableListOf<String>()
        val args = mutableListOf<Any>()
        q.txType?.let { parts += "type = ?"; args.add(it) }
        q.payMethod?.let { parts += "paymentMethod = ?"; args.add(it) }
        q.minAmount?.let { parts += "amount >= ?"; args.add(it) }
        q.maxAmount?.let { parts += "amount <= ?"; args.add(it) }
        q.dateFrom?.let { parts += "dateTime >= ?"; args.add(it) }
        q.dateTo?.let { parts += "dateTime <= ?"; args.add(it) }
        q.accountId?.let { parts += "accountId = ?"; args.add(it) }
        TxnSearch.build(q.search, now, zone)?.let { parts.add(it.sql); args.addAll(it.args) }
        return SqlPart(if (parts.isEmpty()) "1 = 1" else parts.joinToString(" AND "), args)
    }
}

object TxnSearch {

    private val months = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12
    )

    // SQLite numbers weekdays 0 = Sunday ... 6 = Saturday
    private val weekdays = mapOf(
        "sunday" to 0, "sun" to 0, "monday" to 1, "mon" to 1, "tuesday" to 2, "tue" to 2, "tues" to 2,
        "wednesday" to 3, "wed" to 3, "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4,
        "friday" to 5, "fri" to 5, "saturday" to 6, "sat" to 6
    )

    private val incomeWords = setOf("income", "incomes", "credit", "credited", "received", "earned", "deposit", "deposited")
    private val expenseWords = setOf("expense", "expenses", "debit", "debited", "spent", "paid", "withdrawal")

    // Hours are 0-23. "night" wraps around midnight.
    private val partsOfDay = mapOf(
        "morning" to (5 to 12),
        "afternoon" to (12 to 17),
        "evening" to (17 to 21),
        "night" to (21 to 5)
    )

    private val amountRe = Regex("""^(\d[\d,]*)(\.\d+)?$""")
    private val compareRe = Regex("""^(>=|<=|>|<)(\d[\d,]*(?:\.\d+)?)$""")
    private val clockSuffixRe = Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm)$""")
    private val clockRe = Regex("""^(\d{1,2}):(\d{2})$""")
    private val slashDateRe = Regex("""^(\d{1,2})([/\-.])(\d{1,2})(?:\2(\d{2}|\d{4}))?$""")
    private val isoDateRe = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val dayRe = Regex("""^(\d{1,2})(st|nd|rd|th)?$""")
    private val ordinalRe = Regex("""^(\d{1,2})(st|nd|rd|th)$""")
    private val yearRe = Regex("""^(?:19|20)\d{2}$""")

    /** Returns null when there is nothing to search for. */
    fun build(text: String, now: Long, zone: TimeZone): SqlPart? {
        val tokens = text.lowercase()
            .replace("₹", " ")
            .split(Regex("""\s+"""))
            .map { it.trim(',', ';', ':') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val local = Local(zone.getOffset(now))
        val terms = mutableListOf<SqlPart>()
        var i = 0
        while (i < tokens.size) {
            val (term, used) = parseTerm(tokens, i, local, now, zone)
            terms += term
            i += used
        }
        return SqlPart(terms.joinToString(" AND ") { it.sql }, terms.flatMap { it.args })
    }

    // ------------------------------------------------------------ one term

    private fun parseTerm(t: List<String>, i: Int, local: Local, now: Long, zone: TimeZone): Pair<SqlPart, Int> {
        val w = t[i]
        val next = t.getOrNull(i + 1)
        val next2 = t.getOrNull(i + 2)

        // "> 500" written with a space
        if (w in setOf(">", "<", ">=", "<=") && next != null && amountRe.matches(next)) {
            return compare(w, next) to 2
        }
        compareRe.find(w)?.let { return compare(it.groupValues[1], it.groupValues[2]) to 1 }

        // "3 pm" written with a space
        if (next == "am" || next == "pm") {
            val joined = w + next
            clock(joined, local)?.let { return it to 2 }
        }
        clock(w, local)?.let { return it to 1 }

        // 15/10   15-10-2026   2026-10-15
        slashDate(w, local)?.let { return it to 1 }

        // 15 oct   15th oct 2026
        val dayMatch = dayRe.find(w)
        if (dayMatch != null && next != null && months.containsKey(next)) {
            val day = dayMatch.groupValues[1].toInt()
            if (day in 1..31) {
                val year = next2?.takeIf { yearRe.matches(it) }?.toInt()
                return dateTerm(local, day, months.getValue(next), year) to (if (year != null) 3 else 2)
            }
        }
        // oct 15   oct 15 2026
        if (months.containsKey(w) && next != null) {
            val m = dayRe.find(next)
            val day = m?.groupValues?.get(1)?.toInt()
            if (day != null && day in 1..31) {
                val year = next2?.takeIf { yearRe.matches(it) }?.toInt()
                return dateTerm(local, day, months.getValue(w), year) to (if (year != null) 3 else 2)
            }
        }
        // 15th on its own = the 15th of any month
        ordinalRe.find(w)?.let { m ->
            val day = m.groupValues[1].toInt()
            if (day in 1..31) return dateTerm(local, day, null, null) to 1
        }

        if (w == "today") return dayRange(now, zone, 0, 1) to 1
        if (w == "yesterday") return dayRange(now, zone, -1, 0) to 1

        months[w]?.let { return or(SqlPart("${local.month} = $it", emptyList()), text(w)) to 1 }
        weekdays[w]?.let { return or(SqlPart("${local.weekday} = $it", emptyList()), text(w)) to 1 }
        partsOfDay[w]?.let { (from, to) -> return or(hourRange(local, from, to), text(w)) to 1 }
        if (w in incomeWords) return or(SqlPart("type = ?", listOf("INCOME")), text(w)) to 1
        if (w in expenseWords) return or(SqlPart("type = ?", listOf("EXPENSE")), text(w)) to 1

        // Numbers: amount, and for a year-like number also that year
        amountRe.find(w)?.let { m ->
            val whole = m.groupValues[1].replace(",", "")
            val decimals = m.groupValues[2]
            val typed = whole + decimals
            val options = mutableListOf(
                SqlPart("amount = ?", listOf(typed.toDouble())),
                SqlPart("CAST(amount AS TEXT) LIKE ?", listOf("$typed%"))
            )
            if (decimals.isEmpty() && yearRe.matches(whole)) options += SqlPart("${local.year} = ${whole.toInt()}", emptyList())
            // Long numbers can be a reference or phone number, which live in the description
            if (whole.length >= 6) options += text(whole)
            return or(*options.toTypedArray()) to 1
        }

        return text(w) to 1
    }

    // ------------------------------------------------------------ pieces

    private fun compare(op: String, number: String): SqlPart =
        SqlPart("amount $op ?", listOf(number.replace(",", "").toDouble()))

    /** 3pm, 3:24pm, 3:24 (any 3 o'clock) or 15:30. Null if it isn't a valid time. */
    private fun clock(w: String, local: Local): SqlPart? {
        clockSuffixRe.find(w)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (h !in 1..12 || (min != null && min > 59)) return null
            val hour24 = h % 12 + if (m.groupValues[3] == "pm") 12 else 0
            return clockParts(local, "${local.hour} = $hour24", min)
        }
        clockRe.find(w)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            // 13:00 and up (or 0:xx) can only be 24-hour; 1:30 to 12:59 could be am or pm
            val hourSql = if (h == 0 || h > 12) "${local.hour} = $h" else "(${local.hour} % 12) = ${h % 12}"
            return clockParts(local, hourSql, min)
        }
        return null
    }

    private fun clockParts(local: Local, hourSql: String, minute: Int?): SqlPart =
        SqlPart(if (minute == null) "($hourSql)" else "($hourSql AND ${local.minute} = $minute)", emptyList())

    private fun hourRange(local: Local, from: Int, to: Int): SqlPart =
        SqlPart(
            if (from < to) "(${local.hour} >= $from AND ${local.hour} < $to)"
            else "(${local.hour} >= $from OR ${local.hour} < $to)",
            emptyList()
        )

    private fun slashDate(w: String, local: Local): SqlPart? {
        isoDateRe.find(w)?.let { m ->
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
            return if (d in 1..31 && mo in 1..12) dateTerm(local, d, mo, y) else null
        }
        slashDateRe.find(w)?.let { m ->
            val sep = m.groupValues[2]
            val yearText = m.groupValues[4]
            if (sep == "." && yearText.isEmpty()) return null          // 5.5 is an amount, not 5 May
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[3].toInt()
            if (d !in 1..31 || mo !in 1..12) return null
            val year = yearText.takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            return dateTerm(local, d, mo, year)
        }
        return null
    }

    private fun dateTerm(local: Local, day: Int?, month: Int?, year: Int?): SqlPart {
        val checks = buildList {
            if (day != null) add("${local.day} = $day")
            if (month != null) add("${local.month} = $month")
            if (year != null) add("${local.year} = $year")
        }
        return SqlPart("(" + checks.joinToString(" AND ") + ")", emptyList())
    }

    /** From the start of the day [fromDay] days from today until the start of day [toDay] (exclusive). */
    private fun dayRange(now: Long, zone: TimeZone, fromDay: Int, toDay: Int): SqlPart {
        fun startOf(plusDays: Int): Long = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, plusDays)
        }.timeInMillis
        return SqlPart("(dateTime >= ? AND dateTime < ?)", listOf(startOf(fromDay), startOf(toDay)))
    }

    private fun text(word: String): SqlPart {
        val p = "%" + escapeLike(word) + "%"
        return SqlPart(
            "(title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR paymentMethod LIKE ? ESCAPE '\\' " +
                    "OR accountId IN (SELECT id FROM accounts WHERE name LIKE ? ESCAPE '\\'))",
            listOf(p, p, p, p)
        )
    }

    private fun or(vararg parts: SqlPart): SqlPart =
        SqlPart("(" + parts.joinToString(" OR ") { it.sql } + ")", parts.flatMap { it.args })

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Date and time pieces of dateTime, in the phone's time zone (SQLite itself only knows UTC). */
    private class Local(offsetMs: Int) {
        private val seconds = "((dateTime + $offsetMs) / 1000)"
        private fun part(code: String) = "CAST(strftime('$code', $seconds, 'unixepoch') AS INTEGER)"
        val hour = part("%H")
        val minute = part("%M")
        val day = part("%d")
        val month = part("%m")
        val year = part("%Y")
        val weekday = part("%w")
    }
}

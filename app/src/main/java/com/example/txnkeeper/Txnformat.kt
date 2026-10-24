package com.example.txnkeeper

import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private val IndianLocale = Locale("en", "IN")

// ------------------------------------------------------------------ money

/** Plain number for text fields: 945 or 945.5 (never "945.0" or "1.0E7"). */
fun formatAmount(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

/** Display money with Indian grouping: ₹1,25,000 or ₹1,250.50 (paise only when present). */
fun formatMoney(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    val whole = rounded % 1.0 == 0.0
    val nf = NumberFormat.getNumberInstance(IndianLocale).apply {
        minimumFractionDigits = if (whole) 0 else 2
        maximumFractionDigits = if (whole) 0 else 2
    }
    return "₹" + nf.format(rounded)
}

/** ₹1,250 or −₹300: for balances, which have no "+" sign. */
fun formatBalance(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return if (rounded < 0) "−" + formatMoney(abs(rounded)) else formatMoney(rounded)
}

/** +₹300 / −₹300 (zero has no sign). */
fun formatSignedMoney(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return when {
        rounded == 0.0 -> formatMoney(0.0)
        rounded < 0 -> "−" + formatMoney(abs(rounded))
        else -> "+" + formatMoney(rounded)
    }
}

// ------------------------------------------------------------------ dates

fun startOfDay(millis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

fun endOfDay(millis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = startOfDay(millis)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis - 1

/**
 * Material date pickers return UTC midnight of the chosen day. In India that is 05:30 local
 * (still the same day), but west of UTC it would be the day before. These two functions
 * convert safely in both directions.
 */
fun pickerToLocalDay(utcMidnight: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

fun localToPickerDay(local: Long): Long {
    val l = Calendar.getInstance().apply { timeInMillis = local }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** The calendar day of [day], with the clock time of [timeSource]. */
fun onDayAtTimeOf(day: Long, timeSource: Long): Long {
    val t = Calendar.getInstance().apply { timeInMillis = timeSource }
    return Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, t.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, t.get(Calendar.MINUTE))
        set(Calendar.SECOND, t.get(Calendar.SECOND))
        set(Calendar.MILLISECOND, t.get(Calendar.MILLISECOND))
    }.timeInMillis
}

fun formatDateLong(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

fun formatTime(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

/** "Today", "Yesterday", "12 Oct" (or "12 Oct 2025" for another year). [dayStart] = startOfDay(...). */
fun dayHeading(dayStart: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = today
        add(Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis
    if (dayStart == today) return "Today"
    if (dayStart == yesterday) return "Yesterday"
    val sameYear = Calendar.getInstance().apply { timeInMillis = dayStart }.get(Calendar.YEAR) ==
            Calendar.getInstance().get(Calendar.YEAR)
    return SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", Locale.getDefault())
        .format(Date(dayStart))
}
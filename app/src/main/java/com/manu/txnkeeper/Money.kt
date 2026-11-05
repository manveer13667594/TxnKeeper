package com.manu.txnkeeper

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Money is stored as whole paise in a Long (Rs 12.50 = 1250), so adding many amounts never drifts
 * the way a Double does (0.1 + 0.2 = 0.30000000000000004).
 *
 * Pure Kotlin, no Android code, so it can be unit-tested. Showing paise as text is in Txnformat.kt.
 */

/** The largest amount accepted: Rs 10 trillion, in paise. Far above anything real, far below Long overflow. */
const val MAX_PAISE: Long = 1_000_000_000_000_000L

private const val MAX_RUPEES: Double = 1.0E13

/**
 * Text typed by the user or read from a file ("12.5", "945", "-500.25") -> paise, or null if it is
 * not a plain number or is absurdly large. Commas are NOT removed here: callers do that first.
 * More than two decimals round half up (12.345 -> 1235).
 */
fun parsePaise(text: String): Long? {
    val number = text.trim().toBigDecimalOrNull() ?: return null
    // Reject absurd exponents before doing any arithmetic on them. Large ones (1e999999999) would
    // build an enormous number; tiny ones (1e-99999999) make the rounding step below run for ages
    // and freeze the app. No real amount has more than 30 decimals.
    if (number.precision() - number.scale() > 16 || number.scale() > 30) return null
    val paise = number.movePointRight(2).setScale(0, RoundingMode.HALF_UP)
    return if (paise.abs() <= BigDecimal.valueOf(MAX_PAISE)) paise.toLong() else null
}

/**
 * Rupees as a Double (what the OCR / voice parsers and the filter boxes produce) -> paise.
 * Values beyond the limit are clamped to +/- [MAX_PAISE], which keeps "amount above 1e30" meaningful.
 */
fun Double.toPaise(): Long = when {
    isNaN() -> 0L
    this >= MAX_RUPEES -> MAX_PAISE
    this <= -MAX_RUPEES -> -MAX_PAISE
    else -> BigDecimal.valueOf(this).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
}

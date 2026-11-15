package com.manu.txnkeeper

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Pure helpers for the receipt images (no Android), so they can be unit-tested.
// The files themselves are handled by ReceiptStore.

private val SAFE_RECEIPT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\\.jpg$")

/**
 * A receipt file name is only ever one plain name ending in .jpg: no folders, no "..", no spaces.
 * Every name read from the database, a CSV file or a ZIP file goes through this before it is
 * turned into a file on disk, so a bad file can never reach outside the receipts folder.
 */
internal fun isSafeReceiptName(name: String?): Boolean =
    name != null && SAFE_RECEIPT_NAME.matches(name) && ".." !in name

/** A new unique file name, for example r_20261115-100530_a1b2c3d4.jpg */
internal fun newReceiptName(nowMillis: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
    val unique = UUID.randomUUID().toString().replace("-", "").take(8)
    return "r_${stamp}_$unique.jpg"
}

/**
 * The largest power-of-two shrink that still leaves the longest side at [maxSide] or more
 * (1 = no shrinking). Decoding with this is fast and uses little memory.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= maxSide) sample *= 2
    return sample
}


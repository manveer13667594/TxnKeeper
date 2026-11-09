package com.manu.txnkeeper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.roundToInt

/**
 * Loads a photo for the second OCR attempt: sized so ML Kit reads it well (huge photos are shrunk,
 * tiny screenshots are enlarged) and cleaned up by ReceiptImageEnhancer. Call it off the main thread.
 */
object ReceiptImagePrep {
    private const val MAX_SIDE = 1800   // longer side; keeps memory and time in check
    private const val MIN_SIDE = 1200   // smaller pictures have text too small to read reliably

    /** The cleaned-up picture, or null if the image can't be opened. The caller recycles it. */
    fun enhancedBitmap(context: Context, uri: Uri): Bitmap? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // pixels must be readable
                val w = info.size.width
                val h = info.size.height
                val longSide = maxOf(w, h)
                val scale = when {
                    longSide > MAX_SIDE -> MAX_SIDE.toFloat() / longSide
                    longSide < MIN_SIDE -> minOf(MIN_SIDE.toFloat() / longSide, 3f)
                    else -> 1f
                }
                if (scale != 1f) {
                    decoder.setTargetSize(maxOf(1, (w * scale).roundToInt()), maxOf(1, (h * scale).roundToInt()))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            val w = decoded.width
            val h = decoded.height
            val pixels = IntArray(w * h)
            decoded.getPixels(pixels, 0, w, 0, 0, w, h)
            val clean = ReceiptImageEnhancer.enhance(pixels, w, h)
            Bitmap.createBitmap(clean, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        } finally {
            decoded.recycle()
        }
    }
}

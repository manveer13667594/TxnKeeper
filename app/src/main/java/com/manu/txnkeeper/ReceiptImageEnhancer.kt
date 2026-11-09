package com.manu.txnkeeper

/**
 * Cleans up a receipt photo before a second OCR attempt: grey scale, shadow removal and contrast stretch.
 * Faded thermal paper and photos with a shadow across them turn into dark text on an even white page.
 *
 * Plain int arrays (no Android classes), so it can be unit tested. ReceiptImagePrep wraps it for Bitmaps.
 */
object ReceiptImageEnhancer {

    /**
     * @param argb pixels as returned by Bitmap.getPixels, row by row (width * height values)
     * @return new grey ARGB pixels of the same size; [argb] is not changed
     */
    fun enhance(argb: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad image size" }
        val n = width * height

        // 1. brightness of every pixel, 0..255
        val lum = IntArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            lum[i] = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }

        // 2. divide by the local paper brightness, which removes shadows and uneven light
        val paper = blurredCopy(lum, width, height, radius = maxOf(15, maxOf(width, height) / 30))
        val flat = IntArray(n)
        for (i in 0 until n) {
            flat[i] = (lum[i] * 255 / maxOf(paper[i], 16)).coerceIn(0, 255)
        }

        // 3. stretch so the darkest ink is black and the paper is white
        val hist = IntArray(256)
        for (v in flat) hist[v]++
        val low = percentile(hist, n, 0.01)
        val high = percentile(hist, n, 0.99)
        val range = high - low
        val out = IntArray(n)
        for (i in 0 until n) {
            val v = if (range < 30) flat[i] else ((flat[i] - low) * 255 / range).coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    private fun percentile(hist: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).toLong()
        var seen = 0L
        for (v in 0..255) {
            seen += hist[v]
            if (seen > target) return v
        }
        return 255
    }

    /** Box blur through an integral image: one pass over the picture whatever the radius. */
    private fun blurredCopy(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        // sums[(y)*(w+1)+x] = sum of src over columns < x and rows < y. Fits an Int up to ~8 megapixels.
        val stride = w + 1
        val sums = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                rowSum += src[y * w + x]
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + rowSum
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radius)
            val y1 = minOf(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radius)
                val x1 = minOf(w, x + radius + 1)
                val sum = sums[y1 * stride + x1] - sums[y0 * stride + x1] - sums[y1 * stride + x0] + sums[y0 * stride + x0]
                out[y * w + x] = sum / ((x1 - x0) * (y1 - y0))
            }
        }
        return out
    }
}

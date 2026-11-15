package com.manu.txnkeeper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The receipts folder
// ---------------------------------------------------------------------------------------------

/**
 * Keeps the images attached to transactions.
 *
 * - Images live in the app's private folder (filesDir/receipts): not in the gallery, and other
 *   apps cannot read them. The database only stores the file name.
 * - A picked or scanned image is shrunk (longest side [MAX_SIDE_PX]) and saved as a JPEG, so one
 *   receipt costs a few hundred KB instead of several MB. The phone's rotation flag is applied.
 * - The original photo (gallery, shared image) is never touched.
 */
object ReceiptStore {

    /** Longest side of a saved image, in pixels. Raise it for sharper (and bigger) images. */
    const val MAX_SIDE_PX = 1600
    private const val JPEG_QUALITY = 85

    /** Largest image accepted when importing a ZIP, so a damaged file cannot fill the phone. */
    const val MAX_IMPORT_IMAGE_BYTES = 15L * 1024 * 1024

    private const val TEMP_SUFFIX = ".tmp"

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "receipts").also { it.mkdirs() }

    /** The file for a stored name, or null if the name is not a safe receipt name. */
    fun file(context: Context, name: String?): File? =
        if (isSafeReceiptName(name)) File(dir(context), name!!) else null

    fun exists(context: Context, name: String?): Boolean = file(context, name)?.isFile == true

    // ------------------------------------------------------------------ saving

    /** Shrinks and saves the image at [uri]. Returns the new file name, or null if it can't be read. */
    suspend fun saveFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val decoded = decodeForStorage(context, uri) ?: return@withContext null
            try {
                writeJpeg(context, decoded)
            } finally {
                decoded.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Saves an image read from [input] as it is (used by ZIP import: the file was already shrunk
     * when it was exported). Stops at [maxBytes], and refuses anything that is not a readable image.
     * Returns the new file name, or null.
     */
    suspend fun saveFromStream(context: Context, input: InputStream, maxBytes: Long = MAX_IMPORT_IMAGE_BYTES): String? =
        withContext(Dispatchers.IO) {
            val name = newReceiptName()
            val target = File(dir(context), name)
            val temp = File(dir(context), name + TEMP_SUFFIX)
            try {
                var total = 0L
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            temp.delete()
                            return@withContext null
                        }
                        out.write(buffer, 0, read)
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || !temp.renameTo(target)) {
                    temp.delete()
                    return@withContext null
                }
                name
            } catch (e: CancellationException) {
                temp.delete()
                throw e
            } catch (e: Exception) {
                temp.delete()
                null
            }
        }

    /** Decodes [uri] no larger than needed, with the phone's rotation applied. */
    private fun decodeForStorage(context: Context, uri: Uri): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = sampleSizeFor(info.size.width, info.size.height, MAX_SIDE_PX)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        // The shrink above is in whole steps, so finish with an exact resize if it is still too big
        val longest = maxOf(bitmap.width, bitmap.height)
        var result = bitmap
        if (longest > MAX_SIDE_PX) {
            val scale = MAX_SIDE_PX.toFloat() / longest
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            result = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (result !== bitmap) bitmap.recycle()
        }
        // JPEG has no transparency: a PNG screenshot with see-through parts goes onto white
        if (result.hasAlpha()) {
            val flat = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(result, 0f, 0f, null)
            }
            result.recycle()
            result = flat
        }
        return result
    }

    /** Writes through a temporary file, so a crash never leaves a half-written image behind. */
    private fun writeJpeg(context: Context, bitmap: Bitmap): String? {
        val name = newReceiptName()
        val target = File(dir(context), name)
        val temp = File(dir(context), name + TEMP_SUFFIX)
        return try {
            val ok = FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            if (ok && temp.renameTo(target)) name else {
                temp.delete()
                null
            }
        } catch (e: Exception) {
            temp.delete()
            null
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * A bitmap for showing on screen, no larger than about [maxSide] pixels on its longest side.
     * Decoded off the main thread and kept in a small memory cache, so scrolling the list stays smooth.
     */
    suspend fun loadForScreen(context: Context, name: String?, maxSide: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (name == null) return@withContext null
            val key = "$name@$maxSide"
            cache.get(key)?.let { return@withContext it.asImageBitmap() }
            val bitmap = decodeSampled(context, name, maxSide) ?: return@withContext null
            cache.put(key, bitmap)
            bitmap.asImageBitmap()
        }

    /** A fresh bitmap (not cached, the caller recycles it) for the PDF export. Call off the main thread. */
    fun decodeSampled(context: Context, name: String?, maxSide: Int): Bitmap? {
        val f = file(context, name)?.takeIf { it.isFile } ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
            }
            BitmapFactory.decodeFile(f.path, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // About 1/16 of the app's memory, between 2 and 16 MB
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(2 * 1024 * 1024, 16 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // ------------------------------------------------------------------ deleting

    fun delete(context: Context, name: String?) {
        val f = file(context, name) ?: return
        f.delete()
        forget(name!!)
    }

    fun deleteAll(context: Context, names: Collection<String?>) {
        names.forEach { delete(context, it) }
    }

    /** Deletes every image (used by "delete all data"). */
    fun deleteEverything(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        cache.evictAll()
    }

    /**
     * Housekeeping: deletes image files that no transaction uses (left behind by a crash, or an
     * image picked in a form that was then closed). Files younger than [minAgeMs] are kept, so an
     * image in a form that is still open is never touched.
     */
    fun deleteUnused(context: Context, inUse: Set<String>, minAgeMs: Long, now: Long = System.currentTimeMillis()) {
        dir(context).listFiles()?.forEach { f ->
            val old = now - f.lastModified() >= minAgeMs
            if (old && f.name !in inUse) {
                f.delete()
                forget(f.name)
            }
        }
    }

    private fun forget(name: String) {
        cache.snapshot().keys.filter { it.startsWith("$name@") }.forEach { cache.remove(it) }
    }
}

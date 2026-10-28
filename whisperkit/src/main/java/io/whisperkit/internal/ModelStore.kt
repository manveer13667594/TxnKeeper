package io.whisperkit.internal

import android.content.Context
import io.whisperkit.WhisperException
import io.whisperkit.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Downloads models into app-private storage, resumable, and refuses to keep a corrupt file. */
internal object ModelStore {

    fun fileFor(context: Context, model: WhisperModel): File {
        val dir = File(context.applicationContext.filesDir, "whisperkit/models").apply { mkdirs() }
        return File(dir, model.fileName)
    }

    fun isDownloaded(context: Context, model: WhisperModel): Boolean =
        fileFor(context, model).let { it.exists() && hasGgmlMagic(it) }

    fun delete(context: Context, model: WhisperModel) {
        val f = fileFor(context, model)
        f.delete()
        File(f.path + ".part").delete()
    }

    suspend fun ensure(
        context: Context,
        model: WhisperModel,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = fileFor(context, model)
        if (target.exists() && hasGgmlMagic(target)) {
            onProgress(1f)
            return@withContext target
        }
        target.delete()
        download(model.url, target, onProgress)
        target
    }

    private suspend fun download(url: String, target: File, onProgress: (Float) -> Unit, retry: Boolean = true) {
        val part = File(target.path + ".part")
        val have = if (part.exists()) part.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && retry) { // our partial file is bad/complete-but-wrong; start over
                part.delete()
                return download(url, target, onProgress, retry = false)
            }
            if (code != 200 && code != 206) throw WhisperException("Model download failed: HTTP $code")

            val resuming = code == 206
            val startAt = if (resuming) have else 0L
            val total = conn.contentLengthLong.let { if (it > 0) it + startAt else -1L }

            RandomAccessFile(part, "rw").use { out ->
                if (resuming) out.seek(startAt) else out.setLength(0)
                var done = startAt
                var lastPct = -1
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        coroutineContext.ensureActive() // keeps the .part file, so the next call resumes
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(done.toFloat() / total) }
                        }
                    }
                }
                if (total > 0 && done != total) throw WhisperException("Model download incomplete ($done of $total bytes)")
            }
        } finally {
            conn.disconnect()
        }

        if (!hasGgmlMagic(part)) {
            part.delete()
            throw WhisperException("Downloaded file is not a valid whisper model")
        }
        if (!part.renameTo(target)) throw WhisperException("Could not save model file")
        onProgress(1f)
    }

    /** Whisper ggml files start with the bytes "lmgg". Catches HTML error pages saved as a model. */
    private fun hasGgmlMagic(file: File): Boolean = runCatching {
        FileInputStream(file).use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 'l'.code.toByte() && b[1] == 'm'.code.toByte() &&
                b[2] == 'g'.code.toByte() && b[3] == 'g'.code.toByte()
        }
    }.getOrDefault(false)
}

package io.whisperkit

import android.content.Context
import android.net.Uri
import io.whisperkit.internal.AudioDecoder
import io.whisperkit.internal.ModelStore
import io.whisperkit.internal.NativeCallback
import io.whisperkit.internal.NativeWhisper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.coroutines.cancellation.CancellationException

/**
 * Speech-to-text on top of whisper.cpp.
 *
 * ```
 * val whisper = WhisperKit.create(context)            // downloads the small q5 model on first run
 * val result = whisper.transcribe(audioUri)
 * println(result.text)
 * whisper.close()                                      // when you're done (e.g. ViewModel.onCleared)
 * ```
 * One instance runs one transcription at a time; extra calls wait their turn.
 * Cancel by cancelling the coroutine / flow collection.
 */
class WhisperKit private constructor(
    handle: Long,
    private val appContext: Context,
) : AutoCloseable {

    private val handleRef = AtomicLong(handle)
    private val closed = AtomicBoolean(false)
    private val lock = Mutex()

    // ---- Simple API ------------------------------------------------------------------------

    suspend fun transcribe(
        uri: Uri,
        options: TranscribeOptions = TranscribeOptions(),
        onProgress: ((Float) -> Unit)? = null,
        onSegment: ((Segment) -> Unit)? = null,
    ): Transcription = collectResult(transcribeAsFlow(uri, options), onProgress, onSegment)

    suspend fun transcribe(
        file: File,
        options: TranscribeOptions = TranscribeOptions(),
        onProgress: ((Float) -> Unit)? = null,
        onSegment: ((Segment) -> Unit)? = null,
    ): Transcription = collectResult(transcribeAsFlow(file, options), onProgress, onSegment)

    /** [samples]: 16 kHz, mono, floats in -1..1 (use this if you record audio yourself). */
    suspend fun transcribe(
        samples: FloatArray,
        options: TranscribeOptions = TranscribeOptions(),
        onProgress: ((Float) -> Unit)? = null,
        onSegment: ((Segment) -> Unit)? = null,
    ): Transcription = collectResult(transcribeAsFlow(samples, options), onProgress, onSegment)

    // ---- Flow API (segments arrive live, good for Compose/ViewModel) -------------------------

    fun transcribeAsFlow(uri: Uri, options: TranscribeOptions = TranscribeOptions()): Flow<TranscribeEvent> =
        run(options) { AudioDecoder.decode(appContext, uri) }

    fun transcribeAsFlow(file: File, options: TranscribeOptions = TranscribeOptions()): Flow<TranscribeEvent> =
        transcribeAsFlow(Uri.fromFile(file), options)

    fun transcribeAsFlow(samples: FloatArray, options: TranscribeOptions = TranscribeOptions()): Flow<TranscribeEvent> =
        run(options) {
            ShortArray(samples.size) { (samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        }

    // ---- Lifecycle ----------------------------------------------------------------------------

    /** Frees the model (~200+ MB of RAM). Safe to call while transcribing: that run is aborted. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val h = handleRef.get()
        if (h != 0L) NativeWhisper.nAbort(h)
        if (lock.tryLock()) {
            try { freeNative() } finally { lock.unlock() }
        } // else: the running transcription frees it as soon as it unwinds
    }

    companion object {
        /** Loads [model], downloading it first if needed. [onDownloadProgress] gets 0f..1f. */
        suspend fun create(
            context: Context,
            model: WhisperModel = WhisperModel.SmallQ5,
            onDownloadProgress: (Float) -> Unit = {},
        ): WhisperKit = create(context, ModelStore.ensure(context, model, onDownloadProgress))

        /** Loads your own ggml model file (e.g. a different quantization). */
        suspend fun create(context: Context, modelFile: File): WhisperKit = withContext(Dispatchers.IO) {
            if (!modelFile.isFile) throw WhisperException("Model file not found: ${modelFile.path}")
            val h = NativeWhisper.nInit(modelFile.absolutePath)
            if (h == 0L) throw WhisperException("Could not load model ${modelFile.name} (corrupt or not a whisper ggml model)")
            WhisperKit(h, context.applicationContext)
        }

        fun isModelDownloaded(context: Context, model: WhisperModel = WhisperModel.SmallQ5): Boolean =
            ModelStore.isDownloaded(context, model)

        fun deleteModel(context: Context, model: WhisperModel = WhisperModel.SmallQ5) =
            ModelStore.delete(context, model)
    }

    // ---- Internals ----------------------------------------------------------------------------

    private suspend fun collectResult(
        flow: Flow<TranscribeEvent>,
        onProgress: ((Float) -> Unit)?,
        onSegment: ((Segment) -> Unit)?,
    ): Transcription {
        var result: Transcription? = null
        flow.collect { e ->
            when (e) {
                is TranscribeEvent.Progress -> onProgress?.invoke(e.fraction)
                is TranscribeEvent.SegmentReady -> onSegment?.invoke(e.segment)
                is TranscribeEvent.Finished -> result = e.result
                TranscribeEvent.Decoding -> Unit
            }
        }
        return result ?: throw WhisperException("Transcription produced no result")
    }

    private fun ensureOpen() {
        check(!closed.get()) { "WhisperKit is closed" }
    }

    private fun freeNative() {
        val h = handleRef.getAndSet(0)
        if (h != 0L) NativeWhisper.nFree(h)
    }

    private fun run(options: TranscribeOptions, pcmProvider: suspend () -> ShortArray): Flow<TranscribeEvent> =
        channelFlow {
            require(options.chunkSeconds >= 30) { "chunkSeconds must be at least 30" }
            require(options.threads >= 1) { "threads must be at least 1" }
            ensureOpen()
            try {
                lock.withLock {
                    ensureOpen()
                    send(TranscribeEvent.Decoding)
                    val pcm = pcmProvider()
                    if (pcm.isEmpty()) throw WhisperException("Audio is empty")

                    val handle = handleRef.get()
                    val total = pcm.size
                    val chunkSamples = options.chunkSeconds * AudioDecoder.TARGET_RATE
                    val segments = ArrayList<Segment>()
                    var language: String? = null
                    var start = 0

                    while (start < total) {
                        ensureActive()
                        // Take everything if the rest is only a bit longer than a chunk.
                        val end = if (total - start <= chunkSamples * 5 / 4) total
                        else quietSplit(pcm, start + chunkSamples)

                        val chunkStart = start
                        val chunkEnd = end
                        val offsetMs = chunkStart * 1000L / AudioDecoder.TARGET_RATE
                        val floats = FloatArray(chunkEnd - chunkStart) { pcm[chunkStart + it] / 32768f }

                        val callback = object : NativeCallback {
                            override fun onProgress(percent: Int) {
                                val done = chunkStart + (chunkEnd - chunkStart) * (percent / 100f)
                                trySend(TranscribeEvent.Progress((done / total).coerceIn(0f, 1f)))
                            }

                            override fun onSegment(t0: Long, t1: Long, textUtf8: ByteArray) {
                                val text = String(textUtf8, Charsets.UTF_8).trim()
                                if (text.isEmpty() || text == "[BLANK_AUDIO]") return
                                val seg = Segment(offsetMs + t0 * 10, offsetMs + t1 * 10, text)
                                segments.add(seg)
                                trySend(TranscribeEvent.SegmentReady(seg))
                            }
                        }

                        val rc = runNative(handle, floats, options, callback)
                        if (rc == 1) throw WhisperException("Transcription was aborted")
                        if (rc != 0) throw WhisperException("whisper.cpp failed with code $rc")

                        if (language == null) language = NativeWhisper.nDetectedLanguage(handle)
                        start = end
                    }

                    send(TranscribeEvent.Progress(1f))
                    send(
                        TranscribeEvent.Finished(
                            Transcription(
                                text = segments.joinToString(" ") { it.text },
                                segments = segments,
                                language = language,
                                audioDurationMs = total * 1000L / AudioDecoder.TARGET_RATE,
                            )
                        )
                    )
                }
            } finally {
                if (closed.get()) freeNative()
            }
        }
            .buffer(Channel.UNLIMITED) // native callbacks must never block or drop segments
            .flowOn(Dispatchers.Default)

    /**
     * Runs whisper on its own thread. If the caller is cancelled we abort the native run and still wait
     * for it to unwind, so the model is never touched by two runs at once.
     */
    private suspend fun runNative(handle: Long, samples: FloatArray, options: TranscribeOptions, cb: NativeCallback): Int {
        val result = CompletableDeferred<Int>()
        thread(name = "whisperkit-native", isDaemon = true) {
            try {
                result.complete(
                    NativeWhisper.nTranscribe(
                        handle, samples, options.threads, options.language, options.translateToEnglish, cb,
                    )
                )
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        try {
            return result.await()
        } catch (e: CancellationException) {
            NativeWhisper.nAbort(handle)
            withContext(NonCancellable) { runCatching { result.await() } }
            throw e
        }
    }

    /** Picks a cut point near [target] inside the quietest 100 ms, so we rarely split a word. */
    private fun quietSplit(pcm: ShortArray, target: Int): Int {
        val frame = AudioDecoder.TARGET_RATE / 10
        val radius = AudioDecoder.TARGET_RATE * 5
        val lo = (target - radius).coerceAtLeast(frame)
        val hi = (target + radius).coerceAtMost(pcm.size - frame)
        if (hi <= lo) return target.coerceAtMost(pcm.size)
        var best = target
        var bestEnergy = Long.MAX_VALUE
        var i = lo
        while (i <= hi) {
            var e = 0L
            for (j in i until i + frame) e += abs(pcm[j].toInt())
            if (e < bestEnergy) { bestEnergy = e; best = i + frame / 2 }
            i += frame
        }
        return best
    }
}

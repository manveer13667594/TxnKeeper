package io.whisperkit.internal

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.whisperkit.WhisperException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes any audio Android can play (mp3, m4a/aac, wav, ogg, flac, 3gp, video soundtracks) to 16 kHz mono PCM16. */
internal object AudioDecoder {
    const val TARGET_RATE = 16_000

    suspend fun decode(context: Context, uri: Uri): ShortArray = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context.applicationContext, uri, null)
            } catch (e: Exception) {
                throw WhisperException("Cannot open audio: $uri", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw WhisperException("No audio track found in $uri")
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val converter = Converter(
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                floatPcm = false,
            )

            if (mime == "audio/raw") {
                // WAV/PCM: no codec needed, samples are already 16-bit PCM.
                val buf = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    ensureActive()
                    buf.clear()
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.limit(n)
                    converter.consume(buf)
                    extractor.advance()
                }
            } else {
                decodeWithCodec(extractor, format, mime, converter) { ensureActive() }
            }
            converter.finish()
        } finally {
            extractor.release()
        }
    }

    private fun decodeWithCodec(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String,
        converter: Converter,
        checkActive: () -> Unit,
    ) {
        val codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            throw WhisperException("This device cannot decode audio type $mime", e)
        }
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                checkActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(o)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            converter.consume(out)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        converter.reconfigure(
                            f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT,
                        )
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** Raw decoder output (any rate/channels) -> mono 16 kHz shorts, one sample at a time. */
    private class Converter(private var rate: Int, private var channels: Int, private var floatPcm: Boolean) {
        private var out = ShortArray(1 shl 20)
        private var size = 0

        // Linear resampler state: `k` = index of the incoming mono sample, `nextT` = next output position.
        private var step = rate.toDouble() / TARGET_RATE
        private var nextT = 0.0
        private var k = 0L
        private var prev = 0f

        fun reconfigure(rate: Int, channels: Int, floatPcm: Boolean) {
            this.rate = rate
            this.channels = channels
            this.floatPcm = floatPcm
            // Keep stream position continuous; only the step changes.
            val newStep = rate.toDouble() / TARGET_RATE
            nextT = k + (nextT - k) * (newStep / step)
            step = newStep
        }

        fun consume(buf: ByteBuffer) {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val ch = channels.coerceAtLeast(1)
            if (floatPcm) {
                val fb = buf.asFloatBuffer()
                while (fb.remaining() >= ch) {
                    var sum = 0f
                    repeat(ch) { sum += fb.get() }
                    feed(sum / ch)
                }
            } else {
                val sb = buf.asShortBuffer()
                while (sb.remaining() >= ch) {
                    var sum = 0
                    repeat(ch) { sum += sb.get() }
                    feed(sum / ch / 32768f)
                }
            }
        }

        private fun feed(x: Float) {
            if (rate == TARGET_RATE) {
                add(x)
            } else {
                // Emit every output position that falls between the previous sample and this one.
                while (nextT <= k) {
                    val frac = (nextT - (k - 1)).toFloat()
                    add(prev + (x - prev) * frac)
                    nextT += step
                }
                prev = x
            }
            k++
        }

        private fun add(v: Float) {
            if (size == out.size) out = out.copyOf(size * 2)
            out[size++] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        fun finish(): ShortArray = out.copyOf(size)
    }
}

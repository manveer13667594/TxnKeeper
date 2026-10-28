package io.whisperkit

/** One piece of transcribed speech. Times are in milliseconds from the start of the audio. */
data class Segment(val startMs: Long, val endMs: Long, val text: String)

data class Transcription(
    /** Full text, segments joined together. */
    val text: String,
    val segments: List<Segment>,
    /** Detected language code ("en", "hi", "pa"...) from the first chunk, or null if unknown. */
    val language: String?,
    val audioDurationMs: Long,
)

/** What a [WhisperKit.transcribeAsFlow] emits, in order. */
sealed interface TranscribeEvent {
    /** Audio file is being decoded to 16 kHz mono. */
    data object Decoding : TranscribeEvent

    /** Overall progress, 0f..1f. */
    data class Progress(val fraction: Float) : TranscribeEvent

    /** A finished segment, emitted as soon as whisper produces it. */
    data class SegmentReady(val segment: Segment) : TranscribeEvent

    data class Finished(val result: Transcription) : TranscribeEvent
}

data class TranscribeOptions(
    /** "auto" to detect, or a code like "en", "hi", "pa". */
    val language: String = "auto",
    /** Translate speech to English instead of transcribing in its own language. */
    val translateToEnglish: Boolean = false,
    /** CPU threads. Default is half your cores, 2..4, which is usually the fastest on phones. */
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
    /** Long audio is split into pieces of about this size (cut at a quiet moment) to keep memory low. */
    val chunkSeconds: Int = 600,
)

class WhisperException(message: String, cause: Throwable? = null) : Exception(message, cause)

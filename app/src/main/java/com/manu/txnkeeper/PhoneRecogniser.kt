package com.manu.txnkeeper

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.util.Locale

/**
 * The phone's own Google speech recogniser (the one behind the keyboard mic), used as the fast way to turn a
 * voice entry into text. The app's recorder gives it the same audio live while you speak, so the text is
 * ready a fraction of a second after you tap Stop. It needs Android 13+ (to hand it our own audio) and
 * normally the internet; when it is off, unavailable or fails, the caller falls back to on-device Whisper.
 */
object PhoneRecogniser {
    // Same names AppSettings uses for the "Fast voice (Google)" switch (its constants are private)
    private const val SETTINGS_FILE = "txnkeeper_settings"
    private const val KEY_FAST_VOICE = "fast_voice_google"
    private const val KEY_DOUBLE_CHECK = "double_check_whisper"

    /** What the recogniser produced for one recording. */
    class Result(val text: String, val secondsAfterStop: Double)

    private class Session(val id: Int, val prefersOffline: Boolean) {
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        val done = CompletableDeferred<Result?>()
        @Volatile var startedAt = SystemClock.elapsedRealtime()
        @Volatile var firstWordAt = 0L
        @Volatile var stopAt = 0L
        @Volatile var lastText = ""
        @Volatile var partials = 0
        @Volatile var finalCalls = 0

        /** True when Google listens through the microphone itself (no recording of ours is passed to it). */
        var usesMic = false
        /** Only for [usesMic]: the running recogniser, and why it failed (null when it did not). */
        @Volatile var recognizer: SpeechRecognizer? = null
        @Volatile var error: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    /** The words heard so far, updating while you speak (empty when nothing is being recognised). Screens show it live. */
    var liveText by mutableStateOf("")
        private set

    @Volatile private var appContext: Context? = null
    @Volatile private var session: Session? = null

    /** Gives the recogniser a context (called when the voice screens open). */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    /** On when the setting is on (default) and this phone can do it. */
    fun isEnabled(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
                prefs(context).getBoolean(KEY_FAST_VOICE, true) &&
                SpeechRecognizer.isRecognitionAvailable(context)

    /** Debug only: ask the recogniser to prefer an offline language pack. */
    fun preferOffline(context: Context): Boolean =
        DebugMode.on && prefs(context).getBoolean("debug_google_offline", false)

    fun setPreferOffline(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("debug_google_offline", on).apply()
    }

    /** Whether Whisper double-checks every recording in the background after Google answered ("Double-check" setting). */
    fun alsoRunWhisper(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOUBLE_CHECK, true)

    fun setAlsoRunWhisper(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_DOUBLE_CHECK, on).apply()
    }

    private fun errorName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recogniser busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        else -> "code $code"
    }

    // ---- Google on its own: used while Whisper is not downloaded yet, or the phone cannot run it.
    // This is the ordinary speech recogniser, so it works on every Android version the app supports.

    /** On when the Fast voice setting is on (default) and the phone has a speech service. */
    fun canUseMic(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAST_VOICE, true) &&
                SpeechRecognizer.isRecognitionAvailable(context)

    /** True when the current recording is Google listening through the microphone itself. */
    fun isMicSession(): Boolean = session?.usesMic == true

    /** Why the last Google-only recording gave no text ("network", "no match"...). */
    @Volatile var lastMicError: String? = null
        private set

    /** Starts Google on the microphone. [onEnd] runs on the main thread when it stops listening by itself. */
    fun beginMic(onEnd: () -> Unit) {
        val context = appContext ?: return
        cancel()
        lastMicError = null
        val s = Session(VoiceTap.recordingId, false).apply { usesMic = true }
        session = s
        main.post {
            liveText = ""
            if (session !== s || s.done.isCompleted) return@post
            val recognizer = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (e: Exception) { null }
            if (recognizer == null) {
                s.error = "unavailable"
                s.done.complete(null)
                onEnd()
                return@post
            }
            s.recognizer = recognizer
            fun finish(text: String, error: String?) {
                if (s.done.isCompleted) return
                s.error = error
                if (text.isNotBlank()) liveText = text
                val after = if (s.stopAt > 0) maxOf(0.0, (SystemClock.elapsedRealtime() - s.stopAt) / 1000.0) else 0.0
                s.done.complete(if (text.isBlank()) null else Result(text, after))
                s.recognizer = null
                runCatching { recognizer.destroy() }
                if (DebugMode.on) {
                    VoiceTap.publish("Google (microphone): \"$text\"" + (error?.let { ", error: $it" } ?: ""))
                }
                if (session === s) onEnd()
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank() && !s.done.isCompleted) {
                        s.lastText = t
                        liveText = t
                    }
                }
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    finish(t.ifBlank { s.lastText }, null)
                }
                override fun onError(error: Int) = finish(s.lastText, errorName(error))
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Asks it to wait a little through pauses between two entries (some phones ignore this)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                finish("", "unavailable")
            }
        }
    }

    /** Throws a Google-only recording away (the screen was closed). */
    fun cancel() {
        val s = session ?: return
        if (!s.usesMic) return
        session = null
        main.post {
            val recognizer = s.recognizer
            s.recognizer = null
            s.done.complete(null)
            if (recognizer != null) {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }
    }

    // ---- called by the recorder (any thread)

    /** A recording started: open the recogniser and a pipe that carries the audio to it. */
    fun begin() {
        val context = appContext ?: return
        cancel()
        session = null
        // Cleared even when Google is off, so words from an earlier entry never show up again
        main.post { liveText = "" }
        if (!isEnabled(context)) return
        val s = Session(VoiceTap.recordingId, preferOffline(context))
        session = s

        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: Exception) {
            session = null; return
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        // Feeds the pipe from a queue, so a slow reader never holds up the recorder
        scope.launch(Dispatchers.IO) {
            try {
                FileOutputStream(writeEnd.fileDescriptor).use { out -> for (chunk in s.queue) out.write(chunk) }
            } catch (_: Exception) {
            }
            runCatching { writeEnd.close() }
        }

        main.post {
            val recognizer = try { SpeechRecognizer.createSpeechRecognizer(context) } catch (e: Exception) { null }
            if (recognizer == null) {
                s.done.complete(null)
                runCatching { readEnd.close() }
                return@post
            }
            var finished = false
            fun finish(text: String, error: String?) {
                if (finished) return
                finished = true
                val end = SystemClock.elapsedRealtime()
                val first = if (s.firstWordAt > 0) (s.firstWordAt - s.startedAt) / 1000.0 else null
                val after = if (s.stopAt > 0) maxOf(0.0, (end - s.stopAt) / 1000.0) else null
                if (DebugMode.on) {
                    VoiceTap.addGoogle(
                        VoiceTap.GoogleRun(
                            s.id, text, first, after, "en-IN", null, error,
                            "partials ${s.partials}, results ${s.finalCalls}"
                        )
                    )
                    fun f(v: Double?) = if (v == null) "-" else "%.1f s".format(Locale.US, v)
                    VoiceTap.publish(
                        "Google (phone recogniser, en-IN${if (s.prefersOffline) ", offline preferred" else ""}): \"$text\", " +
                                "first word ${f(first)}, final text ${f(after)} after Stop" + (error?.let { ", error: $it" } ?: "")
                    )
                }
                if (text.isNotBlank() && session === s) liveText = text
                s.done.complete(if (text.isBlank()) null else Result(text, after ?: 0.0))
                runCatching { recognizer.destroy() }
                runCatching { readEnd.close() }
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    s.partials++
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank()) {
                        if (s.firstWordAt == 0L) s.firstWordAt = SystemClock.elapsedRealtime()
                        s.lastText = t
                        if (session === s) liveText = t
                    }
                }
                override fun onResults(results: Bundle?) {
                    s.finalCalls++
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (t.isNotBlank() && s.firstWordAt == 0L) s.firstWordAt = SystemClock.elapsedRealtime()
                    // Sometimes the final result is empty although words were heard: use the last live text
                    finish(t.ifBlank { s.lastText }, null)
                }
                override fun onError(error: Int) = finish(s.lastText, errorName(error))
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, s.prefersOffline)
                // The app's own recording, 16 kHz mono 16-bit, instead of the microphone
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                finish("", "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        }
    }

    /** A chunk of the recording (16 kHz mono 16-bit; only the first [count] samples are valid). */
    fun push(samples: ShortArray, count: Int) {
        val s = session ?: return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            bytes[i * 2] = samples[i].toByte()
            bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
        }
        s.queue.trySend(bytes)
    }

    /** The recording ended: closing the audio makes the recogniser give its final text. */
    fun endInput() {
        val s = session ?: return
        s.stopAt = SystemClock.elapsedRealtime()
        s.queue.close()
        if (s.usesMic) main.post { s.recognizer?.let { runCatching { it.stopListening() } } }
    }

    /** True when this recording is being recognised (so [result] can be asked for). */
    fun isActive(): Boolean = session != null

    /** The text, waiting up to [timeoutMs] after Stop; null when it failed, heard nothing or timed out. */
    suspend fun result(timeoutMs: Long): Result? {
        val s = session ?: return null
        var r = withTimeoutOrNull(timeoutMs) { s.done.await() }
        if (s.usesMic) {
            lastMicError = s.error
            if (r == null && !s.done.isCompleted) {
                // No final answer in time: keep the words shown so far, and shut the recogniser down
                if (s.lastText.isNotBlank()) r = Result(s.lastText, timeoutMs / 1000.0)
                cancel()
            }
        }
        if (session === s) {
            session = null
            main.post { if (session == null) liveText = "" }
        }
        return r
    }
}
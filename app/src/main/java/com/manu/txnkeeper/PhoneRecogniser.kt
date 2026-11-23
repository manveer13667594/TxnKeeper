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
        BuildConfig.DEBUG && prefs(context).getBoolean("debug_google_offline", false)

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

    // ---- called by the recorder (any thread)

    /** A recording started: open the recogniser and a pipe that carries the audio to it. */
    fun begin() {
        val context = appContext ?: return
        session = null
        if (!isEnabled(context)) return
        val s = Session(VoiceTap.recordingId, preferOffline(context))
        session = s
        main.post { liveText = "" }

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
                if (BuildConfig.DEBUG) {
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
                if (text.isNotBlank()) liveText = text
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
                        liveText = t
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
    }

    /** True when this recording is being recognised (so [result] can be asked for). */
    fun isActive(): Boolean = session != null

    /** The text, waiting up to [timeoutMs] after Stop; null when it failed, heard nothing or timed out. */
    suspend fun result(timeoutMs: Long): Result? {
        val s = session ?: return null
        val r = withTimeoutOrNull(timeoutMs) { s.done.await() }
        if (session === s) session = null
        return r
    }
}

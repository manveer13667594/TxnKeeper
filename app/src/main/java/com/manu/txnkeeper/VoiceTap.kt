package com.manu.txnkeeper

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf

/**
 * Debug-only hook on the voice recorder: a debug tool can receive the very same microphone audio the app
 * records (used to feed Google's recogniser live, side by side with Whisper). Nothing is called in release builds.
 */
object VoiceTap {

    /** What Whisper made of one recording (debug comparison). [afterStop] is how long you waited after tapping Stop. */
    data class WhisperRun(val id: Int, val text: String, val afterStop: Double, val transcribe: Double, val audio: Double)

    /** What the phone's Google recogniser made of the same recording. */
    data class GoogleRun(
        val id: Int, val text: String, val firstWord: Double?, val afterStop: Double?,
        val language: String, val detected: String?, val error: String?, val events: String
    )

    val whisperRuns = mutableStateListOf<WhisperRun>()
    val googleRuns = mutableStateListOf<GoogleRun>()

    /** Number of the current recording (1, 2, 3 ...). */
    @Volatile
    var recordingId = 0

    private val ui = Handler(Looper.getMainLooper())

    fun addWhisper(run: WhisperRun) = ui.post { whisperRuns.add(0, run); while (whisperRuns.size > 10) whisperRuns.removeAt(whisperRuns.size - 1) }
    fun addGoogle(run: GoogleRun) = ui.post { googleRuns.add(0, run); while (googleRuns.size > 10) googleRuns.removeAt(googleRuns.size - 1) }

    private val pending = mutableListOf<String>()
    private var rowReady = false

    /** A new recording begins: results of the previous one no longer apply. */
    @Synchronized
    fun newRecording() {
        recordingId++
        pending.clear()
        rowReady = false
    }

    /** A tool's result line for the current recording; it goes into the debug row as soon as that row exists. */
    @Synchronized
    fun publish(line: String) {
        if (rowReady) DebugInsights.addNote(line) else pending.add(line)
    }

    /** The debug row for the current recording now exists: add what finished early. */
    @Synchronized
    fun rowCreated() {
        rowReady = true
        pending.forEach { DebugInsights.addNote(it) }
        pending.clear()
    }
}

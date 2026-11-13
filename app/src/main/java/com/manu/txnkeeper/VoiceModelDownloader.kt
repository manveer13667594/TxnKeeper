package com.manu.txnkeeper

import android.content.Context
import io.whisperkit.WhisperKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the speech model download stands. [fraction] is 0f..1f. */
sealed interface VoiceDownloadState {
    data object Idle : VoiceDownloadState
    data class Downloading(val fraction: Float) : VoiceDownloadState
    data class Paused(val fraction: Float) : VoiceDownloadState
    data class Failed(val message: String, val fraction: Float) : VoiceDownloadState
    data object Done : VoiceDownloadState
}

/**
 * Downloads the speech model. It lives for as long as the app is running, NOT inside a screen, so
 * leaving the voice screen (or the Add page) no longer stops the download. It only stops when the
 * person pauses it. A paused or failed download keeps the part already downloaded and carries on
 * from there when started again, without asking again.
 */
object VoiceModelDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<VoiceDownloadState>(VoiceDownloadState.Idle)
    val state: StateFlow<VoiceDownloadState> = _state.asStateFlow()

    private var job: Job? = null

    /** Bumped on every start / pause so a download that is winding down cannot overwrite the new state. */
    @Volatile private var generation = 0
    @Volatile private var lastFraction = 0f

    /** Starts the download, or carries on after a pause or a failure. Does nothing if it is already running. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (_state.value is VoiceDownloadState.Downloading) return
        if (VoiceEngine.isModelReady(app)) {
            _state.value = VoiceDownloadState.Done
            return
        }
        val mine = ++generation
        val previous = job
        _state.value = VoiceDownloadState.Downloading(lastFraction)
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                WhisperKit.downloadModel(app, VOICE_MODEL) { p ->
                    if (mine == generation) {
                        lastFraction = p
                        _state.value = VoiceDownloadState.Downloading(p)
                    }
                }
                if (mine == generation) _state.value = VoiceDownloadState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) {
                    _state.value = VoiceDownloadState.Failed(
                        e.message ?: "Download failed. Check your internet and try again.",
                        lastFraction
                    )
                }
            }
        }
    }

    /** Stops the download but keeps what was downloaded; [start] carries on from there. */
    @Synchronized
    fun pause() {
        if (_state.value !is VoiceDownloadState.Downloading) return
        generation++
        _state.value = VoiceDownloadState.Paused(lastFraction)
        job?.cancel()
    }
}

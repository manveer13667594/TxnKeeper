package com.manu.txnkeeper

import android.content.Context
import io.whisperkit.WhisperKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the speech model download stands. [fraction] is 0f..1f. */
sealed interface VoiceDownloadState {
    data object Idle : VoiceDownloadState
    data class Downloading(val fraction: Float) : VoiceDownloadState
    data class Failed(val message: String) : VoiceDownloadState
    data object Done : VoiceDownloadState
}

/**
 * Downloads the speech model. It starts by itself when the app opens (MainActivity) if the model is
 * not on the phone yet, and shows its progress as a notification (VoiceDownloadNotification.kt).
 * It lives for as long as the app is running, NOT inside a screen. A download that stopped keeps the
 * part already downloaded and carries on from there: at the next app start, or when the mic is tapped.
 */
object VoiceModelDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<VoiceDownloadState>(VoiceDownloadState.Idle)
    val state: StateFlow<VoiceDownloadState> = _state.asStateFlow()

    /** Starts the download, or carries on after a failure. Does nothing if it is running or not needed. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        // Phones that cannot run Whisper never download it
        if (!WhisperKit.isSupported) return
        if (_state.value is VoiceDownloadState.Downloading) return
        if (VoiceEngine.isModelReady(app)) {
            _state.value = VoiceDownloadState.Done
            return
        }
        _state.value = VoiceDownloadState.Downloading(0f)
        scope.launch {
            val notification = VoiceDownloadNotification(app)
            notification.progress(0f)
            try {
                WhisperKit.downloadModel(app, VOICE_MODEL) { p ->
                    _state.value = VoiceDownloadState.Downloading(p)
                    notification.progress(p)
                }
                _state.value = VoiceDownloadState.Done
                notification.ready()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = VoiceDownloadState.Failed("Voice setup didn't finish. Check your internet.")
                notification.failed()
            }
        }
    }
}

package com.manu.txnkeeper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import com.manu.manudesignsystem.components.ManuCard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.content.ContextCompat
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuFab
import com.manu.txnkeeper.ui.theme.ExpenseColor
import io.whisperkit.TranscribeOptions
import io.whisperkit.WhisperKit
import io.whisperkit.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which speech model voice input uses. This is the ONLY place to change it.
 *   BaseEnQ5  ~57 MB, fastest, English only
 *   BaseQ5    ~57 MB, fast, multilingual
 *   SmallQ5   ~181 MB, about 4x slower, slightly better punctuation/words
 * The model is downloaded once, the first time voice input is used (needs internet that one time).
 */
val VOICE_MODEL = WhisperModel.BaseEnQ5

/** Longest recording; it stops by itself and fills the form. */
private const val MAX_RECORD_SECONDS = 30
private const val SAMPLE_RATE = 16_000

// ------------------------------------------------------------------ recording

/** Records the microphone as 16 kHz mono, the format whisper needs. */
class VoiceRecorder {
    private var job: Job? = null
    private var pcm = ShortArray(SAMPLE_RATE * 15)
    private var size = 0

    /** [onSecond] runs on the main thread once per second, [onLimit] when the time limit is hit. */
    @SuppressLint("MissingPermission") // the banner asks for the permission before calling this
    fun start(
        scope: CoroutineScope,
        onSecond: (Int) -> Unit,
        onLimit: () -> Unit,
        onError: (String) -> Unit
    ) {
        size = 0
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 4096) * 2
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError("Microphone is not available") }
                return@launch
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                withContext(Dispatchers.Main) { onError("Microphone is not available") }
                return@launch
            }
            try {
                rec.startRecording()
                VoiceTap.newRecording()
                VoiceEngine.clearAlternative()
                PhoneRecogniser.begin()
                val buf = ShortArray(4096)
                var lastSecond = -1
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (size + n > pcm.size) pcm = pcm.copyOf(pcm.size * 2)
                    System.arraycopy(buf, 0, pcm, size, n)
                    size += n
                    PhoneRecogniser.push(buf, n)
                    val second = size / SAMPLE_RATE
                    if (second != lastSecond) {
                        lastSecond = second
                        withContext(Dispatchers.Main) { onSecond(second) }
                    }
                    if (second >= MAX_RECORD_SECONDS) {
                        withContext(Dispatchers.Main) { onLimit() }
                        break
                    }
                }
            } finally {
                PhoneRecogniser.endInput()
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    /** Stops recording and returns what was recorded. */
    suspend fun stop(): FloatArray {
        job?.cancelAndJoin()
        job = null
        return FloatArray(size) { pcm[it] / 32768f }
    }
}

// ------------------------------------------------------------------ speech to text

/** What the speech model heard, and how long that took (model loading is not counted). */
data class VoiceTranscript(
    val text: String,
    val seconds: Double,
    val audioSeconds: Double,
    /** How long loading the model took for this run (0 when it was already loaded). */
    val loadSeconds: Double = 0.0,
    /** "Google" (the phone's speech service) or "Whisper" (on-device). */
    val engine: String = "Whisper"
)

/** Holds the one loaded speech model and turns recorded audio into text. */
/** "Downloading voice model… 23% (13 of 57 MB)". The size is the model's approximate size. */
internal fun downloadStatus(fraction: Float): String {
    val total = VOICE_MODEL.approxSizeMb
    val done = (fraction * total).toInt().coerceIn(0, total)
    return "Downloading voice model… ${(fraction * 100).toInt()}% ($done of $total MB)"
}

object VoiceEngine {
    /** The other engine's wording, when it understood the recording differently from the one already used. */
    class Alternative(val text: String, val engine: String)

    var alternative by mutableStateOf<Alternative?>(null)
        private set

    fun clearAlternative() {
        alternative = null
    }

    /** What the parser makes of a text: amount and title of every transaction (empty = nothing usable). */
    private fun parseKey(text: String): List<String> =
        SpokenTransactionParser.parseAll(text).map { "${it.amount}|${(it.title ?: "").trim().lowercase()}" }

    private val mutex = Mutex()
    @Volatile private var kit: WhisperKit? = null

    /** After the voice page is closed the model stays in memory this long, so the next entry starts at once. */
    private const val KEEP_LOADED_MS = 3 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var releaseJob: Job? = null

    /** Debug builds only: how long loading and transcribing took, for the debug card. */
    @Volatile var lastTimings: String = ""
        private set

    private fun cancelRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    /** How long the model took to load the last time it was loaded (during a preload or a recording). */
    @Volatile private var modelLoadSeconds = 0.0

    private suspend fun loadModel(context: Context, onStatus: (String) -> Unit): WhisperKit {
        val loadStart = SystemClock.elapsedRealtime()
        val created = WhisperKit.create(context.applicationContext, VOICE_MODEL) { p ->
            onStatus(downloadStatus(p))
        }
        modelLoadSeconds = (SystemClock.elapsedRealtime() - loadStart) / 1000.0
        return created
    }

    /**
     * Turns a recording into text: the phone's Google recogniser first (it has been listening along, so its
     * text is ready almost at once); if that is off, offline or heard nothing, the on-device Whisper model.
     */
    suspend fun transcribe(
        context: Context,
        samples: FloatArray,
        onStatus: (String) -> Unit
    ): VoiceTranscript {
        val audioSeconds = samples.size / SAMPLE_RATE.toDouble()
        if (PhoneRecogniser.isActive()) {
            onStatus("Transcribing…")
            val google = PhoneRecogniser.result(4_000)
            val googleKey = google?.let { parseKey(it.text) }.orEmpty()
            if (google != null && googleKey.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    lastTimings = "Voice engine: Google (phone recogniser), final text %.1f s after Stop, audio %.1f s"
                        .format(google.secondsAfterStop, audioSeconds)
                }
                if (PhoneRecogniser.alsoRunWhisper(context)) {
                    // Double-check: Whisper listens to the same recording in the background. If it understood the
                    // transaction differently, the Add page offers its wording (tap "Use this" to switch).
                    scope.launch {
                        try {
                            val w = transcribeWithWhisper(context, samples) { }
                            val whisperKey = parseKey(w.text)
                            if (whisperKey.isNotEmpty() && whisperKey != googleKey) {
                                alternative = Alternative(w.text, "Whisper")
                            }
                            if (BuildConfig.DEBUG) {
                                VoiceTap.publish(
                                    "Whisper (comparison): \"${w.text}\", transcribe %.1f s, audio %.1f s".format(w.seconds, w.audioSeconds)
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) VoiceTap.publish("Whisper (comparison): failed (${e.message})")
                        }
                    }
                }
                return VoiceTranscript(
                    text = google.text.trim(), seconds = google.secondsAfterStop,
                    audioSeconds = audioSeconds, engine = "Google"
                )
            }
            // Google heard nothing, or nothing that looks like a transaction: Whisper decides
            if (BuildConfig.DEBUG) VoiceTap.publish("Google gave no usable transaction: used Whisper instead")
        }
        return transcribeWithWhisper(context, samples, onStatus)
    }

    private suspend fun transcribeWithWhisper(
        context: Context,
        samples: FloatArray,
        onStatus: (String) -> Unit
    ): VoiceTranscript {
        cancelRelease()
        val requested = SystemClock.elapsedRealtime()
        return mutex.withLock {
            // Time spent waiting for a preload that was still running, or loading the model right now
            val loadedNow = kit == null
            val loaded = kit ?: run {
                onStatus("Loading voice model…")
                loadModel(context, onStatus).also { kit = it }
            }
            val waitedSeconds = (SystemClock.elapsedRealtime() - requested) / 1000.0
            onStatus("Transcribing…")
            val start = SystemClock.elapsedRealtime()
            // shortWindow: a spoken sentence does not need whisper's full 30 s window
            val text = loaded.transcribe(samples, TranscribeOptions(language = "en", shortWindow = true)).text.trim()
            val result = VoiceTranscript(
                text = text,
                seconds = (SystemClock.elapsedRealtime() - start) / 1000.0,
                audioSeconds = samples.size / SAMPLE_RATE.toDouble(),
                loadSeconds = waitedSeconds
            )
            if (BuildConfig.DEBUG) {
                val loadNote = when {
                    loadedNow -> "model load %.1f s (during this entry)".format(modelLoadSeconds)
                    waitedSeconds > 0.3 -> "model load %.1f s (preload; waited %.1f s for it)".format(modelLoadSeconds, waitedSeconds)
                    else -> "model load %.1f s (preloaded, ready in time)".format(modelLoadSeconds)
                }
                lastTimings = "Voice timing: $loadNote, transcribe %.1f s, audio %.1f s".format(
                    result.seconds, result.audioSeconds
                )
                VoiceTap.addWhisper(
                    VoiceTap.WhisperRun(
                        VoiceTap.recordingId, text, waitedSeconds + result.seconds, result.seconds, result.audioSeconds
                    )
                )
            }
            result
        }
    }

    /** True when the speech model is already on the phone (so no download is needed). */
    fun isModelReady(context: Context): Boolean =
        WhisperKit.isModelDownloaded(context.applicationContext, VOICE_MODEL)

    /** Downloads the model if needed and loads it, without transcribing anything. */
    suspend fun prepare(context: Context, onStatus: (String) -> Unit) {
        cancelRelease()
        mutex.withLock {
            if (kit == null) {
                onStatus(downloadStatus(0f))
                kit = loadModel(context, onStatus)
            }
        }
    }

    /**
     * Loads the model in the background when a voice page opens, so it is ready by the time you finish
     * speaking. Only when it is already downloaded: this never starts a download.
     */
    fun preloadIfReady(context: Context) {
        PhoneRecogniser.attach(context)
        if (kit != null || !isModelReady(context)) return
        val app = context.applicationContext
        scope.launch {
            try {
                prepare(app) { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not important: it simply loads when it is needed
            }
        }
    }

    /** Called when a voice page closes: the model's memory is freed after [KEEP_LOADED_MS], unless it is used again. */
    fun release() {
        cancelRelease()
        releaseJob = scope.launch {
            delay(KEEP_LOADED_MS)
            mutex.withLock {   // waits for a transcription that is still running
                kit?.close()
                kit = null
            }
        }
    }
}

// ------------------------------------------------------------------ the download question

/**
 * Asked before the speech model is downloaded for the first time: what it is, how big, that it needs
 * internet once. Used by the voice screen and by the mic button on the Add page.
 */
@Composable
fun VoiceDownloadDialog(
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ManuDialog(
        visible = visible,
        title = "Download essential resources?",
        confirmText = "Download",
        dismissText = "Not now",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voice entry needs some resources of about ${VOICE_MODEL.approxSizeMb} MB. Click Download to continue.")
            Text(
                text = "(Required to process data on your phone)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // "privacy policy" is a link: tapping it opens the policy page in the browser
            Text(
                text = buildAnnotatedString {
                    append("To know more, read the ")
                    withLink(
                        LinkAnnotation.Url(
                            url = PRIVACY_POLICY_URL,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) { append("privacy policy") }
                    append(".")
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ------------------------------------------------------------------ the button

/**
 * Floating mic button: tap and say one or several transactions ("paid 500 to Rahul for groceries and
 * 120 for chai"), tap again to stop.
 * Progress and problems are shown in the note above the form ([TxnFormState.notice]).
 */
@Composable
fun VoiceFab(form: TxnFormState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val recorder = remember { VoiceRecorder() }

    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<String?>(null) }

    // The download runs in VoiceModelDownloader, not on this page, so it keeps going when you leave
    val download by VoiceModelDownloader.state.collectAsState()
    var sawDownload by remember { mutableStateOf(false) }
    LaunchedEffect(download) {
        when (val d = download) {
            is VoiceDownloadState.Downloading -> {
                sawDownload = true
                form.notice = downloadStatus(d.fraction) + " Tap the mic to pause."
            }
            // The messages below only appear when the change happens while this page is open
            is VoiceDownloadState.Done -> if (sawDownload) {
                sawDownload = false
                form.notice = "Voice model ready. Tap the mic and speak."
            }
            is VoiceDownloadState.Paused -> if (sawDownload) {
                sawDownload = false
                form.notice = "Download paused at ${(d.fraction * 100).toInt()}%. Tap the mic to resume."
            }
            is VoiceDownloadState.Failed -> if (sawDownload) {
                sawDownload = false
                form.notice = d.message + " Tap the mic to try again."
            }
            VoiceDownloadState.Idle -> Unit
        }
    }

    // Load the model in the background now, and free its memory a few minutes after the Add page is closed
    LaunchedEffect(Unit) { VoiceEngine.preloadIfReady(context) }
    DisposableEffect(Unit) { onDispose { VoiceEngine.release() } }

    // While recording, the words you are saying appear in the note above the form
    val live = PhoneRecogniser.liveText
    LaunchedEffect(live, recording) {
        if (recording) form.notice = if (live.isBlank()) "Listening…" else live
    }

    fun finish() {
        if (!recording) return
        recording = false
        scope.launch {
            val samples = recorder.stop()
            if (samples.size < SAMPLE_RATE) {
                form.notice = "That was too short. Tap and speak for at least a second."
                return@launch
            }
            working = "Working…"
            form.notice = "Working…"
            try {
                val heard = VoiceEngine.transcribe(context, samples) { working = it; form.notice = it }
                fillFromSpeech(form, heard.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                form.notice = e.message ?: "Voice input failed"
            } finally {
                working = null
            }
        }
    }

    fun start() {
        form.notice = null
        focusManager.clearFocus()
        seconds = 0
        recording = true
        recorder.start(
            scope = scope,
            onSecond = { seconds = it },
            onLimit = { finish() },
            onError = { recording = false; form.notice = it }
        )
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else form.notice = "Microphone permission is needed for voice input."
    }

    var askDownload by remember { mutableStateOf(false) }

    fun onClick() {
        when {
            working != null -> Unit
            download is VoiceDownloadState.Downloading -> VoiceModelDownloader.pause()
            recording -> finish()
            !VoiceEngine.isModelReady(context) -> {
                // Already agreed to the download before: carry on without asking again
                if (download is VoiceDownloadState.Paused || download is VoiceDownloadState.Failed) {
                    form.notice = null
                    VoiceModelDownloader.start(context)
                } else {
                    askDownload = true
                }
            }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    VoiceDownloadDialog(
        visible = askDownload,
        onConfirm = {
            askDownload = false
            VoiceModelDownloader.start(context)
        },
        onDismiss = { askDownload = false }
    )

    ManuFab(
        onClick = { onClick() },
        modifier = modifier,
        // Red while recording so it is obvious the mic is live
        containerColor = if (recording) ExpenseColor else Color.Unspecified,
        contentColor = if (recording) Color.White else Color.Unspecified,
        contentPadding = if (recording) PaddingValues(horizontal = 20.dp, vertical = 16.dp) else PaddingValues(16.dp)
    ) {
        when {
            working != null || download is VoiceDownloadState.Downloading -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )
            recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Stop, contentDescription = "Stop and fill the form")
                Spacer(Modifier.width(8.dp))
                Text("${seconds}s")
            }
            else -> Icon(Icons.Default.Mic, contentDescription = "Speak a transaction")
        }
    }
}

/**
 * Puts what was said straight into the form, with the words shown above the fields:
 *   Heard: "rahul gave 35rs". Check the form, then save.
 * Several transactions in one recording open one at a time (Save & next).
 * Nothing is saved here.
 */
internal fun fillFromSpeech(form: TxnFormState, text: String) {
    if (text.isBlank()) {
        form.notice = "Could not hear anything. Try again, a bit closer to the mic."
        return
    }
    val found = SpokenTransactionParser.parseAll(text)
    // Debug builds only: what was heard and what the parser made of it
    if (BuildConfig.DEBUG) {
        DebugInsights.recordVoice(
            text,
            found.ifEmpty { listOf(SpokenTransactionParser.parse(text)) },
            listOf(VoiceEngine.lastTimings).filter { it.isNotBlank() }
        )
    }
    if (found.size > 1) {
        form.startBatch(found, text)
        return
    }
    // Nothing with an amount: still use whatever the parser could pick up (a title, a date...)
    val single = found.firstOrNull() ?: SpokenTransactionParser.parse(text)
    if (single.isEmpty) {
        form.notice = "Heard: \"$text\". Couldn't find transaction details in this."
    } else {
        form.fill(single, message = "Heard: \"$text\". Check the form, then save.")
    }
}

/**
 * Shown on the Add page when the two speech engines understood the same recording differently:
 * the form has the first one's wording; this offers the other one.
 */
@Composable
fun VoiceAlternativeBanner(form: TxnFormState, modifier: Modifier = Modifier) {
    val alt = VoiceEngine.alternative ?: return
    ManuCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${alt.engine} heard it differently:",
                style = MaterialTheme.typography.labelLarge
            )
            Text(text = "\u201C${alt.text}\u201D", style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = {
                    fillFromSpeech(form, alt.text)
                    VoiceEngine.clearAlternative()
                }) { Text("Use this") }
                TextButton(onClick = { VoiceEngine.clearAlternative() }) { Text("Keep current") }
            }
        }
    }
}

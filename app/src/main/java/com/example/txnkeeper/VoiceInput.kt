package com.example.txnkeeper

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCustomDialog
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuTextButton
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.content.ContextCompat
import com.manu.manudesignsystem.components.ManuListItem
import io.whisperkit.TranscribeOptions
import io.whisperkit.WhisperKit
import io.whisperkit.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
                val buf = ShortArray(4096)
                var lastSecond = -1
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (size + n > pcm.size) pcm = pcm.copyOf(pcm.size * 2)
                    System.arraycopy(buf, 0, pcm, size, n)
                    size += n
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
data class VoiceTranscript(val text: String, val seconds: Double, val audioSeconds: Double)

/** Holds the one loaded speech model and turns recorded audio into text. */
object VoiceEngine {
    private val mutex = Mutex()
    @Volatile private var kit: WhisperKit? = null

    suspend fun transcribe(
        context: Context,
        samples: FloatArray,
        onStatus: (String) -> Unit
    ): VoiceTranscript = mutex.withLock {
        val loaded = kit ?: run {
            onStatus("Loading voice model…")
            WhisperKit.create(context.applicationContext, VOICE_MODEL) { p ->
                onStatus("Downloading voice model… ${(p * 100).toInt()}%")
            }.also { kit = it }
        }
        onStatus("Transcribing…")
        val start = SystemClock.elapsedRealtime()
        val text = loaded.transcribe(samples, TranscribeOptions(language = "en")).text.trim()
        VoiceTranscript(
            text = text,
            seconds = (SystemClock.elapsedRealtime() - start) / 1000.0,
            audioSeconds = samples.size / SAMPLE_RATE.toDouble()
        )
    }

    /** Frees the model's memory. It reloads (a second or two) the next time it is needed. */
    fun release() {
        kit?.close()
        kit = null
    }
}

// ------------------------------------------------------------------ the button

/** Tap to speak a transaction ("paid 500 to Rahul for groceries"); tap again to fill the form. */
@Composable
fun VoiceBanner(form: TxnFormState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val recorder = remember { VoiceRecorder() }

    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Set when a transcription finishes; the dialog below shows it
    var result by remember { mutableStateOf<VoiceTranscript?>(null) }

    // Free the model's memory when the Add page is closed
    DisposableEffect(Unit) { onDispose { VoiceEngine.release() } }

    fun finish() {
        if (!recording) return
        recording = false
        scope.launch {
            val samples = recorder.stop()
            if (samples.size < SAMPLE_RATE) {
                error = "That was too short. Tap and speak for at least a second."
                return@launch
            }
            working = "Working…"
            try {
                // Nothing is filled yet: the dialog shows the text and the parsed fields first
                result = VoiceEngine.transcribe(context, samples) { working = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Voice input failed"
            } finally {
                working = null
            }
        }
    }

    fun start() {
        error = null
        focusManager.clearFocus()
        seconds = 0
        recording = true
        recorder.start(
            scope = scope,
            onSecond = { seconds = it },
            onLimit = { finish() },
            onError = { recording = false; error = it }
        )
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else error = "Microphone permission is needed for voice input."
    }

    fun onClick() {
        when {
            working != null -> Unit
            recording -> finish()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ManuListItem(
        title = when {
            working != null -> working!!
            recording -> "Listening… ${seconds}s"
            else -> "Speak transaction"
        },
        description = when {
            working != null -> "Please wait"
            recording -> "Tap to stop and fill the form"
            error != null -> error!!
            else -> "Say it, e.g. \"paid 500 to Rahul for groceries\""
        },
        time = "",
        leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
        onClick = { onClick() }
    )

    result?.let { heard ->
        VoiceResultDialog(
            heard = heard,
            onClose = { result = null },
            onFill = { parsed ->
                form.fill(parsed, message = "Heard: \"${heard.text}\". Check the form, then save.")
            }
        )
    }
}

private fun voiceSummaryLines(p: ParsedTransaction): List<String> = buildList {
    p.title?.let { add("Title: $it") }
    p.amount?.let {
        add("Amount: ₹" + if (it % 1.0 == 0.0) it.toLong().toString() else it.toString())
    }
    p.type?.let { add("Type: " + it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
    p.paymentMethod?.let { add("Payment: $it") }
    p.dateMillis?.let {
        add(
            "Date: " + java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        )
    }
    p.description?.let { add(it) }
}

/** What was heard, how long it took, and what the parser found. "Fill form" only fills; it never saves. */
@Composable
private fun VoiceResultDialog(
    heard: VoiceTranscript,
    onClose: () -> Unit,
    onFill: (ParsedTransaction) -> Unit
) {
    val context = LocalContext.current
    val parsed = remember(heard) { SpokenTransactionParser.parse(heard.text) }
    val summary = remember(parsed) { voiceSummaryLines(parsed) }

    ManuCustomDialog(
        onDismissRequest = onClose,
        title = "Heard",
        buttons = {
            ManuOutlineButton(onClick = onClose) { Text("Close") }
            if (heard.text.isNotBlank()) {
                ManuTextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Voice text", heard.text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            }
            if (!parsed.isEmpty) {
                ManuButton(onClick = {
                    onFill(parsed)
                    onClose()
                }) { Text("Fill form") }
            }
        }
    ) {
        if (heard.text.isBlank()) {
            Text("Could not hear anything. Try again, a bit closer to the mic.")
        } else {
            Text(
                "Model: ${VOICE_MODEL.name}  ·  " +
                        "${"%.1f".format(java.util.Locale.US, heard.seconds)}s to transcribe " +
                        "${"%.1f".format(java.util.Locale.US, heard.audioSeconds)}s of audio",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text("Full text", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
            SelectionContainer {
                Text(heard.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (summary.isEmpty()) {
                Text(
                    "Couldn't find transaction details in this.",
                    modifier = Modifier.padding(top = 12.dp)
                )
            } else {
                Text(
                    "Found",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                summary.forEach { Text(it) }
                Text(
                    "Check these, then tap Fill form. Nothing is saved automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
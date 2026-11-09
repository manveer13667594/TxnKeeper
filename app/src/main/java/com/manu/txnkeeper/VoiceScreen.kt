package com.manu.txnkeeper

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.Loop
import app.rive.runtime.kotlin.core.Rive
import com.manu.manudesignsystem.components.ManuFab
import com.manu.manudesignsystem.theme.ExpenseColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Names inside voice_speaking.riv (the face only, no buttons). If the file is replaced
 * with a new one, these three animations and the artboard name are what to update.
 */
private const val RIVE_ARTBOARD = "Eani_Shared_Rig_Test 2"
/** Background behind the face, the same in light and dark mode. */
private val FACE_BACKGROUND = Color(0xFFF7F7F7)
private const val ANIM_IDLE = "Idle"                      // waiting for the mic to be tapped
private const val ANIM_TALKING = "Talking_ shared"        // while recording
private const val ANIM_CELEBRATE = "Celebration_Shared"   // while the speech is being turned into text

/** Shortest recording that is worth sending to the speech model (one second at 16 kHz). */
private const val MIN_SAMPLES = 16_000

/**
 * Full-screen voice entry, opened by swiping from right to left on the home screen:
 * the speaking animation in the middle, the mic button at the bottom centre.
 * Tap the mic and say one or more transactions, tap it again to stop. The words are
 * turned into text and handed to [onHeard], which opens the Add page filled in.
 * Swipe left-to-right, the back arrow or the system back closes it.
 */
@Composable
fun VoiceScreen(
    onClose: () -> Unit,
    onHeard: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val recorder = remember { VoiceRecorder() }

    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // The animation currently playing (not state: changing it must not recompose anything)
    val shownAnimation = remember { object { var value = ANIM_IDLE } }

    BackHandler(onBack = onClose)

    // Closing the screen cancels the scope (stops the mic) and frees the model's memory
    DisposableEffect(Unit) { onDispose { VoiceEngine.release() } }

    fun finish() {
        if (!recording) return
        recording = false
        scope.launch {
            val samples = recorder.stop()
            if (samples.size < MIN_SAMPLES) {
                message = "That was too short. Tap the mic and speak for at least a second."
                return@launch
            }
            working = "Working…"
            try {
                val heard = VoiceEngine.transcribe(context, samples) { working = it }
                if (heard.text.isBlank()) {
                    message = "Could not hear anything. Try again, a bit closer to the mic."
                } else {
                    onHeard(heard.text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Voice input failed"
            } finally {
                working = null
            }
        }
    }

    fun start() {
        message = null
        focusManager.clearFocus()
        seconds = 0
        recording = true
        recorder.start(
            scope = scope,
            onSecond = { seconds = it },
            onLimit = { finish() },
            onError = { recording = false; message = it }
        )
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else message = "Microphone permission is needed for voice input."
    }

    fun onMicClick() {
        when {
            working != null -> Unit
            recording -> finish()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .swipeRightTo(onClose)
        ) {
            // ---- Top bar: back arrow and title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Voice entry",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            // ---- The speaking animation, in the middle of the screen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                // The drawing has black hair, so it sits on a light card: it stays readable in dark mode too
                AndroidView(
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
//                        .clip(RoundedCornerShape(32.dp))
                        .clip(shape = CircleShape)
                        .background(FACE_BACKGROUND),
                    factory = { ctx ->
                        // Loads Rive's native library. Rive normally does this by itself at app start,
                        // but that did not run here, so do it explicitly (safe to call more than once).
                        Rive.init(ctx.applicationContext)
                        RiveAnimationView(ctx).apply {
                            setRiveResource(
                                resId = R.raw.voice_speaking,
                                artboardName = RIVE_ARTBOARD,
                                animationName = ANIM_IDLE,
                                autoplay = true,
                                fit = Fit.CONTAIN,
                                loop = Loop.LOOP
                            )
                        }
                    },
                    update = { view ->
                        // Idle -> Talking while recording -> Celebration while transcribing
                        val wanted = when {
                            working != null -> ANIM_CELEBRATE
                            recording -> ANIM_TALKING
                            else -> ANIM_IDLE
                        }
                        if (wanted != shownAnimation.value) {
                            shownAnimation.value = wanted
                            runCatching {
                                view.stop()
                                view.play(wanted, Loop.LOOP)
                            }
                        }
                    }
                )
            }

            // ---- What is happening, then the mic at the bottom centre
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = when {
                        working != null -> working!!
                        recording -> "Listening… ${seconds}s. Tap to stop."
                        message != null -> message!!
                        else -> "Tap the mic and say a transaction, like \"paid 500 to Rahul\"."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                ManuFab(
                    onClick = { onMicClick() },
                    // Red while recording so it is obvious the mic is live
                    containerColor = if (recording) ExpenseColor else Color.Unspecified,
                    contentColor = if (recording) Color.White else Color.Unspecified,
                    contentPadding = PaddingValues(20.dp)
                ) {
                    when {
                        working != null -> CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp
                        )
                        recording -> Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop and fill the form",
                            modifier = Modifier.size(28.dp)
                        )
                        else -> Icon(
                            Icons.Default.Mic,
                            contentDescription = "Speak a transaction",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
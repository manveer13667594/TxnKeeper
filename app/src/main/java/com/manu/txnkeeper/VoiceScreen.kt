package com.manu.txnkeeper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.Fit
import app.rive.runtime.kotlin.core.Loop
import app.rive.runtime.kotlin.core.Rive
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuFab
import com.manu.manudesignsystem.theme.ManuDesignSystem
import com.manu.manudesignsystem.theme.ExpenseColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
private const val ANIM_CELEBRATE = "Celebration_Shared"   // once, right when you stop talking
private const val ANIM_CURIOUS = "Cusious_shared"        // (sic) the confused face "??", only when nothing was understood
private const val CELEBRATE_MS = 2000L                    // how long the celebration plays before the waiting faces

/** While the text is being worked out (after the celebration): friendly faces, one after another. */
private val WAITING_ANIMS = listOf("Idea_shared", "Confident_Shared", "Happy_Shared")
private const val WAITING_ANIM_MS = 2500L                 // how long each waiting face stays

/**
 * One tip is shown per visit, and the next visit shows the next one, so they do not repeat until all
 * have been seen. Keep each under about 110 characters (the tip area has a fixed height).
 * The basics (what to say, swipe shortcuts) are left for the welcome tutorial.
 * All the speech examples here are understood by the speech parser.
 * The tips can be turned off in Settings.
 */
private val VOICE_TIPS = listOf(
    "Your voice is turned into text on this phone. Recordings are not saved and nothing is uploaded.",
    "Say several at once: \u201CChai 80, petrol 800, lunch 250\u201D. They open one by one to check.",
    "Add a day: \u201Cyesterday\u201D, \u201Ctwo days ago\u201D or \u201Con Monday\u201D.",
    "No financial data leaves your phone. Everything you record stays on this device.",
    "Say how you paid: \u201Cby UPI\u201D, \u201Cby card\u201D or \u201Cin cash\u201D.",
    "After the first download, voice entry works without internet.",
    "Numbers can be words: \u201Ctwo thousand five hundred\u201D, \u201C5k\u201D or \u201C1.5 lakh\u201D.",
    "Money coming in? Say \u201Creceived 2000 from Amit\u201D or \u201Csalary credited 30000\u201D."
)
private const val TIPS_PREFS = "voice_tips"
private const val TIPS_NEXT = "next"

/** Height reserved for the tip, so the face stays in the same place with or without it. */
private val TIP_AREA_HEIGHT = 112.dp

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
    onHeard: (String) -> Unit,
    /** False when tips are turned off in Settings. */
    showTips: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val recorder = remember { VoiceRecorder() }

    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Today's tip: the next one in the list each time this screen opens
    // (null when tips are off; the list only moves on when a tip is actually shown)
    val tip = remember(showTips) {
        if (!showTips) return@remember null
        val prefs = context.getSharedPreferences(TIPS_PREFS, Context.MODE_PRIVATE)
        val index = prefs.getInt(TIPS_NEXT, 0).mod(VOICE_TIPS.size)
        prefs.edit().putInt(TIPS_NEXT, (index + 1) % VOICE_TIPS.size).apply()
        VOICE_TIPS[index]
    }
    var tipDismissed by remember { mutableStateOf(false) }
    // Once you start recording the tip is gone for this visit (it does not come back afterwards)
    LaunchedEffect(recording) { if (recording) tipDismissed = true }
    // Fades out when dismissed
    val tipAlpha by animateFloatAsState(
        targetValue = if (tipDismissed) 0f else 1f,
        label = "tipAlpha"
    )

    // While transcribing: step 0 is the celebration (once), then the waiting faces take turns
    var workStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(working != null) {
        if (working != null) {
            workStep = 0
            delay(CELEBRATE_MS)
            var step = 1
            while (true) {
                workStep = step
                delay(WAITING_ANIM_MS)
                step++
            }
        }
    }

    // True when the last recording gave no transaction: the confused face stays until the next try
    var notDetected by remember { mutableStateOf(false) }

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
                    notDetected = true
                    message = "Could not hear anything. Try again, a bit closer to the mic."
                } else if (SpokenTransactionParser.parseAll(heard.text).isEmpty() &&
                    SpokenTransactionParser.parse(heard.text).isEmpty
                ) {
                    // Heard words, but no amount or details: stay here and let the person try again
                    notDetected = true
                    message = "Heard: \"${heard.text}\". No transaction found, try again."
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
        notDetected = false
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
                        // Idle -> Talking while recording -> Celebration (once) -> Thinking while transcribing
                        val wanted = when {
                            working != null && workStep == 0 -> ANIM_CELEBRATE
                            working != null -> WAITING_ANIMS[(workStep - 1) % WAITING_ANIMS.size]
                            recording -> ANIM_TALKING
                            notDetected -> ANIM_CURIOUS
                            else -> ANIM_IDLE
                        }
                        if (wanted != shownAnimation.value) {
                            shownAnimation.value = wanted
                            runCatching {
                                view.stop()
                                view.play(wanted, if (wanted == ANIM_CELEBRATE) Loop.ONESHOT else Loop.LOOP)
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
                // One tip, with a small cross at its top right. The area keeps its height, so the face
                // stays where it is whether the tip is showing or not. No area at all when tips are off.
                if (tip != null) {
                    Box(modifier = Modifier.fillMaxWidth().height(TIP_AREA_HEIGHT)) {
                        if (tipAlpha > 0f) {
                            ManuCard(modifier = Modifier.fillMaxWidth().alpha(tipAlpha)) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                top = ManuDesignSystem.spacing.md,
                                                bottom = ManuDesignSystem.spacing.md,
                                                end = 28.dp
                                            ),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Tip",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = tip,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { tipDismissed = true },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss tip",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Status line: only shown when there is something to say (listening, working, a problem).
                // The space is always kept, so the face and the mic do not move when it appears.
                val status = when {
                    working != null -> working
                    recording -> "Listening… ${seconds}s. Tap to stop."
                    else -> message
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
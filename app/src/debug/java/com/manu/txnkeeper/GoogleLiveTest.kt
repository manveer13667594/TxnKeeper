package com.manu.txnkeeper

import android.os.Build
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuChip
import java.util.Locale

/*
 * DEBUG BUILDS ONLY (src/debug; src/release has an empty version of GoogleLiveCard).
 * Shows which voice engine is in use and compares Google's recogniser with Whisper on the same recordings:
 * the text and how long you waited after tapping Stop.
 */
@Composable
fun GoogleLiveCard() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var alsoWhisper by remember { mutableStateOf(PhoneRecogniser.alsoRunWhisper(context)) }
    var offline by remember { mutableStateOf(PhoneRecogniser.preferOffline(context)) }
    val state = when {
        Build.VERSION.SDK_INT < 33 -> "Google voice needs Android 13+ (this is Android ${Build.VERSION.RELEASE}): Whisper is used"
        !SpeechRecognizer.isRecognitionAvailable(context) -> "No speech service found on this phone: Whisper is used"
        !PhoneRecogniser.isEnabled(context) -> "Fast voice is off in Settings → Defaults: Whisper is used"
        else -> "Google (phone recogniser) first, Whisper as the fallback"
    }
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "DEBUG · voice engines: Google vs Whisper",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open) {
                Text(state, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManuChip(
                        label = "Also run Whisper",
                        selected = alsoWhisper,
                        onClick = { alsoWhisper = !alsoWhisper; PhoneRecogniser.setAlsoRunWhisper(context, alsoWhisper) }
                    )
                    ManuChip(
                        label = "Prefer offline",
                        selected = offline,
                        onClick = { offline = !offline; PhoneRecogniser.setPreferOffline(context, offline) }
                    )
                }
                CompareTable()
            }
        }
    }
}

private fun sec(v: Double?) = if (v == null) "-" else "%.1f s".format(Locale.US, v)

/** Last recordings, Whisper and Google side by side: the text and how long you waited after Stop. */
@Composable
private fun CompareTable() {
    val whisper = VoiceTap.whisperRuns
    val google = VoiceTap.googleRuns
    val ids = (whisper.map { it.id } + google.map { it.id }).distinct().sortedDescending().take(6)
    if (ids.isEmpty()) return
    val w = ids.mapNotNull { id -> whisper.firstOrNull { it.id == id } }
    val gg = ids.mapNotNull { id -> google.firstOrNull { it.id == id } }
    if (w.isNotEmpty() && gg.isNotEmpty()) {
        val both = ids.filter { id -> whisper.any { it.id == id } && google.any { it.id == id } }
        val wAvg = both.mapNotNull { id -> whisper.firstOrNull { it.id == id }?.afterStop }.average()
        val gAvg = both.mapNotNull { id -> google.firstOrNull { it.id == id }?.afterStop }.average()
        if (both.isNotEmpty() && !wAvg.isNaN() && !gAvg.isNaN()) {
            Text(
                "Average wait after Stop (${both.size} recordings): Whisper ${sec(wAvg)} · Google ${sec(gAvg)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    ids.forEach { id ->
        val a = whisper.firstOrNull { it.id == id }
        val b = google.firstOrNull { it.id == id }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "#$id" + (a?.let { " · audio ${sec(it.audio)}" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (a == null) "Whisper: waiting…"
                else "Whisper: ${sec(a.afterStop)} after Stop (transcribe ${sec(a.transcribe)}): \"${a.text}\"",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
            )
            Text(
                if (b == null) "Google: waiting…"
                else "Google: ${sec(b.afterStop)} after Stop, first word ${sec(b.firstWord)}, " +
                    "${if (b.language == "auto") "auto" else b.language}${b.detected?.let { " → $it" } ?: ""}" +
                    (b.error?.let { ", error: $it" } ?: "") + " (${b.events}): \"${b.text}\"",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
            )
        }
    }
}

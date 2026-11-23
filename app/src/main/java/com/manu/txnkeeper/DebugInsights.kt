package com.manu.txnkeeper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuCard

/*
 * DEBUG BUILDS ONLY. Shows what a scan or a voice recording found: the raw text, and which value went
 * into which field of the Add form, so you can see what the parsers get right and wrong.
 * Everything checks BuildConfig.DEBUG, which is a constant false in release builds, so R8 removes this
 * code from the release APK: nothing is recorded and nothing is shown there.
 */

/** What one scan or recording found. */
class DebugReport(
    /** "Scan" or "Voice". */
    val source: String,
    /** Everything the OCR read, or the words the speech recogniser heard. */
    val rawText: String,
    /** What the parser put in the form fields (one per spoken entry; one for a scan). */
    val entries: List<ParsedTransaction>,
    /** Extra lines: object labels and scores, the product-name guess, title suggestions, timing ... */
    val notes: List<String>
)

object DebugInsights {
    /** The latest report; the Add screen shows it. Null in release builds. */
    var report by mutableStateOf<DebugReport?>(null)

    fun recordScan(rawText: String, parsed: ParsedTransaction, notes: List<String>) {
        if (!BuildConfig.DEBUG) return
        report = DebugReport("Scan", rawText, listOf(parsed), notes)
        DebugDataLog.addSamples("Scan", rawText, listOf(parsed), notes)
    }

    fun recordVoice(heard: String, entries: List<ParsedTransaction>, notes: List<String> = emptyList()) {
        if (!BuildConfig.DEBUG) return
        report = DebugReport("Voice", heard, entries, notes)
        DebugDataLog.addSamples("Voice", heard, entries, notes)
        VoiceTap.rowCreated()
    }

    /** Adds a line to the card's "Other" notes and to the latest log rows (used by the voice comparison, which finishes a bit later). */
    fun addNote(line: String) {
        if (!BuildConfig.DEBUG) return
        report = report?.let { DebugReport(it.source, it.rawText, it.entries, it.notes + line) }
        DebugDataLog.appendNote(line)
    }

    fun clear() {
        if (!BuildConfig.DEBUG) return
        report = null
    }
}

private fun money(v: Double): String = "₹" + if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** The form fields with what the parser found for each ("not found" when nothing). */
private fun fieldRows(p: ParsedTransaction): List<Pair<String, String?>> = listOf(
    "Title" to p.title,
    "Amount" to p.amount?.let { money(it) },
    "Type" to p.type,
    "Payment" to p.paymentMethod,
    "Date" to p.dateMillis?.let {
        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
    },
    "Description" to p.description
)

/** Debug-only card for the top of the Add screen. Draws nothing in release builds or with no report. */
@Composable
fun DebugInsightsCard(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return
    val context = androidx.compose.ui.platform.LocalContext.current
    var logOpen by remember { mutableStateOf(false) }
    if (logOpen) DebugDataDialog(onDismiss = { logOpen = false })
    val report = DebugInsights.report
    if (report == null) {
        // Nothing scanned yet: just the way into the saved samples
        ManuCard(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { logOpen = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DEBUG · transactions log (${DebugDataLog.samples.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text("Open", style = MaterialTheme.typography.labelMedium)
            }
        }
        return
    }
    var open by remember(report) { mutableStateOf(true) }
    val found = report.entries.sumOf { e -> fieldRows(e).count { it.second != null } }
    val total = report.entries.size * 6

    ManuCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DEBUG · ${report.source} · $found/$total fields found",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
            }
            if (open) {
                Text("Raw text", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(
                        text = report.rawText.ifBlank { "(nothing read)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                report.entries.forEachIndexed { index, entry ->
                    Text(
                        text = if (report.entries.size > 1) "Entry ${index + 1}: text → form fields" else "Text → form fields",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    fieldRows(entry).forEach { (field, value) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = (if (value != null) "✓ " else "✗ ") + field,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = value ?: "not found",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (report.notes.isNotEmpty()) {
                    Text("Other", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    report.notes.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { logOpen = true }) { Text("Log (${DebugDataLog.samples.size})") }
                    TextButton(onClick = {
                        val rows = DebugDataLog.samples.filter { it.id in DebugDataLog.latestGroup }
                        DebugDataLog.share(context, rows, redact = false, format = DebugExportFormat.CSV)
                    }) { Text("Share this") }
                    TextButton(onClick = { DebugInsights.clear() }) { Text("Clear card") }
                }
            }
        }
    }
}

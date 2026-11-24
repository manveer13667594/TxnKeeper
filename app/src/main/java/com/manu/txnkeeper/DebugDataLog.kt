package com.manu.txnkeeper

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.manu.manudesignsystem.components.ManuCard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/*
 * DEBUG BUILDS ONLY. Keeps a private log for improving the scan and voice parsers, ONE ROW PER
 * TRANSACTION: where it came from (Scan / Voice / Manual), the raw text, the fields the parser detected,
 * and the fields you actually saved, so the mistakes can be found. The rows can be exported as a CSV
 * (opens in Excel / Google Sheets) or a JSON file: all of them, the latest scan or recording, or only the
 * ticked ones. Everything checks BuildConfig.DEBUG (constant false in release),
 * so none of this exists in the release APK, and nothing ever leaves the phone unless you share the file.
 */

/** One transaction. [detected] is null for a manually typed one; [saved] is null until it is saved. */
data class DebugSample(
    val id: String,
    val time: Long,
    /** "Scan", "Voice" or "Manual". */
    val source: String,
    val rawText: String,
    val detected: Map<String, String?>?,
    val saved: Map<String, String?>? = null,
    val notes: List<String> = emptyList(),
    /** Entries of one recording share a group. */
    val group: String = id
) {
    private fun same(a: String?, b: String?) = (a ?: "").trim().equals((b ?: "").trim(), ignoreCase = true)

    /** The fields you changed from what the parser detected (empty for manual or unsaved ones). */
    fun editedFields(): List<String> {
        val found = detected ?: return emptyList()
        val final = saved ?: return emptyList()
        return FIELD_NAMES.filter { !same(found[it], final[it]) }
    }

    val status: String
        get() = when {
            saved == null -> "not saved"
            detected == null -> "manual, saved"
            editedFields().isEmpty() -> "saved, no edits"
            else -> "saved, edited"
        }
}

private val FIELD_NAMES = listOf("title", "amount", "type", "payment", "date", "description")

private fun fmtAmount(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(Locale.US, v)

private fun dayOf(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

fun ParsedTransaction.toFieldMap(): Map<String, String?> = mapOf(
    "title" to title,
    "amount" to amount?.let { fmtAmount(it) },
    "type" to type,
    "payment" to paymentMethod,
    "date" to dateMillis?.let { dayOf(it) },
    "description" to description
)

private fun com.manu.txnkeeper.data.local.TransactionEntity.toFieldMap(): Map<String, String?> = mapOf(
    "title" to title,
    "amount" to fmtAmount(amountPaise / 100.0),
    "type" to type,
    "payment" to paymentMethod,
    "date" to dayOf(dateTime),
    "description" to description.ifBlank { null }
)

enum class DebugExportFormat(val label: String, val extension: String, val mime: String) {
    CSV("CSV (Excel / Sheets)", "csv", "text/csv"),
    JSON("JSON", "json", "application/json")
}

object DebugDataLog {
    /** Newest first. Compose reads this, so the screens update by themselves. */
    var samples by mutableStateOf<List<DebugSample>>(emptyList())
        private set

    /** The rows of the latest scan or recording (what the insights card is showing). */
    var latestGroup by mutableStateOf<List<String>>(emptyList())
        private set

    private var file: File? = null
    /** Entry number -> sample id, for the scan / recording being reviewed. */
    private val pending = mutableMapOf<Int, String>()
    private val writer = Executors.newSingleThreadExecutor()

    /** Opens (and reads) the log file. Called once when the app starts; does nothing in release. */
    fun attach(context: Context) {
        if (!DebugMode.on || file != null) return
        val f = File(context.applicationContext.filesDir, "debug_samples.jsonl")
        file = f
        samples = try {
            if (f.isFile) f.readLines().mapNotNull { fromJson(it) }.reversed() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** A scan or recording was read: one row per detected entry (a scan has one). */
    fun addSamples(source: String, rawText: String, entries: List<ParsedTransaction>, notes: List<String>) {
        if (!DebugMode.on) return
        val group = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val created = entries.mapIndexed { i, e ->
            DebugSample(
                id = UUID.randomUUID().toString(), time = now + i, source = source, rawText = rawText,
                detected = e.toFieldMap(), notes = notes, group = group
            )
        }
        pending.clear()
        created.forEachIndexed { i, s -> pending[i] = s.id }
        latestGroup = created.map { it.id }
        samples = created.reversed() + samples
        persist()
    }

    /** A speech-engine test (no form fields): one row with the heard text and the timing notes. */
    fun addSpeechTest(source: String, heard: String, notes: List<String>) {
        if (!DebugMode.on) return
        val id = UUID.randomUUID().toString()
        val sample = DebugSample(id = id, time = System.currentTimeMillis(), source = source, rawText = heard, detected = null, notes = notes)
        samples = listOf(sample) + samples
        persist()
    }

    private fun update(id: String, change: (DebugSample) -> DebugSample) {
        samples = samples.map { if (it.id == id) change(it) else it }
    }

    /** A transaction was saved from the Add screen: keep what was really saved. */
    fun markSaved(entity: com.manu.txnkeeper.data.local.TransactionEntity, entryIndex: Int) {
        if (!DebugMode.on) return
        val id = pending.remove(entryIndex)
        if (id != null) {
            update(id) { it.copy(saved = entity.toFieldMap()) }
        } else {
            // Typed by hand (or not from the current scan): a row with only the saved values
            val now = System.currentTimeMillis()
            val manual = DebugSample(
                id = UUID.randomUUID().toString(), time = now, source = "Manual", rawText = "",
                detected = null, saved = entity.toFieldMap()
            )
            samples = listOf(manual) + samples
        }
        persist()
    }

    /** "Save & exit" on a recording: saved ones are matched to the detected entries by amount, then by order. */
    fun markSavedAll(entities: List<com.manu.txnkeeper.data.local.TransactionEntity>) {
        if (!DebugMode.on) return
        entities.forEach { e ->
            val amount = fmtAmount(e.amountPaise / 100.0)
            val index = pending.keys.sorted().firstOrNull { k ->
                samples.firstOrNull { it.id == pending[k] }?.detected?.get("amount") == amount
            } ?: pending.keys.minOrNull() ?: -1
            markSaved(e, index)
        }
    }

    /** Adds a note to the rows of the latest scan / recording. */
    fun appendNote(line: String) {
        if (!DebugMode.on) return
        if (latestGroup.isEmpty()) return
        samples = samples.map { if (it.id in latestGroup) it.copy(notes = it.notes + line) else it }
        persist()
    }

    fun delete(ids: Set<String>) {
        if (!DebugMode.on) return
        samples = samples.filter { it.id !in ids }
        pending.entries.removeAll { it.value in ids }
        persist()
    }

    private fun persist() {
        val f = file ?: return
        val lines = samples.reversed().map { toJson(it, redact = false).toString() }
        writer.execute {
            try {
                f.writeText(lines.joinToString("\n") + "\n")
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ JSON

    private fun fieldsJson(m: Map<String, String?>?, redact: Boolean): Any {
        if (m == null) return JSONObject.NULL
        return JSONObject().also { o ->
            FIELD_NAMES.forEach { o.put(it, m[it]?.let { v -> if (redact) mask(v) else v } ?: JSONObject.NULL) }
        }
    }

    fun toJson(s: DebugSample, redact: Boolean): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("group", s.group)
        put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(s.time)))
        put("source", s.source)
        put("status", s.status)
        put("raw_text", if (redact) mask(s.rawText) else s.rawText)
        put("detected", fieldsJson(s.detected, redact))
        put("saved", fieldsJson(s.saved, redact))
        put("edited_fields", JSONArray(s.editedFields()))
        put("notes", JSONArray(s.notes.map { if (redact) mask(it) else it }))
    }

    private fun fromJson(line: String): DebugSample? = try {
        val o = JSONObject(line)
        fun fields(name: String): Map<String, String?>? {
            if (o.isNull(name)) return null
            val j = o.getJSONObject(name)
            return FIELD_NAMES.associateWith { if (j.isNull(it)) null else j.getString(it) }
        }
        val notes = o.getJSONArray("notes").let { a -> (0 until a.length()).map { a.getString(it) } }
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(o.getString("time"))?.time ?: 0L
        DebugSample(
            o.getString("id"), time, o.getString("source"), o.getString("raw_text"),
            fields("detected"), fields("saved"), notes, o.optString("group", o.getString("id"))
        )
    } catch (e: Exception) {
        null
    }

    /** Hides long digit runs (account, card, phone, reference numbers) and UPI ids / emails. Amounts stay. */
    private fun mask(text: String): String = text
        .replace(Regex("[\\w.\\-]+@[\\w.\\-]+"), "<id>")
        .replace(Regex("\\d{8,}"), "<num>")

    // ------------------------------------------------------------ CSV

    private fun cell(value: String?): String {
        val v = value ?: ""
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    /** One row per transaction; opens in Excel / Google Sheets. */
    private fun toCsv(chosen: List<DebugSample>, redact: Boolean): String {
        fun m(v: String?) = if (redact && v != null) mask(v) else v
        val header = listOf("time", "source", "status", "raw_text") +
            FIELD_NAMES.map { "detected_$it" } + FIELD_NAMES.map { "saved_$it" } + listOf("edited_fields", "notes")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val rows = chosen.sortedBy { it.time }.map { s ->
            (listOf(fmt.format(Date(s.time)), s.source, s.status, m(s.rawText)) +
                FIELD_NAMES.map { m(s.detected?.get(it)) } +
                FIELD_NAMES.map { m(s.saved?.get(it)) } +
                listOf(s.editedFields().joinToString(", "), m(s.notes.joinToString(" | "))))
                .joinToString(",") { cell(it) }
        }
        // The byte-order mark makes Excel read the file as UTF-8 (the rupee sign shows properly)
        return "\uFEFF" + (listOf(header.joinToString(",") { cell(it) }) + rows).joinToString("\r\n") + "\r\n"
    }

    // ------------------------------------------------------------ export

    /** Writes the chosen rows to one file (CSV or JSON) and opens the share sheet. */
    fun share(context: Context, chosen: List<DebugSample>, redact: Boolean, format: DebugExportFormat) {
        if (!DebugMode.on) return
        if (chosen.isEmpty()) {
            Toast.makeText(context, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(dir, "txnkeeper-samples-$stamp.${format.extension}")
            when (format) {
                DebugExportFormat.CSV -> out.writeText(toCsv(chosen, redact))
                DebugExportFormat.JSON -> out.writeText(
                    JSONArray(chosen.sortedBy { it.time }.map { toJson(it, redact) }).toString(2)
                )
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = format.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "TxnKeeper samples (${chosen.size})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share samples").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't share: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}

/** Debug-only list of the collected samples: tick some (or all), then share or delete them. */
@Composable
fun DebugDataDialog(onDismiss: () -> Unit) {
    if (!DebugMode.on) return
    val context = LocalContext.current
    val samples = DebugDataLog.samples
    val selected: SnapshotStateList<String> = remember { emptyList<String>().toMutableStateList() }
    val redact = false   // everything is shared as it is (nothing is hidden)
    var format by remember { mutableStateOf(DebugExportFormat.CSV) }
    val chosen = if (selected.isEmpty()) samples else samples.filter { it.id in selected }
    val dateFormat = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        ManuCard(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "DEBUG · transactions log (${samples.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        selected.clear()
                        selected.addAll(samples.map { it.id })
                    }) { Text("Select all") }
                    TextButton(onClick = { selected.clear() }) { Text("None") }
                    TextButton(onClick = {
                        selected.clear()
                        selected.addAll(DebugDataLog.latestGroup)
                    }) { Text("Latest scan") }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(samples, key = { it.id }) { s ->
                        val on = s.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (on) selected.remove(s.id) else selected.add(s.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    "${s.source} · ${dateFormat.format(Date(s.time))} · ${s.status}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                val edits = s.editedFields()
                                if (edits.isNotEmpty()) {
                                    Text(
                                        "edited: " + edits.joinToString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    (s.saved?.get("title") ?: s.detected?.get("title") ?: "(no title)") +
                                        (s.saved?.get("amount")?.let { " · ₹$it" } ?: "") +
                                        (if (s.rawText.isNotBlank()) " — " + s.rawText.replace('\n', ' ') else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Text(
                    text = if (selected.isEmpty()) "Nothing ticked: Share sends all ${samples.size}"
                    else "${selected.size} ticked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DebugExportFormat.values().forEach { f ->
                        TextButton(onClick = { format = f }) {
                            Text(if (f == format) "● ${f.label}" else "○ ${f.label}")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { DebugDataLog.share(context, chosen, redact, format) }) { Text("Share") }
                    TextButton(
                        onClick = {
                            DebugDataLog.delete(chosen.map { it.id }.toSet())
                            selected.clear()
                        },
                        enabled = samples.isNotEmpty()
                    ) { Text(if (selected.isEmpty()) "Delete all" else "Delete ticked") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

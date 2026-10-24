package com.example.txnkeeper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import com.example.txnkeeper.data.local.TransactionEntity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCustomDialog
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuTextButton

/** Holds on-device OCR state so any screen can start a scan and the home screen shows the result. */
class OcrController(
    private val context: Context,
    private val recognizer: TextRecognizer
) {
    var dialogOpen by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var text by mutableStateOf("")
    var parsed by mutableStateOf(ParsedTransaction())

    fun run(uri: Uri) {
        dialogOpen = true
        busy = true
        text = ""
        parsed = ParsedTransaction()
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    text = result.text
                    // Keep each line's size, so the parser can tell the big amount from small print
                    val lines = result.textBlocks
                        .flatMap { it.lines }
                        .map { OcrLine(it.text, it.boundingBox?.height() ?: 0) }
                    // Fix verifiable OCR slips first (e.g. rupee sign read as a digit, checked against the words)
                    val fixed = OcrCorrector.correct(lines)
                    val base = TransactionParser.parse(fixed.lines)
                    // Nothing found in figures but the words give an amount -> use it
                    parsed = if (base.amount == null && fixed.wordsAmount != null)
                        base.copy(amount = fixed.wordsAmount.toDouble()) else base
                    busy = false
                }
                .addOnFailureListener {
                    text = ""
                    busy = false
                    Toast.makeText(context, "Couldn't read text", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            busy = false
            Toast.makeText(context, "Couldn't open image", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberOcrController(): OcrController {
    val context = LocalContext.current
    val recognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    DisposableEffect(Unit) { onDispose { recognizer.close() } }
    return remember { OcrController(context, recognizer) }
}

private fun summaryLines(p: ParsedTransaction): List<String> = buildList {
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

/**
 * Shows what was read and what the parser found. "Fill form" only fills; it never saves.
 *
 * [findDuplicate] looks up a saved transaction with the same reference number. When one
 * exists, a warning is shown here, before anything is filled in.
 */
@Composable
fun OcrResultDialog(
    ocr: OcrController,
    onFill: (ParsedTransaction) -> Unit,
    findDuplicate: suspend (String) -> TransactionEntity? = { null }
) {
    if (!ocr.dialogOpen) return
    val context = LocalContext.current

    var duplicate by remember(ocr.parsed) { mutableStateOf<TransactionEntity?>(null) }
    LaunchedEffect(ocr.parsed, ocr.busy) {
        duplicate = null
        val ref = ocr.parsed.description?.removePrefix("Ref: ")?.trim()
        if (!ocr.busy && !ref.isNullOrEmpty()) duplicate = findDuplicate(ref)
    }

    // The lib's dialog frame; its content scrolls by itself and the buttons wrap if they don't fit.
    // While reading, the dialog can't be dismissed (same as before).
    ManuCustomDialog(
        onDismissRequest = { if (!ocr.busy) ocr.dialogOpen = false },
        title = if (ocr.busy) "Reading…" else "Detected transaction",
        dismissOnBackPress = !ocr.busy,
        dismissOnOutsideClick = !ocr.busy,
        buttons = {
            ManuOutlineButton(onClick = { ocr.dialogOpen = false }, enabled = !ocr.busy) {
                Text("Close")
            }
            if (!ocr.busy && ocr.text.isNotBlank()) {
                ManuTextButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("OCR text", ocr.text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
            }
            if (!ocr.busy && !ocr.parsed.isEmpty) {
                ManuButton(onClick = {
                    onFill(ocr.parsed)
                    ocr.dialogOpen = false
                }) {
                    Text(if (duplicate != null) "Fill anyway" else "Fill form")
                }
            }
        }
    ) {
        when {
            ocr.busy -> Text("Reading text…")
            ocr.text.isBlank() -> Text("No text found in this image.")
            else -> {
                val summary = summaryLines(ocr.parsed)
                duplicate?.let { d ->
                    Text(
                        text = "Possible duplicate: you already saved \"${d.title}\" " +
                                "(${formatMoney(d.amount)}, ${formatDateLong(d.dateTime)}) " +
                                "with the same reference number.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (summary.isEmpty()) {
                    Text("Couldn't detect transaction fields. You can still copy the text below.")
                } else {
                    summary.forEach { Text(it) }
                    Text(
                        "Check these, then tap Fill form. Nothing is saved automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(
                    "Full text",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                SelectionContainer {
                    Text(text = ocr.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
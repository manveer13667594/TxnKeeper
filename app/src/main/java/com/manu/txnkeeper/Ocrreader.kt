package com.manu.txnkeeper

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** What one finished scan found: the raw text and the transaction fields the parser picked out. */
class OcrOutcome(val text: String, val parsed: ParsedTransaction)

/** Holds on-device OCR state so any screen can start a scan; the home screen fills the form with the result. */
class OcrController(
    private val context: Context,
    private val recognizer: TextRecognizer
) {
    var busy by mutableStateOf(false)

    /** Set when a scan finishes. The screen reads it once, then sets it back to null. */
    var outcome by mutableStateOf<OcrOutcome?>(null)

    fun run(uri: Uri) {
        busy = true
        outcome = null
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // Keep each line's size, so the parser can tell the big amount from small print
                    val lines = result.textBlocks
                        .flatMap { it.lines }
                        .map { OcrLine(it.text, it.boundingBox?.height() ?: 0) }
                    // Fix verifiable OCR slips first (e.g. rupee sign read as a digit, checked against the words)
                    val fixed = OcrCorrector.correct(lines)
                    val base = TransactionParser.parse(fixed.lines)
                    // Nothing found in figures but the words give an amount -> use it
                    val parsed = if (base.amount == null && fixed.wordsAmount != null)
                        base.copy(amount = fixed.wordsAmount.toDouble()) else base
                    outcome = OcrOutcome(result.text, parsed)
                    busy = false
                }
                .addOnFailureListener {
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

/** The detected fields on one line for the note above the form: "Rahul · ₹35 · Expense · UPI". */
fun ocrDetectedLine(p: ParsedTransaction): String = listOfNotNull(
    p.title,
    p.amount?.let { "₹" + if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() },
    p.type?.lowercase()?.replaceFirstChar { it.uppercase() },
    p.paymentMethod,
    p.dateMillis?.let {
        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
    },
    p.description
).joinToString(" · ")
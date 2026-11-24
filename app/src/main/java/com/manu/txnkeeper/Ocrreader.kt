package com.manu.txnkeeper

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** What one finished scan found: the raw text and the transaction fields the parser picked out. */
class OcrOutcome(
    val text: String,
    val parsed: ParsedTransaction,
    /** Other possible titles for a photographed product, shown as tappable chips on the form. */
    val suggestions: List<String> = emptyList()
)

/** Holds on-device OCR state so any screen can start a scan; the home screen fills the form with the result. */
class OcrController(
    private val context: Context,
    private val recognizer: TextRecognizer,
    private val labeler: ImageLabeler
) {
    var busy by mutableStateOf(false)

    /** Set when a scan finishes. The screen reads it once, then sets it back to null. */
    var outcome by mutableStateOf<OcrOutcome?>(null)

    /** Counts scans, so the late result of an old scan can't overwrite a newer one (second pass only). */
    private var runId = 0
    private var startedAtMs = 0L
    /** Debug builds only: extra lines for the insights card (labels, guesses ...). */
    private var debugNotes: List<String> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Lines with their size and position (the position lets ReceiptRows rebuild rows of a bill). */
    private fun linesOf(result: Text): List<OcrLine> = result.textBlocks
        .flatMap { it.lines }
        .map {
            val box = it.boundingBox
            OcrLine(it.text, box?.height() ?: 0, box?.top ?: 0, box?.bottom ?: 0, box?.left ?: 0)
        }

    fun run(uri: Uri) {
        busy = true
        outcome = null
        val thisRun = ++runId
        startedAtMs = System.currentTimeMillis()
        debugNotes = emptyList()
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // Keep each line's size, so the parser can tell the big amount from small print
                    val lines = linesOf(result)
                    // Fix verifiable OCR slips first (e.g. rupee sign read as a digit, checked against the words)
                    val fixed = OcrCorrector.correct(lines)
                    val base = TransactionParser.parse(fixed.lines)
                    // Nothing found in figures but the words give an amount -> use it
                    val parsed = if (base.amount == null && fixed.wordsAmount != null)
                        base.copy(amount = fixed.wordsAmount.toDouble()) else base
                    // Labels and figures that OCR read in separate columns are put back on one row.
                    // Only empty fields get filled; anything already found stays as it was.
                    val complete = ReceiptInterpreter.fillFromRows(parsed, lines)
                    if (complete.amount == null) {
                        // Still no amount (faded print, shadow, tiny picture): try once more on a cleaned-up copy
                        retryOnCleanImage(uri, thisRun, result.text, complete)
                    } else {
                        withObjectTitle(uri, thisRun, result.text, complete, fixed.lines)
                    }
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

    /** Hands the finished scan to the screen, unless a newer scan has started meanwhile. */
    private fun publish(thisRun: Int, rawText: String, parsed: ParsedTransaction, suggestions: List<String> = emptyList()) {
        if (thisRun != runId) return
        if (DebugMode.on) {
            DebugInsights.recordScan(
                rawText,
                parsed,
                debugNotes + listOf(
                    "Title suggestions: " + (suggestions.joinToString().ifEmpty { "none" }),
                    "Time: ${System.currentTimeMillis() - startedAtMs} ms"
                )
            )
        }
        outcome = OcrOutcome(rawText, parsed, suggestions)
        busy = false
    }

    /**
     * When the text gave no title (a photographed product: notebook, perfume, snack ...), works out what
     * the product is from the name printed on it and from ML Kit's on-device image labeling. A sure guess
     * becomes the title; the other candidates are offered as suggestion chips. Any failure leaves the
     * scan result as it was.
     */
    private fun withObjectTitle(
        uri: Uri,
        thisRun: Int,
        rawText: String,
        parsed: ParsedTransaction,
        lines: List<OcrLine>
    ) {
        // A payment screenshot or bill (it has a title, a payment method or a reference): nothing to guess
        if (parsed.title != null || parsed.paymentMethod != null || parsed.description != null) {
            if (DebugMode.on) debugNotes = listOf("Product guessing skipped (text gave a title, payment method or reference)")
            publish(thisRun, rawText, parsed)
            return
        }
        fun finishWith(labels: List<Pair<String, Float>>) {
            val guess = ImageTitleGuesser.guess(lines, labels)
            if (DebugMode.on) {
                debugNotes = listOf(
                    "Name from text: " + (ImageTitleGuesser.nameFromLines(lines) ?: "none"),
                    "Type word: " + (ImageTitleGuesser.typeFromText(lines.joinToString(" ") { it.text }) ?: "none"),
                    "Image labels: " + (labels.sortedByDescending { it.second }
                        .joinToString { "${it.first} %.2f".format(it.second) }.ifEmpty { "none" }),
                    "Title filled: " + (guess.title ?: "no (suggestions only)")
                )
            }
            publish(thisRun, rawText, if (guess.title != null) parsed.copy(title = guess.title) else parsed, guess.suggestions)
        }
        try {
            labeler.process(InputImage.fromFilePath(context, uri))
                .addOnSuccessListener { labels -> finishWith(labels.map { it.text to it.confidence }) }
                .addOnFailureListener { finishWith(emptyList()) }
        } catch (e: Throwable) {
            finishWith(emptyList())
        }
    }

    /**
     * Second attempt for scans that found no amount: reads a cleaned-up copy of the photo and fills the
     * fields the first reading missed. If anything goes wrong the first result is used, as before.
     */
    private fun retryOnCleanImage(uri: Uri, thisRun: Int, rawText: String, first: ParsedTransaction) {
        fun finish(parsed: ParsedTransaction, lines: List<OcrLine> = emptyList()) {
            withObjectTitle(uri, thisRun, rawText, parsed, lines)
        }
        Thread {
            val bitmap = try { ReceiptImagePrep.enhancedBitmap(context, uri) } catch (e: Throwable) { null }
            if (bitmap == null) {
                mainHandler.post { finish(first) }
                return@Thread
            }
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        bitmap.recycle()
                        val second = linesOf(result)
                        finish(ReceiptInterpreter.fillGaps(first, ReceiptInterpreter.interpret(second)), second)
                    }
                    .addOnFailureListener {
                        bitmap.recycle()
                        finish(first)
                    }
            } catch (e: Throwable) {
                bitmap.recycle()
                mainHandler.post { finish(first) }
            }
        }.start()
    }
}

@Composable
fun rememberOcrController(): OcrController {
    val context = LocalContext.current
    val recognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    val labeler = remember {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build())
    }
    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            labeler.close()
        }
    }
    return remember { OcrController(context, recognizer, labeler) }
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
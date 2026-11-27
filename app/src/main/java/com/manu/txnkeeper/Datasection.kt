package com.manu.txnkeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuButton
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.AppDatabase
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDateRangePicker
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuPickerField
import com.manu.manudesignsystem.components.ManuSwitch
import com.manu.manudesignsystem.components.ManuTextButton
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.theme.ManuDesignSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Returned by [amountPaiseOrInvalid] for text that is not a usable amount. */
private const val INVALID_AMOUNT = Long.MIN_VALUE

/** "1 image", "3 images". */
private fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

/**
 * Text from an amount box -> paise. Blank means "no limit" (null), a valid amount gives its paise,
 * and anything else (letters, a negative number, too large) gives [INVALID_AMOUNT].
 */
private fun amountPaiseOrInvalid(text: String): Long? {
    val clean = text.trim().replace(",", "")
    if (clean.isEmpty()) return null
    val paise = parsePaise(clean) ?: return INVALID_AMOUNT
    return if (paise < 0) INVALID_AMOUNT else paise
}

/**
 * The "Data" group in Settings.
 *
 * Export: first choose what to export (everything, a date range, or the latest N transactions),
 * optionally narrow it down (account, payment method, income / expense, amount range), then export
 * to CSV or PDF. An "Include images" switch (kept in Settings) decides about the receipt images:
 * on = a ZIP (the CSV and an images folder) and a PDF that shows the images after the table.
 * It only changes anything once some transactions have an image.
 * Import: pick a CSV file, or a ZIP made by this export.
 *
 * The system file picker chooses where a file is saved or which file is read, so the app needs
 * no storage permission. A short help text (and then the result of each action) is shown at the top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataTransferControls(database: AppDatabase, settings: AppSettings) {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // The result of the last action, line by line; a warning line is shown in red
    var status by remember { mutableStateOf<List<StatusLine>?>(null) }
    fun note(text: String) = listOf(StatusLine(text))
    fun warn(text: String) = listOf(StatusLine(text, warning = true))

    // What to export: the same filters as the transaction list (period, type, payment, amount, tags...),
    // an account, and optionally only the newest N of whatever matches
    var f by remember { mutableStateOf(TxnFilters()) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var limitOn by remember { mutableStateOf(false) }
    var countText by remember { mutableStateOf("50") }
    var filtersOpen by remember { mutableStateOf(false) }
    var previewAllOpen by remember { mutableStateOf(false) }
    // The "how the PDF will look" picture: null = closed, loading while the pages are drawn
    var pdfPreviewOpen by remember { mutableStateOf(false) }
    var pdfPreview by remember { mutableStateOf<PdfPreview?>(null) }
    // "Include images" is saved, so the next export starts the way this one was left
    val withImages = settings.exportWithImagesByDefault

    // What the file picker was opened for (the picker only gives back the file's Uri)
    var pendingScope by remember { mutableStateOf<ExportScope>(ExportScope.All) }
    var pendingFilters by remember { mutableStateOf(ExportFilters()) }

    // The accounts, and the payment methods that transactions use, to offer as chips
    val dao = remember(database) { database.transactionDao() }
    val accountsFlow = remember(dao) { dao.observeAccounts() }
    val accounts: List<AccountSummary> by accountsFlow.collectAsState(initial = emptyList())
    val methodsFlow = remember(dao) { dao.observePaymentMethodsInUse() }
    val usedMethods: List<String> by methodsFlow.collectAsState(initial = emptyList())
    val tagsFlow = remember(dao) { dao.observeTags() }
    val allTags by tagsFlow.collectAsState(initial = emptyList())
    val methods = remember(settings.paymentMethods, usedMethods) { (settings.paymentMethods + usedMethods).distinct() }
    // How many transactions have an image: the switch only matters (and only shows) when there are some
    val imageCountFlow = remember(dao) { dao.observeImageCount() }
    val imageCount: Int by imageCountFlow.collectAsState(initial = 0)
    val includeImages = withImages && imageCount > 0

    // What the choices above mean as an export (null limit = everything that matches)
    val limitN = if (limitOn) countText.trim().toIntOrNull()?.takeIf { it > 0 } else null
    val scopeNow: ExportScope = run {
        val (from, to) = f.dateRange()
        when {
            from != null && to != null -> ExportScope.DateRange(from, to, limitN)
            limitN != null -> ExportScope.Latest(limitN)
            else -> ExportScope.All
        }
    }
    val filtersNow = ExportFilters(
        accountId = accountId,
        paymentMethod = f.payment,
        type = f.type,
        minAmountPaise = f.minAmount?.toPaise(),
        maxAmountPaise = f.maxAmount?.toPaise(),
        tagIds = f.tagIds,
        withImage = f.withImage,
        withItems = f.withItems
    )
    // Live preview: updates a moment after each change
    var preview by remember { mutableStateOf<ExportPreview?>(null) }
    LaunchedEffect(scopeNow, filtersNow) {
        kotlinx.coroutines.delay(150)
        preview = try { DataTransfer.preview(database, scopeNow, filtersNow) } catch (e: Exception) { null }
    }

    /** Runs [work] once at a time and shows its returned message (or the error) as the status. */
    fun runTask(work: suspend () -> List<StatusLine>) {
        if (busy) return
        busy = true
        status = null
        coroutines.launch {
            try {
                status = work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = warn("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    val csvImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask { DataTransfer.importFile(context, database, uri).summaryLines() }
    }

    // The "Exporting… / Exported" popup, and the newest export (both in ExportDone.kt)
    var exportProgress by remember { mutableStateOf<ExportProgress?>(null) }
    var latestExport by remember { mutableStateOf(lastExport(context)) }

    /** Writes the export while the popup says so, then shows where it went. [work] returns what was exported. */
    fun runExport(uri: android.net.Uri, mime: String, fallbackName: String, work: suspend () -> String) {
        if (busy) return
        busy = true
        status = null
        exportProgress = ExportProgress.Working
        coroutines.launch {
            try {
                val started = System.currentTimeMillis()
                val summary = work()
                val file = withContext(Dispatchers.IO) { rememberExport(context, uri, mime, fallbackName) }
                // A very quick export would only flash "Exporting…", so it stays long enough to be read
                kotlinx.coroutines.delay((700 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                latestExport = file
                exportProgress = ExportProgress.Done(file, summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exportProgress = ExportProgress.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun fileName(extension: String): String =
        "txnkeeper_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.$extension"

    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runExport(uri, "text/csv", "TxnKeeper export.csv") {
            val n = DataTransfer.exportCsv(context, database, uri, pendingScope, pendingFilters)
            "${plural(n, "transaction")} saved as CSV."
        }
    }
    val zipExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runExport(uri, "application/zip", "TxnKeeper export.zip") {
            val r = DataTransfer.exportZip(context, database, uri, pendingScope, pendingFilters)
            "${plural(r.transactions, "transaction")} and ${plural(r.images, "image")} saved as ZIP."
        }
    }
    val pdfExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runExport(uri, "application/pdf", "TxnKeeper export.pdf") {
            val r = DataTransfer.exportPdf(context, database, uri, pendingScope, pendingFilters, withImages = includeImages)
            "${plural(r.transactions, "transaction")} saved as PDF" +
                    (if (includeImages) " with ${plural(r.images, "image")}." else ".")
        }
    }

    /** The choice, or null (with a message in [status]) when it is incomplete. */
    fun chosenScope(): ExportScope? {
        if (limitOn && limitN == null) {
            status = warn("Enter a number, 1 or more.")
            return null
        }
        if (f.period == PeriodKind.CUSTOM && (f.customFrom == null || f.customTo == null)) {
            status = warn("Choose the dates first.")
            return null
        }
        return scopeNow
    }

    fun chosenFilters(): ExportFilters? {
        val min = filtersNow.minAmountPaise
        val max = filtersNow.maxAmountPaise
        if (min != null && max != null && min > max) {
            status = warn("Minimum amount is more than maximum.")
            return null
        }
        return filtersNow
    }

    /** Draws the PDF's first pages and shows them, so you can see how it will look before saving it. */
    fun previewPdf() {
        if (busy) return
        status = null
        val scope = chosenScope() ?: return
        val filters = chosenFilters() ?: return
        pdfPreview = null
        pdfPreviewOpen = true
        coroutines.launch {
            try {
                if (DataTransfer.countFor(database, scope, filters) == 0) {
                    pdfPreviewOpen = false
                    status = warn("No transactions match those choices, so there is nothing to preview.")
                } else {
                    pdfPreview = DataTransfer.renderPdfPreview(context, database, scope, filters)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pdfPreviewOpen = false
                status = warn("Could not build the preview: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Checks the choice, says so when nothing matches, and otherwise opens the "save as" picker. */
    fun exportClicked(open: () -> Unit) {
        if (busy) return
        status = null
        val scope = chosenScope() ?: return
        val filters = chosenFilters() ?: return
        coroutines.launch {
            if (DataTransfer.countFor(database, scope, filters) == 0) {
                status = warn("No transactions match those choices, so there is nothing to export.")
            } else {
                pendingScope = scope
                pendingFilters = filters
                open()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ------------------------------------------------ result of the last action (only when there is one)
        val lines = if (busy) note("Working...") else status
        if (!lines.isNullOrEmpty()) {
            Column {
                lines.forEach { line ->
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ------------------------------------------------ export
        val activeCount = f.activeCount + (if (accountId != null) 1 else 0)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Export", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // Same filter icon as the transactions screen: accent colour and a count while filters are on
            IconButton(onClick = { filtersOpen = true }) {
                if (activeCount > 0) {
                    BadgedBox(badge = { Badge { Text("$activeCount") } }) {
                        Icon(
                            AppIcons.FilterFunnel,
                            contentDescription = "Filters applied: $activeCount",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Icon(AppIcons.FilterFunnel, contentDescription = "Filters")
                }
            }
        }

        // ---- What to export: one tap opens the same filter sheet as the transaction list
        ActiveFilterRow(
            filters = f,
            tags = allTags,
            onChange = { f = it; status = null }
        )
        if (accountId != null) {
            val name = accounts.firstOrNull { it.id == accountId }?.name ?: "Account"
            ManuChip(label = "$name  ✕", selected = true, onClick = { accountId = null; status = null })
        }

        // Only the newest N of whatever matches
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Latest only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (limitOn) {
                ManuTextField(
                    value = countText,
                    onValueChange = { countText = it.filter { c -> c.isDigit() }.take(6); status = null },
                    label = { Text("How many") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp)
                )
            }
            ManuSwitch(checked = limitOn, onCheckedChange = { limitOn = it; status = null })
        }

        // ---- Preview: what the file will hold
        ExportPreviewCard(
            preview, accounts.associate { it.id to it.name },
            onSeeAll = { previewAllOpen = true },
            onPreviewPdf = { previewPdf() }
        )

        // Images in the export (ZIP and PDF). Always here, so it can be set before any image exists.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Include images", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        !withImages -> "Images are left out."
                        imageCount == 0 -> "No receipt images yet."
                        else -> "Add receipts to ZIP and PDF."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ManuSwitch(
                checked = withImages,
                onCheckedChange = {
                    settings.updateExportWithImagesByDefault(it)
                    status = null
                }
            )
        }

        // CSV (or ZIP with images) and PDF side by side
        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
            ManuButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (includeImages) exportClicked { zipExport.launch(fileName("zip")) }
                    else exportClicked { csvExport.launch(fileName("csv")) }
                }
            ) { Text(if (includeImages) "Export ZIP" else "Export CSV", maxLines = 1) }
            ManuOutlineButton(
                modifier = Modifier.weight(1f),
                onClick = { exportClicked { pdfExport.launch(fileName("pdf")) } }
            ) { Text("Export PDF", maxLines = 1) }
        }

        latestExport?.let { LastExportCard(it) }

        HorizontalDivider()

        // ------------------------------------------------ import
        Text("Import", style = MaterialTheme.typography.titleMedium)

        ManuOutlineButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!busy) {
                    status = null
                    csvImport.launch(
                        arrayOf(
                            "text/*", "application/csv", "application/vnd.ms-excel",
                            "application/zip", "application/x-zip-compressed", "application/octet-stream"
                        )
                    )
                }
            }
        ) { Text("Import from CSV or ZIP") }
        Text(
            text = "No duplicates are added. ZIP restores images too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    exportProgress?.let { progress ->
        ExportDialog(progress, onDone = { exportProgress = null })
    }

    if (filtersOpen) {
        FilterSheet(
            filters = f,
            onChange = { f = it; status = null },
            onDismiss = { filtersOpen = false },
            paymentMethods = methods,
            tags = allTags,
            resultCount = preview?.count,
            showSort = false,
            accounts = accounts,
            accountId = accountId,
            onAccountChange = { accountId = it; status = null }
        )
    }

    if (pdfPreviewOpen) {
        PdfPreviewDialog(
            preview = pdfPreview,
            onClose = { pdfPreviewOpen = false; pdfPreview = null },
            onExport = {
                pdfPreviewOpen = false
                pdfPreview = null
                exportClicked { pdfExport.launch(fileName("pdf")) }
            }
        )
    }

    if (previewAllOpen) {
        val p = preview
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { previewAllOpen = false },
            confirmButton = { ManuTextButton(onClick = { previewAllOpen = false }) { Text("Close") } },
            title = { Text(if (p == null) "Preview" else "${plural(p.count, "transaction")} to export") },
            text = {
                if (p != null) {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(p.rows.size) { i -> PreviewRow(p.rows[i]) }
                        if (p.count > p.rows.size) item {
                            Text(
                                "…and ${p.count - p.rows.size} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

/** Full-screen look at the first pages of the PDF, with Export at the bottom. */
@Composable
private fun PdfPreviewDialog(preview: PdfPreview?, onClose: () -> Unit, onExport: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PDF preview", style = MaterialTheme.typography.titleLarge)
                        if (preview != null) {
                            Text(
                                text = if (preview.totalPages > preview.pages.size)
                                    "First ${preview.pages.size} of ${preview.totalPages} pages · ${plural(preview.transactions, "transaction")}"
                                else "${plural(preview.totalPages, "page")} · ${plural(preview.transactions, "transaction")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    ManuTextButton(onClick = onClose) { Text("Close") }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (preview == null) {
                        Text("Building the preview…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(preview.pages.size) { i ->
                                androidx.compose.foundation.Image(
                                    bitmap = preview.pages[i].asImageBitmap(),
                                    contentDescription = "Page ${i + 1}",
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .shadow(3.dp, RoundedCornerShape(2.dp))
                                        .background(androidx.compose.ui.graphics.Color.White)
                                )
                            }
                            if (preview.totalPages > preview.pages.size) item {
                                Text(
                                    "…and ${preview.totalPages - preview.pages.size} more pages. Receipt images (if included) follow the table.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                        }
                    }
                }
                if (preview != null) ManuButton(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("Export this PDF") }
            }
        }
    }
}

private val previewDateFormat = SimpleDateFormat("d MMM yy", Locale.getDefault())

@Composable
private fun PreviewRow(t: com.manu.txnkeeper.data.local.TransactionEntity) {
    val income = t.type == "INCOME"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(
                "${previewDateFormat.format(Date(t.dateTime))} · ${t.paymentMethod}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            (if (income) "+" else "-") + formatMoney(t.amountPaise),
            style = MaterialTheme.typography.bodyMedium,
            color = if (income) com.manu.txnkeeper.ui.theme.IncomeColor else com.manu.txnkeeper.ui.theme.ExpenseColor
        )
    }
}

/** "What you'll get": count, money in and out, and the first few rows. */
@Composable
private fun ExportPreviewCard(
    preview: ExportPreview?,
    accountNames: Map<Long, String>,
    onSeeAll: () -> Unit,
    onPreviewPdf: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.onSurface.copy(alpha = 0.05f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Preview", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        when {
            preview == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            preview.count == 0 -> Text(
                "Nothing matches these choices.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.error
            )
            else -> {
                Text(plural(preview.count, "transaction"), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("In ${formatMoney(preview.incomePaise)}", style = MaterialTheme.typography.bodySmall, color = com.manu.txnkeeper.ui.theme.IncomeColor)
                    Text("Out ${formatMoney(preview.expensePaise)}", style = MaterialTheme.typography.bodySmall, color = com.manu.txnkeeper.ui.theme.ExpenseColor)
                }
                val newest = preview.rows.first().dateTime
                val oldest = preview.rows.last().dateTime
                Text(
                    "${previewDateFormat.format(Date(oldest))} – ${previewDateFormat.format(Date(newest))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                preview.rows.take(4).forEach { PreviewRow(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (preview.count > 4) ManuTextButton(onClick = onSeeAll) { Text("See all ${preview.count}") }
                    ManuTextButton(onClick = onPreviewPdf) { Text("Preview as PDF") }
                }
            }
        }
    }
}

/** Small grey heading above one group of export filters. */
@Composable
private fun ExportFilterLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
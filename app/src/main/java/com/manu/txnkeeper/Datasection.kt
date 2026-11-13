package com.manu.txnkeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
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
import com.manu.manudesignsystem.components.ManuTextButton
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.theme.ManuDesignSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the export buttons will export. */
private enum class ExportKind(val label: String) {
    ALL("All"),
    RANGE("Date range"),
    LATEST("Latest N")
}

/** Returned by [amountPaiseOrInvalid] for text that is not a usable amount. */
private const val INVALID_AMOUNT = Long.MIN_VALUE

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
 * to CSV or PDF. Import: pick a CSV file.
 *
 * The system file picker chooses where a file is saved or which file is read, so the app needs
 * no storage permission. A short help text (and then the result of each action) is shown at the top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataTransferControls(database: AppDatabase) {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // What to export
    var kind by remember { mutableStateOf(ExportKind.ALL) }
    var rangeFrom by remember { mutableStateOf<Long?>(null) }   // start of the first day
    var rangeTo by remember { mutableStateOf<Long?>(null) }     // end of the last day
    var countText by remember { mutableStateOf("50") }
    var rangeDialogOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }

    // Optional narrowing down (null / blank = don't filter on it)
    var accountId by remember { mutableStateOf<Long?>(null) }
    var payment by remember { mutableStateOf<String?>(null) }
    var txType by remember { mutableStateOf<String?>(null) }    // "INCOME" / "EXPENSE"
    var minText by remember { mutableStateOf("") }
    var maxText by remember { mutableStateOf("") }

    // What the file picker was opened for (the picker only gives back the file's Uri)
    var pendingScope by remember { mutableStateOf<ExportScope>(ExportScope.All) }
    var pendingFilters by remember { mutableStateOf(ExportFilters()) }

    // The accounts, and the payment methods that transactions use, to offer as chips
    val dao = remember(database) { database.transactionDao() }
    val accountsFlow = remember(dao) { dao.observeAccounts() }
    val accounts: List<AccountSummary> by accountsFlow.collectAsState(initial = emptyList())
    val methodsFlow = remember(dao) { dao.observePaymentMethodsInUse() }
    val usedMethods: List<String> by methodsFlow.collectAsState(initial = emptyList())
    // A selected method stays visible even if no transaction uses it any more, so it can be switched off
    val methodChips = (usedMethods + listOfNotNull(payment)).distinct()

    val filtersOn = accountId != null || payment != null || txType != null ||
            minText.isNotBlank() || maxText.isNotBlank()

    /** Runs [work] once at a time and shows its returned message (or the error) as the status. */
    fun runTask(work: suspend () -> String) {
        if (busy) return
        busy = true
        status = null
        coroutines.launch {
            try {
                status = work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runTask {
            val n = DataTransfer.exportCsv(context, database, uri, pendingScope, pendingFilters)
            "Exported $n transaction${if (n == 1) "" else "s"} to CSV."
        }
    }
    val pdfExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runTask {
            val n = DataTransfer.exportPdf(context, database, uri, pendingScope, pendingFilters)
            "Exported $n transaction${if (n == 1) "" else "s"} to PDF."
        }
    }
    val csvImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask { DataTransfer.importCsv(context, database, uri).summary() }
    }

    fun fileName(extension: String): String =
        "txnkeeper_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.$extension"

    /** The chosen scope, or null (with a message in [status]) when the choice is incomplete. */
    fun chosenScope(): ExportScope? = when (kind) {
        ExportKind.ALL -> ExportScope.All
        ExportKind.RANGE -> {
            val from = rangeFrom
            val to = rangeTo
            if (from == null || to == null) {
                status = "Choose the dates to export first."
                null
            } else {
                ExportScope.DateRange(from, to)
            }
        }
        ExportKind.LATEST -> {
            val n = countText.trim().toIntOrNull()
            if (n == null || n <= 0) {
                status = "Enter how many transactions to export (1 or more)."
                null
            } else {
                ExportScope.Latest(n)
            }
        }
    }

    /** The chosen filters, or null (with a message in [status]) when an amount is not usable. */
    fun chosenFilters(): ExportFilters? {
        val min = amountPaiseOrInvalid(minText)
        val max = amountPaiseOrInvalid(maxText)
        if (min == INVALID_AMOUNT || max == INVALID_AMOUNT) {
            status = "Type the amounts as plain numbers, like 500 or 1250.50."
            return null
        }
        if (min != null && max != null && min > max) {
            status = "The minimum amount is higher than the maximum."
            return null
        }
        return ExportFilters(
            accountId = accountId,
            paymentMethod = payment,
            type = txType,
            minAmountPaise = min,
            maxAmountPaise = max
        )
    }

    /** Checks the choice, says so when nothing matches, and otherwise opens the "save as" picker. */
    fun exportClicked(open: () -> Unit) {
        if (busy) return
        status = null
        val scope = chosenScope() ?: return
        val filters = chosenFilters() ?: return
        coroutines.launch {
            if (DataTransfer.countFor(database, scope, filters) == 0) {
                status = "No transactions match those choices, so there is nothing to export."
            } else {
                pendingScope = scope
                pendingFilters = filters
                open()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ------------------------------------------------ result of the last action (only when there is one)
        val message = if (busy) "Working..." else status
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ------------------------------------------------ export
        Text("Export", style = MaterialTheme.typography.titleMedium)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
        ) {
            ExportKind.entries.forEach { option ->
                ManuChip(
                    label = option.label,
                    selected = kind == option,
                    onClick = {
                        kind = option
                        status = null
                    }
                )
            }
        }

        when (kind) {
            ExportKind.ALL -> Unit
            ExportKind.RANGE -> ManuPickerField(
                value = rangeFrom?.let { from -> rangeTo?.let { to -> "${formatDateLong(from)} - ${formatDateLong(to)}" } }
                    ?: "Choose dates",
                leadingIcon = Icons.Default.DateRange,
                trailingIcon = Icons.Default.KeyboardArrowDown,
                onClick = { rangeDialogOpen = true }
            )
            ExportKind.LATEST -> ManuTextField(
                value = countText,
                onValueChange = { countText = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Number of transactions (newest first)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ---- Filters: folded away until you open them. Everything chosen must match, on top of the choice above.
        val activeFilters = listOf(
            accountId != null,
            payment != null,
            txType != null,
            minText.isNotBlank() || maxText.isNotBlank()
        ).count { it }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { filtersOpen = !filtersOpen }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Filters", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (activeFilters == 0) "None" else "$activeFilters on",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (filtersOpen && filtersOn) {
                ManuTextButton(
                    onClick = {
                        accountId = null
                        payment = null
                        txType = null
                        minText = ""
                        maxText = ""
                        status = null
                    }
                ) { Text("Clear") }
            }
            Icon(
                if (filtersOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (filtersOpen) "Hide filters" else "Show filters",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (filtersOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Account (with a single account there is nothing to choose between)
                if (accounts.size > 1) {
                    ExportFilterLabel("Account")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
                    ) {
                        ManuChip(label = "All", selected = accountId == null, onClick = {
                            accountId = null
                            status = null
                        })
                        accounts.forEach { account ->
                            ManuChip(label = account.name, selected = accountId == account.id, onClick = {
                                accountId = account.id
                                status = null
                            })
                        }
                    }
                }

                // Payment method
                if (methodChips.isNotEmpty()) {
                    ExportFilterLabel("Payment method")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
                    ) {
                        ManuChip(label = "All", selected = payment == null, onClick = {
                            payment = null
                            status = null
                        })
                        methodChips.forEach { method ->
                            ManuChip(label = method, selected = payment == method, onClick = {
                                payment = method
                                status = null
                            })
                        }
                    }
                }

                // Income / expense
                ExportFilterLabel("Type")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
                ) {
                    ManuChip(label = "All", selected = txType == null, onClick = {
                        txType = null
                        status = null
                    })
                    ManuChip(label = "Income", selected = txType == "INCOME", onClick = {
                        txType = "INCOME"
                        status = null
                    })
                    ManuChip(label = "Expense", selected = txType == "EXPENSE", onClick = {
                        txType = "EXPENSE"
                        status = null
                    })
                }

                // Amount range
                ExportFilterLabel("Amount (₹)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ManuTextField(
                        value = minText,
                        onValueChange = {
                            minText = it.filter { c -> c.isDigit() || c == '.' }.take(16)
                            status = null
                        },
                        label = { Text("Min") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    ManuTextField(
                        value = maxText,
                        onValueChange = {
                            maxText = it.filter { c -> c.isDigit() || c == '.' }.take(16)
                            status = null
                        },
                        label = { Text("Max") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // CSV and PDF side by side
        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
            ManuButton(
                modifier = Modifier.weight(1f),
                onClick = { exportClicked { csvExport.launch(fileName("csv")) } }
            ) { Text("Export CSV", maxLines = 1) }
            ManuOutlineButton(
                modifier = Modifier.weight(1f),
                onClick = { exportClicked { pdfExport.launch(fileName("pdf")) } }
            ) { Text("Export PDF", maxLines = 1) }
        }

        HorizontalDivider()

        // ------------------------------------------------ import
        Text("Import", style = MaterialTheme.typography.titleMedium)

        ManuOutlineButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!busy) {
                    status = null
                    csvImport.launch(
                        arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")
                    )
                }
            }
        ) { Text("Import from CSV") }
        Text(
            text = "Importing the same file again never adds a transaction twice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ---- Date range for the export
    ManuDateRangePicker(
        expanded = rangeDialogOpen,
        onDismissRequest = { rangeDialogOpen = false },
        initialStartDateMillis = rangeFrom?.let { localToPickerDay(it) },
        initialEndDateMillis = rangeTo?.let { localToPickerDay(it) },
        onDateRangeSelected = { startUtc, endUtc ->
            rangeFrom = pickerToLocalDay(startUtc)
            rangeTo = endOfDay(pickerToLocalDay(endUtc))
            status = null
        }
    )
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
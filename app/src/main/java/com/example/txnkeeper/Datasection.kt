package com.example.txnkeeper

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.txnkeeper.data.local.AppDatabase
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDateRangePicker
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuPickerField
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

/**
 * The "Data" group in Settings.
 *
 * Export: first choose what to export (everything, a date range, or the latest N transactions),
 * then export to CSV or PDF. Import: pick a CSV file.
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
    // The scope the file picker was opened for (the picker only gives back the file's Uri)
    var pendingScope by remember { mutableStateOf<ExportScope>(ExportScope.All) }

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
            val n = DataTransfer.exportCsv(context, database, uri, pendingScope)
            "Exported $n transaction${if (n == 1) "" else "s"} to CSV."
        }
    }
    val pdfExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runTask {
            val n = DataTransfer.exportPdf(context, database, uri, pendingScope)
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

    /** Checks the choice, says so when nothing matches, and otherwise opens the "save as" picker. */
    fun exportClicked(open: () -> Unit) {
        if (busy) return
        status = null
        val scope = chosenScope() ?: return
        coroutines.launch {
            if (DataTransfer.countFor(database, scope) == 0) {
                status = "No transactions match that choice, so there is nothing to export."
            } else {
                pendingScope = scope
                open()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ------------------------------------------------ help text, and the result of the last action
        val message = status
        Text(
            text = when {
                busy -> "Working..."
                message != null -> message
                else -> "CSV columns: Date, Title, Amount, Type, Payment Method, Account, Description. " +
                        "Importing a file again never adds the same transaction twice."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

        ManuOutlineButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { exportClicked { csvExport.launch(fileName("csv")) } }
        ) { Text("Export to CSV") }

        ManuOutlineButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { exportClicked { pdfExport.launch(fileName("pdf")) } }
        ) { Text("Export to PDF") }

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
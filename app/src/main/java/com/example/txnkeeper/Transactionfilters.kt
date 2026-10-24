package com.example.txnkeeper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.txnkeeper.data.local.TransactionDao
import com.example.txnkeeper.data.local.TransactionEntity
import com.example.txnkeeper.data.local.TransactionTotals
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.manu.manudesignsystem.components.ManuBottomSheet
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuTextButton
import com.manu.manudesignsystem.components.ManuDateRangePicker

// ---------------------------------------------------------------- model

enum class PeriodKind { ALL, DAY, WEEK, MONTH, YEAR, CUSTOM }

/** Everything the filter icon controls (search is separate, it lives next to the icon). */
data class TxnFilters(
    val newestFirst: Boolean = true,
    val type: String? = null,            // "INCOME" / "EXPENSE" / null = all
    val payment: String? = null,         // one of PAYMENT_METHODS / null = all
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val period: PeriodKind = PeriodKind.ALL,
    val offset: Int = 0,                 // 0 = this day/week/month/year, -1 = the one before, ...
    val customFrom: Long? = null,        // CUSTOM only: start of first day
    val customTo: Long? = null           // CUSTOM only: end of last day
) {
    /** How many filters are on (sort order doesn't count). Shown as a badge on the filter icon. */
    val activeCount: Int
        get() = listOf(
            type != null,
            payment != null,
            minAmount != null || maxAmount != null,
            period != PeriodKind.ALL
        ).count { it }

    fun dateRange(): Pair<Long?, Long?> {
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay(System.currentTimeMillis()) }
        return when (period) {
            PeriodKind.ALL -> null to null
            PeriodKind.CUSTOM -> customFrom to customTo
            PeriodKind.DAY -> {
                cal.add(Calendar.DAY_OF_YEAR, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                start to cal.timeInMillis - 1
            }
            PeriodKind.WEEK -> {
                // Weeks run Monday to Sunday
                val sinceMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                cal.add(Calendar.DAY_OF_YEAR, -sinceMonday + offset * 7)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 7)
                start to cal.timeInMillis - 1
            }
            PeriodKind.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                start to cal.timeInMillis - 1
            }
            PeriodKind.YEAR -> {
                cal.set(Calendar.MONTH, Calendar.JANUARY)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.YEAR, offset)
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                start to cal.timeInMillis - 1
            }
        }
    }
}

private fun fmt(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

/** Main label of the period: "Today", "Yesterday", "This week", "October 2026", "2026"... */
fun periodTitle(f: TxnFilters): String {
    val (from, to) = f.dateRange()
    if (from == null || to == null) return "All time"
    return when (f.period) {
        PeriodKind.ALL -> "All time"
        PeriodKind.DAY -> when (f.offset) {
            0 -> "Today"
            -1 -> "Yesterday"
            else -> fmt(from, "d MMM yyyy")
        }
        PeriodKind.WEEK -> when (f.offset) {
            0 -> "This week"
            -1 -> "Last week"
            else -> "${fmt(from, "d MMM")} – ${fmt(to, "d MMM")}"
        }
        PeriodKind.MONTH -> fmt(from, "MMMM yyyy")
        PeriodKind.YEAR -> fmt(from, "yyyy")
        PeriodKind.CUSTOM -> "${fmt(from, "d MMM yyyy")} – ${fmt(to, "d MMM yyyy")}"
    }
}

/** Small second line: the actual dates of "This week" / "Last week". */
fun periodSubtitle(f: TxnFilters): String? {
    if (f.period != PeriodKind.WEEK || f.offset < -1) return null
    val (from, to) = f.dateRange()
    if (from == null || to == null) return null
    return "${fmt(from, "d MMM")} – ${fmt(to, "d MMM")}"
}

// ---------------------------------------------------- filters -> database

/** TxnQuery (plain values) and the SQL builder live in Txnsearch.kt. */
fun TxnFilters.toQuery(search: String, accountId: Long? = null): TxnQuery {
    val (from, to) = dateRange()
    return TxnQuery(type, payment, minAmount, maxAmount, from, to, accountId, search, newestFirst)
}

/** One page of matching rows, newest or oldest first. Raise [limit] to load more. */
fun TransactionDao.observePage(q: TxnQuery, limit: Int): Flow<List<TransactionEntity>> {
    val where = TxnSql.where(q)
    val direction = if (q.newestFirst) "DESC" else "ASC"
    return rawTransactions(
        SimpleSQLiteQuery(
            "SELECT * FROM transactions WHERE ${where.sql} ORDER BY dateTime $direction, id $direction LIMIT ?",
            (where.args + limit).toTypedArray()
        )
    )
}

fun TransactionDao.observeTotals(q: TxnQuery): Flow<TransactionTotals> {
    val where = TxnSql.where(q)
    return rawTotals(
        SimpleSQLiteQuery(
            "SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount END), 0.0) AS income, " +
                    "COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount END), 0.0) AS expense, " +
                    "COUNT(*) AS count FROM transactions WHERE ${where.sql}",
            where.args.toTypedArray()
        )
    )
}

// ------------------------------------------------------------------- UI

/** "‹  October 2026  ›  ✕": step back and forward through days/weeks/months/years. */
@Composable
fun PeriodNavigator(
    filters: TxnFilters,
    onChange: (TxnFilters) -> Unit,
    modifier: Modifier = Modifier
) {
    if (filters.period == PeriodKind.ALL) return
    val steppable = filters.period != PeriodKind.CUSTOM

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (steppable) {
            IconButton(onClick = { onChange(filters.copy(offset = filters.offset - 1)) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Previous period")
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = periodTitle(filters),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            periodSubtitle(filters)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (steppable) {
            IconButton(
                onClick = { onChange(filters.copy(offset = filters.offset + 1)) },
                enabled = filters.offset < 0       // no future periods
            ) {
                Icon(Icons.Default.ArrowForward, contentDescription = "Next period")
            }
        }
        IconButton(onClick = { onChange(filters.copy(period = PeriodKind.ALL, offset = 0)) }) {
            Icon(Icons.Default.Close, contentDescription = "Show all time")
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ManuChip(label = label, selected = selected, onClick = onClick)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** The single place for sort, period, type, payment and amount. Changes apply immediately. */
@Composable
fun FilterSheet(
    filters: TxnFilters,
    onChange: (TxnFilters) -> Unit,
    onDismiss: () -> Unit
) {
    var rangeDialogOpen by remember { mutableStateOf(false) }
    var minText by remember { mutableStateOf(filters.minAmount?.let { formatAmount(it) } ?: "") }
    var maxText by remember { mutableStateOf(filters.maxAmount?.let { formatAmount(it) } ?: "") }

    // ManuBottomSheet scrolls its content and moves up with the keyboard by itself
    ManuBottomSheet(expanded = true, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                ManuTextButton(
                    onClick = {
                        onChange(TxnFilters(newestFirst = filters.newestFirst))
                        minText = ""
                        maxText = ""
                    },
                    enabled = filters.activeCount > 0
                ) { Text("Reset all") }
            }

            // ---- Period tabs
            SectionLabel("Period")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OptionChip("All", filters.period == PeriodKind.ALL) {
                    onChange(filters.copy(period = PeriodKind.ALL, offset = 0))
                }
                OptionChip("Today", filters.period == PeriodKind.DAY && filters.offset == 0) {
                    onChange(filters.copy(period = PeriodKind.DAY, offset = 0))
                }
                OptionChip("Yesterday", filters.period == PeriodKind.DAY && filters.offset == -1) {
                    onChange(filters.copy(period = PeriodKind.DAY, offset = -1))
                }
                OptionChip("Week", filters.period == PeriodKind.WEEK) {
                    onChange(filters.copy(period = PeriodKind.WEEK, offset = 0))
                }
                OptionChip("Month", filters.period == PeriodKind.MONTH) {
                    onChange(filters.copy(period = PeriodKind.MONTH, offset = 0))
                }
                OptionChip("Year", filters.period == PeriodKind.YEAR) {
                    onChange(filters.copy(period = PeriodKind.YEAR, offset = 0))
                }
                OptionChip("Custom", filters.period == PeriodKind.CUSTOM) {
                    rangeDialogOpen = true
                }
            }
            PeriodNavigator(filters = filters, onChange = onChange)

            // ---- Type
            SectionLabel("Type")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip("All", filters.type == null) { onChange(filters.copy(type = null)) }
                OptionChip("Income", filters.type == "INCOME") { onChange(filters.copy(type = "INCOME")) }
                OptionChip("Expense", filters.type == "EXPENSE") { onChange(filters.copy(type = "EXPENSE")) }
            }

            // ---- Payment method
            SectionLabel("Payment")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OptionChip("All", filters.payment == null) { onChange(filters.copy(payment = null)) }
                PAYMENT_METHODS.forEach { method ->
                    OptionChip(method, filters.payment == method) {
                        onChange(filters.copy(payment = method))
                    }
                }
            }

            // ---- Amount range
            SectionLabel("Amount (₹)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManuTextField(
                    value = minText,
                    onValueChange = {
                        minText = it
                        onChange(filters.copy(minAmount = it.toDoubleOrNull()))
                    },
                    label = { Text("Min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                ManuTextField(
                    value = maxText,
                    onValueChange = {
                        maxText = it
                        onChange(filters.copy(maxAmount = it.toDoubleOrNull()))
                    },
                    label = { Text("Max") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }

            // ---- Sort
            SectionLabel("Sort by date")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip("Latest first", filters.newestFirst) { onChange(filters.copy(newestFirst = true)) }
                OptionChip("Oldest first", !filters.newestFirst) { onChange(filters.copy(newestFirst = false)) }
            }

            ManuButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) { Text("Done") }
        }
    }

    // ---- Custom date range
    ManuDateRangePicker(
        expanded = rangeDialogOpen,
        onDismissRequest = { rangeDialogOpen = false },
        initialStartDateMillis = filters.customFrom?.let { localToPickerDay(it) },
        initialEndDateMillis = filters.customTo?.let { localToPickerDay(it) },
        onDateRangeSelected = { startUtc, endUtc ->
            onChange(
                filters.copy(
                    period = PeriodKind.CUSTOM,
                    offset = 0,
                    customFrom = pickerToLocalDay(startUtc),
                    customTo = endOfDay(pickerToLocalDay(endUtc))
                )
            )
        }
    )
}

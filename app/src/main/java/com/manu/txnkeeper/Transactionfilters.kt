package com.manu.txnkeeper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.manu.txnkeeper.data.local.TransactionDao
import com.manu.txnkeeper.data.local.TransactionEntity
import com.manu.txnkeeper.data.local.TransactionTotals
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

enum class PeriodKind { ALL, DAY, WEEK, MONTH, YEAR, CUSTOM, LAST7, LAST30 }

/** Everything the filter icon controls (search is separate, it lives next to the icon). */
data class TxnFilters(
    val newestFirst: Boolean = true,
    val type: String? = null,            // "INCOME" / "EXPENSE" / null = all
    val payment: String? = null,         // a payment method name / null = all
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val period: PeriodKind = PeriodKind.ALL,
    val offset: Int = 0,                 // 0 = this day/week/month/year, -1 = the one before, ...
    val customFrom: Long? = null,        // CUSTOM only: start of first day
    val customTo: Long? = null,          // CUSTOM only: end of last day
    val tagIds: Set<Long> = emptySet(),  // has ANY of these tags
    val withImage: Boolean = false,      // only transactions with a receipt image
    val withItems: Boolean = false       // only transactions with bill items
) {
    /** How many filters are on (sort order doesn't count). Shown as a badge on the filter icon. */
    val activeCount: Int
        get() = listOf(
            type != null,
            payment != null,
            minAmount != null || maxAmount != null,
            period != PeriodKind.ALL,
            tagIds.isNotEmpty(),
            withImage,
            withItems
        ).count { it }

    fun dateRange(): Pair<Long?, Long?> {
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay(System.currentTimeMillis()) }
        return when (period) {
            PeriodKind.ALL -> null to null
            PeriodKind.CUSTOM -> customFrom to customTo
            PeriodKind.LAST7, PeriodKind.LAST30 -> {
                val days = if (period == PeriodKind.LAST7) 6 else 29
                cal.add(Calendar.DAY_OF_YEAR, -days)
                val start = cal.timeInMillis
                start to endOfDay(System.currentTimeMillis())
            }
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
        PeriodKind.LAST7 -> "Last 7 days"
        PeriodKind.LAST30 -> "Last 30 days"
    }
}

/** Small second line: the actual dates of "This week" / "Last week". */
fun periodSubtitle(f: TxnFilters): String? {
    if (f.period == PeriodKind.LAST7 || f.period == PeriodKind.LAST30) {
        val (a, b) = f.dateRange()
        return if (a != null && b != null) "${fmt(a, "d MMM")} – ${fmt(b, "d MMM")}" else null
    }
    if (f.period != PeriodKind.WEEK || f.offset < -1) return null
    val (from, to) = f.dateRange()
    if (from == null || to == null) return null
    return "${fmt(from, "d MMM")} – ${fmt(to, "d MMM")}"
}

// ---------------------------------------------------- filters -> database

/** TxnQuery (plain values) and the SQL builder live in Txnsearch.kt. */
fun TxnFilters.toQuery(search: String, accountId: Long? = null): TxnQuery {
    val (from, to) = dateRange()
    return TxnQuery(
        type, payment, minAmount, maxAmount, from, to, accountId, search, newestFirst,
        tagIds = tagIds, hasImage = withImage, hasItems = withItems
    )
}

/** One removable chip in the "active filters" row: its label and what the filters look like without it. */
class ActiveFilter(val label: String, val without: (TxnFilters) -> TxnFilters)

fun activeFilters(f: TxnFilters, tags: List<com.manu.txnkeeper.data.local.TagEntity>): List<ActiveFilter> {
    val out = mutableListOf<ActiveFilter>()
    if (f.period != PeriodKind.ALL) out += ActiveFilter(periodTitle(f)) { it.copy(period = PeriodKind.ALL, offset = 0) }
    f.type?.let { t -> out += ActiveFilter(if (t == "INCOME") "Income" else "Expense") { it.copy(type = null) } }
    f.payment?.let { out += ActiveFilter(it) { x -> x.copy(payment = null) } }
    if (f.minAmount != null || f.maxAmount != null) {
        val label = when {
            f.minAmount != null && f.maxAmount != null -> "₹${formatAmount(f.minAmount)}–₹${formatAmount(f.maxAmount)}"
            f.minAmount != null -> "Over ₹${formatAmount(f.minAmount)}"
            else -> "Under ₹${formatAmount(f.maxAmount!!)}"
        }
        out += ActiveFilter(label) { it.copy(minAmount = null, maxAmount = null) }
    }
    f.tagIds.forEach { id ->
        val name = tags.firstOrNull { it.id == id }?.name ?: "Tag"
        out += ActiveFilter(name) { it.copy(tagIds = it.tagIds - id) }
    }
    if (f.withImage) out += ActiveFilter("Has receipt") { it.copy(withImage = false) }
    if (f.withItems) out += ActiveFilter("Has items") { it.copy(withItems = false) }
    return out
}

/** The row of removable chips under the top bar, plus "Clear". Tap a chip to drop just that filter. */
@Composable
fun ActiveFilterRow(
    filters: TxnFilters,
    tags: List<com.manu.txnkeeper.data.local.TagEntity>,
    onChange: (TxnFilters) -> Unit,
    modifier: Modifier = Modifier
) {
    val chips = activeFilters(filters, tags)
    if (chips.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { c ->
            ManuChip(label = c.label + "  ✕", selected = true, onClick = { onChange(c.without(filters)) })
        }
        if (chips.size > 1) {
            ManuTextButton(onClick = { onChange(TxnFilters(newestFirst = filters.newestFirst)) }) { Text("Clear all") }
        }
    }
}

/**
 * The part of the list the home card's small trend line draws. With no date filter that is this month so far
 * (at least the last 14 days, so the first days of a month still show a line) instead of all your history;
 * when a date filter is on, the line follows it. Returns the query to draw and a short caption (null with a date filter).
 */
fun recentTrendWindow(q: TxnQuery, now: Long = System.currentTimeMillis()): Pair<TxnQuery, String?> {
    if (q.dateFrom != null) return q to null
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val monthStart = cal.timeInMillis
    val fourteenDays = now - 14L * 24 * 60 * 60 * 1000
    return if (monthStart <= fourteenDays) q.copy(dateFrom = monthStart) to "This month"
    else q.copy(dateFrom = fourteenDays) to "Last 14 days"
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

/** Every matching row, oldest first, with no limit: the stats chart plots one point per transaction. */
fun TransactionDao.observeAllForChart(q: TxnQuery): Flow<List<TransactionEntity>> {
    val where = TxnSql.where(q)
    return rawTransactions(
        SimpleSQLiteQuery(
            "SELECT * FROM transactions WHERE ${where.sql} ORDER BY dateTime ASC, id ASC",
            where.args.toTypedArray()
        )
    )
}

/**
 * What the matching transactions add up to over time, in at most [buckets] slices between the first
 * and the last of them (slices with nothing in them are left out). The home card's small trend line
 * only needs this, so SQLite does the adding and no transaction rows are loaded.
 */
fun TransactionDao.observeSparkBuckets(q: TxnQuery, buckets: Int = 60): Flow<List<com.manu.txnkeeper.data.local.SparkBucket>> {
    val where = TxnSql.where(q)
    return rawSparkBuckets(
        SimpleSQLiteQuery(
            "SELECT CAST((t.dateTime - m.lo) * $buckets / (m.hi - m.lo + 1) AS INTEGER) AS bucket, " +
                    "SUM(CASE t.type WHEN 'INCOME' THEN t.amountPaise WHEN 'EXPENSE' THEN -t.amountPaise ELSE 0 END) AS netPaise " +
                    "FROM transactions t, " +
                    "(SELECT MIN(dateTime) AS lo, MAX(dateTime) AS hi FROM transactions WHERE ${where.sql}) m " +
                    "WHERE ${where.sql} GROUP BY bucket ORDER BY bucket",
            // the filter text appears twice, so its values are passed twice
            (where.args + where.args).toTypedArray()
        )
    )
}

fun TransactionDao.observeTotals(q: TxnQuery): Flow<TransactionTotals> {
    val where = TxnSql.where(q)
    return rawTotals(
        SimpleSQLiteQuery(
            "SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amountPaise END), 0) AS incomePaise, " +
                    "COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountPaise END), 0) AS expensePaise, " +
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
    val steppable = filters.period != PeriodKind.CUSTOM &&
            filters.period != PeriodKind.LAST7 && filters.period != PeriodKind.LAST30

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

/** A tappable row "Label            value ▾" that opens a menu (used for Period and Payment). */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    highlighted: Boolean,
    menu: @Composable (close: () -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (highlighted) scheme.primary.copy(alpha = 0.10f) else scheme.onSurface.copy(alpha = 0.05f))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** How many tags show before "Show more" (about two rows). */
private val TAGS_COLLAPSED_HEIGHT = 92.dp

/** All filters in one sheet. Changes apply immediately; the count at the top follows them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filters: TxnFilters,
    onChange: (TxnFilters) -> Unit,
    onDismiss: () -> Unit,
    /** The payment methods to offer (your list plus any that transactions still use). */
    paymentMethods: List<String>,
    /** Your tags, to filter by (empty = the Tags section is hidden). */
    tags: List<com.manu.txnkeeper.data.local.TagEntity> = emptyList(),
    /** How many transactions match right now (shown at the top). Null = not shown. */
    resultCount: Int? = null,
    /** How many transactions exist in all, for "24 of 130". */
    totalCount: Int? = null,
    /** False where the order means nothing (export). */
    showSort: Boolean = true,
    /** More than one account: shows an Account row. [accountId] null = all accounts. */
    accounts: List<com.manu.txnkeeper.data.local.AccountSummary> = emptyList(),
    accountId: Long? = null,
    onAccountChange: (Long?) -> Unit = {}
) {
    var rangeDialogOpen by remember { mutableStateOf(false) }
    var minText by remember { mutableStateOf(filters.minAmount?.let { formatAmount(it) } ?: "") }
    var maxText by remember { mutableStateOf(filters.maxAmount?.let { formatAmount(it) } ?: "") }
    var tagsExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scheme = MaterialTheme.colorScheme

    // ManuBottomSheet scrolls its content and moves up with the keyboard by itself
    // The sheet stops at 75% of the screen, so the top quarter stays visible (tap it or swipe down to close)
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    ManuBottomSheet(expanded = true, onDismissRequest = onDismiss) {
      Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // tapping empty space drops the cursor from the Min / Max boxes (chips and rows still get their taps)
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .padding(start = 24.dp, end = 24.dp, bottom = 88.dp),   // room under the floating Done button
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- Title, live count, reset
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Filters", style = MaterialTheme.typography.titleLarge)
                    if (resultCount != null) {
                        Text(
                            text = when {
                                totalCount != null && resultCount != totalCount -> "$resultCount of $totalCount transactions"
                                resultCount == 1 -> "1 transaction"
                                else -> "$resultCount transactions"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary
                        )
                    }
                }
                ManuTextButton(
                    onClick = {
                        onChange(TxnFilters(newestFirst = filters.newestFirst))
                        minText = ""
                        maxText = ""
                    },
                    enabled = filters.activeCount > 0
                ) { Text("Reset all") }
            }

            // ---- Period
            PickerRow(
                label = "Period",
                value = if (filters.period == PeriodKind.ALL) "All time" else periodTitle(filters),
                highlighted = filters.period != PeriodKind.ALL
            ) { close ->
                fun pick(label: String, on: Boolean, action: () -> Unit) = Triple(label, on, action)
                listOf(
                    pick("All time", filters.period == PeriodKind.ALL) { onChange(filters.copy(period = PeriodKind.ALL, offset = 0)) },
                    pick("Today", filters.period == PeriodKind.DAY && filters.offset == 0) { onChange(filters.copy(period = PeriodKind.DAY, offset = 0)) },
                    pick("Yesterday", filters.period == PeriodKind.DAY && filters.offset == -1) { onChange(filters.copy(period = PeriodKind.DAY, offset = -1)) },
                    pick("Last 7 days", filters.period == PeriodKind.LAST7) { onChange(filters.copy(period = PeriodKind.LAST7, offset = 0)) },
                    pick("Last 30 days", filters.period == PeriodKind.LAST30) { onChange(filters.copy(period = PeriodKind.LAST30, offset = 0)) },
                    pick("This week", filters.period == PeriodKind.WEEK) { onChange(filters.copy(period = PeriodKind.WEEK, offset = 0)) },
                    pick("This month", filters.period == PeriodKind.MONTH) { onChange(filters.copy(period = PeriodKind.MONTH, offset = 0)) },
                    pick("This year", filters.period == PeriodKind.YEAR) { onChange(filters.copy(period = PeriodKind.YEAR, offset = 0)) },
                    pick("Custom range…", filters.period == PeriodKind.CUSTOM) { rangeDialogOpen = true }
                ).forEach { (label, on, action) ->
                    DropdownMenuItem(
                        text = { Text(label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { action(); close() }
                    )
                }
            }
            PeriodNavigator(filters = filters, onChange = onChange)

            // ---- Account (only when there is a choice)
            if (accounts.size > 1) {
                PickerRow(
                    label = "Account",
                    value = accounts.firstOrNull { it.id == accountId }?.name ?: "All",
                    highlighted = accountId != null
                ) { close ->
                    DropdownMenuItem(text = { Text("All") }, onClick = { onAccountChange(null); close() })
                    accounts.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.name, fontWeight = if (a.id == accountId) FontWeight.SemiBold else FontWeight.Normal) },
                            onClick = { onAccountChange(a.id); close() }
                        )
                    }
                }
            }

            // ---- Payment
            PickerRow(
                label = "Payment",
                value = filters.payment ?: "All",
                highlighted = filters.payment != null
            ) { close ->
                // a method that is selected but no longer in the list is still shown, so it can be switched off
                (listOf<String?>(null) + (paymentMethods + listOfNotNull(filters.payment)).distinct()).forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m ?: "All", fontWeight = if (filters.payment == m) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { onChange(filters.copy(payment = m)); close() }
                    )
                }
            }

            // ---- Type
            SegmentedRow(
                labels = listOf("All", "Income", "Expense"),
                selectedIndex = when (filters.type) { "INCOME" -> 1; "EXPENSE" -> 2; else -> 0 },
                onSelect = { onChange(filters.copy(type = when (it) { 1 -> "INCOME"; 2 -> "EXPENSE"; else -> null })) },
                modifier = Modifier.fillMaxWidth()
            )

            // ---- Amount
            SectionLabel("Amount (₹)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManuTextField(
                    value = minText,
                    onValueChange = {
                        minText = it
                        onChange(filters.copy(minAmount = it.toDoubleOrNull()))
                    },
                    label = { Text("Min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f)
                )
                ManuTextField(
                    value = maxText,
                    onValueChange = {
                        maxText = it
                        onChange(filters.copy(maxAmount = it.toDoubleOrNull()))
                    },
                    label = { Text("Max") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f)
                )
            }
            val presets = listOf(
                Triple("<100", null as Double?, 100.0 as Double?),
                Triple("100–500", 100.0 as Double?, 500.0 as Double?),
                Triple("500–2k", 500.0 as Double?, 2000.0 as Double?),
                Triple(">2k", 2000.0 as Double?, null as Double?)
            )
            val presetIndex = presets.indexOfFirst { it.second == filters.minAmount && it.third == filters.maxAmount }
            SegmentedRow(
                labels = presets.map { it.first },
                selectedIndex = presetIndex,
                onSelect = { i ->
                    focusManager.clearFocus()
                    val (_, lo, hi) = presets[i]
                    val off = i == presetIndex          // tapping the selected one switches it off
                    val nl = if (off) null else lo
                    val nh = if (off) null else hi
                    minText = nl?.let { formatAmount(it) } ?: ""
                    maxText = nh?.let { formatAmount(it) } ?: ""
                    onChange(filters.copy(minAmount = nl, maxAmount = nh))
                },
                modifier = Modifier.fillMaxWidth()
            )

            // ---- Tags: a few rows, fading out, then "Show more"
            if (tags.isNotEmpty()) {
                SectionLabel("Tags" + if (filters.tagIds.isNotEmpty()) " · ${filters.tagIds.size} selected" else "")
                // chosen tags first, so they are never hidden below the fade
                val ordered = remember(tags, filters.tagIds) { tags.sortedByDescending { it.id in filters.tagIds } }
                val collapsible = tags.size > 6
                val clipped = collapsible && !tagsExpanded
                Box {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (clipped) Modifier.heightIn(max = TAGS_COLLAPSED_HEIGHT).clipToBounds() else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ordered.forEach { tag ->
                            val on = tag.id in filters.tagIds
                            OptionChip(tag.name, on) {
                                onChange(filters.copy(tagIds = if (on) filters.tagIds - tag.id else filters.tagIds + tag.id))
                            }
                        }
                    }
                    if (clipped) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, scheme.surface)))
                        )
                    }
                }
                if (collapsible) {
                    ManuTextButton(
                        onClick = { tagsExpanded = !tagsExpanded },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text(if (tagsExpanded) "Show less" else "Show all ${tags.size} tags") }
                }
            }

            // ---- Extras
            SwitchLine("Only with receipt image", filters.withImage) { onChange(filters.copy(withImage = it)) }
            SwitchLine("Only with bill items", filters.withItems) { onChange(filters.copy(withItems = it)) }

            // ---- Sort
            if (showSort) SegmentedRow(
                labels = listOf("Latest first", "Oldest first"),
                selectedIndex = if (filters.newestFirst) 0 else 1,
                onSelect = { onChange(filters.copy(newestFirst = it == 0)) },
                modifier = Modifier.fillMaxWidth()
            )

        }
        // Small floating Done button, always in reach while the filters scroll
        ManuButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 20.dp)
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
package com.example.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.txnkeeper.data.local.AccountSummary
import com.example.txnkeeper.data.local.TransactionDao
import com.example.txnkeeper.data.local.TransactionEntity
import com.example.txnkeeper.data.local.TransactionTotals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuListItem
import com.manu.manudesignsystem.components.ManuMenuItem
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.theme.ExpenseColor
import com.manu.manudesignsystem.theme.IncomeColor
import com.manu.manudesignsystem.theme.ManuDesignSystem
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.manu.manudesignsystem.components.ManuDialogBox

/** How many rows are loaded at a time. More are loaded as you scroll near the end. */
private const val PAGE_SIZE = 50

private val NoTotals = TransactionTotals(0.0, 0.0, 0)

private sealed class ListRow(val key: String, val kind: Int) {
    class Header(val dayStart: Long, val label: String) : ListRow("h$dayStart", 0)
    class Item(val txn: TransactionEntity) : ListRow("t${txn.id}", 1)
}

/** Inserts a day heading ("Today", "Yesterday", "12 Oct") before each new day. */
private fun buildRows(list: List<TransactionEntity>): List<ListRow> {
    val out = ArrayList<ListRow>(list.size + 8)
    var lastDay = Long.MIN_VALUE
    for (t in list) {
        val day = startOfDay(t.dateTime)
        if (day != lastDay) {
            out += ListRow.Header(day, dayHeading(day))
            lastDay = day
        }
        out += ListRow.Item(t)
    }
    return out
}

/**
 * The home screen: title + scan / search / filter buttons, totals, and the list of transactions.
 * It owns the filters, search text, paging and the edit / delete dialogs.
 *
 * Where the system bars are is handled by the caller (the Scaffold in MainActivity passes its
 * padding in [modifier]), and the "+" button floats over the list from there.
 */
@Composable
fun TransactionsScreen(
    dao: TransactionDao,
    accounts: List<AccountSummary>,
    accountId: Long?,                       // null = all accounts
    onAccountChange: (Long?) -> Unit,
    onManageAccounts: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }

    var filters by remember { mutableStateOf(TxnFilters()) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }          // searchText after a short pause
    var filterSheetOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TransactionEntity?>(null) }
    var deleting by remember { mutableStateOf<TransactionEntity?>(null) }

    // Wait a moment after typing so we don't query on every keystroke
    LaunchedEffect(searchText) {
        if (searchText.isNotBlank()) delay(250)
        search = searchText.trim()
    }
    // Opening search puts the cursor in the box
    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }

    // ---- Data: one page at a time, plus totals computed by the database
    val query = remember(filters, search, accountId) { filters.toQuery(search, accountId) }
    var limit by remember(filters, search, accountId) { mutableIntStateOf(PAGE_SIZE) }

    val pageFlow = remember(query, limit) { dao.observePage(query, limit) }
    val page: List<TransactionEntity>? by pageFlow.collectAsState(initial = null)

    val totalsFlow = remember(query) { dao.observeTotals(query) }
    val totals by totalsFlow.collectAsState(initial = NoTotals)

    val overallFlow = remember { dao.getTotalCount() }
    val overall: Int? by overallFlow.collectAsState(initial = null)

    val rows = remember(page) { buildRows(page.orEmpty()) }

    // Load the next page when the user scrolls near the end
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 10
        }
    }
    LaunchedEffect(nearEnd, page?.size, limit) {
        // A full page means there may be more
        if (nearEnd && (page?.size ?: 0) >= limit) limit += PAGE_SIZE
    }
    // New filters or search: start from the top
    LaunchedEffect(query) { listState.scrollToItem(0) }

    val filtersOn = filters.activeCount > 0 || search.isNotEmpty()
    val countLabel = when {
        overall == null -> "Transactions"
        filtersOn || accountId != null -> "Transactions (${totals.count} of $overall)"
        else -> "Transactions ($overall)"
    }

    // Balance of the chosen account, or of all accounts together
    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    val balance = selectedAccount?.balance ?: accounts.sumOf { it.balance }
    val balanceLabel = when {
        selectedAccount != null -> "Balance · ${selectedAccount.name}"
        accounts.size > 1 -> "Balance · all accounts"
        else -> "Balance"
    }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    // Name the account on each row only when rows from several accounts are mixed together
    val showAccountOnRows = accounts.size > 1 && accountId == null

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // ---- Title + scan / search buttons
        ManuTopBar(
            title = "TxnKeeper",
            actions = {
                IconButton(onClick = {
                    focusManager.clearFocus()
                    onScan()
                }) {
                    Icon(AppIcons.CameraAlt, contentDescription = "Scan receipt")
                }
                IconButton(onClick = {
                    if (searchOpen) {
                        searchText = ""
                        focusManager.clearFocus()
                    }
                    searchOpen = !searchOpen
                }) {
                    Icon(
                        if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (searchOpen) "Close search" else "Search"
                    )
                }
            }
        )

        // ---- Search box (opens from the search button)
        AnimatedVisibility(visible = searchOpen) {
            ManuTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Search title, amount, date, evening…") },
                modifier = Modifier
                    .padding(bottom = ManuDesignSystem.spacing.sm)
                    .focusRequester(searchFocus)
            )
        }

        // ---- Accounts: All | Main account | PNB ... | Manage
        AccountChips(
            accounts = accounts,
            selectedId = accountId,
            onSelect = onAccountChange,
            onManage = onManageAccounts,
            filterCount = filters.activeCount,
            onFilters = {
                focusManager.clearFocus()
                filterSheetOpen = true
            }
        )

        // ---- Period stepper: ‹ October 2026 › (only when a period filter is on)
        PeriodNavigator(filters = filters, onChange = { filters = it })

        // ---- Balance, and Income / Expense / Net for everything the filters match
        TotalsCard(totals, balance, balanceLabel)

        Text(
            text = countLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
        )

        // ---- The list, or a message when there is nothing to show
        val loaded = page
        val allCount = overall
        if (loaded != null && allCount != null) {
            when {
                allCount == 0 -> EmptyMessage(
                    title = "No transactions yet",
                    hint = "Tap + to add one, or tap the camera icon (or swipe right) to scan a receipt."
                )

                loaded.isEmpty() && !filtersOn && selectedAccount != null -> EmptyMessage(
                    title = "No transactions in ${selectedAccount.name}",
                    hint = "Tap + to add one."
                )

                loaded.isEmpty() -> EmptyMessage(
                    title = "No transactions match",
                    hint = "Try a different search, period or filter.",
                    actionLabel = "Clear filters",
                    onAction = {
                        filters = TxnFilters(newestFirst = filters.newestFirst)
                        searchText = ""
                    }
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    // Extra room at the bottom so the + button never covers the last row
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(items = rows, key = { it.key }, contentType = { it.kind }) { row ->
                        when (row) {
                            is ListRow.Header -> Text(
                                text = row.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                            )

                            is ListRow.Item -> TransactionRow(
                                txn = row.txn,
                                accountName = if (showAccountOnRows) accountNames[row.txn.accountId] else null,
                                onEdit = { editing = row.txn },
                                onDelete = { deleting = row.txn }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Dialogs and sheets
    if (filterSheetOpen) {
        FilterSheet(
            filters = filters,
            onChange = { filters = it },
            onDismiss = { filterSheetOpen = false }
        )
    }

    editing?.let { txn ->
        EditTransactionDialog(
            original = txn,
            accounts = accounts,
            onSave = { updated ->
                scope.launch { dao.update(updated) }
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    deleting?.let { txn ->
        ManuDialogBox(
            visible = true,
            title = "Delete this transaction?",
            message = "${txn.title}\n" +
                    "${if (txn.type == "INCOME") "+" else "−"}${formatMoney(txn.amount)} " +
                    "on ${formatDateLong(txn.dateTime)}\n\n" +
                    "This can't be undone.",
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {
                scope.launch { dao.delete(txn) }
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

// --------------------------------------------------------------- pieces

@Composable
private fun TotalsCard(totals: TransactionTotals, balance: Double, balanceLabel: String) {
    val net = totals.income - totals.expense
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
            Text(
                text = balanceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatBalance(balance),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (balance < 0) ExpenseColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Row(modifier = Modifier.padding(top = 10.dp)) {
                TotalCell("Income", formatMoney(totals.income), IncomeColor, Alignment.Start)
                TotalCell("Expense", formatMoney(totals.expense), ExpenseColor, Alignment.CenterHorizontally)
                TotalCell(
                    "Net",
                    formatSignedMoney(net),
                    if (net >= 0) IncomeColor else ExpenseColor,
                    Alignment.End
                )
            }
        }
    }
}

/** Row of chips under the title: All, one per account, and "Accounts" to open the manage screen. */
@Composable
private fun AccountChips(
    accounts: List<AccountSummary>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onManage: () -> Unit,
    filterCount: Int,
    onFilters: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
    ) {
        // With a single account there is nothing to switch between
        if (accounts.size > 1) {
            ManuChip(label = "All", selected = selectedId == null, onClick = { onSelect(null) })
            accounts.forEach { account ->
                ManuChip(
                    label = account.name,
                    selected = selectedId == account.id,
                    onClick = { onSelect(account.id) }
                )
            }
        }
        ManuChip(
            label = if (filterCount > 0) "Filters · $filterCount" else "Filters",
            selected = filterCount > 0,
            onClick = onFilters
        )
        ManuChip(label = "Accounts", onClick = onManage)
    }
}

@Composable
private fun RowScope.TotalCell(
    label: String,
    value: String,
    color: Color,
    align: Alignment.Horizontal
) {
    Column(modifier = Modifier.weight(1f), horizontalAlignment = align) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyMessage(
    title: String,
    hint: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (actionLabel != null) {
            ManuButton(onClick = onAction, modifier = Modifier.padding(top = ManuDesignSystem.spacing.md)) {
                Text(actionLabel)
            }
        }
    }
}

/** One transaction as a lib list item: tap to edit, three-dot menu for Edit / Delete. */
@Composable
private fun TransactionRow(
    txn: TransactionEntity,
    accountName: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val income = txn.type == "INCOME"
    val sign = if (income) "+" else "−"

    ManuCard(
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ManuDesignSystem.spacing.xs)
    ) {
        ManuListItem(
            title = txn.title,
            description = buildString {
                append(sign).append(formatMoney(txn.amount))
                append(" • ").append(txn.paymentMethod)
                if (accountName != null) append(" • ").append(accountName)
            },
            time = buildString {
                append(formatTime(txn.dateTime))
                if (txn.description.isNotBlank()) append(" • ").append(txn.description)
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (income) IncomeColor else ExpenseColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(sign, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            },
            onClick = onEdit,
            menuItems = listOf(
                ManuMenuItem(label = "Edit", onClick = onEdit),
                ManuMenuItem(label = "Delete", onClick = onDelete)
            )
        )
    }
}

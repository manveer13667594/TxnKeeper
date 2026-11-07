package com.manu.txnkeeper

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
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.TransactionDao
import com.manu.txnkeeper.data.local.TransactionEntity
import com.manu.txnkeeper.data.local.TransactionTotals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuListCard
import com.manu.manudesignsystem.components.ManuListItemData
import com.manu.manudesignsystem.components.ManuMenuItem
import com.manu.manudesignsystem.components.ManuDropdownMenu
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuTextButton
import com.manu.manudesignsystem.theme.ExpenseColor
import com.manu.manudesignsystem.theme.IncomeColor
import com.manu.manudesignsystem.theme.ManuDesignSystem
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.manu.manudesignsystem.components.ManuDialogBox

/** How many rows are loaded at a time. More are loaded as you scroll near the end. */
private const val PAGE_SIZE = 50

private val NoTotals = TransactionTotals(0L, 0L, 0)

/** One day of transactions: a heading ("Today", "Yesterday", "12 Oct") and one list card. */
private class DayGroup(val dayStart: Long, val label: String, val txns: List<TransactionEntity>) {
    val key: String get() = "d$dayStart"
}

/** Groups the (already sorted) transactions by day. */
private fun buildGroups(list: List<TransactionEntity>): List<DayGroup> {
    val out = ArrayList<DayGroup>()
    var day = Long.MIN_VALUE
    var bucket = ArrayList<TransactionEntity>()
    for (t in list) {
        val d = startOfDay(t.dateTime)
        if (d != day) {
            if (bucket.isNotEmpty()) out += DayGroup(day, dayHeading(day), bucket)
            bucket = ArrayList()
            day = d
        }
        bucket += t
    }
    if (bucket.isNotEmpty()) out += DayGroup(day, dayHeading(day), bucket)
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
    /** Where the payment methods come from (the Edit dialog and the filters use them). */
    settings: AppSettings,
    accounts: List<AccountSummary>,
    accountId: Long?,                       // null = all accounts
    onAccountChange: (Long?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onScan: () -> Unit,
    /** true = the floating + button should show, false = hide it (scrolling down the list). */
    onScrollDirection: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
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

    // Filter chips: your payment methods, plus any a transaction still uses (e.g. one you removed)
    val usedMethodsFlow = remember { dao.observePaymentMethodsInUse() }
    val usedMethods by usedMethodsFlow.collectAsState(initial = emptyList())
    val filterMethods = remember(settings.paymentMethods, usedMethods) {
        (settings.paymentMethods + usedMethods).distinct()
    }

    val groups = remember(page) { buildGroups(page.orEmpty()) }

    // Load the next page when the user scrolls near the end
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            // Each list item is a whole day now, so "near the end" means the last couple of days
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, page?.size, limit) {
        // A full page means there may be more
        if (nearEnd && (page?.size ?: 0) >= limit) limit += PAGE_SIZE
    }
    FabAutoHide(
        position = { listState.firstVisibleItemIndex * 100_000 + listState.firstVisibleItemScrollOffset },
        onVisible = onScrollDirection
    )
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
    val balance = selectedAccount?.balancePaise ?: accounts.sumOf { it.balancePaise }
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
                // Filter icon with the number of filters on. Only shown while filters are applied,
                // so it is easy to see that the list is filtered. Tap it to change them.
                if (filters.activeCount > 0) {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        filterSheetOpen = true
                    }) {
                        BadgedBox(badge = { Badge { Text("${filters.activeCount}") } }) {
                            Icon(
                                AppIcons.FilterList,
                                contentDescription = "Filters applied: ${filters.activeCount}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    focusManager.clearFocus()
                    onScan() }
                )
                {
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
                // Three-dot menu
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    ManuDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        items = listOf(
                            ManuMenuItem(
                                label = if (filters.activeCount > 0) "Filters · ${filters.activeCount}" else "Filters",
                                onClick = {
                                    focusManager.clearFocus()
                                    filterSheetOpen = true
                                }
                            ),
                            ManuMenuItem(label = "Settings", onClick = onOpenSettings),
                            ManuMenuItem(label = "Help", onClick = onOpenHelp),
                            ManuMenuItem(label = "About", onClick = onOpenAbout)
                        ),
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

        // ---- "2 filters applied  [Clear filters]" (only while filters are on). Sort order is kept.
        AnimatedVisibility(visible = filters.activeCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (filters.activeCount == 1) "1 filter applied" else "${filters.activeCount} filters applied",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                ManuTextButton(
                    onClick = { filters = TxnFilters(newestFirst = filters.newestFirst) }
                ) { Text("Clear filters") }
            }
        }

        // ---- Accounts: All | Main account | PNB ...  (only when there is more than one account)
        AccountChips(
            accounts = accounts,
            selectedId = accountId,
            onSelect = onAccountChange
        )

        // ---- Period stepper: ‹ October 2026 › (only when a period filter is on)
        PeriodNavigator(filters = filters, onChange = { filters = it })

        // ---- Balance, and Income / Expense / Net for everything the filters match.
        // In the list it is the first item, so it scrolls away and gives the rows the whole screen;
        // scroll back to the top and it is there again. With no list it stays put.
        val totalsHeader: @Composable () -> Unit = {
            TotalsCard(totals, balance, balanceLabel)
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
            )
        }

        // ---- The list, or a message when there is nothing to show
        val loaded = page
        val allCount = overall
        if (loaded != null && allCount != null) {
            if (loaded.isEmpty() || allCount == 0) totalsHeader()
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
                    item(key = "totals") { totalsHeader() }
                    items(items = groups, key = { it.key }) { group ->
                        Column {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                            )
                            // One lib list card per day, with a divider between its transactions
                            ManuListCard(
                                items = group.txns.map { txn ->
                                    transactionItem(
                                        txn = txn,
                                        accountName = if (showAccountOnRows) accountNames[txn.accountId] else null,
                                        onEdit = { editing = txn },
                                        onDelete = { deleting = txn }
                                    )
                                }
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
            onDismiss = { filterSheetOpen = false },
            paymentMethods = filterMethods
        )
    }

    editing?.let { txn ->
        EditTransactionDialog(
            original = txn,
            accounts = accounts,
            settings = settings,
            onAddAccount = { newAccount, onCreated ->
                scope.launch {
                    var newId = 0L
                    if (safeWrite(context, "add the account") { newId = dao.insertAccount(newAccount) }) {
                        onCreated(newId)
                    }
                }
            },
            onSave = { updated ->
                scope.launch { safeWrite(context, "save the changes") { dao.update(updated) } }
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
                    "${if (txn.type == "INCOME") "+" else "−"}${formatMoney(txn.amountPaise)} " +
                    "on ${formatDateLong(txn.dateTime)}\n\n" +
                    "This can't be undone.",
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {
                scope.launch { safeWrite(context, "delete the transaction") { dao.delete(txn) } }
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

// --------------------------------------------------------------- pieces

@Composable
private fun TotalsCard(totals: TransactionTotals, balance: Long, balanceLabel: String) {
    val net = totals.incomePaise - totals.expensePaise
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
                TotalCell("Income", formatMoney(totals.incomePaise), IncomeColor, Alignment.Start)
                TotalCell("Expense", formatMoney(totals.expensePaise), ExpenseColor, Alignment.CenterHorizontally)
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

/**
 * Row of chips under the title: All, then one per account. With a single account there is
 * nothing to switch between, so nothing is shown. Filters and account management live in the
 * three-dot menu and in Settings.
 */
@Composable
private fun AccountChips(
    accounts: List<AccountSummary>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    if (accounts.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
    ) {
        ManuChip(label = "All", selected = selectedId == null, onClick = { onSelect(null) })
        accounts.forEach { account ->
            ManuChip(
                label = account.name,
                selected = selectedId == account.id,
                onClick = { onSelect(account.id) }
            )
        }
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

/** One transaction as a row of the day's list card: tap to edit, three-dot menu for Edit / Delete. */
private fun transactionItem(
    txn: TransactionEntity,
    accountName: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
): ManuListItemData {
    val income = txn.type == "INCOME"
    val sign = if (income) "+" else "−"

    return ManuListItemData(
        title = txn.title,
        description = buildString {
            append(sign).append(formatMoney(txn.amountPaise))
            // A transaction can have no payment method (when you removed every method)
            if (txn.paymentMethod.isNotBlank()) append(" • ").append(txn.paymentMethod)
            if (accountName != null) append(" • ").append(accountName)
        },
        time = buildString {
            append(formatTime(txn.dateTime))
            if (txn.description.isNotBlank()) append(" • ").append(txn.description)
        },
        leadingContent = {
            Image(
                painter = painterResource(
                    if (income) R.drawable.ic_income else R.drawable.ic_expense
                ),
                contentDescription = if (income) "Income" else "Expense",
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape), // remove if your PNGs are already round or have their own shape
                contentScale = ContentScale.Fit
            )
        },
        onClick = onEdit,
        menuItems = listOf(
            ManuMenuItem(label = "Edit", onClick = onEdit),
            ManuMenuItem(label = "Delete", onClick = onDelete)
        )
    )
}
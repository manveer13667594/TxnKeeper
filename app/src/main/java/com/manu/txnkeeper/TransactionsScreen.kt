package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.heightIn
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
//import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
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
    var dayEnd = Long.MIN_VALUE      // the first moment after [day]
    var bucket = ArrayList<TransactionEntity>()
    for (t in list) {
        // The rows are sorted, so most sit in the day we already know: no calendar work for those
        val d =
            if (day != Long.MIN_VALUE && t.dateTime >= day && t.dateTime < dayEnd) day else startOfDay(
                t.dateTime
            )
        if (d != day) {
            if (bucket.isNotEmpty()) out += DayGroup(day, dayHeading(day), bucket)
            bucket = ArrayList()
            day = d
            // 36 hours after a day starts is always inside the next day (days last 23 to 25 hours)
            dayEnd = startOfDay(d + 36L * 3_600_000L)
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
    /**
     * Tapping the balance card opens the stats chart for what the list shows now: the query,
     * a title, and whether the line may start from the real account balance (no filters/search on).
     */
    onOpenStats: (filters: TxnFilters, search: String, accountId: Long?) -> Unit = { _, _, _ -> },
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
    // The transaction shown in full in a popup (tap a row)
    var viewing by remember { mutableStateOf<TransactionEntity?>(null) }

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

    // Small trend line in the balance card: the running balance of what the list shows
    // (the database sums it into about 60 slices, so no transaction rows are loaded for it)
    val sparkFlow = remember(query) { dao.observeSparkBuckets(query) }
    val sparkBuckets by sparkFlow.collectAsState(initial = null)

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
    // How many transactions each day holds (the scroll bar needs only the counts)
    val dayCounts = remember(groups) { groups.map { it.txns.size } }

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
        // Grow by half again each time (not a fixed 50): the page is re-read from the start whenever
        // the limit changes, so fixed steps would re-read thousands of rows over and over
        if (nearEnd && (page?.size ?: 0) >= limit) limit += maxOf(PAGE_SIZE, limit / 2)
    }
    FabAutoHide(
        position = { listState.firstVisibleItemIndex * 100_000 + listState.firstVisibleItemScrollOffset },
        onVisible = { onScrollDirection(it) }
    )
    // The totals card is the first list item: once it has scrolled away you are no longer at the top
    val showScrollTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
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
    val sparkline = remember(sparkBuckets, balance, totals, filtersOn) {
        sparkBuckets?.let { buckets ->
            // Same rule as the Stats screen: real balance when nothing is filtered, otherwise a running net
            val start = if (filtersOn) 0L else balance - (totals.incomePaise - totals.expensePaise)
            sparklineFromBuckets(buckets, start)
        }.orEmpty()
    }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    // Name the account on each row only when rows from several accounts are mixed together
    val showAccountOnRows = accounts.size > 1 && accountId == null

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // ---- Title + scan / search buttons
            ManuTopBar(
                title = "TxnKeeper",
                actions = {
                    // Filter icon, always shown next to the camera. Tap it to open the filters.
                    // While filters are on, it turns to the accent colour and shows how many.
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        filterSheetOpen = true
                    }) {
                        if (filters.activeCount > 0) {
                            BadgedBox(badge = { Badge { Text("${filters.activeCount}") } }) {
                                Icon(
                                    AppIcons.Sliders,
                                    contentDescription = "Filters applied: ${filters.activeCount}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Icon(AppIcons.Sliders, contentDescription = "Filters")
                        }
                    }
//                    IconButton(onClick = {
//                        focusManager.clearFocus()
//                        onScan()
//                         }
//                    )
//                    {
//                        Icon(AppIcons.CameraAlt, contentDescription = "Scan receipt")
//                    }
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
                            items = listOfNotNull(
                                ManuMenuItem(
                                    leadingIcon = { Icon(AppIcons.Sliders, "Filters") },
                                    label = if (filters.activeCount > 0) "Filters · ${filters.activeCount}" else "Filters",
                                    onClick = {
                                        focusManager.clearFocus()
                                        filterSheetOpen = true
                                    },
                                ),
                                ManuMenuItem(
                                    label = "Settings",
                                    leadingIcon = { Icon(Icons.Default.Settings, "Settings") },
                                    onClick = onOpenSettings
                                ),
                                ManuMenuItem(label = "Help", onClick = onOpenHelp, leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Help, "Help"
                                    )
                                }),
                                // Only there once an email address is set (FEEDBACK_EMAIL in AboutScreen.kt)
                                if (FEEDBACK_EMAIL.isNotBlank()) {
                                    ManuMenuItem(
                                        label = "Send feedback",
                                        leadingIcon = {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Chat,
                                                "Send Feedback"
                                            )
                                        },
                                        onClick = { sendFeedback(context) })
                                } else {
                                    null
                                },
                                ManuMenuItem(
                                    label = "About",
                                    leadingIcon = { Icon(Icons.Default.AccountCircle, "About") },
                                    onClick = onOpenAbout
                                )
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
                TotalsCard(
                    totals, balance, balanceLabel, sparkline,
                    modifier = Modifier.clickable {
                        focusManager.clearFocus()
                        onOpenStats(filters, search, accountId)
                    }
                )
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                )
            }

            // ---- First-use shortcut hints, one at a time (gone once closed, or once you use the shortcut).
            // Takes no space: the card floats over the top of the list (or the empty message) instead.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.dp)
                    .zIndex(1f)
            ) {
                Box(
                    modifier = Modifier
                        .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                        .absoluteOffset(0.dp, 350.dp)
                    //.height(50.dp)
                ) {
                    SwipeHintCard(settings = settings, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // ---- The list, or a message when there is nothing to show
            val loaded = page
            val allCount = overall
            if (loaded != null && allCount != null) {
                if (loaded.isEmpty() || allCount == 0) totalsHeader()
                when {
                    allCount == 0 -> EmptyMessage(
                        title = "No transactions yet",
                        hint = ""
                        // hint = "Tap + to add one, or tap the camera icon (or swipe right) to scan a receipt."
                    )

                    loaded.isEmpty() && !filtersOn && selectedAccount != null -> EmptyMessage(
                        title = "No transactions in ${selectedAccount.name}",
                        hint = ""
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

                    else -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
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
                                    // One card per day; amount at the right end of each row
                                    ManuCard(modifier = Modifier.fillMaxWidth()) {
                                        Column {
                                            group.txns.forEachIndexed { index, txn ->
                                                if (index > 0) HorizontalDivider()
                                                TransactionRow(
                                                    txn = txn,
                                                    accountName = if (showAccountOnRows) accountNames[txn.accountId] else null,
                                                    onOpen = { viewing = txn },
                                                    onEdit = { editing = txn },
                                                    onDelete = { deleting = txn }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Scroll bar: shows where you are in the whole list; drag it to jump
                        TransactionScrollbar(
                            listState = listState,
                            dayCounts = dayCounts,
                            totalCount = totals.count,
                            // Dragging far down needs more rows than are loaded: load them in steps of 500
                            onLoadUpTo = { wanted ->
                                val rounded = (wanted + 499) / 500 * 500
                                if (rounded > limit) limit = rounded
                            }
                        )
                    }
                }
            }
        }

        // ---- "Back to top" arrow, floating over the list (only while scrolled down)
        ScrollToTopButton(
            visible = showScrollTop && groups.isNotEmpty(),
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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

    viewing?.let { txn ->
        TransactionDetailDialog(
            txn = txn,
            accountName = accountNames[txn.accountId] ?: "",
            onEdit = {
                viewing = null
                editing = txn
            },
            onDelete = {
                viewing = null
                deleting = txn
            },
            onDismiss = { viewing = null }
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
                    if (safeWrite(context, "add the account") {
                            newId = dao.insertAccount(newAccount)
                        }) {
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

/**
 * A small round "back to top" arrow at the bottom right, shown while the list is scrolled down
 * (so after loading thousands of rows you do not have to scroll all the way back up).
 *
 * It sits at the bottom centre of the screen, 16 dp from the bottom edge (the + button is at the
 * bottom right). Call it inside a Box and pass Modifier.align(Alignment.BottomCenter) in [modifier].
 */
@Composable
private fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier.padding(bottom = 16.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 4.dp,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Back to top")
            }
        }
    }
}

@Composable
private fun TotalsCard(
    totals: TransactionTotals,
    balance: Long,
    balanceLabel: String,
    trend: List<Long>,
    modifier: Modifier = Modifier
) {
    val net = totals.incomePaise - totals.expensePaise
    ManuCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = balanceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = formatBalance(balance),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (balance < 0) ExpenseColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
                // The empty top-right corner: a small preview of the Stats chart
                if (trend.size >= 2) {
                    Sparkline(
                        values = trend,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(width = 110.dp, height = 44.dp)
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 10.dp)) {
                TotalCell("Income", formatMoney(totals.incomePaise), IncomeColor, Alignment.Start)
                TotalCell(
                    "Expense",
                    formatMoney(totals.expensePaise),
                    ExpenseColor,
                    Alignment.CenterHorizontally
                )
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
internal fun AccountChips(
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
            ManuButton(
                onClick = onAction,
                modifier = Modifier.padding(top = ManuDesignSystem.spacing.md)
            ) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * One transaction: icon, title, small details under it, and the amount at the right end
 * (before the three-dot menu), so every row has the same shape. Tap to open it in full.
 */
@Composable
private fun TransactionRow(
    txn: TransactionEntity,
    accountName: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val income = txn.type == "INCOME"
    val sign = if (income) "+" else "−"
    var menuOpen by remember { mutableStateOf(false) }

    // Details under the title: payment method and account (when there is something to show)
    val details = buildString {
        if (txn.paymentMethod.isNotBlank()) append(txn.paymentMethod)
        if (accountName != null) {
            if (isNotEmpty()) append(" • ")
            append(accountName)
        }
    }
    val timeLine = buildString {
        append(formatTime(txn.dateTime))
        if (txn.description.isNotBlank()) append(" • ").append(txn.description)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(if (income) R.drawable.ic_income else R.drawable.ic_expense),
            contentDescription = if (income) "Income" else "Expense",
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Fit
        )
        Column(modifier = Modifier.weight(1f)) {
            // Title gets the full width up to the three-dot menu
            Text(text = txn.title, style = MaterialTheme.typography.titleMedium)
            // Payment / account and time on the left, the amount at the right end of this row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (details.isNotEmpty()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = timeLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = sign + formatMoney(txn.amountPaise),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (income) IncomeColor else ExpenseColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        // Three-dot menu on its own, centred on the right
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            ManuDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                items = listOf(
                    ManuMenuItem(label = "Edit", onClick = onEdit),
                    ManuMenuItem(label = "Delete", onClick = onDelete)
                )
            )
        }
    }
}

/**
 * A transaction shown in full as a popup. Tap outside it or press Back to close.
 * Layout: type and Edit / Delete on top, then title and amount, then the details as label / value
 * rows, then the description in its own box.
 */
@Composable
private fun TransactionDetailDialog(
    txn: TransactionEntity,
    accountName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val income = txn.type == "INCOME"
    val sign = if (income) "+" else "−"
    val typeColor = if (income) IncomeColor else ExpenseColor
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Top: what it is, and Edit / Delete at the right
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(if (income) R.drawable.ic_income else R.drawable.ic_expense),
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Fit
                    )
                    Text(
                        text = if (income) "Income" else "Expense",
                        style = MaterialTheme.typography.labelLarge,
                        color = typeColor,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Title and amount
                Text(
                    text = txn.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = sign + formatMoney(txn.amountPaise),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = typeColor,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )

                // Details: label on the left, value on the right
                HorizontalDivider()
                DetailLine("Date", formatDateLong(txn.dateTime))
                HorizontalDivider()
                DetailLine("Time", formatTime(txn.dateTime))
                if (accountName.isNotBlank()) {
                    HorizontalDivider()
                    DetailLine("Account", accountName)
                }
                if (txn.paymentMethod.isNotBlank()) {
                    HorizontalDivider()
                    DetailLine("Payment", txn.paymentMethod)
                }

                // Description in its own box, shown in full
                if (txn.description.isNotBlank()) {
                    Text(
                        text = "Description",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = txn.description,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** One detail: grey label at the left, its value at the right. */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}
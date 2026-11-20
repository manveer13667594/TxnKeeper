package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.manu.txnkeeper.data.local.TransactionTagCrossRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.updateWithTags
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
import com.manu.txnkeeper.ui.theme.ExpenseColor
import com.manu.txnkeeper.ui.theme.IncomeColor
import com.manu.manudesignsystem.theme.ManuDesignSystem
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Info
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
@OptIn(ExperimentalFoundationApi::class)
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
    /** true while rows are selected: the caller hides the + button and the swipe shortcuts. */
    onSelectionModeChange: (Boolean) -> Unit = {},
    /** true when something outside (the widget's search icon) asks for the search box to open. */
    searchRequested: Boolean = false,
    onSearchRequestHandled: () -> Unit = {},
    /** Runs the given changes as one database transaction (all or nothing). */
    runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
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
    // "Edit" with several rows selected: they open one after the other. The ids still to come, and how many there were.
    var editQueue by remember { mutableStateOf<List<Int>>(emptyList()) }
    var editTotal by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<TransactionEntity?>(null) }
    // The transaction shown in full in a popup (tap a row)
    var viewing by remember { mutableStateOf<TransactionEntity?>(null) }

    // Selection mode: long-press a row to start, tap rows to add or remove them. Holds transaction ids.
    var selectedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val selectionMode = selectedIds.isNotEmpty()
    var moveOpen by remember { mutableStateOf(false) }
    var deleteSelectedOpen by remember { mutableStateOf(false) }
    var paymentOpen by remember { mutableStateOf(false) }
    var tagOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(selectionMode) { onSelectionModeChange(selectionMode) }
    // Back leaves selection mode first
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    // Wait a moment after typing so we don't query on every keystroke
    LaunchedEffect(searchText) {
        if (searchText.isNotBlank()) delay(250)
        search = searchText.trim()
    }
    // Opening search puts the cursor in the box
    LaunchedEffect(searchOpen) { if (searchOpen) searchFocus.requestFocus() }
    LaunchedEffect(searchRequested) {
        if (searchRequested) {
            searchOpen = true
            onSearchRequestHandled()
        }
    }

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

    // Tags: the list of tags, and which transaction has which (for the chips on rows and in the Edit dialog)
    val tagsFlow = remember { dao.observeTags() }
    val allTags by tagsFlow.collectAsState(initial = emptyList())
    val linksFlow = remember { dao.observeTagLinks() }
    val tagLinks by linksFlow.collectAsState(initial = emptyList())
    val tagMap = remember(allTags, tagLinks) { tagsByTransaction(allTags, tagLinks) }

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

    // ---- Actions on the selected transactions
    fun clearSelection() {
        selectedIds = emptySet()
    }

    fun toggle(id: Int) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun countText(n: Int) = if (n == 1) "1 transaction" else "$n transactions"

    /** "Select all": every transaction the current filters, search and account match, not only the loaded page. */
    fun selectAll() {
        scope.launch {
            var ids = emptyList<Int>()
            if (safeWrite(context, "select all") { ids = dao.matchingIds(query) }) selectedIds = ids.toSet()
        }
    }

    /**
     * Selects every transaction of one day (all of them that the current filters and search match, not only
     * the loaded rows). When the whole day is selected already, it is deselected instead.
     */
    fun toggleDay(dayStart: Long) {
        scope.launch {
            var ids = emptyList<Int>()
            val dayQuery = query.copy(
                dateFrom = maxOf(query.dateFrom ?: dayStart, dayStart),
                dateTo = minOf(query.dateTo ?: Long.MAX_VALUE, endOfDay(dayStart))
            )
            if (safeWrite(context, "select the day") { ids = dao.matchingIds(dayQuery) }) {
                val day = ids.toSet()
                if (day.isNotEmpty()) {
                    selectedIds = if (selectedIds.containsAll(day)) selectedIds - day else selectedIds + day
                }
            }
        }
    }

    fun shareSelected(format: ShareFormat) {
        val ids = selectedIds.toList()
        scope.launch {
            if (format != ShareFormat.TEXT) toast(if (format == ShareFormat.PDF) "Preparing PDF..." else "Preparing CSV...")
            safeWrite(context, "share the transactions") {
                val rows = dao.loadByIds(ids)
                if (rows.isNotEmpty()) shareTransactions(context, format, rows, accountNames, tagMap.mapValues { (_, list) -> list.map { it.name } })
            }
        }
    }

    /** Opens the next transaction of the edit queue, or closes the editor when the queue is empty. */
    fun openNextInQueue() {
        val next = editQueue.firstOrNull()
        if (next == null) {
            editing = null
            editTotal = 0
            return
        }
        editQueue = editQueue.drop(1)
        scope.launch {
            var found: TransactionEntity? = null
            safeWrite(context, "open the transaction") { found = dao.getByIds(listOf(next)).firstOrNull() }
            val txn = found
            if (txn == null) {
                openNextInQueue()
            } else {
                editing = txn
                toast("Editing ${editTotal - editQueue.size} of $editTotal")
            }
        }
    }

    /** One selected row opens its editor; with several, the editor opens for each in turn (newest first). */
    fun editSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            var rows = emptyList<TransactionEntity>()
            safeWrite(context, "open the transactions") { rows = dao.loadByIds(ids) }
            val ordered = rows.sortedWith(compareByDescending<TransactionEntity> { it.dateTime }.thenByDescending { it.id })
            val first = ordered.firstOrNull() ?: return@launch
            clearSelection()
            editQueue = ordered.drop(1).map { it.id }
            editTotal = ordered.size
            editing = first
            if (ordered.size > 1) toast("Editing 1 of ${ordered.size}. Save opens the next, Cancel stops.")
        }
    }

    fun moveSelected(toId: Long) {
        val ids = selectedIds.toList()
        val target = accountNames[toId].orEmpty()
        scope.launch {
            if (safeWrite(context, "move the transactions") {
                    runInTransaction { ids.chunked(ID_CHUNK).forEach { dao.moveByIds(it, toId) } }
                }
            ) {
                clearSelection()
                toast("Moved ${countText(ids.size)} to $target")
            }
        }
    }

    fun setPaymentOfSelected(method: String) {
        val ids = selectedIds.toList()
        scope.launch {
            if (safeWrite(context, "change the payment method") {
                    runInTransaction { ids.chunked(ID_CHUNK).forEach { dao.setPaymentByIds(it, method) } }
                }
            ) {
                clearSelection()
                toast("Payment method set to $method")
            }
        }
    }

    fun addTagToSelected(tagId: Long) {
        val ids = selectedIds.toList()
        scope.launch {
            if (safeWrite(context, "add the tag") {
                    runInTransaction {
                        ids.chunked(ID_CHUNK).forEach { chunk ->
                            dao.insertTagLinks(chunk.map { TransactionTagCrossRef(it, tagId) })
                        }
                    }
                }
            ) {
                clearSelection()
                toast("Tag added to ${countText(ids.size)}")
            }
        }
    }

    fun deleteSelected() {
        val ids = selectedIds.toList()
        scope.launch {
            var images = emptyList<String>()
            // The images are read first and removed only once the rows are really gone
            if (safeWrite(context, "delete the transactions") {
                    runInTransaction {
                        images = ids.chunked(ID_CHUNK).flatMap { dao.imageFilesOfIds(it) }
                        ids.chunked(ID_CHUNK).forEach { dao.deleteByIds(it) }
                    }
                }
            ) {
                clearSelection()
                if (images.isNotEmpty()) withContext(Dispatchers.IO) { ReceiptStore.deleteAll(context, images) }
                toast("Deleted ${countText(ids.size)}")
            }
        }
    }

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
                                    AppIcons.FilterFunnel,
                                    contentDescription = "Filters applied: ${filters.activeCount}",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Icon(AppIcons.FilterFunnel, contentDescription = "Filters")
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
                                    leadingIcon = { Icon(AppIcons.FilterFunnel, "Filters") },
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
                                    leadingIcon = { Icon(Icons.Default.Info, "About") },
                                    onClick = onOpenAbout
                                )
                            ),
                        )
                    }
                }
            )

            // ---- While rows are selected: how many, plus "Select all" and "Cancel". The action buttons
            // (share, edit, move, delete ...) float at the right edge, see SelectionRail.
            AnimatedVisibility(visible = selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    ManuTextButton(onClick = { selectAll() }) { Text("Select all") }
                    ManuTextButton(onClick = { clearSelection() }) { Text("Cancel") }
                }
            }

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
                                // Width of this day's card: a selected row paints itself out to the card's edges
                                var cardWidth by remember { mutableIntStateOf(0) }
                                Column {
                                    // Day heading. Long-press it to select the whole day; while selecting, tap it
                                    // (or "Select day") to add or remove the whole day.
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { if (selectionMode) toggleDay(group.dayStart) },
                                                onLongClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    toggleDay(group.dayStart)
                                                }
                                            )
                                            .padding(top = 14.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = group.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (selectionMode) {
                                            Text(
                                                text = if (group.txns.all { it.id in selectedIds }) "Deselect day" else "Select day",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    // One card per day; amount at the right end of each row
                                    ManuCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { cardWidth = it.width }
                                    ) {
                                        Column {
                                            group.txns.forEachIndexed { index, txn ->
                                                if (index > 0) HorizontalDivider()
                                                TransactionRow(
                                                    txn = txn,
                                                    accountName = if (showAccountOnRows) accountNames[txn.accountId] else null,
                                                    tags = tagMap[txn.id] ?: emptyList(),
                                                    selectionMode = selectionMode,
                                                    selected = txn.id in selectedIds,
                                                    cardWidthPx = cardWidth,
                                                    onLongPress = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        toggle(txn.id)
                                                    },
                                                    onOpen = { if (selectionMode) toggle(txn.id) else viewing = txn },
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

        // ---- Quick actions for the selected rows, floating at the right edge (where the + button is)
        SelectionRail(
            visible = selectionMode,
            onShare = { format -> shareSelected(format) },
            onEdit = { editSelected() },
            onMove = {
                if (accounts.size < 2) {
                    toast("Add another account first (Settings > Accounts)")
                } else {
                    moveOpen = true
                }
            },
            onPayment = { paymentOpen = true },
            onTag = {
                if (allTags.isEmpty()) {
                    toast("No tags yet. Create one from a transaction's Edit page.")
                } else {
                    tagOpen = true
                }
            },
            onDelete = { deleteSelectedOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = 24.dp)
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
            tags = tagMap[txn.id] ?: emptyList(),
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
      // key: each transaction of the queue gets a fresh dialog (otherwise it would keep the previous one's input)
      key(txn.id) {
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
            tags = allTags,
            initialTagIds = (tagMap[txn.id] ?: emptyList()).map { it.id }.toSet(),
            onAddTag = { name, colorIndex, onCreated ->
                scope.launch {
                    var newId = 0L
                    if (safeWrite(context, "add the tag") {
                            newId = dao.insertTag(TagEntity(name = name, colorIndex = colorIndex))
                        }) {
                        onCreated(newId)
                    }
                }
            },
            onSave = { updated, tagIds ->
                scope.launch {
                    if (safeWrite(context, "save the changes") { dao.updateWithTags(updated, tagIds) }) {
                        // The image it had before is not used any more if it was removed or replaced
                        txn.imageFile?.takeIf { it != updated.imageFile }?.let { ReceiptStore.delete(context, it) }
                    }
                }
                // Next one of the queue, or close (a normal single edit has an empty queue)
                openNextInQueue()
            },
            onDismiss = {
                editQueue = emptyList()
                editTotal = 0
                editing = null
            }
        )
      }
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
                scope.launch {
                    // Its image goes with it (only once the transaction is really deleted)
                    if (safeWrite(context, "delete the transaction") { dao.delete(txn) }) {
                        ReceiptStore.delete(context, txn.imageFile)
                    }
                }
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }

    // ---- Dialogs for the selected transactions
    if (deleteSelectedOpen) {
        ManuDialogBox(
            visible = true,
            title = if (selectedIds.size == 1) "Delete this transaction?" else "Delete ${selectedIds.size} transactions?",
            message = "They will be deleted together with their receipt images.\n\nThis can't be undone.",
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {
                deleteSelectedOpen = false
                deleteSelected()
            },
            onDismiss = { deleteSelectedOpen = false }
        )
    }

    if (moveOpen) {
        PickOneDialog(
            title = "Move to account",
            message = "Move ${countText(selectedIds.size)} to:",
            confirmText = "Move",
            options = accounts.map { it.id to it.name },
            onConfirm = { id ->
                moveOpen = false
                moveSelected(id)
            },
            onDismiss = { moveOpen = false }
        )
    }

    if (paymentOpen) {
        PickOneDialog(
            title = "Payment method",
            message = "Set the payment method of ${countText(selectedIds.size)} to:",
            confirmText = "Apply",
            options = settings.paymentMethods.map { it to it },
            onConfirm = { method ->
                paymentOpen = false
                setPaymentOfSelected(method)
            },
            onDismiss = { paymentOpen = false }
        )
    }

    if (tagOpen) {
        PickOneDialog(
            title = "Add tag",
            message = "Add a tag to ${countText(selectedIds.size)}:",
            confirmText = "Add",
            options = allTags.map { it.id to it.name },
            onConfirm = { tagId ->
                tagOpen = false
                addTagToSelected(tagId)
            },
            onDismiss = { tagOpen = false }
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    txn: TransactionEntity,
    accountName: String?,
    tags: List<TagEntity>,
    /** Selection mode: a checkbox shows at the left and a tap selects the row instead of opening it. */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    /** Width of the card the row sits in; the selected highlight is painted out to the card's edges. */
    cardWidthPx: Int = 0,
    onLongPress: () -> Unit = {},
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val income = txn.type == "INCOME"
    val sign = if (income) "+" else "−"
    var menuOpen by remember { mutableStateOf(false) }
    var viewerOpen by remember { mutableStateOf(false) }
    val selectedTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    // The card pads its content at the sides; reach past that padding to the card's own edges
                    val extra = ((cardWidthPx - size.width) / 2f).coerceAtLeast(0f)
                    drawRect(
                        color = selectedTint,
                        topLeft = Offset(-extra, 0f),
                        size = Size(size.width + 2 * extra, size.height)
                    )
                }
            }
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectionMode) {
            // The whole row handles the tap, so the box itself is only a picture of the state
            Checkbox(checked = selected, onCheckedChange = null)
        }
        // The receipt image when there is one (tap it to see it full screen), otherwise the first
        // letter of the title, in the same rounded square. The income / expense arrow is a small badge at its top right.
        Box(modifier = Modifier.size(46.dp)) {
            if (txn.imageFile != null) {
                ReceiptThumb(
                    fileName = txn.imageFile,
                    size = 40.dp,
                    modifier = Modifier.align(Alignment.BottomStart),
                    // While selecting, a tap on the picture selects the row like anywhere else
                    onClick = if (selectionMode) null else ({ viewerOpen = true })
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.BottomStart)
                        // Same rounded square as the receipt image, so both look alike
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = txn.title.trim().take(1).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Image(
                painter = painterResource(if (income) R.drawable.ic_income else R.drawable.ic_expense),
                contentDescription = if (income) "Income" else "Expense",
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            // Line 1: title (one line)
            Text(
                text = txn.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Line 2: tags (first two, then +N), only when there are any
            TagChipLine(tags = tags, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            // Line 3: one muted line, time · payment · account · note (the note is cut first when short of space)
            val meta = listOfNotNull(
                formatTime(txn.dateTime),
                txn.paymentMethod.takeIf { it.isNotBlank() },
                accountName?.takeIf { it.isNotBlank() },
                txn.description.takeIf { it.isNotBlank() }?.replace('\n', ' ')
            ).joinToString(" • ")
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // The amount, centred vertically on the right so it lines up across every row
        Text(
            text = sign + formatMoney(txn.amountPaise),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (income) IncomeColor else ExpenseColor,
            maxLines = 1
        )
        // Three-dot menu on its own, centred on the right
//        Box {
//            IconButton(onClick = { menuOpen = true }) {
//                Icon(Icons.Default.MoreVert, contentDescription = "More")
//            }
//            ManuDropdownMenu(
//                expanded = menuOpen,
//                onDismissRequest = { menuOpen = false },
//                items = listOf(
//                    ManuMenuItem(label = "Edit", onClick = onEdit),
//                    ManuMenuItem(label = "Delete", onClick = onDelete)
//                )
//            )
//        }
    }

    if (viewerOpen && txn.imageFile != null) {
        ReceiptImageViewer(fileName = txn.imageFile, onDismiss = { viewerOpen = false })
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
    tags: List<TagEntity>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val income = txn.type == "INCOME"
    val sign = if (income) "+" else "−"
    val typeColor = if (income) IncomeColor else ExpenseColor
    var viewerOpen by remember { mutableStateOf(false) }
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
                if (tags.isNotEmpty()) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Tags",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TagChipRow(tags = tags, modifier = Modifier.weight(1f))
                    }
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

                // The receipt image, if there is one; tap it to see it full screen
                if (txn.imageFile != null) {
                    Text(
                        text = "Receipt image",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                    )
                    ReceiptPreview(fileName = txn.imageFile, onClick = { viewerOpen = true })
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (viewerOpen && txn.imageFile != null) {
        ReceiptImageViewer(fileName = txn.imageFile, onDismiss = { viewerOpen = false })
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
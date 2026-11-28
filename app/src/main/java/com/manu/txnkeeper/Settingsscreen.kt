package com.manu.txnkeeper

import io.whisperkit.WhisperKit
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import com.manu.txnkeeper.ui.theme.DarkInk
import com.manu.txnkeeper.ui.theme.DarkOnSurface
import com.manu.txnkeeper.ui.theme.DarkSurface
import com.manu.txnkeeper.ui.theme.DarkSurfaceVariant
import com.manu.txnkeeper.ui.theme.LightInk
import com.manu.txnkeeper.ui.theme.LightOnSurface
import com.manu.txnkeeper.ui.theme.LightSurface
import com.manu.txnkeeper.ui.theme.LightSurfaceVariant
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuButton
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.AppDatabase
import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.widget.QuickActionsWidgetReceiver
import com.manu.txnkeeper.widget.SearchBarWidgetReceiver
import kotlinx.coroutines.launch
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuSwitch
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.theme.ManuDesignSystem
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** The pages of Settings. Only [MAIN] is a list; the others open from a row on it. */
private enum class SettingsPage(val title: String) {
    MAIN("Settings"),
    PAYMENT("Payment methods"),
    TAGS("Tags"),
    DEFAULTS("Defaults"),
    WIDGETS("Home screen widgets"),
    DATA("Export & import"),
    DELETE("Delete data")
}

/**
 * Full-screen Settings page (opened from the three-dot menu on the home screen).
 *
 * The main page is a short list: a card of quick preferences, then cards of rows. A row with an
 * arrow opens its own page inside Settings (Back returns to the list), so the long controls
 * (payment methods, export filters, delete) are not all on one scrolling screen.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    database: AppDatabase,
    onManageAccounts: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onShowTutorial: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    // For the "Defaults" page: the account to pick from (updates by itself when accounts change)
    val accounts by remember(database) { database.transactionDao().observeAccounts() }
        .collectAsState(initial = emptyList<AccountSummary>())
    var addingMethod by remember { mutableStateOf(false) }
    // The payment method being removed, and how many transactions use it (null while counting)
    var removingMethod by remember { mutableStateOf<String?>(null) }
    var removingUsage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(removingMethod) {
        removingUsage = null
        removingMethod?.let { removingUsage = database.transactionDao().countByPaymentMethod(it) }
    }

    // Tags: the list, how many transactions use each, and the add / edit / delete dialogs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tagDao = remember(database) { database.transactionDao() }
    val tags by remember(tagDao) { tagDao.observeTags() }.collectAsState(initial = emptyList<TagEntity>())
    val tagUsage by remember(tagDao) { tagDao.observeTagUsage() }.collectAsState(initial = emptyList())
    val usageById = remember(tagUsage) { tagUsage.associate { it.tagId to it.uses } }
    var addingTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var deletingTag by remember { mutableStateOf<TagEntity?>(null) }

    // Back from a sub-page returns to the list; from the list it closes Settings (handled by the app)
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    // Opening a page from the list slides it in over the list, and Back slides it away again, the
    // same way every other screen of the app opens and closes (see ScreenTransitions.kt)
    AnimatedContent(
        targetState = page,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != SettingsPage.MAIN) subPageOpenTransition() else subPageCloseTransition()
        },
        label = "settings page"
    ) { shown ->
        ManuScaffold(
            contentPadding = PaddingValues(horizontal = 20.dp),
            topBar = {
                ManuTopBar(
                    title = shown.title,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavigationClick = {
                        if (shown == SettingsPage.MAIN) onClose() else page = SettingsPage.MAIN
                    }
                )
            }
        ) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                when (shown) {
                    SettingsPage.MAIN -> MainSettings(
                        settings = settings,
                        onManageAccounts = onManageAccounts,
                        onOpenPayment = { page = SettingsPage.PAYMENT },
                        tagCount = tags.size,
                        onOpenTags = { page = SettingsPage.TAGS },
                        onOpenDefaults = { page = SettingsPage.DEFAULTS },
                        onOpenWidgets = { page = SettingsPage.WIDGETS },
                        onOpenData = { page = SettingsPage.DATA },
                        onOpenDelete = { page = SettingsPage.DELETE },
                        onOpenHelp = onOpenHelp,
                        onOpenAbout = onOpenAbout,
                        onShowTutorial = onShowTutorial
                    )

                    SettingsPage.PAYMENT -> PaymentMethodsPage(
                        settings = settings,
                        onAdd = { addingMethod = true },
                        onRemove = { removingMethod = it }
                    )

                    SettingsPage.TAGS -> TagsPage(
                        tags = tags,
                        usage = usageById,
                        onAdd = { addingTag = true },
                        onEdit = { editingTag = it },
                        onDelete = { deletingTag = it }
                    )

                    SettingsPage.DEFAULTS -> DefaultsPage(settings = settings, accounts = accounts)

                    SettingsPage.WIDGETS -> WidgetsPage()

                    SettingsPage.DATA -> SettingsCard {
                        Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg)) {
                            DataTransferControls(database = database, settings = settings)
                        }
                    }

                    // Brings its own cards, like the Payment methods and Defaults pages
                    SettingsPage.DELETE -> DeleteDataControls(settings = settings, database = database)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (addingTag) {
        TagDialog(
            initial = null,
            takenNames = tags.map { it.name.lowercase() }.toSet(),
            onSave = { name, colorIndex ->
                scope.launch {
                    safeWrite(context, "add the tag") {
                        tagDao.insertTag(TagEntity(name = name, colorIndex = colorIndex))
                    }
                }
                addingTag = false
            },
            onDismiss = { addingTag = false }
        )
    }

    editingTag?.let { tag ->
        TagDialog(
            initial = tag,
            takenNames = tags.filter { it.id != tag.id }.map { it.name.lowercase() }.toSet(),
            onSave = { name, colorIndex ->
                scope.launch {
                    safeWrite(context, "save the tag") {
                        tagDao.updateTag(tag.copy(name = name, colorIndex = colorIndex))
                    }
                }
                editingTag = null
            },
            onDismiss = { editingTag = null }
        )
    }

    // Asks before deleting, and says how many transactions have it. Only the label goes, never a transaction.
    deletingTag?.let { tag ->
        val uses = usageById[tag.id] ?: 0
        ManuDialog(
            visible = true,
            title = "Delete \"${tag.name}\"?",
            confirmText = "Delete",
            onConfirm = {
                scope.launch { safeWrite(context, "delete the tag") { tagDao.deleteTag(tag.id) } }
                deletingTag = null
            },
            onDismiss = { deletingTag = null },
            destructive = true
        ) {
            Text(
                if (uses == 0) {
                    "No transactions have this tag."
                } else {
                    "${if (uses == 1) "1 transaction has" else "$uses transactions have"} this tag. " +
                            "The tag is taken off; the transactions themselves are not deleted."
                }
            )
        }
    }

    if (addingMethod) {
        AddPaymentMethodDialog(
            existing = settings.paymentMethods,
            onAdd = {
                settings.addPaymentMethod(it)
                addingMethod = false
            },
            onDismiss = { addingMethod = false }
        )
    }

    // Asks before removing, and says how many transactions use it. Those keep their label.
    val target = removingMethod
    val usage = removingUsage
    if (target != null && usage != null) {
        ManuDialog(
            visible = true,
            title = "Remove \"$target\"?",
            confirmText = "Remove",
            onConfirm = {
                settings.removePaymentMethod(target)
                removingMethod = null
            },
            onDismiss = { removingMethod = null },
            destructive = true
        ) {
            Text(
                if (usage == 0) {
                    "No transactions use it."
                } else {
                    "${if (usage == 1) "1 transaction uses" else "$usage transactions use"} it. " +
                            "They keep this label, but it won't be offered for new ones."
                }
            )
        }
    }
}

/** The short list shown first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainSettings(
    settings: AppSettings,
    onManageAccounts: () -> Unit,
    onOpenPayment: () -> Unit,
    tagCount: Int,
    onOpenTags: () -> Unit,
    onOpenDefaults: () -> Unit,
    onOpenWidgets: () -> Unit,
    onOpenData: () -> Unit,
    onOpenDelete: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onShowTutorial: () -> Unit
) {
    // Look of the app: the theme, changed right here
    SettingsCard {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingIcon(Icons.Default.Palette)
                Text("Theme", style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
            ) {
                ThemeMode.entries.forEach { mode ->
                    ManuChip(
                        label = mode.label,
                        selected = settings.themeMode == mode,
                        onClick = { settings.updateThemeMode(mode) }
                    )
                }
            }
        }
    }

    // Widgets get their own card near the top, with a picture, so they are easy to discover
    SettingsCard {
        SettingsRow(
            title = "Home screen widgets",
            icon = Icons.Default.Widgets,
            subtitle = "Add from your home screen",
            onClick = onOpenWidgets
        )
        WidgetPreview(
            icons = SEARCH_WIDGET_ICONS,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(onClickLabel = "Open home screen widgets", onClick = onOpenWidgets)
        )
        Spacer(Modifier.height(14.dp))
    }

    SettingsCard {
        SettingsRow(
            title = "Accounts",
            icon = Icons.Default.AccountBalanceWallet,
            subtitle = "Accounts and balances",
            onClick = onManageAccounts
        )
        HorizontalDivider()
        val count = settings.paymentMethods.size
        SettingsRow(
            title = "Payment methods",
            icon = Icons.Default.CreditCard,
            subtitle = if (count == 0) "None" else "$count saved",
            onClick = onOpenPayment
        )
        HorizontalDivider()
        SettingsRow(
            title = "Tags",
            icon = Icons.Default.Sell,
            subtitle = if (tagCount == 0) "None" else "$tagCount saved",
            onClick = onOpenTags
        )
        HorizontalDivider()
        SettingsRow(
            title = "Defaults",
            icon = Icons.Default.Tune,
            subtitle = "Your usual choices and voice options",
            onClick = onOpenDefaults
        )
    }

    SettingsCard {
        SettingsRow(
            title = "Export & import",
            icon = Icons.Default.ImportExport,
            subtitle = "Back up, share or restore",
            onClick = onOpenData
        )
        HorizontalDivider()
        SettingsRow(
            title = "Delete data",
            icon = Icons.Default.DeleteForever,
            subtitle = "Delete an account or all data",
            titleColor = MaterialTheme.colorScheme.error,
            onClick = onOpenDelete
        )
    }

    // Debug builds only: view and share the scan / voice logs (this block is removed from release builds)
    if (BuildConfig.DEBUG) {
        var logOpen by remember { mutableStateOf(false) }
        if (logOpen) DebugDataDialog(onDismiss = { logOpen = false })
        SettingsCard {
            SwitchRow(
                icon = Icons.Default.BugReport,
                title = "Hide debug features",
                subtitle = "Debug build only. Hides the debug cards and logs and stops debug logging, so the app behaves like the release version.",
                checked = settings.hideDebug,
                onCheckedChange = { settings.updateHideDebug(it) }
            )
            if (DebugMode.on) {
                HorizontalDivider()
                SettingsRow(
                    title = "Debug logs",
                    icon = Icons.Default.BugReport,
                    subtitle = "${DebugDataLog.samples.size} saved · view, share as CSV or JSON, delete",
                    onClick = { logOpen = true }
                )
            }
        }
    }

    SettingsCard {
        SettingsRow(title = "Help", icon = Icons.AutoMirrored.Filled.HelpOutline, onClick = onOpenHelp)
        HorizontalDivider()
        SettingsRow(
            title = "Show tutorial",
            icon = Icons.Default.PlayCircle,
            subtitle = "See how the app works",
            onClick = onShowTutorial
        )
        HorizontalDivider()
        SettingsRow(
            title = "About",
            icon = Icons.Default.Info,
            subtitle = "Version and privacy policy",
            onClick = onOpenAbout
        )
    }
}

/** The two home screen widgets, each with a picture of it and a button that adds it. */
@Composable
private fun WidgetsPage() {
    val context = LocalContext.current
    Text(
        text = "Add entries from your home screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    WidgetChoice(
        title = "Quick actions",
        description = "Add, speak or scan",
        icons = QUICK_WIDGET_ICONS,
        onAdd = { pinWidget(context, QuickActionsWidgetReceiver::class.java) }
    )
    WidgetChoice(
        title = "Quick actions with search",
        description = "Search, add, speak or scan",
        icons = SEARCH_WIDGET_ICONS,
        onAdd = { pinWidget(context, SearchBarWidgetReceiver::class.java) }
    )
    Text(
        text = "Touch and hold a widget to resize it.\n\n" +
                "Not added? Touch and hold your home screen, tap Widgets and find TxnKeeper.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** One widget on the Widgets page: what it does, what it looks like, and a button to add it. */
@Composable
private fun WidgetChoice(
    title: String,
    description: String,
    icons: List<Int>,
    onAdd: () -> Unit
) {
    SettingsCard {
        Column(
            modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            WidgetPreview(icons = icons, modifier = Modifier.align(Alignment.CenterHorizontally))
            ManuButton(modifier = Modifier.fillMaxWidth(), onClick = onAdd) {
                Text("Add to home screen")
            }
        }
    }
}

private val QUICK_WIDGET_ICONS =
    listOf(R.drawable.ic_widget_add, R.drawable.ic_widget_mic, R.drawable.ic_widget_camera)
private val SEARCH_WIDGET_ICONS = listOf(R.drawable.ic_widget_search) + QUICK_WIDGET_ICONS

/**
 * A picture of a widget's row of round buttons, drawn with the widget's own sizes and colours
 * (see widget/WidgetBar.kt). The mic is highlighted, as an example of the last used button.
 */
@Composable
private fun WidgetPreview(icons: List<Int>, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (dark) DarkSurfaceVariant else LightSurfaceVariant)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icons.forEach { icon ->
            val focused = icon == R.drawable.ic_widget_mic
            val fill = when {
                focused -> if (dark) DarkInk else LightInk
                else -> if (dark) DarkSurface else LightSurface
            }
            val tint = when {
                focused -> if (dark) Color.Black else Color.White
                else -> if (dark) DarkOnSurface else LightOnSurface
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(fill),
                contentAlignment = Alignment.Center
            ) {
                // Only a picture: the text next to it already says what the widget does
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Opens the phone's own "add this widget" prompt. Not every home screen app supports it. */
private fun pinWidget(context: Context, receiver: Class<*>) {
    val manager = AppWidgetManager.getInstance(context)
    val asked = runCatching {
        manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
    }.getOrDefault(false)
    if (!asked) {
        Toast.makeText(
            context,
            "Add it from your home screen's Widgets list.",
            Toast.LENGTH_LONG
        ).show()
    }
}

/** The list of payment methods, with a remove button on each and one Add button. */
@Composable
private fun PaymentMethodsPage(
    settings: AppSettings,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Text(
        text = "Shown when you add a transaction.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingsCard {
        Column(
            modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (settings.paymentMethods.isEmpty()) {
                Text(
                    text = "No payment methods yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            settings.paymentMethods.forEach { method ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = method,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onRemove(method) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove $method")
                    }
                }
            }
        }
    }
    ManuButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onAdd
    ) { Text("Add payment method") }
}

/** The list of tags: edit or delete each one, and one Add button. */
@Composable
private fun TagsPage(
    tags: List<TagEntity>,
    usage: Map<Long, Int>,
    onAdd: () -> Unit,
    onEdit: (TagEntity) -> Unit,
    onDelete: (TagEntity) -> Unit
) {
    Text(
        text = "Labels like Groceries or Savings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingsCard {
        Column(
            modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (tags.isEmpty()) {
                Text(
                    text = "No tags yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            tags.forEach { tag ->
                val uses = usage[tag.id] ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row { TagChip(tag) }
                        Text(
                            text = if (uses == 1) "1 transaction" else "$uses transactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    IconButton(onClick = { onEdit(tag) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit ${tag.name}")
                    }
                    IconButton(onClick = { onDelete(tag) }) {
                        Icon(Icons.Default.Close, contentDescription = "Delete ${tag.name}")
                    }
                }
            }
        }
    }
    ManuButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onAdd
    ) { Text("Add tag") }
}

/**
 * What a new transaction starts with: its type, account and payment method. They only fill the
 * Add page in advance; every one can still be changed there for a single entry.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultsPage(
    settings: AppSettings,
    accounts: List<AccountSummary>
) {
    Text(
        text = "Used for new entries. You can change them anytime.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // ---- Type
    DefaultsSection(title = "Transaction type", icon = Icons.Default.SwapVert) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
        ) {
            ManuChip(
                label = "Expense",
                selected = settings.defaultType == TYPE_EXPENSE,
                onClick = { settings.updateDefaultType(TYPE_EXPENSE) }
            )
            ManuChip(
                label = "Income",
                selected = settings.defaultType == TYPE_INCOME,
                onClick = { settings.updateDefaultType(TYPE_INCOME) }
            )
        }
    }

    // ---- Account ("Last used" = the account you are looking at, or the one you used last)
    val chosenAccount = settings.validDefaultAccountId(accounts.map { it.id })
    DefaultsSection(
        title = "Account",
        icon = Icons.Default.AccountBalanceWallet,
        caption = "Last used: the account you viewed or used last."
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
        ) {
            ManuChip(
                label = "Last used",
                selected = chosenAccount == null,
                onClick = { settings.updateDefaultAccountId(null) }
            )
            accounts.forEach { account ->
                ManuChip(
                    label = account.name,
                    selected = chosenAccount == account.id,
                    onClick = { settings.updateDefaultAccountId(account.id) }
                )
            }
        }
    }

    // ---- Payment method (from your list; add or remove methods on the Payment methods page)
    DefaultsSection(title = "Payment method", icon = Icons.Default.CreditCard) {
        if (settings.paymentMethods.isEmpty()) {
            Text(
                text = "Add a payment method in Settings first.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
            ) {
                settings.paymentMethods.forEach { method ->
                    ManuChip(
                        label = method,
                        selected = settings.defaultPayment == method,
                        onClick = { settings.updateDefaultPayment(method) }
                    )
                }
            }
        }
    }

    // ---- Images: whether a scanned or attached image is kept with a new transaction
    SettingsCard {
        SwitchRow(
            icon = Icons.Default.Image,
            title = "Include images",
            subtitle = "Keep receipts with transactions",
            checked = settings.includeImageByDefault,
            onCheckedChange = { settings.updateIncludeImageByDefault(it) }
        )
        HorizontalDivider()
        SwitchRow(
            icon = Icons.Default.Image,
            title = "Fill from image",
            subtitle = "Add details from receipts",
            checked = settings.fillFromImageByDefault,
            onCheckedChange = { settings.updateFillFromImageByDefault(it) }
        )
    }

    // ---- Voice entry
    SettingsCard {
        SwitchRow(
            icon = Icons.Default.Mic,
            title = "Fast voice entry",
            subtitle = if (WhisperKit.isSupported) "Get voice results quicker" else "Needed for voice on this phone",
            checked = settings.fastVoice,
            onCheckedChange = { settings.updateFastVoice(it) }
        )
        // Needs offline voice, which some phones cannot run
        if (WhisperKit.isSupported) {
            HorizontalDivider()
            SwitchRow(
                icon = Icons.Default.Mic,
                title = "Double-check voice",
                subtitle = "Choose the right one if results differ",
                checked = settings.doubleCheckVoice,
                onCheckedChange = { settings.updateDoubleCheckVoice(it) }
            )
        }
        HorizontalDivider()
        SwitchRow(
            icon = Icons.Default.Mic,
            title = "Listen automatically",
            subtitle = "Starts and stops as you speak",
            checked = settings.voiceAutoListen,
            onCheckedChange = { settings.updateVoiceAutoListen(it) }
        )
        HorizontalDivider()
        SwitchRow(
            icon = Icons.Default.Mic,
            title = "Voice sounds",
            subtitle = "Beep on start and stop",
            checked = settings.voiceSounds,
            onCheckedChange = { settings.updateVoiceSounds(it) }
        )
        HorizontalDivider()
        SwitchRow(
            icon = Icons.Default.Mic,
            title = "Voice tips",
            subtitle = "Show examples of what to say",
            checked = settings.voiceTips,
            onCheckedChange = { settings.updateVoiceTips(it) }
        )
    }
}

/** A card with an icon and title, an optional one-line note, and the controls below them. */
@Composable
private fun DefaultsSection(
    title: String,
    icon: ImageVector,
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsCard {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingIcon(icon)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

/** A title with a one-line explanation and an on / off switch at the right. */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingIcon(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ManuSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** One card that holds a group of rows or controls. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * The small icon in front of a setting's title. It only decorates (the title says what it is),
 * so it has no description for screen readers. A destructive row passes the error colour.
 */
@Composable
private fun SettingIcon(
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Icon(icon, contentDescription = null, tint = tint)
}

/** A tappable row: an icon, the title, an optional one-line summary, and an arrow. */
@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingIcon(
            icon = icon,
            tint = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else titleColor
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
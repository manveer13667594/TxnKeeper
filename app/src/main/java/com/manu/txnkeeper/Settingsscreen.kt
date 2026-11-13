package com.manu.txnkeeper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuButton
import com.manu.txnkeeper.data.local.AppDatabase
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.theme.ManuDesignSystem
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color

/** The pages of Settings. Only [MAIN] is a list; the others open from a row on it. */
private enum class SettingsPage(val title: String) {
    MAIN("Settings"),
    PAYMENT("Payment methods"),
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    var addingMethod by remember { mutableStateOf(false) }
    // The payment method being removed, and how many transactions use it (null while counting)
    var removingMethod by remember { mutableStateOf<String?>(null) }
    var removingUsage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(removingMethod) {
        removingUsage = null
        removingMethod?.let { removingUsage = database.transactionDao().countByPaymentMethod(it) }
    }

    // Back from a sub-page returns to the list; from the list it closes Settings (handled by the app)
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    ManuScaffold(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = page.title,
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.Default.ArrowBack,
                onNavigationClick = {
                    if (page == SettingsPage.MAIN) onClose() else page = SettingsPage.MAIN
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
            when (page) {
                SettingsPage.MAIN -> MainSettings(
                    settings = settings,
                    onManageAccounts = onManageAccounts,
                    onOpenPayment = { page = SettingsPage.PAYMENT },
                    onOpenData = { page = SettingsPage.DATA },
                    onOpenDelete = { page = SettingsPage.DELETE },
                    onOpenHelp = onOpenHelp,
                    onOpenAbout = onOpenAbout
                )

                SettingsPage.PAYMENT -> PaymentMethodsPage(
                    settings = settings,
                    onAdd = { addingMethod = true },
                    onRemove = { removingMethod = it }
                )

                SettingsPage.DATA -> SettingsCard {
                    Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg)) {
                        DataTransferControls(database = database)
                    }
                }

                SettingsPage.DELETE -> SettingsCard {
                    Column(
                        modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DeleteDataControls(settings = settings, database = database)
                    }
                }
            }
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
    onOpenData: () -> Unit,
    onOpenDelete: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit
) {
    // Quick preferences, changed right here
    SettingsCard {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
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
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Voice tips", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Show a tip on the voice screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.voiceTips,
                onCheckedChange = { settings.updateVoiceTips(it) }
            )
        }
    }

    SettingsCard {
        SettingsRow(title = "Accounts", subtitle = "Add, edit or delete", onClick = onManageAccounts)
        HorizontalDivider()
        val count = settings.paymentMethods.size
        SettingsRow(
            title = "Payment methods",
            subtitle = if (count == 0) "None" else "$count saved",
            onClick = onOpenPayment
        )
    }

    SettingsCard {
        SettingsRow(title = "Export & import", subtitle = "CSV and PDF", onClick = onOpenData)
        HorizontalDivider()
        SettingsRow(
            title = "Delete data",
            subtitle = "Everything, or one account",
            titleColor = MaterialTheme.colorScheme.error,
            onClick = onOpenDelete
        )
    }

    SettingsCard {
        SettingsRow(title = "Help", onClick = onOpenHelp)
        HorizontalDivider()
        SettingsRow(title = "About", subtitle = "Version and privacy policy", onClick = onOpenAbout)
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
        text = "The choices shown when you add or edit a transaction.",
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
                    text = "No payment methods. Transactions are saved without one.",
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

/** One card that holds a group of rows or controls. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ManuCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** A tappable row: title, an optional one-line summary, and an arrow. */
@Composable
private fun SettingsRow(
    title: String,
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
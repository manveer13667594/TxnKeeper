package com.manu.txnkeeper

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

/**
 * Full-screen Settings page (opened from the three-dot menu on the home screen).
 * Each group of settings is a [SettingsSection]; add new sections below "Data".
 */
@OptIn(ExperimentalLayoutApi::class)
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
    var addingMethod by remember { mutableStateOf(false) }
    // The payment method being removed, and how many transactions use it (null while counting)
    var removingMethod by remember { mutableStateOf<String?>(null) }
    var removingUsage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(removingMethod) {
        removingUsage = null
        removingMethod?.let { removingUsage = database.transactionDao().countByPaymentMethod(it) }
    }

    ManuScaffold(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "Settings",
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.Default.ArrowBack,
                onNavigationClick = onClose
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
            SettingsSection(title = "Appearance") {
                Text("Theme", style = MaterialTheme.typography.titleMedium)

                Text(
                    text = "System default follows your phone's light / dark setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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

            SettingsSection(title = "Accounts") {
                Text(
                    text = "Add, edit or delete accounts. The account chips on the home screen show up once you have more than one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ManuOutlineButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onManageAccounts
                ) { Text("Manage accounts") }
            }

            SettingsSection(title = "Payment methods") {
                Text(
                    text = "The choices shown when you add or edit a transaction. Remove the ones you don't use, or add your own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (settings.paymentMethods.isEmpty()) {
                    Text(
                        text = "No payment methods. Transactions are saved without one.",
                        style = MaterialTheme.typography.bodyMedium
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
                        IconButton(onClick = { removingMethod = method }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove $method")
                        }
                    }
                }
                ManuButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { addingMethod = true }
                ) { Text("Add payment method") }
            }

            SettingsSection(title = "Data") {
                DataTransferControls(database = database)
            }

            SettingsSection(title = "Delete data") {
                DeleteDataControls(settings = settings, database = database)
            }

            SettingsSection(title = "Help & About") {
                Text(
                    text = "Quick answers on adding transactions, scanning receipts, voice entry, accounts, " +
                            "filters, and importing or exporting your data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ManuButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenHelp
                ) { Text("Help") }
                Text(
                    text = "See the app version, what TxnKeeper is for, and read the privacy policy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ManuOutlineButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenAbout
                ) { Text("About") }
            }

            // More sections (notifications, ...) go here later.
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

/** A small heading with a card under it that holds one group of settings. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ManuCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}
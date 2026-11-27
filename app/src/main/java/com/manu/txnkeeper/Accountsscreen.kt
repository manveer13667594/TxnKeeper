package com.manu.txnkeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuDropdownMenu
import com.manu.manudesignsystem.components.ManuMenuItem
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.txnkeeper.ui.theme.ExpenseColor
import com.manu.manudesignsystem.theme.ManuDesignSystem
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AccountSummary

private const val MAX_ACCOUNT_NAME = 30

/**
 * Full-screen page that lists the accounts with their balances, and lets you add, edit and delete them.
 *
 * It is laid out like the home screen and Settings: a balance card on top, then one card with a row
 * per account (initial, name and number of transactions on the left, balance on the right).
 *
 * Deleting an account also deletes all of its transactions. The confirmation says how many, and the
 * same phrase as on the "Delete data" page has to be typed. The last account can't be deleted.
 */
@Composable
fun AccountsScreen(
    accounts: List<AccountSummary>,
    onClose: () -> Unit,
    onAdd: (AccountEntity) -> Unit,
    onUpdate: (AccountEntity) -> Unit,
    /** Deletes the account together with all of its transactions. */
    onDelete: (accountId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf<AccountSummary?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AccountSummary?>(null) }

    val totalBalance = accounts.sumOf { it.balancePaise }
    val canDelete = accounts.size > 1

    // ManuScaffold insets for the status bar, navigation bar and keyboard
    ManuScaffold(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "Accounts",
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onClose
            )
        },
        bottomBar = {
            ManuButton(
                onClick = { adding = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
            ) { Text("Add account") }
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Tap an account to edit it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Same look as the balance card on the home screen
            ManuCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
                    Text(
                        text = if (accounts.size > 1) "Total balance · ${accounts.size} accounts" else "Total balance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = formatBalance(totalBalance),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (totalBalance < 0) ExpenseColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }

            // One card, one row per account, like the day cards on the home screen
            ManuCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    accounts.forEachIndexed { index, account ->
                        if (index > 0) HorizontalDivider()
                        AccountRow(
                            account = account,
                            canDelete = canDelete,
                            onEdit = { editing = account },
                            onDelete = { deleting = account }
                        )
                    }
                }
            }

            if (!canDelete) {
                Text(
                    text = "The last account can't be deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (adding) {
        AccountDialog(
            initial = null,
            takenNames = accounts.map { it.name.trim().lowercase() }.toSet(),
            onSave = {
                onAdd(it)
                adding = false
            },
            onDismiss = { adding = false }
        )
    }

    editing?.let { account ->
        AccountDialog(
            initial = account,
            takenNames = accounts.filter { it.id != account.id }.map { it.name.trim().lowercase() }.toSet(),
            onSave = {
                onUpdate(it)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    deleting?.let { account ->
        DeleteAccountDialog(
            account = account,
            onConfirm = {
                onDelete(account.id)
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

/**
 * One account: its initial in a circle, the name with the number of transactions under it, the
 * balance at the right end (red when it is below zero) and a three-dot menu. Tap the row to edit.
 */
@Composable
private fun AccountRow(
    account: AccountSummary,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (account.txnCount == 1) "1 transaction" else "${account.txnCount} transactions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = formatBalance(account.balancePaise),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (account.balancePaise < 0) ExpenseColor else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options for ${account.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ManuDropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                items = listOf(
                    ManuMenuItem(label = "Edit", onClick = onEdit),
                    ManuMenuItem(label = "Delete", onClick = onDelete, enabled = canDelete)
                )
            )
        }
    }
}

/**
 * Add (initial = null) or edit an account: just a name and an optional opening balance.
 * Not private: the Add and Edit transaction pages open it too, for "+ New account".
 */
@Composable
internal fun AccountDialog(
    initial: AccountSummary?,
    takenNames: Set<String>,
    onSave: (AccountEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var openingText by remember {
        mutableStateOf(initial?.openingBalancePaise?.takeIf { it != 0L }?.let { formatAmountPaise(it) } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    ManuDialog(
        visible = true,
        title = if (initial == null) "New account" else "Edit account",
        confirmText = "Save",
        onConfirm = {
            val clean = name.trim()
            val opening = if (openingText.isBlank()) 0L else parsePaise(openingText.replace(",", "").trim())
            when {
                clean.isEmpty() -> error = "Give the account a name."
                clean.lowercase() in takenNames -> error = "You already have an account with this name."
                opening == null -> error = "Opening balance must be a number."
                else -> onSave(
                    AccountEntity(
                        id = initial?.id ?: 0,
                        name = clean,
                        // The type is no longer asked for; an existing account keeps what it has
                        type = initial?.type ?: "BANK",
                        openingBalancePaise = opening
                    )
                )
            }
        },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManuTextField(
                value = name,
                onValueChange = {
                    if (it.length <= MAX_ACCOUNT_NAME) {
                        name = it
                        error = null
                    }
                },
                label = { Text("Account name") },
                placeholder = { Text("e.g. PNB savings") }
            )
            ManuTextField(
                value = openingText,
                onValueChange = {
                    openingText = it
                    error = null
                },
                label = { Text("Opening balance (₹)") },
                placeholder = { Text("0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Asks before deleting. The account's transactions are deleted with it, so the count is shown. */
@Composable
private fun DeleteAccountDialog(
    account: AccountSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val hasTransactions = account.txnCount > 0
    var typed by remember(account.id) { mutableStateOf("") }
    var error by remember(account.id) { mutableStateOf<String?>(null) }

    ManuDialog(
        visible = true,
        title = "Delete \"${account.name}\"?",
        confirmText = "Delete",
        onConfirm = {
            // Same check as the "Delete data" page (DeleteData.kt)
            if (confirmationMatches(typed, DELETE_ACCOUNT_PHRASE)) {
                onConfirm()
            } else {
                error = "Type \"$DELETE_ACCOUNT_PHRASE\" to continue."
            }
        },
        onDismiss = onDismiss,
        destructive = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasTransactions) {
                Text(
                    "This will also permanently delete its ${account.txnCount} " +
                            (if (account.txnCount == 1) "transaction" else "transactions") + " (with their images).",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text("This account has no transactions.")
            }
            Text("This can't be undone.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)

            Text("To confirm, type:", style = MaterialTheme.typography.bodyMedium)
            Text(DELETE_ACCOUNT_PHRASE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ManuTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    error = null
                },
                placeholder = { Text(DELETE_ACCOUNT_PHRASE) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
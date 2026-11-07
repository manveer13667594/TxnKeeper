package com.manu.txnkeeper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.components.ManuListItem
import com.manu.manudesignsystem.components.ManuMenuItem
import com.manu.manudesignsystem.theme.ManuDesignSystem
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuScaffold

private const val MAX_ACCOUNT_NAME = 30

/**
 * Full-screen page that lists the accounts with their balances, and lets you add, edit and delete them.
 *
 * Deleting an account also deletes all of its transactions (after a clear confirmation that says
 * how many). The last account can't be deleted.
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

    // ManuScaffold insets for the status bar, navigation bar and keyboard
    ManuScaffold(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "Accounts",
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.Default.ArrowBack,
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
                .padding(horizontal = 20.dp)
        ) {
            ManuCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md)) {
                    Text(
                        text = "Total balance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatBalance(accounts.sumOf { it.balancePaise }),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)
            ) {
                items(items = accounts, key = { it.id }) { account ->
                    AccountCard(
                        account = account,
                        canDelete = accounts.size > 1,
                        onEdit = { editing = account },
                        onDelete = { deleting = account }
                    )
                }
            }
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

@Composable
private fun AccountCard(
    account: AccountSummary,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ManuCard(contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth()) {
        ManuListItem(
            title = account.name,
            description = formatBalance(account.balancePaise),
            time = if (account.txnCount == 1) "1 transaction" else "${account.txnCount} transactions",
            leadingContent = {
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
            },
            onClick = onEdit,
            menuItems = listOf(
                ManuMenuItem(label = "Edit", onClick = onEdit),
                ManuMenuItem(
                    label = if (canDelete) "Delete" else "Delete (keep at least one)",
                    onClick = onDelete,
                    enabled = canDelete
                )
            )
        )
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
                onValueChange = { if (it.length <= MAX_ACCOUNT_NAME) name = it },
                label = { Text("Account name") },
                placeholder = { Text("e.g. PNB savings") }
            )
            ManuTextField(
                value = openingText,
                onValueChange = { openingText = it },
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

    ManuDialog(
        visible = true,
        title = "Delete \"${account.name}\"?",
        confirmText = if (hasTransactions) "Delete all" else "Delete",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasTransactions) {
                Text(
                    "This will also permanently delete its ${account.txnCount} " +
                            (if (account.txnCount == 1) "transaction" else "transactions") + "."
                )
            } else {
                Text("This account has no transactions.")
            }
            Text("This can't be undone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
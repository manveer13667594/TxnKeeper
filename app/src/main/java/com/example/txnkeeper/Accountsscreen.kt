package com.example.txnkeeper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
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
import com.example.txnkeeper.data.local.AccountEntity
import com.example.txnkeeper.data.local.AccountSummary
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuChip
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
import com.manu.manudesignsystem.components.ManuDropdownMenu
import com.manu.manudesignsystem.components.ManuPickerField
import com.manu.manudesignsystem.components.ManuScaffold

private const val MAX_ACCOUNT_NAME = 30

/**
 * Full-screen page that lists the accounts with their balances, and lets you add, edit and delete them.
 *
 * Deleting an account that has transactions asks where to move them first, so no transaction is
 * ever lost or left without an account. The last account can't be deleted.
 */
@Composable
fun AccountsScreen(
    accounts: List<AccountSummary>,
    onClose: () -> Unit,
    onAdd: (AccountEntity) -> Unit,
    onUpdate: (AccountEntity) -> Unit,
    /** [moveToId] is the account that receives the transactions, or null when there are none to move. */
    onDelete: (accountId: Long, moveToId: Long?) -> Unit,
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
                        text = formatBalance(accounts.sumOf { it.balance }),
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
            others = accounts.filter { it.id != account.id },
            onConfirm = { moveToId ->
                onDelete(account.id, moveToId)
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
            description = formatBalance(account.balance),
            time = account.type + " • " +
                    if (account.txnCount == 1) "1 transaction" else "${account.txnCount} transactions",
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

/** Add (initial = null) or edit an account: name, type and opening balance. */
@Composable
private fun AccountDialog(
    initial: AccountSummary?,
    takenNames: Set<String>,
    onSave: (AccountEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ACCOUNT_TYPES.first()) }
    var openingText by remember {
        mutableStateOf(initial?.openingBalance?.takeIf { it != 0.0 }?.let { formatAmount(it) } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    ManuDialog(
        visible = true,
        title = if (initial == null) "New account" else "Edit account",
        confirmText = "Save",
        onConfirm = {
            val clean = name.trim()
            val opening = if (openingText.isBlank()) 0.0 else openingText.replace(",", "").trim().toDoubleOrNull()
            when {
                clean.isEmpty() -> error = "Give the account a name."
                clean.lowercase() in takenNames -> error = "You already have an account with this name."
                opening == null -> error = "Opening balance must be a number."
                else -> onSave(
                    AccountEntity(
                        id = initial?.id ?: 0,
                        name = clean,
                        type = type,
                        openingBalance = opening
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ACCOUNT_TYPES.forEach { t ->
                    ManuChip(label = t, selected = type == t, onClick = { type = t })
                }
            }
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

/** Asks before deleting; if the account has transactions, also where to move them. */
@Composable
private fun DeleteAccountDialog(
    account: AccountSummary,
    others: List<AccountSummary>,
    onConfirm: (moveToId: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val hasTransactions = account.txnCount > 0
    var target by remember { mutableStateOf(others.firstOrNull()) }
    var menuOpen by remember { mutableStateOf(false) }

    ManuDialog(
        visible = true,
        title = "Delete \"${account.name}\"?",
        confirmText = if (hasTransactions) "Move and delete" else "Delete",
        onConfirm = { onConfirm(if (hasTransactions) target?.id else null) },
        onDismiss = onDismiss,
        destructive = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasTransactions) {
                Text(
                    "This account has ${account.txnCount} " +
                            (if (account.txnCount == 1) "transaction" else "transactions") +
                            ". They will not be deleted. Move them to:"
                )
                Box {
                    ManuPickerField(
                        value = target?.name ?: "",
                        trailingIcon = Icons.Default.KeyboardArrowDown,
                        onClick = { menuOpen = true }
                    )
                    ManuDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        items = others.map { other ->
                            ManuMenuItem(label = other.name, onClick = { target = other })
                        }
                    )
                }
            } else {
                Text("This account has no transactions.")
            }
            Text("This can't be undone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

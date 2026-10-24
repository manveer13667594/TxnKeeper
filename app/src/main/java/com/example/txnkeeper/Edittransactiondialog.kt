package com.example.txnkeeper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.txnkeeper.data.local.AccountSummary
import com.example.txnkeeper.data.local.TransactionEntity
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDatePicker
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuDropdownMenu
import com.manu.manudesignsystem.components.ManuMenuItem
import com.manu.manudesignsystem.components.ManuPickerField
import com.manu.manudesignsystem.components.ManuTextField

/** Edit one saved transaction. Nothing is written until "Save" is tapped. */
@Composable
fun EditTransactionDialog(
    original: TransactionEntity,
    onSave: (TransactionEntity) -> Unit,
    onDismiss: () -> Unit,
    accounts: List<AccountSummary> = emptyList()      // the account picker only shows with 2 or more
) {
    var title by remember { mutableStateOf(original.title) }
    var amountText by remember { mutableStateOf(formatAmount(original.amount)) }
    var type by remember { mutableStateOf(original.type) }
    var payment by remember { mutableStateOf(original.paymentMethod) }
    var dateTime by remember { mutableStateOf(original.dateTime) }
    var description by remember { mutableStateOf(original.description) }
    var error by remember { mutableStateOf<String?>(null) }
    var payMenuOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf(original.accountId) }
    var accountMenuOpen by remember { mutableStateOf(false) }

    // The lib's dialog: title, scrolling content, Cancel + Save buttons
    ManuDialog(
        visible = true,
        title = "Edit transaction",
        confirmText = "Save",
        onConfirm = {
            val value = amountText.toDoubleOrNull()
            if (title.isBlank() || value == null || value <= 0) {
                error = "Enter a valid title and amount."
            } else {
                onSave(
                    original.copy(
                        title = title.trim(),
                        amount = value,
                        type = type,
                        paymentMethod = payment,
                        dateTime = dateTime,
                        description = description.trim(),
                        accountId = accountId
                    )
                )
            }
        },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ManuTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Transaction title") }
            )
            ManuTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManuChip(
                    label = "Expense",
                    selected = type == "EXPENSE",
                    onClick = { type = "EXPENSE" }
                )
                ManuChip(
                    label = "Income",
                    selected = type == "INCOME",
                    onClick = { type = "INCOME" }
                )
            }
            if (accounts.size > 1) {
                Box {
                    ManuPickerField(
                        value = accounts.firstOrNull { it.id == accountId }?.name ?: "?",
                        label = { Text("Account") },
                        leadingIcon = AppIcons.Wallet,
                        trailingIcon = Icons.Default.KeyboardArrowDown,
                        onClick = { accountMenuOpen = true }
                    )
                    ManuDropdownMenu(
                        expanded = accountMenuOpen,
                        onDismissRequest = { accountMenuOpen = false },
                        items = accounts.map { account ->
                            ManuMenuItem(label = account.name, onClick = { accountId = account.id })
                        }
                    )
                }
            }
            Box {
                ManuPickerField(
                    value = payment,
                    label = { Text("Payment method") },
                    leadingIcon = AppIcons.Wallet,
                    trailingIcon = Icons.Default.KeyboardArrowDown,
                    onClick = { payMenuOpen = true }
                )
                ManuDropdownMenu(
                    expanded = payMenuOpen,
                    onDismissRequest = { payMenuOpen = false },
                    items = PAYMENT_METHODS.map { method ->
                        ManuMenuItem(label = method, onClick = { payment = method })
                    }
                )
            }
            ManuPickerField(
                value = formatDateLong(dateTime),
                label = { Text("Date") },
                leadingIcon = Icons.Default.DateRange,
                trailingIcon = Icons.Default.KeyboardArrowDown,
                onClick = { datePickerOpen = true }
            )
            ManuTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                singleLine = false
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    ManuDatePicker(
        expanded = datePickerOpen,
        onDismissRequest = { datePickerOpen = false },
        initialSelectedDateMillis = localToPickerDay(dateTime),
        onDateSelected = {
            // New day, same time of day as before
            dateTime = onDayAtTimeOf(pickerToLocalDay(it), dateTime)
        }
    )
}

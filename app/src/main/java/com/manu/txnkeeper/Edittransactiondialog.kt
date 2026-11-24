package com.manu.txnkeeper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.TransactionEntity
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDatePicker
import com.manu.manudesignsystem.components.ManuTimePicker
import java.util.Calendar
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.txnkeeper.data.local.TransactionItemEntity
import com.manu.manudesignsystem.components.ManuPickerField
import com.manu.manudesignsystem.components.ManuTextField

/** Edit one saved transaction. Nothing is written until "Save" is tapped. */
@Composable
fun EditTransactionDialog(
    original: TransactionEntity,
    /** The changed transaction and the ids of the tags it should have now. */
    onSave: (TransactionEntity, Set<Long>, List<TransactionItemEntity>) -> Unit,
    onDismiss: () -> Unit,
    /** Where the payment methods come from (and where a new one is saved). */
    settings: AppSettings,
    /** Saves a new account; calls the second function with its id once it exists. */
    onAddAccount: (AccountEntity, (Long) -> Unit) -> Unit,
    accounts: List<AccountSummary> = emptyList(),
    /** Every tag there is, and the ids this transaction has now. */
    tags: List<TagEntity> = emptyList(),
    initialTagIds: Set<Long> = emptySet(),
    /** The bill items it has now (empty for a normal transaction). */
    initialItems: List<TransactionItemEntity> = emptyList(),
    /** Saves a new tag (name, colour number); calls the last function with its id once it exists. */
    onAddTag: (String, Int, (Long) -> Unit) -> Unit = { _, _, _ -> }
) {
    var title by remember { mutableStateOf(original.title) }
    var amountText by remember { mutableStateOf(formatAmountPaise(original.amountPaise)) }
    var type by remember { mutableStateOf(original.type) }
    var payment by remember { mutableStateOf(original.paymentMethod) }
    var dateTime by remember { mutableStateOf(original.dateTime) }
    var description by remember { mutableStateOf(original.description) }
    var error by remember { mutableStateOf<String?>(null) }
    var payMenuOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf(original.accountId) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var addAccountOpen by remember { mutableStateOf(false) }
    var addPaymentOpen by remember { mutableStateOf(false) }
    var tagIds by remember { mutableStateOf(initialTagIds) }
    var items by remember { mutableStateOf(initialItems.toDrafts()) }
    var addTagOpen by remember { mutableStateOf(false) }

    // The receipt image. "Keep image" unticked removes it from the transaction when saved. A newly picked
    // image is a file made by this dialog ([added]); it is deleted again if the dialog is cancelled.
    val context = LocalContext.current
    var imageFile by remember { mutableStateOf(original.imageFile) }
    var keepImage by remember { mutableStateOf(true) }
    var added by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    // Your list from Settings. If this transaction uses a method you have since removed, it stays
    // in the dropdown too, so editing the transaction doesn't force you to change it.
    val methods = settings.paymentMethods
    val options = methods + listOfNotNull(original.paymentMethod.takeIf { it.isNotBlank() && it !in methods })

    // The lib's dialog: title, scrolling content, Cancel + Save buttons
    ManuDialog(
        visible = true,
        title = "Edit transaction",
        confirmText = "Save",
        onConfirm = {
            val billed = items.hasValidItems()
            val value = if (billed) items.validTotalPaise() else parsePaise(amountText)
            if (items.isNotEmpty() && !billed) {
                error = "Add a price to at least one item, or remove the items."
            } else if (title.isBlank() || value == null || value <= 0) {
                error = "Enter a valid title and amount."
            } else {
                val finalImage = if (keepImage) imageFile else null
                saved = true
                // A picked image that is not kept (unticked afterwards) is not needed
                added?.takeIf { it != finalImage }?.let { ReceiptStore.delete(context, it) }
                onSave(
                    original.copy(
                        title = title.trim(),
                        amountPaise = value,
                        type = type,
                        paymentMethod = payment,
                        dateTime = dateTime,
                        description = description.trim(),
                        accountId = accountId,
                        imageFile = finalImage
                    ),
                    // A tag deleted while this dialog was open is dropped here
                    tagIds.filter { id -> tags.any { it.id == id } }.toSet(),
                    if (billed) items.toEntities() else emptyList()
                )
            }
        },
        onDismiss = {
            // Cancelled: an image picked in this dialog is not needed (not after Save)
            if (!saved) added?.let { ReceiptStore.delete(context, it) }
            onDismiss()
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ManuTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Transaction title") }
            )
            if (items.isNotEmpty()) {
                ItemsEditor(items = items, onChange = { items = it })
                TypeToggle(type, { type = it }, Modifier.fillMaxWidth())
            } else {
                // The amount with Expense | Income beside it
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ManuTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    TypeToggle(type, { type = it }, Modifier.width(150.dp))
                }
                AddItemsLink(onClick = { items = listOf(ItemDraft()) })
            }
            // Account and payment method, then date and time: two per row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Account: always shown, so a new one can be added from here
                PickChip(
                    icon = AppIcons.Wallet,
                    text = accounts.firstOrNull { it.id == accountId }?.name ?: "?",
                    modifier = Modifier.weight(1f),
                    onClick = { accountMenuOpen = true }
                )
                // Payment method: your list from Settings, plus "+ Add new" at the end
                PickChip(
                    icon = Icons.Default.CreditCard,
                    text = payment.ifBlank { "Payment" },
                    muted = payment.isBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = { payMenuOpen = true }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip(
                    icon = Icons.Default.DateRange,
                    text = formatDateLong(dateTime),
                    modifier = Modifier.weight(1f),
                    onClick = { datePickerOpen = true }
                )
                PickChip(
                    icon = AppIcons.Schedule,
                    text = formatTime(dateTime),
                    modifier = Modifier.weight(1f),
                    onClick = { timePickerOpen = true }
                )
            }
            ManuTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                singleLine = false
            )
            TagPicker(
                tags = tags,
                selected = tagIds,
                onChange = { tagIds = it },
                onNew = { addTagOpen = true }
            )
            ReceiptImageField(
                imageFile = imageFile,
                include = keepImage,
                includeLabel = "Keep image",
                excludedWarning = "The image will be deleted from this transaction when you save.",
                onIncludeChange = { keepImage = it },
                onPicked = { name ->
                    // Replacing an image picked here earlier: the earlier one is not needed
                    added?.let { ReceiptStore.delete(context, it) }
                    added = name
                    imageFile = name
                    keepImage = true
                }
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // Account and payment method lists open as a card in the middle of the screen
    if (accountMenuOpen) {
        PickerListDialog(
            title = "Account",
            options = accounts.map { acc ->
                PickerOption(acc.name, selected = acc.id == accountId, onClick = { accountId = acc.id })
            } + PickerOption("+ New account", accent = true, onClick = { addAccountOpen = true }),
            onDismiss = { accountMenuOpen = false }
        )
    }
    if (payMenuOpen) {
        PickerListDialog(
            title = "Payment method",
            options = options.map { method ->
                PickerOption(method, selected = method == payment, onClick = { payment = method })
            } + PickerOption("+ Add new", accent = true, onClick = { addPaymentOpen = true }),
            onDismiss = { payMenuOpen = false }
        )
    }

    // "+ New account": name and optional opening balance; the new account is selected right away
    if (addAccountOpen) {
        AccountDialog(
            initial = null,
            takenNames = accounts.map { it.name.trim().lowercase() }.toSet(),
            onSave = { newAccount ->
                onAddAccount(newAccount) { id -> accountId = id }
                addAccountOpen = false
            },
            onDismiss = { addAccountOpen = false }
        )
    }

    // "+ Add new" payment method: saved to the list and selected right away
    if (addPaymentOpen) {
        AddPaymentMethodDialog(
            existing = methods,
            onAdd = { name ->
                settings.addPaymentMethod(name)
                payment = name
                addPaymentOpen = false
            },
            onDismiss = { addPaymentOpen = false }
        )
    }

    // "+ New tag": saved, then switched on for this transaction right away
    if (addTagOpen) {
        TagDialog(
            initial = null,
            takenNames = tags.map { it.name.lowercase() }.toSet(),
            onSave = { name, colorIndex ->
                onAddTag(name, colorIndex) { id -> tagIds = tagIds + id }
                addTagOpen = false
            },
            onDismiss = { addTagOpen = false }
        )
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

    ManuTimePicker(
        expanded = timePickerOpen,
        onDismissRequest = { timePickerOpen = false },
        initialHour = Calendar.getInstance().apply { timeInMillis = dateTime }.get(Calendar.HOUR_OF_DAY),
        initialMinute = Calendar.getInstance().apply { timeInMillis = dateTime }.get(Calendar.MINUTE),
        onTimeSelected = { hour, minute -> dateTime = withClockTime(dateTime, hour, minute) }
    )
}
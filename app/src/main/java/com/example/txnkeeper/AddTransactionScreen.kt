package com.example.txnkeeper

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.txnkeeper.data.local.AccountSummary
import com.example.txnkeeper.data.local.TransactionEntity
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuListItem
import com.manu.manudesignsystem.theme.ManuDesignSystem
import com.manu.manudesignsystem.components.ManuDropdownMenu
import com.manu.manudesignsystem.components.ManuMenuItem
import com.manu.manudesignsystem.components.ManuPickerField
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuDatePicker

/**
 * The values of the "Add transaction" form. They live outside the screen so the receipt scanner
 * can fill them in, and so a half-typed entry survives closing and re-opening the page.
 */
class TxnFormState {
    var title by mutableStateOf("")
    var amount by mutableStateOf("")
    var type by mutableStateOf("EXPENSE")
    var payment by mutableStateOf("CASH")
    var dateTime by mutableStateOf(System.currentTimeMillis())
    var description by mutableStateOf("")

    /** Chosen account. 0 = not chosen yet, so the first account is used. It is kept after saving. */
    var accountId by mutableStateOf(0L)

    /** A short note shown above the form (for example after scanning a receipt). */
    var notice by mutableStateOf<String?>(null)

    fun reset() {
        title = ""
        amount = ""
        type = "EXPENSE"
        payment = "CASH"
        dateTime = System.currentTimeMillis()
        description = ""
        notice = null
    }

    /** Only fills what the scanner detected; anything unsure keeps its current value. */
    fun fill(
        parsed: ParsedTransaction,
        message: String = "Form filled from the receipt. Check it, then save."
    ) {
        parsed.title?.let { title = it }
        parsed.amount?.let { amount = formatAmount(it) }
        parsed.type?.let { type = it }
        parsed.paymentMethod?.let { payment = it }
        // The receipt's day, with the current time of day
        parsed.dateMillis?.let { dateTime = onDayAtTimeOf(it, System.currentTimeMillis()) }
        parsed.description?.let { description = it }
        notice = message
    }
}

/** Full-screen page for adding a transaction by hand (opened by the + button or after a scan). */
@Composable
fun AddTransactionScreen(
    form: TxnFormState,
    accounts: List<AccountSummary>,
    onClose: () -> Unit,
    onSubmit: (TransactionEntity) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var error by remember { mutableStateOf<String?>(null) }
    var payMenuOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    // The account that will be used: the chosen one, or the first if none is chosen (or it was deleted)
    val account = accounts.firstOrNull { it.id == form.accountId } ?: accounts.firstOrNull()

    // ManuScaffold already insets for the status bar, navigation bar and keyboard
    ManuScaffold(
        modifier = modifier.swipeRightTo(onScan),
        contentPadding = PaddingValues(horizontal = 20.dp),
        topBar = {
            ManuTopBar(
                title = "Add Transaction",
                modifier = Modifier.padding(horizontal = 20.dp),
                navigationIcon = Icons.Default.ArrowBack,
                onNavigationClick = {
                    focusManager.clearFocus()
                    onClose()
                }
            )
        }
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            form.notice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            ManuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ScanBanner(onClick = {
                        focusManager.clearFocus()
                        onScan()
                    })

                    VoiceBanner(form)

                    ManuTextField(
                        value = form.title,
                        onValueChange = { form.title = it },
                        placeholder = { Text("Transaction title") }
                    )

                    ManuTextField(
                        value = form.amount,
                        onValueChange = { form.amount = it },
                        placeholder = { Text("Amount") },
                        leadingContent = {
                            Text("₹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
                        ManuChip(label = "Expense", selected = form.type == "EXPENSE", onClick = { form.type = "EXPENSE" })
                        ManuChip(label = "Income", selected = form.type == "INCOME", onClick = { form.type = "INCOME" })
                    }

                    // Account (only when there is a choice)
                    if (accounts.size > 1) {
                        Box {
                            ManuPickerField(
                                value = account?.name ?: "",
                                leadingIcon = AppIcons.Wallet,
                                trailingIcon = Icons.Default.KeyboardArrowDown,
                                onClick = { accountMenuOpen = true }
                            )
                            ManuDropdownMenu(
                                expanded = accountMenuOpen,
                                onDismissRequest = { accountMenuOpen = false },
                                items = accounts.map { a ->
                                    ManuMenuItem(label = a.name, onClick = { form.accountId = a.id })
                                }
                            )
                        }
                    }

                    // Payment method
                    Box {
                        ManuPickerField(
                            value = form.payment,
                            leadingIcon = AppIcons.Wallet,
                            trailingIcon = Icons.Default.KeyboardArrowDown,
                            onClick = { payMenuOpen = true }
                        )
                        ManuDropdownMenu(
                            expanded = payMenuOpen,
                            onDismissRequest = { payMenuOpen = false },
                            items = PAYMENT_METHODS.map { method ->
                                ManuMenuItem(label = method, onClick = { form.payment = method })
                            }
                        )
                    }

                    // Date
                    ManuPickerField(
                        value = formatDateLong(form.dateTime),
                        leadingIcon = Icons.Default.DateRange,
                        trailingIcon = Icons.Default.KeyboardArrowDown,
                        onClick = {
                            focusManager.clearFocus()
                            datePickerOpen = true
                        }
                    )

                    ManuTextField(
                        value = form.description,
                        onValueChange = { form.description = it },
                        placeholder = { Text("Description (optional)") },
                        singleLine = false
                    )

                    ManuButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val value = form.amount.replace(",", "").trim().toDoubleOrNull()
                            if (form.title.isBlank() || value == null || value <= 0) {
                                error = "Enter a valid title and amount."
                            } else {
                                error = null
                                focusManager.clearFocus()
                                onSubmit(
                                    TransactionEntity(
                                        title = form.title.trim(),
                                        amount = value,
                                        type = form.type,
                                        paymentMethod = form.payment,
                                        dateTime = form.dateTime,
                                        description = form.description.trim(),
                                        accountId = account?.id ?: 1L
                                    )
                                )
                            }
                        }
                    ) { Text("Save Transaction") }

                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    ManuDatePicker(
        expanded = datePickerOpen,
        onDismissRequest = { datePickerOpen = false },
        initialSelectedDateMillis = localToPickerDay(form.dateTime),
        onDateSelected = {
            // Chosen day, keeping the current time of day
            form.dateTime = onDayAtTimeOf(pickerToLocalDay(it), form.dateTime)
        }
    )
}

/** Tap to scan a receipt: opens the camera, and the detected details fill this form. */
@Composable
private fun ScanBanner(onClick: () -> Unit) {
    ManuListItem(
        title = "Scan receipt",
        description = "Fill this form from a payment screenshot or photo",
        time = "",
        leadingContent = { Icon(AppIcons.CameraAlt, contentDescription = null) },
        onClick = onClick
    )
}

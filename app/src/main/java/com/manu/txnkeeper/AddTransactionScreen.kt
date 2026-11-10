package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack as MirroredArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.TransactionEntity
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuFab
import com.manu.manudesignsystem.theme.ManuDesignSystem
import com.manu.manudesignsystem.components.ManuDropdownMenu
import com.manu.manudesignsystem.components.ManuMenuItem
import com.manu.manudesignsystem.components.ManuPickerField
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.components.ManuDatePicker
import com.manu.manudesignsystem.components.ManuTimePicker
import java.util.Calendar

/** One spoken transaction, ready to be shown in the form. */
data class BatchEntry(
    val title: String,
    val amount: String,
    val type: String,
    val payment: String,
    val dateTime: Long,
    val description: String
)

/** The same defaults as the form: anything the parser was unsure about stays at its usual value. */
private fun ParsedTransaction.toBatchEntry(): BatchEntry {
    val now = System.currentTimeMillis()
    return BatchEntry(
        title = title ?: "",
        amount = amount?.let { formatAmount(it) } ?: "",
        type = type ?: "EXPENSE",
        payment = paymentMethod ?: "CASH",
        dateTime = dateMillis?.let { onDayAtTimeOf(it, now) } ?: now,
        description = description ?: ""
    )
}

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

    /**
     * The entries of one voice recording that held several transactions. You can move back and forth
     * between them; what you changed in one is kept while you look at another. Saved ones are removed.
     */
    var batch by mutableStateOf<List<BatchEntry>>(emptyList())
    /** Which entry of [batch] is in the form now. */
    var batchIndex by mutableStateOf(0)
    /** How many entries of this recording are already saved. */
    var batchSaved by mutableStateOf(0)

    val inBatch: Boolean get() = batch.isNotEmpty()

    /** Starts reviewing several spoken entries: the first goes into the form. */
    fun startBatch(list: List<ParsedTransaction>, heard: String) {
        if (list.isEmpty()) return
        val account = accountId
        reset()
        accountId = account
        batch = list.map { it.toBatchEntry() }
        batchIndex = 0
        batchSaved = 0
        load(batch[0])
        notice = "Heard: \"$heard\". Use the arrows to move between the ${list.size} entries, " +
                "check each one, then Save & next."
    }

    /** Shows another entry of the batch (what you changed in the current one is kept). */
    fun goToEntry(target: Int) {
        if (!inBatch) return
        val t = target.coerceIn(0, batch.lastIndex)
        if (t == batchIndex) return
        batch = batch.toMutableList().also { it[batchIndex] = snapshot() }
        batchIndex = t
        load(batch[t])
    }

    /**
     * After saving the one in the form: drops it and shows the next waiting entry.
     * Returns false when none are left (or this is not a batch).
     */
    fun nextFromBatch(): Boolean {
        if (!inBatch) return false
        val rest = batch.toMutableList().also { it.removeAt(batchIndex) }
        batchSaved++
        if (rest.isEmpty()) {
            batch = emptyList()
            batchIndex = 0
            return false
        }
        batch = rest
        batchIndex = batchIndex.coerceAtMost(rest.lastIndex)
        load(rest[batchIndex])
        return true
    }

    companion object {
        /**
         * Keeps the form when the screen is rotated (the phone re-creates the screen then).
         * Use with rememberSaveable(saver = TxnFormState.Saver).
         */
        val Saver = mapSaver(
            save = { f: TxnFormState ->
                mapOf(
                    "title" to f.title,
                    "amount" to f.amount,
                    "type" to f.type,
                    "payment" to f.payment,
                    "dateTime" to f.dateTime,
                    "description" to f.description,
                    "accountId" to f.accountId,
                    "notice" to f.notice,
                    "batchIndex" to f.batchIndex,
                    "batchSaved" to f.batchSaved,
                    "batch" to f.batch.map {
                        listOf(it.title, it.amount, it.type, it.payment, it.dateTime.toString(), it.description)
                    }
                )
            },
            restore = { m ->
                TxnFormState().also { f ->
                    f.title = m["title"] as String
                    f.amount = m["amount"] as String
                    f.type = m["type"] as String
                    f.payment = m["payment"] as String
                    f.dateTime = m["dateTime"] as Long
                    f.description = m["description"] as String
                    f.accountId = m["accountId"] as Long
                    f.notice = m["notice"] as String?
                    f.batchIndex = m["batchIndex"] as Int
                    f.batchSaved = m["batchSaved"] as Int
                    @Suppress("UNCHECKED_CAST")
                    f.batch = (m["batch"] as List<List<String>>).map {
                        BatchEntry(it[0], it[1], it[2], it[3], it[4].toLong(), it[5])
                    }
                }
            }
        )
    }

    private fun snapshot() = BatchEntry(title, amount, type, payment, dateTime, description)

    private fun load(e: BatchEntry) {
        title = e.title
        amount = e.amount
        type = e.type
        payment = e.payment
        dateTime = e.dateTime
        description = e.description
    }

    fun reset() {
        batch = emptyList()
        batchIndex = 0
        batchSaved = 0
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
    /** Where the payment methods come from (and where a new one is saved). */
    settings: AppSettings,
    /** Saves a new account; calls the second function with its id once it exists. */
    onAddAccount: (AccountEntity, (Long) -> Unit) -> Unit,
    onClose: () -> Unit,
    /** [stay] = true for "Save & next": save, then show a fresh (or the next) form instead of closing. */
    onSubmit: (TransactionEntity, stay: Boolean) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    // The Next key on the keyboard goes title -> amount -> description
    val amountFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }
    var payMenuOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var addAccountOpen by remember { mutableStateOf(false) }
    var addPaymentOpen by remember { mutableStateOf(false) }
    // The account that will be used: the chosen one, or the first if none is chosen (or it was deleted).
    // The list is ordered oldest first, so the first one is the main account.
    val account = accounts.firstOrNull { it.id == form.accountId } ?: accounts.firstOrNull()

    // The payment methods you have set up in Settings
    val methods = settings.paymentMethods
    // If the chosen method is not in the list (it was removed, or a scan or recording found one
    // you removed), use the first one in the list, or none when the list is empty
    LaunchedEffect(form.payment, methods) {
        if (form.payment !in methods) form.payment = methods.firstOrNull() ?: ""
    }

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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    // Tapping empty space closes the keyboard (fields and buttons still get their own taps)
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                form.notice?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Several spoken entries: arrows to move between them
                if (form.inBatch) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        FilledIconButton(
                            onClick = {
                                focusManager.clearFocus()
                                form.goToEntry(form.batchIndex - 1)
                            },
                            enabled = form.batchIndex > 0
                        ) { Icon(Icons.AutoMirrored.Filled.MirroredArrowBack, contentDescription = "Previous entry") }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Entry ${form.batchIndex + 1} of ${form.batch.size}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (form.batchSaved > 0) {
                                Text(
                                    text = "${form.batchSaved} saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledIconButton(
                            onClick = {
                                focusManager.clearFocus()
                                form.goToEntry(form.batchIndex + 1)
                            },
                            enabled = form.batchIndex < form.batch.lastIndex
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next entry") }
                    }
                }

                ManuCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ManuTextField(
                            value = form.title,
                            onValueChange = { form.title = it },
                            modifier = Modifier.focusProperties { next = amountFocus },
                            placeholder = { Text("Transaction title") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )

                        ManuTextField(
                            value = form.amount,
                            onValueChange = { form.amount = it },
                            modifier = Modifier
                                .focusRequester(amountFocus)
                                .focusProperties { next = descriptionFocus },
                            placeholder = { Text("Amount") },
                            leadingContent = {
                                Text("₹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
                            ManuChip(label = "Expense", selected = form.type == "EXPENSE", onClick = { form.type = "EXPENSE" })
                            ManuChip(label = "Income", selected = form.type == "INCOME", onClick = { form.type = "INCOME" })
                        }
                        Column(
                            modifier = Modifier.width(220.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)

                        ) {
                            // Account: always shown, so a new one can be added from here
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
                                    } + ManuMenuItem(label = "+ New account", onClick = { addAccountOpen = true })
                                )
                            }

                            // Payment method: your list from Settings, plus "+ Add new" at the end
                            Box {
                                ManuPickerField(
                                    value = form.payment.ifBlank { "Add payment method" },
                                    leadingIcon = AppIcons.Wallet,
                                    trailingIcon = Icons.Default.KeyboardArrowDown,
                                    onClick = { payMenuOpen = true }
                                )
                                ManuDropdownMenu(
                                    expanded = payMenuOpen,
                                    onDismissRequest = { payMenuOpen = false },
                                    items = methods.map { method ->
                                        ManuMenuItem(label = method, onClick = { form.payment = method })
                                    } + ManuMenuItem(label = "+ Add new", onClick = { addPaymentOpen = true })
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

                            // Time
                            ManuPickerField(
                                value = formatTime(form.dateTime),
                                leadingIcon = AppIcons.Schedule,
                                trailingIcon = Icons.Default.KeyboardArrowDown,
                                onClick = {
                                    focusManager.clearFocus()
                                    timePickerOpen = true
                                }
                            )
                        }

                        ManuTextField(
                            value = form.description,
                            onValueChange = { form.description = it },
                            modifier = Modifier.focusRequester(descriptionFocus),
                            placeholder = { Text("Description (optional)") },
                            singleLine = false
                        )

                        // Both buttons save; "Save & next" stays here for another entry
                        fun submit(stay: Boolean) {
                            val value = parsePaise(form.amount.replace(",", "").trim())
                            if (form.title.isBlank() || value == null || value <= 0) {
                                error = "Enter a valid title and amount."
                            } else {
                                error = null
                                focusManager.clearFocus()
                                onSubmit(
                                    TransactionEntity(
                                        title = form.title.trim(),
                                        amountPaise = value,
                                        type = form.type,
                                        paymentMethod = form.payment,
                                        dateTime = form.dateTime,
                                        description = form.description.trim(),
                                        accountId = account?.id ?: 1L
                                    ),
                                    stay
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
                            ManuOutlineButton(modifier = Modifier.weight(1f), onClick = { submit(true) }) {
                                Text("Save & next", maxLines = 1)
                            }
                            ManuButton(modifier = Modifier.weight(1f), onClick = { submit(false) }) {
                                Text("Save & exit", maxLines = 1)
                            }
                        }

                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Room at the bottom so the floating buttons never cover the Save button
                Spacer(Modifier.height(150.dp))
            }

            // Floating buttons: mic (speak a transaction) and camera (scan a receipt).
            // Hidden while the keyboard is open so they don't cover what you are typing.
            val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            // ...and while scrolling down, so they never sit on top of fields or buttons
            var fabsVisible by remember { mutableStateOf(true) }
            FabAutoHide(position = { scrollState.value }, onVisible = { fabsVisible = it })
            AnimatedVisibility(
                visible = !keyboardOpen && fabsVisible,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Column(
                    modifier = Modifier.padding(end = 20.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VoiceFab(form)
                    ManuFab(onClick = {
                        focusManager.clearFocus()
                        onScan()
                    }) { Icon(AppIcons.CameraAlt, contentDescription = "Scan receipt") }
                }
            }
        }
    }

    // "+ New account": name and optional opening balance; the new account is selected right away
    if (addAccountOpen) {
        AccountDialog(
            initial = null,
            takenNames = accounts.map { it.name.trim().lowercase() }.toSet(),
            onSave = { newAccount ->
                onAddAccount(newAccount) { id -> form.accountId = id }
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
                form.payment = name
                addPaymentOpen = false
            },
            onDismiss = { addPaymentOpen = false }
        )
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

    ManuTimePicker(
        expanded = timePickerOpen,
        onDismissRequest = { timePickerOpen = false },
        initialHour = Calendar.getInstance().apply { timeInMillis = form.dateTime }.get(Calendar.HOUR_OF_DAY),
        initialMinute = Calendar.getInstance().apply { timeInMillis = form.dateTime }.get(Calendar.MINUTE),
        onTimeSelected = { hour, minute -> form.dateTime = withClockTime(form.dateTime, hour, minute) }
    )
}
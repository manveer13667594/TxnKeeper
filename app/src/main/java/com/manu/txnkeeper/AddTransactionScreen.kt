package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

    /** Entries still waiting from one voice recording that held several transactions. */
    var pending by mutableStateOf<List<ParsedTransaction>>(emptyList())
    /** How many entries that recording held (0 = not reviewing a batch). */
    var batchTotal by mutableStateOf(0)

    /** Starts reviewing several spoken entries one by one: the first goes into the form. */
    fun startBatch(list: List<ParsedTransaction>, heard: String) {
        if (list.isEmpty()) return
        batchTotal = list.size
        showEntry(list.first(), 1, list.size, heard)
        pending = list.drop(1)
    }

    /** After saving one: fills the next waiting entry. Returns false when none are left. */
    fun nextFromBatch(): Boolean {
        val next = pending.firstOrNull() ?: return false
        val rest = pending.drop(1)
        val number = batchTotal - pending.size + 1
        showEntry(next, number, batchTotal, null)   // this clears the waiting list...
        pending = rest                              // ...so put the rest back
        return true
    }

    private fun showEntry(p: ParsedTransaction, number: Int, total: Int, heard: String?) {
        val account = accountId
        reset()
        accountId = account
        batchTotal = total
        fill(p, message = "Entry $number of $total" + (heard?.let { " (heard: \"$it\")" } ?: "") +
                ". Check it, then Save & next.")
    }

    fun reset() {
        pending = emptyList()
        batchTotal = 0
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
    /** [stay] = true for "Save & next": save, then show a fresh (or the next) form instead of closing. */
    onSubmit: (TransactionEntity, stay: Boolean) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    var error by remember { mutableStateOf<String?>(null) }
    var payMenuOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
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
                  Column(
                      modifier = Modifier.width(220.dp),
                      verticalArrangement = Arrangement.spacedBy(16.dp)

                  ) {
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
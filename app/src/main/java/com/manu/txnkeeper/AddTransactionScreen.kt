package com.manu.txnkeeper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.automirrored.filled.ArrowBack as MirroredArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.TagEntity
import com.manu.txnkeeper.data.local.TransactionEntity
import com.manu.txnkeeper.data.local.TransactionItemEntity
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuTopBar
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuFab
import com.manu.manudesignsystem.theme.ManuDesignSystem
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
    val description: String,
    /** Marked = will be saved. Unmark an entry you do not want to keep. */
    val include: Boolean = true,
    /** The tags of this entry (suggested from its title, changeable). */
    val tagIds: Set<Long> = emptySet()
)

/** The same defaults as the form: anything the parser was unsure about stays at its usual value. */
private fun ParsedTransaction.toBatchEntry(defaultType: String, defaultPayment: String): BatchEntry {
    val now = System.currentTimeMillis()
    return BatchEntry(
        title = title ?: "",
        amount = amount?.let { formatAmount(it) } ?: "",
        type = type ?: defaultType,
        payment = paymentMethod ?: defaultPayment,
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
    var type by mutableStateOf(TYPE_EXPENSE)

    /** The lines of a bill (not used for the entries of a voice recording). When there are any, their total is the amount. */
    var items by mutableStateOf<List<ItemDraft>>(emptyList())
    var payment by mutableStateOf("CASH")
    var dateTime by mutableStateOf(System.currentTimeMillis())
    var description by mutableStateOf("")

    /** The tags picked for this transaction (ids of TagEntity). Not used for the entries of a voice recording. */
    var tagIds by mutableStateOf<Set<Long>>(emptySet())

    /**
     * What a blank form starts with (from Settings > Defaults; the app keeps these up to date).
     * Not saved with the form: they are only used by [reset] and [useDefaults].
     */
    var defaultType: String = TYPE_EXPENSE
    var defaultPayment: String = "CASH"

    /** True when nothing has been typed, scanned or spoken yet. */
    val isBlank: Boolean
        get() = title.isBlank() && amount.isBlank() && description.isBlank() && items.isEmpty() && notice == null && !inBatch

    /** Sets the type and payment method to the defaults (the account is chosen by the app). */
    fun useDefaults() {
        type = defaultType
        payment = defaultPayment
    }

    /** For an entry of a batch: is it marked to be saved? (Always true outside a batch.) */
    var include by mutableStateOf(true)

    /** Chosen account. 0 = not chosen yet, so the first account is used. It is kept after saving. */
    var accountId by mutableStateOf(0L)

    /** A short note shown above the form (for example after scanning a receipt). */
    var notice by mutableStateOf<String?>(null)

    /** Other possible titles for a photographed product (tap one to use it). Not saved with the form. */
    var titleSuggestions by mutableStateOf<List<String>>(emptyList())

    /**
     * The receipt image waiting in this form (a file in ReceiptStore): the photo that was scanned, or
     * one picked with "Attach image". It becomes part of the transaction only if [includeImage] is
     * ticked when saving; otherwise (or if the form is closed) the file is deleted.
     */
    var imageFile by mutableStateOf<String?>(null)
    /** The "Include image" box. */
    var includeImage by mutableStateOf(true)

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

    /** True right after a recording was split into entries: the screen then suggests tags for each of them. */
    var batchTagsPending by mutableStateOf(false)

    /** Gives every entry the tags suggested for it (an entry whose tags were already picked keeps them). */
    fun applyBatchTags(suggested: List<Set<Long>>) {
        if (!inBatch) return
        batch = batch.mapIndexed { i, e ->
            if (i == batchIndex) e else if (e.tagIds.isEmpty()) e.copy(tagIds = suggested.getOrElse(i) { emptySet() }) else e
        }
        if (tagIds.isEmpty()) tagIds = suggested.getOrElse(batchIndex) { emptySet() }
    }

    /** Starts reviewing several spoken entries: the first goes into the form. */
    fun startBatch(list: List<ParsedTransaction>, heard: String) {
        if (list.isEmpty()) return
        val account = accountId
        reset()
        accountId = account
        batch = list.map { it.toBatchEntry(defaultType, defaultPayment) }
        batchIndex = 0
        batchSaved = 0
        load(batch[0])
        batchTagsPending = true
        notice = "Heard: \"$heard\". Use the arrows to move between the ${list.size} entries, " +
                "check each one, untick any you don't want, then Save & exit."
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
     * Keeps what is in the form as the current entry of the batch, then returns every entry that is
     * still marked, in order. Nothing is removed. Not a batch: empty list.
     */
    fun markedEntries(): List<BatchEntry> {
        if (!inBatch) return emptyList()
        batch = batch.toMutableList().also { it[batchIndex] = snapshot() }
        return batch.filter { it.include }
    }

    /** Shows the first marked entry that is not valid, so it can be fixed. Returns its number (1-based) or null. */
    fun firstInvalidMarked(isValid: (BatchEntry) -> Boolean): Int? {
        markedEntries()
        val idx = batch.indexOfFirst { it.include && !isValid(it) }
        if (idx < 0) return null
        goToEntry(idx)
        return idx + 1
    }

    /** Drops the unmarked entry on screen without saving it and shows the next one. False when none are left. */
    fun skipCurrentInBatch(): Boolean {
        if (!inBatch) return false
        val rest = batch.toMutableList().also { it.removeAt(batchIndex) }
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
                    "imageFile" to f.imageFile,
                    "includeImage" to f.includeImage,
                    "tagIds" to ArrayList(f.tagIds),
                    "items" to f.items.map { listOf(it.name, it.qty, it.price) },
                    "batchIndex" to f.batchIndex,
                    "batchSaved" to f.batchSaved,
                    "batch" to f.batch.map {
                        listOf(
                            it.title, it.amount, it.type, it.payment, it.dateTime.toString(), it.description,
                            it.include.toString(), it.tagIds.joinToString(",")
                        )
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
                    f.imageFile = m["imageFile"] as String?
                    f.includeImage = (m["includeImage"] as Boolean?) ?: true
                    @Suppress("UNCHECKED_CAST")
                    f.tagIds = ((m["tagIds"] as List<Long>?) ?: emptyList()).toSet()
                    @Suppress("UNCHECKED_CAST")
                    f.items = ((m["items"] as List<List<String>>?) ?: emptyList()).map { ItemDraft(name = it[0], qty = it[1], price = it[2]) }
                    f.batchIndex = m["batchIndex"] as Int
                    f.batchSaved = m["batchSaved"] as Int
                    @Suppress("UNCHECKED_CAST")
                    f.batch = (m["batch"] as List<List<String>>).map {
                        BatchEntry(
                            it[0], it[1], it[2], it[3], it[4].toLong(), it[5], it.getOrNull(6) != "false",
                            it.getOrNull(7)?.split(',')?.mapNotNull { id -> id.toLongOrNull() }?.toSet() ?: emptySet()
                        )
                    }
                    // The entry on screen carries its own mark
                    f.include = f.batch.getOrNull(f.batchIndex)?.include ?: true
                }
            }
        )
    }

    private fun snapshot() = BatchEntry(title, amount, type, payment, dateTime, description, include, tagIds)

    private fun load(e: BatchEntry) {
        title = e.title
        amount = e.amount
        type = e.type
        payment = e.payment
        dateTime = e.dateTime
        description = e.description
        include = e.include
        tagIds = e.tagIds
    }

    fun reset() {
        batch = emptyList()
        batchIndex = 0
        batchSaved = 0
        title = ""
        amount = ""
        dateTime = System.currentTimeMillis()
        description = ""
        include = true
        notice = null
        titleSuggestions = emptyList()
        useDefaults()   // type and payment method come from Settings > Defaults
        // The image file is not deleted here: after saving it belongs to the transaction.
        // Closing the page without saving deletes it (MainActivity does that before calling reset).
        imageFile = null
        includeImage = true
        tagIds = emptySet()
        items = emptyList()
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
    /** Every tag there is, for the tag chips. */
    tags: List<TagEntity>,
    /** Saves a new tag (name, colour number); calls the last function with its id once it exists. */
    onAddTag: (String, Int, (Long) -> Unit) -> Unit,
    /** Which tags fit this title best (from your past transactions and keywords); empty when unsure. */
    suggestTags: suspend (title: String) -> Set<Long> = { emptySet() },
    onClose: () -> Unit,
    /** [stay] = true for "Save & next": save, then show a fresh (or the next) form instead of closing. */
    onSubmit: (TransactionEntity, tagIds: Set<Long>, stay: Boolean, items: List<TransactionItemEntity>) -> Unit,
    /** Save & exit on a recording with several entries: saves every marked one at once, then closes. */
    /** Saves the marked entries of a recording, with the tags of each (same order). */
    onSubmitAll: (List<TransactionEntity>, List<Set<Long>>) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()
    // Focus the title field and open the keyboard when this screen first appears.
    val titleFocus = remember { FocusRequester() }
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
    var addTagOpen by remember { mutableStateOf(false) }
    // The account that will be used: the chosen one, or the first if none is chosen (or it was deleted).
    // The list is ordered oldest first, so the first one is the main account.
    val account = accounts.firstOrNull { it.id == form.accountId } ?: accounts.firstOrNull()

    // Focus the title field and show the keyboard when the screen opens for a blank entry.
    // Not when it opens already filled from speech or a receipt scan (the note above the form is
    // set then): those only need a look, and you tap a field if something needs fixing.
    LaunchedEffect(Unit) {
        if (form.notice == null && !form.inBatch) {
            titleFocus.requestFocus()
            keyboardController?.show()
        }
    }

    // Tags switched on by the app from the title (typed, scanned or spoken). Once you change the tags
    // yourself, the app stops touching them for this entry.
    var autoTags by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(form.title, tags, form.inBatch) {
        if (form.inBatch) return@LaunchedEffect   // a recording's entries get theirs in the effect below
        val title = form.title.trim()
        if (title.isBlank()) {
            // Blank form: drop what the app picked earlier, if you did not change it
            if (form.tagIds == autoTags) form.tagIds = emptySet()
            autoTags = emptySet()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)   // wait until typing pauses
        if (form.tagIds != autoTags) return@LaunchedEffect   // you picked tags yourself
        val suggestion = suggestTags(title)
        form.tagIds = suggestion
        autoTags = suggestion
    }

    // With bill items, the amount is their total
    LaunchedEffect(form.items) {
        if (form.items.hasValidItems()) form.amount = formatAmountPaise(form.items.validTotalPaise())
    }

    // A recording with several entries: suggest tags for each entry once, then you can change them per entry
    LaunchedEffect(form.batchTagsPending, tags) {
        if (!form.batchTagsPending) return@LaunchedEffect
        val suggested = form.batch.map { suggestTags(it.title.trim()) }
        form.applyBatchTags(suggested)
        form.batchTagsPending = false
    }

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
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
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
                // Debug builds only: what the scan or voice found and where it went (nothing in release)
                DebugInsightsCard()
                if (BuildConfig.DEBUG) GoogleLiveCard()
                VoiceAlternativeBanner(form)

                form.notice?.let { notice ->
                    // The "Possible duplicate" paragraph is a warning, so it is red; the rest stays grey
                    notice.split("\n\n").forEach { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (paragraph.startsWith("Possible duplicate")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
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
                            if (!form.include) {
                                Text(
                                    text = "Won't be saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (form.batchSaved > 0) {
                                Text(
                                    text = "${form.batchSaved} saved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Marked by default; untick to leave this entry out when saving
                            Checkbox(
                                checked = form.include,
                                onCheckedChange = { form.include = it }
                            )
                            FilledIconButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    form.goToEntry(form.batchIndex + 1)
                                },
                                enabled = form.batchIndex < form.batch.lastIndex
                            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next entry") }
                        }
                    }
                }

                ManuCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ManuTextField(
                            value = form.title,
                            onValueChange = { form.title = it },
                            modifier = Modifier
                                .focusRequester(titleFocus)
                                .focusProperties { next = if (form.items.isNotEmpty() && !form.inBatch) descriptionFocus else amountFocus },
                            placeholder = { Text("Transaction title") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )

                        // Other guesses for what the photographed product is: tap one to use it
                        val titleChips = form.titleSuggestions.filter { !it.equals(form.title.trim(), ignoreCase = true) }
                        if (titleChips.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Is it:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                titleChips.forEach { suggestion ->
                                    ManuChip(label = suggestion, selected = false, onClick = { form.title = suggestion })
                                }
                            }
                        }

                        if (!form.inBatch && form.items.isNotEmpty()) {
                            // A bill: the lines decide the amount
                            ItemsEditor(items = form.items, onChange = { form.items = it })
                            TypeToggle(form.type, { form.type = it }, Modifier.fillMaxWidth())
                        } else {
                            // The amount with Expense | Income beside it
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ManuTextField(
                                    value = form.amount,
                                    onValueChange = { form.amount = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(amountFocus)
                                        .focusProperties { next = descriptionFocus },
                                    placeholder = { Text("Amount") },
                                    leadingContent = {
                                        Text("₹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                                )
                                TypeToggle(form.type, { form.type = it }, Modifier.width(156.dp))
                            }
                            if (!form.inBatch) {
                                AddItemsLink(onClick = { form.items = listOf(ItemDraft()) })
                            }
                        }

                        // Tags: one row that scrolls sideways, tap to switch on or off.
                        // For a recording with several entries, each entry has its own tags.
                        TagPicker(
                            tags = tags,
                            selected = form.tagIds,
                            onChange = { form.tagIds = it },
                            onNew = {
                                focusManager.clearFocus()
                                addTagOpen = true
                            }
                        )
                        // Account and payment method, then date and time: two per row
                        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
                            // Account: always shown, so a new one can be added from here
                            PickChip(
                                icon = AppIcons.Wallet,
                                text = account?.name ?: "",
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    focusManager.clearFocus()
                                    accountMenuOpen = true
                                }
                            )
                            // Payment method: your list from Settings, plus "+ Add new" at the end
                            PickChip(
                                icon = Icons.Default.CreditCard,
                                text = form.payment.ifBlank { "Payment" },
                                muted = form.payment.isBlank(),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    focusManager.clearFocus()
                                    payMenuOpen = true
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
                            PickChip(
                                icon = Icons.Default.DateRange,
                                text = formatDateLong(form.dateTime),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    focusManager.clearFocus()
                                    datePickerOpen = true
                                }
                            )
                            PickChip(
                                icon = AppIcons.Schedule,
                                text = formatTime(form.dateTime),
                                modifier = Modifier.weight(1f),
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

                        // Receipt image: from a scan, or attached here. Not for the entries of a voice recording.
                        if (!form.inBatch) {
                            ReceiptImageField(
                                imageFile = form.imageFile,
                                include = form.includeImage,
                                onIncludeChange = { form.includeImage = it },
                                excludedWarning = "This image won't be saved. It is deleted when you save.",
                                onPicked = { name ->
                                    val old = form.imageFile
                                    form.imageFile = name
                                    form.includeImage = true
                                    if (old != null) ReceiptStore.delete(context, old)
                                }
                            )
                        }

                        fun toEntity(e: BatchEntry, value: Long) = TransactionEntity(
                            title = e.title.trim(),
                            amountPaise = value,
                            type = e.type,
                            paymentMethod = e.payment,
                            dateTime = e.dateTime,
                            description = e.description.trim(),
                            accountId = account?.id ?: 1L
                        )
                        fun amountOf(e: BatchEntry) = parsePaise(e.amount.replace(",", "").trim())

                        // "Save & next" saves the one on screen and moves on (one by one);
                        // "Save & exit" saves it, or in a recording with several entries, every marked one.
                        fun submit(stay: Boolean) {
                            // Unmarked entry in a batch: nothing to save, "Save & next" just skips it
                            if (form.inBatch && !form.include && stay) {
                                error = null
                                focusManager.clearFocus()
                                if (!form.skipCurrentInBatch()) form.reset()
                                return
                            }
                            if (form.inBatch && !stay) {
                                val badNumber = form.firstInvalidMarked { e ->
                                    val v = amountOf(e)
                                    e.title.isNotBlank() && v != null && v > 0
                                }
                                if (badNumber != null) {
                                    error = "Entry $badNumber: enter a valid title and amount."
                                    return
                                }
                                error = null
                                focusManager.clearFocus()
                                val marked = form.markedEntries()
                                // A tag deleted while this page was open is dropped here
                                val liveIds = tags.map { it.id }.toSet()
                                onSubmitAll(
                                    marked.map { toEntity(it, amountOf(it)!!) },
                                    marked.map { e -> e.tagIds.filter { it in liveIds }.toSet() }
                                )
                                return
                            }
                            if (!form.inBatch && form.items.isNotEmpty() && !form.items.hasValidItems()) {
                                error = "Add a price to at least one item, or remove the items."
                                return
                            }
                            // With bill items the total of the lines is the amount
                            val billed = !form.inBatch && form.items.hasValidItems()
                            val value = if (billed) form.items.validTotalPaise()
                                else parsePaise(form.amount.replace(",", "").trim())
                            if (form.title.isBlank() || value == null || value <= 0) {
                                error = "Enter a valid title and amount."
                            } else {
                                error = null
                                focusManager.clearFocus()
                                // A tag deleted while this page was open is dropped here
                                val liveTagIds = tags.map { it.id }.toSet()
                                onSubmit(
                                    TransactionEntity(
                                        title = form.title.trim(),
                                        amountPaise = value,
                                        type = form.type,
                                        paymentMethod = form.payment,
                                        dateTime = form.dateTime,
                                        description = form.description.trim(),
                                        accountId = account?.id ?: 1L,
                                        imageFile = if (form.includeImage) form.imageFile else null
                                    ),
                                    form.tagIds.filter { it in liveTagIds }.toSet(),
                                    stay,
                                    if (billed) form.items.toEntities() else emptyList()
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(ManuDesignSystem.spacing.sm)) {
                            ManuOutlineButton(modifier = Modifier.weight(1f), onClick = { submit(true) }) {
                                Text(if (form.inBatch && !form.include) "Skip & next" else "Save & next", maxLines = 1)
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
            // They stay visible (just above the keyboard) when the page opens, and hide only while
            // scrolling down, so they never sit on top of fields or buttons
            var fabsVisible by remember { mutableStateOf(true) }
            FabAutoHide(position = { scrollState.value }, onVisible = { fabsVisible = it })
            AnimatedVisibility(
                visible = fabsVisible,
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

    // Account and payment method lists open as a card in the middle of the screen
    if (accountMenuOpen) {
        PickerListDialog(
            title = "Account",
            options = accounts.map { acc ->
                PickerOption(acc.name, selected = acc.id == account?.id, onClick = { form.accountId = acc.id })
            } + PickerOption("+ New account", accent = true, onClick = { addAccountOpen = true }),
            onDismiss = { accountMenuOpen = false }
        )
    }
    if (payMenuOpen) {
        PickerListDialog(
            title = "Payment method",
            options = methods.map { method ->
                PickerOption(method, selected = method == form.payment, onClick = { form.payment = method })
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

    // "+ New tag": saved, then switched on for this transaction right away
    if (addTagOpen) {
        TagDialog(
            initial = null,
            takenNames = tags.map { it.name.lowercase() }.toSet(),
            onSave = { name, colorIndex ->
                onAddTag(name, colorIndex) { id -> form.tagIds = form.tagIds + id }
                addTagOpen = false
            },
            onDismiss = { addTagOpen = false }
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
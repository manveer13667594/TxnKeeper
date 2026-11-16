package com.manu.txnkeeper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.room.withTransaction
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.DEFAULT_ACCOUNT_NAME
import com.manu.txnkeeper.data.local.DatabaseModule
import com.manu.manudesignsystem.theme.ManuDesignSystemTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.manu.manudesignsystem.components.ManuFab
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import com.manu.manudesignsystem.components.ManuScaffold
import androidx.compose.ui.unit.dp
import com.manu.manudesignsystem.components.ManuSnackbar
import com.manu.manudesignsystem.components.ManuSnackbarType

// Scrims behind the navigation bar (same values the Android templates use)
private val LightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    // Image shared into the app from the gallery / a payment app
    private var sharedUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        val settings = AppSettings(this)
        setContent {
            // Light / dark comes from Settings; "System default" follows the phone
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Keep the status bar and navigation bar icons readable in the chosen theme
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { darkTheme }
                )
                onDispose {}
            }

            ManuDesignSystemTheme(darkTheme = darkTheme) {
                TxnkeeperApp(
                    settings = settings,
                    sharedImageUri = sharedUri,
                    onSharedImageHandled = { sharedUri = null }
                )
            }
        }
    }

    // Called when the app is already open and another image is shared to it
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            sharedUri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
    }
}

/**
 * The app has one home screen (the transactions list). On top of it, when needed:
 *   - the "Add transaction" page (the + button, or after scanning a receipt)
 *   - the camera (the camera icon, or swipe from left to right)
 *   - voice entry (swipe from right to left): its own page with the mic button, then the Add page opens filled in
 *   - the Settings page (from the three-dot menu), and the Accounts page (from Settings)
 *   - the Help and About pages (from the three-dot menu)
 */
@PreviewScreenSizes
@Composable
fun TxnkeeperApp(
    settings: AppSettings = rememberAppSettings(),
    sharedImageUri: Uri? = null,
    onSharedImageHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val database = remember { DatabaseModule.provideDatabase(context) }
    val dao = remember { database.transactionDao() }
    val scope = rememberCoroutineScope()

    // Accounts with their balances (updates by itself when anything changes)
    val accounts by remember { dao.observeAccounts() }.collectAsState(initial = emptyList<AccountSummary>())
    // Which account the list shows: null = all accounts
    var accountFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // The stats chart: what it plots (null = closed), kept after closing so it can slide out
    // Saved, so rotating the phone keeps the Stats page open
    var statsRequest by rememberSaveable(stateSaver = StatsRequestSaver) {
        mutableStateOf(StatsRequest(TxnFilters(), "", null))
    }
    var showStats by rememberSaveable { mutableStateOf(false) }

    // Safety net: there is always at least one account (the database adds it, this covers odd cases)
    LaunchedEffect(Unit) {
        safeWrite(context, null) {
            if (dao.accountCount() == 0) dao.insertAccount(AccountEntity(name = DEFAULT_ACCOUNT_NAME))
        }
    }
    // Housekeeping: delete image files that no transaction uses. Only files a day old or more, so an
    // image in a form that is still open is never touched.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                ReceiptStore.deleteUnused(context, dao.allImageFiles().toSet(), minAgeMs = 24L * 60 * 60 * 1000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing depends on this, try again next time the app opens
            }
        }
    }
    // The chosen account was deleted: go back to "all accounts"
    LaunchedEffect(accounts) {
        if (accountFilter != null && accounts.isNotEmpty() && accounts.none { it.id == accountFilter }) {
            accountFilter = null
        }
    }
    val focusManager = LocalFocusManager.current
    // Message shown by the design system's snackbar (null = hidden)
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    // The form and the open Add page survive a screen rotation, nothing else
    val form = rememberSaveable(saver = TxnFormState.Saver) { TxnFormState() }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    // Settings > Defaults: what a blank form starts with. Read here (not inside the effect) so this
    // recomposes when a default changes, and the form is told right after.
    val defaultType = settings.defaultType
    val defaultPayment = settings.defaultPayment
    SideEffect {
        form.defaultType = defaultType
        form.defaultPayment = defaultPayment
    }

    // The account a new entry starts in: the one you are looking at, else your default account
    // (if it still exists), else the one used last.
    fun startingAccountId(): Long =
        accountFilter ?: settings.validDefaultAccountId(accounts.map { it.id }) ?: form.accountId

    var fabVisible by remember { mutableStateOf(true) }
    var showCamera by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    // Text recognition runs here, after the camera has closed
    val ocr = rememberOcrController()

    // A copy of the scanned / picked photo is saved (shrunk) while its text is being read. It goes into the
    // form when text was found, and is deleted when not.
    var scanImage by remember { mutableStateOf<Deferred<String?>?>(null) }

    fun startScan(uri: Uri) {
        val previous = scanImage
        scanImage = scope.async { ReceiptStore.saveFromUri(context, uri) }
        if (previous != null) scope.launch { ReceiptStore.delete(context, previous.await()) }
        ocr.run(uri)
    }

    /** Nothing usable was found in the photo: its saved copy is not needed. */
    fun dropScanImage() {
        val pending = scanImage ?: return
        scanImage = null
        scope.launch { ReceiptStore.delete(context, pending.await()) }
    }

    // An image shared into the app: make sure the camera is closed, then read it
    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let {
            showCamera = false
            startScan(it)
            onSharedImageHandled()
        }
    }

    // A finished scan fills the Add page right away; what was detected is shown above the fields
    // This listens with snapshotFlow instead of keying the effect on ocr.outcome: clearing the outcome
    // below used to restart the effect and cancel it in the middle of the duplicate check, so
    // sometimes nothing was shown at all (no form, no message).
    LaunchedEffect(Unit) {
        snapshotFlow { ocr.outcome }.collect { outcome ->
            if (outcome == null) return@collect
            ocr.outcome = null
            when {
                outcome.text.isBlank() -> {
                    dropScanImage()
                    Toast.makeText(context, "No text found in this image.", Toast.LENGTH_SHORT).show()
                }

                outcome.parsed.isEmpty -> {
                    dropScanImage()
                    Toast.makeText(context, "Text detected, but no transaction details found.", Toast.LENGTH_LONG).show()
                }

                else -> {
                    var message = "Data detected: ${ocrDetectedLine(outcome.parsed)}. Check the form, then save."
                    // Warn before saving if the same reference number is already in the list
                    val ref = outcome.parsed.description?.removePrefix("Ref: ")?.trim()
                    if (!ref.isNullOrEmpty()) {
                        dao.findByReference(ref)?.let { d ->
                            message += "\n\nPossible duplicate: you already saved \"${d.title}\" " +
                                    "(${formatMoney(d.amountPaise)}, ${formatDateLong(d.dateTime)}) " +
                                    "with the same reference number."
                        }
                    }
                    // A blank form starts from your defaults; the scan then fills what it found
                    if (form.isBlank) {
                        form.useDefaults()
                        form.accountId = startingAccountId()
                    }
                    // The photo goes along with the form; "Include image" starts as set in Settings
                    val pending = scanImage
                    scanImage = null
                    val savedImage = pending?.await()
                    if (savedImage != null) {
                        val old = form.imageFile
                        form.imageFile = savedImage
                        form.includeImage = settings.includeImageByDefault
                        if (old != null) ReceiptStore.delete(context, old)
                    }
                    form.fill(outcome.parsed, message = message)
                    showAdd = true
                }
            }
        }
    }

    // Last photo taken in the app; use this later to attach a photo to a transaction
    var lastPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    fun openCamera() {
        settings.markCameraHintDone()   // they found it: no need to keep explaining it
        focusManager.clearFocus()
        showCamera = true
    }

    fun openVoice() {
        settings.markVoiceHintDone()
        focusManager.clearFocus()
        showVoice = true
    }

    // Leaving the Add page (back arrow, system back, swipe) clears what was typed, so the next
    // "Add transaction" starts empty. The short wait lets the page finish sliding away first.
    LaunchedEffect(showAdd) {
        if (!showAdd) {
            delay(500)
            // Closed without saving: the image waiting in the form is not needed
            form.imageFile?.let { ReceiptStore.delete(context, it) }
            val account = form.accountId
            form.reset()
            form.accountId = account
        }
    }

    // Back from the Add page or the Accounts page returns to the list (the camera has its own back handling)
    BackHandler(enabled = showAdd) {
        focusManager.clearFocus()
        showAdd = false
    }
    BackHandler(enabled = showStats) { showStats = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showHelp) { showHelp = false }
    BackHandler(enabled = showAbout) { showAbout = false }
    // Declared after Settings, so Back closes Accounts first when it was opened from Settings
    BackHandler(enabled = showAccounts) { showAccounts = false }

    AppBackground {
        Box(modifier = Modifier.fillMaxSize()) {

            // ---------------- Home: the transactions list, with the Add button at the bottom.
            // ManuScaffold already insets for the status bar, navigation bar and keyboard.
            ManuScaffold(
                snackbarHost = {
                    ManuSnackbar(
                        message = snackbarMessage ?: "",
                        visible = snackbarMessage != null,
                        onDismiss = { snackbarMessage = null },
                        type = ManuSnackbarType.Success,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            ) { _ ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Left to right opens the camera, right to left opens voice entry
                        .swipeHorizontally(onLeft = { openVoice() }, onRight = { openCamera() })
                ) {
                    TransactionsScreen(
                        dao = dao,
                        settings = settings,
                        accounts = accounts,
                        accountId = accountFilter,
                        onAccountChange = { accountFilter = it },
                        onOpenSettings = { showSettings = true },
                        onOpenHelp = { showHelp = true },
                        onOpenAbout = { showAbout = true },
                        onScan = { openCamera() },
                        onOpenStats = { filters, search, account ->
                            statsRequest = StatsRequest(filters, search, account)
                            showStats = true
                        },
                        onScrollDirection = { fabVisible = it }
                    )

                    // Floating + button: opens the Add transaction page. It slips away while you
                    // scroll down the list, so it never covers a row, and returns on scroll up.
                    AnimatedVisibility(
                        visible = fabVisible,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 16.dp)
                    ) {
                        ManuFab(
                            onClick = {
                                // A blank form starts from your defaults (Settings > Defaults)
                                if (form.isBlank) form.useDefaults()
                                form.accountId = startingAccountId()
                                showAdd = true
                            }
                        ) { Icon(Icons.Default.Add, contentDescription = "Add transaction") }
                    }
                }
            }

            // ---------------- Every full-screen page below opens and closes the same way (ScreenTransitions.kt)
            // ---------------- Add transaction page slides in over the list
            ScreenTransition(visible = showAdd) {
                AddTransactionScreen(
                    form = form,
                    accounts = accounts,
                    settings = settings,
                    // "+ New account" on the Add page: save it, then the page selects it
                    onAddAccount = { newAccount, onCreated ->
                        scope.launch {
                            var newId = 0L
                            if (safeWrite(context, "add the account") { newId = dao.insertAccount(newAccount) }) {
                                onCreated(newId)
                            }
                        }
                    },
                    onClose = { showAdd = false },
                    onScan = { openCamera() },
                    // Save & exit on a recording with several entries: all marked ones, in one go
                    onSubmitAll = { entities ->
                        scope.launch {
                            if (entities.isNotEmpty() &&
                                !safeWrite(context, "save the transactions") { dao.insertAll(entities) }
                            ) return@launch
                            val account = form.accountId
                            form.reset()
                            form.accountId = account
                            showAdd = false
                            snackbarMessage = when (entities.size) {
                                0 -> "Nothing saved"
                                1 -> "Transaction saved"
                                else -> "${entities.size} transactions saved"
                            }
                        }
                    },
                    onSubmit = { entity, stay ->
                        scope.launch {
                            // An image in the form that is not kept ("Include image" unticked) is deleted after saving
                            val unusedImage = form.imageFile?.takeIf { it != entity.imageFile }
                            // If saving fails the form stays open with everything the user typed
                            if (!safeWrite(context, "save the transaction") { dao.insert(entity) }) return@launch
                            if (unusedImage != null) ReceiptStore.delete(context, unusedImage)
                            if (stay) {
                                // Save & next: the next spoken entry, or an empty form
                                if (!form.nextFromBatch()) {
                                    val account = form.accountId
                                    form.reset()
                                    form.accountId = account
                                }
                            } else {
                                form.reset()
                                showAdd = false
                            }
                            snackbarMessage = "Transaction saved"
                        }
                    }
                )
            }

            // ---------------- Stats chart (opened by tapping the balance card)
            ScreenTransition(visible = showStats) {
                StatsScreen(
                    dao = dao,
                    request = statsRequest,
                    settings = settings,
                    accounts = accounts,
                    onClose = { showStats = false }
                )
            }

            // ---------------- Settings page
            ScreenTransition(visible = showSettings) {
                SettingsScreen(
                    settings = settings,
                    database = database,
                    onManageAccounts = { showAccounts = true },
                    // Help and About slide in over Settings, and Back returns to it
                    onOpenHelp = { showHelp = true },
                    onOpenAbout = { showAbout = true },
                    onClose = { showSettings = false }
                )
            }

            // ---------------- Accounts page (over Settings, which opens it)
            ScreenTransition(visible = showAccounts) {
                AccountsScreen(
                    accounts = accounts,
                    onClose = { showAccounts = false },
                    onAdd = { scope.launch { safeWrite(context, "add the account") { dao.insertAccount(it) } } },
                    onUpdate = { scope.launch { safeWrite(context, "save the account") { dao.updateAccount(it) } } },
                    onDelete = { id ->
                        scope.launch {
                            // Delete the transactions, then the account, as one step: either both happen or neither
                            var images = emptyList<String>()
                            val deleted = safeWrite(context, "delete the account") {
                                database.withTransaction {
                                    images = dao.imageFilesOfAccount(id)
                                    dao.deleteTransactionsOfAccount(id)
                                    dao.deleteAccount(id)
                                }
                            }
                            // Their images go too (after the database change worked)
                            if (deleted && images.isNotEmpty()) {
                                withContext(Dispatchers.IO) { ReceiptStore.deleteAll(context, images) }
                            }
                        }
                    }
                )
            }

            // ---------------- Help page
            ScreenTransition(visible = showHelp) {
                HelpScreen(onClose = { showHelp = false })
            }

            // ---------------- About page
            ScreenTransition(visible = showAbout) {
                AboutScreen(onClose = { showAbout = false })
            }

            // ---------------- Camera slides in over everything from the left (the swipe that opens it
            // goes left to right) and leaves the same way. The camera itself starts only once the
            // slide has finished, so starting it never makes the animation stutter.
            ScreenTransition(visible = showCamera, enter = CameraEnter, exit = CameraExit) {
                CameraScreen(
                    isOpen = showCamera,
                    enterFinished = transition.currentState == EnterExitState.Visible,
                    onClose = { showCamera = false },
                    onPhotoSaved = { lastPhotoUri = it },
                    onImageReady = { uri ->
                        showCamera = false   // back first
                        startScan(uri)       // then the Add page opens, filled in
                    }
                )
            }

            // ---------------- Voice entry, over everything
            ScreenTransition(visible = showVoice) {
                VoiceScreen(
                    showTips = settings.voiceTips,
                    onClose = { showVoice = false },
                    onHeard = { text ->
                        // Same as the + button: start from your defaults, then fill what was heard
                        if (form.isBlank) form.useDefaults()
                        form.accountId = startingAccountId()
                        fillFromSpeech(form, text)
                        showVoice = false
                        showAdd = true
                    }
                )
            }

            // ---------------- Shown briefly while a scanned image is being read
            if (ocr.busy) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
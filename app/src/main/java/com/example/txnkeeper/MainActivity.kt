package com.example.txnkeeper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.room.withTransaction
import com.example.txnkeeper.data.local.AccountEntity
import com.example.txnkeeper.data.local.AccountSummary
import com.example.txnkeeper.data.local.DEFAULT_ACCOUNT_NAME
import com.example.txnkeeper.data.local.DatabaseModule
import com.manu.manudesignsystem.theme.ManuDesignSystemTheme
import kotlinx.coroutines.launch
import com.manu.manudesignsystem.components.ManuButton
import com.manu.manudesignsystem.components.ManuFab
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import com.manu.manudesignsystem.components.ManuScaffold
import com.manu.manudesignsystem.theme.ManuDesignSystem
import androidx.compose.foundation.layout.fillMaxWidth
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
 *   - the Settings page (from the three-dot menu), and the Accounts page (from Settings)
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

    // Safety net: there is always at least one account (the database adds it, this covers odd cases)
    LaunchedEffect(Unit) {
        if (dao.accountCount() == 0) dao.insertAccount(AccountEntity(name = DEFAULT_ACCOUNT_NAME))
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

    val form = remember { TxnFormState() }
    var showAdd by remember { mutableStateOf(false) }
    var fabVisible by remember { mutableStateOf(true) }
    var showCamera by remember { mutableStateOf(false) }
    // Text recognition runs here, after the camera has closed
    val ocr = rememberOcrController()

    // An image shared into the app: make sure the camera is closed, then read it
    LaunchedEffect(sharedImageUri) {
        sharedImageUri?.let {
            showCamera = false
            ocr.run(it)
            onSharedImageHandled()
        }
    }

    // Last photo taken in the app; use this later to attach a photo to a transaction
    var lastPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    fun openCamera() {
        focusManager.clearFocus()
        showCamera = true
    }

    // Back from the Add page or the Accounts page returns to the list (the camera has its own back handling)
    BackHandler(enabled = showAdd) {
        focusManager.clearFocus()
        showAdd = false
    }
    BackHandler(enabled = showSettings) { showSettings = false }
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
                        .swipeRightTo { openCamera() }
                ) {
                    TransactionsScreen(
                        dao = dao,
                        accounts = accounts,
                        accountId = accountFilter,
                        onAccountChange = { accountFilter = it },
                        onOpenSettings = { showSettings = true },
                        onScan = { openCamera() },
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
                                // New entries go to the account you are looking at (or the last one used)
                                form.accountId = accountFilter ?: form.accountId
                                showAdd = true
                            }
                        ) { Icon(Icons.Default.Add, contentDescription = "Add transaction") }
                    }
                }
            }

            // ---------------- Add transaction page slides up over the list
            AnimatedVisibility(
                visible = showAdd,
                enter = slideInVertically(initialOffsetY = { it / 4 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 4 }) + fadeOut()
            ) {
                AddTransactionScreen(
                    form = form,
                    accounts = accounts,
                    onClose = { showAdd = false },
                    onScan = { openCamera() },
                    onSubmit = { entity, stay ->
                        scope.launch {
                            dao.insert(entity)
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

            // ---------------- Settings page slides in from the right over the list
            AnimatedVisibility(
                visible = showSettings,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                SettingsScreen(
                    settings = settings,
                    database = database,
                    onManageAccounts = { showAccounts = true },
                    onClose = { showSettings = false }
                )
            }

            // ---------------- Accounts page slides in from the right (over Settings, which opens it)
            AnimatedVisibility(
                visible = showAccounts,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                AccountsScreen(
                    accounts = accounts,
                    onClose = { showAccounts = false },
                    onAdd = { scope.launch { dao.insertAccount(it) } },
                    onUpdate = { scope.launch { dao.updateAccount(it) } },
                    onDelete = { id ->
                        scope.launch {
                            // Delete the transactions, then the account, as one step: either both happen or neither
                            database.withTransaction {
                                dao.deleteTransactionsOfAccount(id)
                                dao.deleteAccount(id)
                            }
                        }
                    }
                )
            }

            // ---------------- Camera slides in from the left over everything
            AnimatedVisibility(
                visible = showCamera,
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
            ) {
                CameraScreen(
                    onClose = { showCamera = false },
                    onPhotoSaved = { lastPhotoUri = it },
                    onImageReady = { uri ->
                        showCamera = false   // back first
                        ocr.run(uri)         // then the dialog opens over it
                    }
                )
            }
        }
    }

    // Recognized-text dialog (shown over everything). "Fill form" opens the Add page, filled in.
    OcrResultDialog(
        ocr = ocr,
        findDuplicate = { ref -> dao.findByReference(ref) },
        onFill = { parsed ->
            form.fill(parsed)
            showAdd = true
        }
    )
}

// Template leftovers kept as they were
enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
    PROFILE("Profile", R.drawable.ic_account_box),
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ManuDesignSystemTheme {
        Greeting("Android")
    }
}
package com.manu.txnkeeper

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.manudesignsystem.components.ManuOutlineButton
import com.manu.manudesignsystem.components.ManuTextField
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AccountSummary
import com.manu.txnkeeper.data.local.AppDatabase
import com.manu.txnkeeper.data.local.DEFAULT_ACCOUNT_NAME
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phrases typed to confirm a deletion. Short (2-3 words) so they are quick to type on a
 * phone, but not something you would type by accident. To change them, edit these two lines.
 */
const val DELETE_ALL_PHRASE = "delete all data"
const val DELETE_ACCOUNT_PHRASE = "delete account"

/**
 * True when [typed] is the confirmation [phrase]. Capital letters don't matter and extra or
 * unusual spaces are ignored (phone keyboards add a space after a word, and sometimes use a
 * non-breaking one), but everything else has to match exactly: a part of the phrase is not enough.
 */
fun confirmationMatches(typed: String, phrase: String): Boolean =
    normalizeConfirmation(typed) == normalizeConfirmation(phrase)

private fun normalizeConfirmation(text: String): String =
    text.replace(Regex("[\\s\\u00A0\\u2007\\u202F]+"), " ").trim().lowercase()

private fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

/**
 * The "Delete data" group in Settings. One button opens a dialog where you choose what to delete
 * (all data, or one account) and type a phrase to confirm.
 */
@Composable
fun DeleteDataControls(settings: AppSettings, database: AppDatabase) {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val dao = remember { database.transactionDao() }
    val accounts by remember { dao.observeAccounts() }.collectAsState(initial = emptyList<AccountSummary>())
    var dialogOpen by remember { mutableStateOf(false) }

    Text(
        text = "Permanently delete what you have entered in this app: everything, or just one account. " +
                "There is no cloud backup, so deleted data can't be recovered. " +
                "Export a CSV first (see Data above) if you want a copy.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        text = "Photos taken with the in-app camera are saved in your gallery (Pictures/TxnKeeper). " +
                "They are not deleted here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ManuOutlineButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { dialogOpen = true }
    ) { Text("Delete data…", color = MaterialTheme.colorScheme.error) }

    if (dialogOpen) {
        DeleteDataDialog(
            accounts = accounts,
            onConfirm = { accountId ->
                dialogOpen = false
                coroutines.launch { runDelete(context, settings, database, accountId) }
            },
            onDismiss = { dialogOpen = false }
        )
    }
}

/**
 * Choose what to delete, read what will happen, then type the phrase. The Delete button only
 * works when the phrase matches; otherwise a message says what to type.
 */
@Composable
private fun DeleteDataDialog(
    accounts: List<AccountSummary>,
    /** The account to delete, or null for all data. */
    onConfirm: (accountId: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    // null = all data
    var chosenId by remember { mutableStateOf<Long?>(null) }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val account = accounts.firstOrNull { it.id == chosenId }
    val phrase = if (account == null) DELETE_ALL_PHRASE else DELETE_ACCOUNT_PHRASE

    // Choosing something else starts over: the phrase to type is different
    fun choose(id: Long?) {
        chosenId = id
        typed = ""
        error = null
    }

    ManuDialog(
        visible = true,
        title = "Delete data",
        confirmText = "Delete",
        onConfirm = {
            if (confirmationMatches(typed, phrase)) {
                onConfirm(account?.id)
            } else {
                error = "Type \"$phrase\" exactly to continue."
            }
        },
        onDismiss = onDismiss,
        destructive = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // What to delete. A single account has nothing to choose between, so no chips then.
            if (accounts.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ManuChip(label = "All data", selected = account == null, onClick = { choose(null) })
                    accounts.forEach { a ->
                        ManuChip(label = a.name, selected = a.id == account?.id, onClick = { choose(a.id) })
                    }
                }
            }

            Text(
                text = if (account == null) {
                    "This deletes all ${plural(accounts.sumOf { it.txnCount }, "transaction")} and all your accounts " +
                            "(one empty \"$DEFAULT_ACCOUNT_NAME\" is kept so the app keeps working), " +
                            "and puts your payment methods back to the defaults."
                } else {
                    "This deletes the account \"${account.name}\" and its ${plural(account.txnCount, "transaction")}. " +
                            "Your other accounts are not touched."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "This can't be undone.",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            Text("To confirm, type:", style = MaterialTheme.typography.bodyMedium)
            Text(phrase, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ManuTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    error = null
                },
                placeholder = { Text(phrase) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Does the deletion. Everything happens in one database transaction: either it all happens or
 * nothing does. A failure shows a message and leaves the data as it was.
 */
private suspend fun runDelete(
    context: Context,
    settings: AppSettings,
    database: AppDatabase,
    accountId: Long?
) {
    val dao = database.transactionDao()
    val saved = safeWrite(context, "delete the data") {
        database.withTransaction {
            if (accountId != null) {
                dao.deleteTransactionsOfAccount(accountId)
                dao.deleteAccount(accountId)
            } else {
                // The app always needs one account: keep the oldest one, empty and renamed
                val keep = dao.getAccountsOnce().firstOrNull()
                dao.deleteAllTransactions()
                if (keep == null) {
                    dao.insertAccount(AccountEntity(name = DEFAULT_ACCOUNT_NAME))
                } else {
                    dao.deleteAccountsExcept(keep.id)
                    dao.updateAccount(AccountEntity(id = keep.id, name = DEFAULT_ACCOUNT_NAME))
                }
            }
        }
    }
    if (!saved) return

    if (accountId == null) settings.resetPaymentMethods()
    Toast.makeText(
        context,
        if (accountId == null) "All data deleted." else "Account deleted.",
        Toast.LENGTH_LONG
    ).show()

    compactDatabase(database)
}

/**
 * Best effort: rewrites the database file so deleted rows are really gone from storage, not just
 * marked as free. If it can't (the database is busy) nothing is lost, so any error is ignored.
 */
private suspend fun compactDatabase(database: AppDatabase) = withContext(Dispatchers.IO) {
    try {
        val db = database.openHelper.writableDatabase
        db.execSQL("VACUUM")
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Ignored on purpose, see above
    }
}
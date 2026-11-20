package com.manu.txnkeeper

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.sqlite.db.SimpleSQLiteQuery
import com.manu.manudesignsystem.components.ManuChip
import com.manu.manudesignsystem.components.ManuDialog
import com.manu.txnkeeper.data.local.TransactionDao
import com.manu.txnkeeper.data.local.TransactionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Helpers for the selection mode of the transactions list (long-press a row to start, tap more rows to
 * add them, then use the actions in the top bar: share, move to another account, delete ...).
 */

/**
 * SQLite allows only so many "?" marks in one statement (999 on older phones), so a long list of ids
 * is always handed to the database in pieces of this size.
 */
internal const val ID_CHUNK = 500

/** The transactions with these ids (any number of them). */
internal suspend fun TransactionDao.loadByIds(ids: Collection<Int>): List<TransactionEntity> =
    ids.chunked(ID_CHUNK).flatMap { getByIds(it) }

/** The ids of every transaction a list query matches: what "Select all" selects (not just the loaded page). */
internal suspend fun TransactionDao.matchingIds(q: TxnQuery): List<Int> {
    val where = TxnSql.where(q)
    return rawIds(SimpleSQLiteQuery("SELECT id FROM transactions WHERE ${where.sql}", where.args.toTypedArray()))
}

/**
 * The line added at the very end of every share. Change it to whatever you like (a Play Store link,
 * a tagline ...); set it to "" to share without any line.
 */
internal const val SHARE_FOOTER = "Tracked with TxnKeeper - simple, offline-first transaction keeper"

private const val SHARE_DIVIDER = "━━━━━━━━━━━━━━"

/**
 * Text for the share sheet, laid out for chat apps: a bold heading, a numbered list (newest first) with
 * the title and amount in bold, and a bold total at the end when there is more than one.
 * Bold is written the WhatsApp way (*like this*); Telegram and others show it as plain asterisks.
 */
internal fun buildShareText(list: List<TransactionEntity>, accountNames: Map<Long, String>): String {
    val sorted = list.sortedWith(compareByDescending<TransactionEntity> { it.dateTime }.thenByDescending { it.id })
    val several = sorted.size > 1
    val sb = StringBuilder()
    sb.append("*TxnKeeper · ")
        .append(if (several) "${sorted.size} transactions" else "1 transaction")
        .append("*\n")
    sb.append(SHARE_DIVIDER).append("\n")
    sorted.forEachIndexed { index, t ->
        val signed = if (t.type == "INCOME") t.amountPaise else -t.amountPaise
        // An asterisk inside a title would break the bold, so it is dropped
        val title = t.title.replace("*", "").trim().ifEmpty { "Transaction" }
        val indent = if (several) "    " else ""
        sb.append("\n")
        sb.append(if (several) "${index + 1}. " else "").append("*").append(title).append("*\n")
        sb.append(indent).append("*").append(formatSignedMoney(signed)).append("*\n")
        sb.append(indent).append(formatDateLong(t.dateTime)).append(", ").append(formatTime(t.dateTime)).append("\n")
        val details = listOf(t.paymentMethod, accountNames[t.accountId].orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (details.isNotEmpty()) sb.append(indent).append(details).append("\n")
        if (t.description.isNotBlank()) sb.append(indent).append(t.description.trim()).append("\n")
    }
    if (several) {
        val income = sorted.filter { it.type == "INCOME" }.sumOf { it.amountPaise }
        val expense = sorted.filter { it.type == "EXPENSE" }.sumOf { it.amountPaise }
        sb.append("\n").append(SHARE_DIVIDER).append("\n")
        sb.append("*Income:* ").append(formatMoney(income)).append("\n")
        sb.append("*Expense:* ").append(formatMoney(expense)).append("\n")
        sb.append("*Net:* ").append(formatBalance(income - expense)).append("\n")
    }
    if (SHARE_FOOTER.isNotBlank()) {
        sb.append("\n_").append(SHARE_FOOTER).append("_")
    }
    return sb.toString().trimEnd()
}

/** Opens the system share sheet with [text]. */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share transactions").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The three ways the selected transactions can be shared. */
internal enum class ShareFormat { TEXT, PDF, CSV }

/**
 * Shares the transactions in the chosen [format]: as text, or as a PDF / CSV file (the same layouts as
 * the export in Settings) made in the app's cache folder and handed to the share sheet through the
 * FileProvider declared in the manifest. The cache folder only keeps the latest shared file.
 */
internal suspend fun shareTransactions(
    context: Context,
    format: ShareFormat,
    list: List<TransactionEntity>,
    accountNames: Map<Long, String>,
    /** Tag names per transaction id (for the CSV file). */
    tagNames: Map<Int, List<String>> = emptyMap()
) {
    if (format == ShareFormat.TEXT) {
        shareText(context, buildShareText(list, accountNames))
        return
    }
    // The file is handed over through a FileProvider: say so plainly if the app was updated without the manifest part
    val authority = "${context.packageName}.fileprovider"
    if (context.packageManager.resolveContentProvider(authority, 0) == null) {
        Toast.makeText(
            context,
            "Sharing files needs the FileProvider: copy AndroidManifest.xml and res/xml/share_file_paths.xml from the update, then rebuild.",
            Toast.LENGTH_LONG
        ).show()
        return
    }
    try {
        val rows = list
            .sortedWith(compareByDescending<TransactionEntity> { it.dateTime }.thenByDescending { it.id })
            .map {
                TxnRow(
                    dateTime = it.dateTime,
                    title = it.title,
                    amountPaise = it.amountPaise,
                    type = it.type,
                    paymentMethod = it.paymentMethod,
                    accountName = accountNames[it.accountId].orEmpty(),
                    description = it.description,
                    tags = tagNames[it.id].orEmpty()
                )
            }
        val pdf = format == ShareFormat.PDF
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val target = File(dir, "TxnKeeper_$stamp.${if (pdf) "pdf" else "csv"}")
            if (pdf) {
                target.outputStream().use {
                    TxnPdf.write(rows, it, scopeLabel = if (rows.size == 1) "1 selected transaction" else "${rows.size} selected transactions")
                }
            } else {
                target.writeText(TxnCsv.write(rows), Charsets.UTF_8)
            }
            target
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (pdf) "application/pdf" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, if (pdf) "Share PDF" else "Share CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Shown as it is, so a failure can be told apart (storage full, no app to share with ...)
        Toast.makeText(
            context,
            "Couldn't share the file: ${e.message ?: e.javaClass.simpleName}",
            Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * A dialog with one chip per option; the user picks one and confirms. Used to choose the account to
 * move to, a payment method, or a tag. Nothing is chosen at first, and confirming without a choice
 * just shows a hint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> PickOneDialog(
    title: String,
    message: String,
    confirmText: String,
    options: List<Pair<T, String>>,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf<Pair<T, String>?>(null) }
    var hint by remember { mutableStateOf(false) }
    ManuDialog(
        visible = true,
        title = title,
        confirmText = confirmText,
        onConfirm = {
            val pick = chosen
            if (pick == null) hint = true else onConfirm(pick.first)
        },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    ManuChip(
                        label = option.second,
                        selected = chosen == option,
                        onClick = {
                            chosen = option
                            hint = false
                        }
                    )
                }
            }
            if (hint) {
                Text(
                    text = "Pick one first.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Quick actions for the selected rows: three small round buttons floating over the list at the right
 * edge (like a + button, they sit on top of the rows and take no space from them).
 *  - Share: as text, as a PDF, or as a CSV file.
 *  - Edit: one row opens its editor; several open one after the other.
 *  - More: move to another account, change payment method, add a tag, and Delete last, apart from
 *    the rest and in red, so it is not hit by accident (it asks again before deleting).
 */
@Composable
internal fun SelectionRail(
    visible: Boolean,
    onShare: (ShareFormat) -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onPayment: () -> Unit,
    onTag: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var shareMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    val container = MaterialTheme.colorScheme.secondaryContainer
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInHorizontally { it },
        exit = fadeOut() + slideOutHorizontally { it }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box {
                SmallFloatingActionButton(
                    onClick = { shareMenuOpen = true },
                    containerColor = container,
                    contentColor = content
                ) { Icon(Icons.Default.Share, contentDescription = "Share") }
                DropdownMenu(expanded = shareMenuOpen, onDismissRequest = { shareMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("Share as text") }, onClick = {
                        shareMenuOpen = false
                        onShare(ShareFormat.TEXT)
                    })
                    DropdownMenuItem(text = { Text("Share as PDF") }, onClick = {
                        shareMenuOpen = false
                        onShare(ShareFormat.PDF)
                    })
                    DropdownMenuItem(text = { Text("Share as CSV") }, onClick = {
                        shareMenuOpen = false
                        onShare(ShareFormat.CSV)
                    })
                }
            }

            SmallFloatingActionButton(
                onClick = onEdit,
                containerColor = container,
                contentColor = content
            ) { Icon(Icons.Default.Edit, contentDescription = "Edit") }

            Box {
                SmallFloatingActionButton(
                    onClick = { moreMenuOpen = true },
                    containerColor = container,
                    contentColor = content
                ) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("Move to account") }, onClick = {
                        moreMenuOpen = false
                        onMove()
                    })
                    DropdownMenuItem(text = { Text("Change payment method") }, onClick = {
                        moreMenuOpen = false
                        onPayment()
                    })
                    DropdownMenuItem(text = { Text("Add tag") }, onClick = {
                        moreMenuOpen = false
                        onTag()
                    })
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    DropdownMenuItem(
                        text = { Text("Delete...", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            moreMenuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

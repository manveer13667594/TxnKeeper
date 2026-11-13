package com.manu.txnkeeper

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.manu.txnkeeper.data.local.AccountEntity
import com.manu.txnkeeper.data.local.AppDatabase
import com.manu.txnkeeper.data.local.DEFAULT_ACCOUNT_NAME
import com.manu.txnkeeper.data.local.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What an import did. If [fileError] is set nothing was imported. */
data class ImportResult(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val invalid: List<CsvRowError> = emptyList(),
    val accountsCreated: List<String> = emptyList(),
    val fileError: String? = null
) {
    /** A short, plain-text report to show the user. */
    fun summary(): String {
        if (fileError != null) return "Import failed: $fileError"
        val lines = mutableListOf<String>()
        lines += if (imported == 0) "No new transactions were imported." else "Imported $imported transaction${if (imported == 1) "" else "s"}."
        if (duplicates > 0) lines += "Skipped $duplicates already in the app."
        if (accountsCreated.isNotEmpty()) lines += "New account${if (accountsCreated.size == 1) "" else "s"}: ${accountsCreated.joinToString(", ")}."
        if (invalid.isNotEmpty()) {
            lines += "${invalid.size} row${if (invalid.size == 1) "" else "s"} skipped:"
            invalid.take(MAX_SHOWN).forEach { lines += "  Row ${it.row}: ${it.reason}" }
            if (invalid.size > MAX_SHOWN) lines += "  ...and ${invalid.size - MAX_SHOWN} more"
        }
        return lines.joinToString("\n")
    }

    private companion object {
        const val MAX_SHOWN = 5
    }
}

/** Which transactions an export contains. */
sealed interface ExportScope {
    /** Everything. */
    data object All : ExportScope

    /** Transactions from [from] to [to] (milliseconds, both included). */
    data class DateRange(val from: Long, val to: Long) : ExportScope

    /** The newest [count] transactions. */
    data class Latest(val count: Int) : ExportScope
}

/**
 * Extra conditions on top of an [ExportScope]. Every condition that is set must match, and they
 * also apply to "Latest N" (the newest N of the transactions that match). null = don't filter on it.
 */
data class ExportFilters(
    val accountId: Long? = null,
    val paymentMethod: String? = null,
    val type: String? = null,               // "INCOME" / "EXPENSE"
    val minAmountPaise: Long? = null,
    val maxAmountPaise: Long? = null
) {
    /** How many conditions are on (the min / max pair counts as one). */
    val activeCount: Int
        get() = listOf(
            accountId != null,
            paymentMethod != null,
            type != null,
            minAmountPaise != null || maxAmountPaise != null
        ).count { it }
}

/**
 * The scope and filters as one [TxnQuery], so export uses the very same condition builder
 * (TxnSql) as the transaction list and the two always agree on what "UPI over 500" means.
 */
internal fun exportQuery(scope: ExportScope, filters: ExportFilters): TxnQuery = TxnQuery(
    txType = filters.type,
    payMethod = filters.paymentMethod,
    minAmount = filters.minAmountPaise?.let { it / 100.0 },
    maxAmount = filters.maxAmountPaise?.let { it / 100.0 },
    dateFrom = (scope as? ExportScope.DateRange)?.from,
    dateTo = (scope as? ExportScope.DateRange)?.to,
    accountId = filters.accountId,
    search = "",
    newestFirst = true
)

/**
 * Reads and writes the files the user picked in the system file picker. The picker hands over a
 * Uri we may read or write directly, so no storage permission is needed.
 */
object DataTransfer {

    /** Writes the transactions of [scope] that match [filters] to [uri] as CSV. Returns how many were written. */
    suspend fun exportCsv(
        context: Context, database: AppDatabase, uri: Uri,
        scope: ExportScope = ExportScope.All,
        filters: ExportFilters = ExportFilters()
    ): Int = withContext(Dispatchers.IO) {
        val rows = loadRows(database, scope, filters)
        val csv = TxnCsv.write(rows)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not open the file for writing")
        rows.size
    }

    /** Writes the transactions of [scope] that match [filters] to [uri] as a PDF. Returns how many were written. */
    suspend fun exportPdf(
        context: Context, database: AppDatabase, uri: Uri,
        scope: ExportScope = ExportScope.All,
        filters: ExportFilters = ExportFilters()
    ): Int = withContext(Dispatchers.IO) {
        val rows = loadRows(database, scope, filters)
        val accountName = filters.accountId?.let { id ->
            database.transactionDao().getAccountsOnce().firstOrNull { it.id == id }?.name
        }
        val label = scopeLabel(scope, filters, accountName)
        context.contentResolver.openOutputStream(uri, "wt")?.use { TxnPdf.write(rows, it, scopeLabel = label) }
            ?: error("Could not open the file for writing")
        rows.size
    }

    /** How many transactions the choice matches (used to say "nothing to export" before the file picker opens). */
    suspend fun countFor(
        database: AppDatabase, scope: ExportScope, filters: ExportFilters = ExportFilters()
    ): Int = withContext(Dispatchers.IO) {
        // Counted by the database: no need to load every row just to know how many there are
        val where = TxnSql.where(exportQuery(scope, filters))
        val matching = database.transactionDao().rawTotals(
            SimpleSQLiteQuery(
                "SELECT 0 AS incomePaise, 0 AS expensePaise, COUNT(*) AS count FROM transactions WHERE ${where.sql}",
                where.args.toTypedArray()
            )
        ).first().count
        val limit = (scope as? ExportScope.Latest)?.count
        if (limit != null) minOf(matching, limit) else matching
    }

    /** Text shown under the PDF title, or null when everything is exported. */
    fun scopeLabel(
        scope: ExportScope,
        filters: ExportFilters = ExportFilters(),
        accountName: String? = null
    ): String? {
        val parts = mutableListOf<String>()
        when (scope) {
            ExportScope.All -> Unit
            is ExportScope.DateRange -> parts += "${formatDateLong(scope.from)} - ${formatDateLong(scope.to)}"
            is ExportScope.Latest -> parts += "Latest ${scope.count} transactions"
        }
        filters.accountId?.let { parts += "Account: ${accountName ?: "#$it"}" }
        filters.paymentMethod?.let { parts += "Payment: $it" }
        filters.type?.let { parts += if (it == "INCOME") "Income only" else "Expense only" }
        val min = filters.minAmountPaise
        val max = filters.maxAmountPaise
        when {
            min != null && max != null -> parts += "Amount ${formatMoney(min)} to ${formatMoney(max)}"
            min != null -> parts += "Amount at least ${formatMoney(min)}"
            max != null -> parts += "Amount at most ${formatMoney(max)}"
        }
        return if (parts.isEmpty()) null else parts.joinToString("  ·  ")
    }

    private suspend fun loadRows(database: AppDatabase, scope: ExportScope, filters: ExportFilters): List<TxnRow> {
        val dao = database.transactionDao()
        val accountNames = dao.getAccountsOnce().associate { it.id to it.name }

        // Same WHERE builder as the list screen; "Latest N" is the first N of the filtered, newest-first rows
        val where = TxnSql.where(exportQuery(scope, filters))
        val limit = (scope as? ExportScope.Latest)?.count
        val sql = "SELECT * FROM transactions WHERE ${where.sql} ORDER BY dateTime DESC, id DESC" +
                (if (limit != null) " LIMIT ?" else "")
        val args = if (limit != null) where.args + limit else where.args
        val transactions = dao.rawTransactions(SimpleSQLiteQuery(sql, args.toTypedArray())).first()

        return transactions.map {
            TxnRow(
                dateTime = it.dateTime,
                title = it.title,
                amountPaise = it.amountPaise,
                type = it.type,
                paymentMethod = it.paymentMethod,
                accountName = accountNames[it.accountId].orEmpty(),
                description = it.description
            )
        }
    }

    /**
     * Imports the CSV at [uri]. All or nothing: the whole import is one database transaction.
     *  - A row already in the app (same account, time to the second, amount, type and title) is
     *    skipped, so importing the same file twice adds nothing. Identical rows are counted, so
     *    a file that really has two identical rows still brings in both on a fresh install.
     *  - An account name not found in the app is created (type BANK, opening balance 0).
     *    Opening balances are not part of the CSV, so set them again in Accounts if you need them.
     *  - A blank account goes to the oldest account.
     */
    suspend fun importCsv(context: Context, database: AppDatabase, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Could not open the file for reading")

        val parsed = TxnCsv.read(text)
        if (parsed.fileError != null) return@withContext ImportResult(fileError = parsed.fileError)

        val dao = database.transactionDao()
        database.withTransaction {
            val accountIds = HashMap<String, Long>()
            val accounts = dao.getAccountsOnce()
            accounts.forEach { accountIds.putIfAbsent(it.name.trim().lowercase(), it.id) }
            val defaultAccountId = accounts.minByOrNull { it.id }?.id
                ?: dao.insertAccount(AccountEntity(name = DEFAULT_ACCOUNT_NAME))
            val created = mutableListOf<String>()

            // How many of each transaction the app already has
            val existing = HashMap<String, Int>()
            if (parsed.rows.isNotEmpty()) {
                // Only rows inside the file's own time span (to the second) can be duplicates,
                // so the rest of a big table is not loaded
                val from = parsed.rows.minOf { it.dateTime } / 1000 * 1000
                val to = parsed.rows.maxOf { it.dateTime } / 1000 * 1000 + 999
                dao.getBetweenOnce(from, to).forEach {
                    existing.merge(dupKey(it.accountId, it.dateTime, it.amountPaise, it.type, it.title), 1, Int::plus)
                }
            }

            val toInsert = ArrayList<TransactionEntity>()
            var duplicates = 0
            for (row in parsed.rows) {
                val name = row.accountName.trim()
                val accountId = if (name.isEmpty()) {
                    defaultAccountId
                } else {
                    accountIds.getOrPut(name.lowercase()) {
                        created += name
                        dao.insertAccount(AccountEntity(name = name))
                    }
                }

                val key = dupKey(accountId, row.dateTime, row.amountPaise, row.type, row.title)
                val alreadyThere = existing[key] ?: 0
                if (alreadyThere > 0) {
                    existing[key] = alreadyThere - 1
                    duplicates++
                } else {
                    toInsert += TransactionEntity(
                        title = row.title,
                        amountPaise = row.amountPaise,
                        type = row.type,
                        dateTime = row.dateTime,
                        paymentMethod = row.paymentMethod,
                        description = row.description,
                        accountId = accountId
                    )
                }
            }
            dao.insertAll(toInsert)

            ImportResult(
                imported = toInsert.size,
                duplicates = duplicates,
                invalid = parsed.errors,
                accountsCreated = created
            )
        }
    }

    // The CSV keeps time to the second and the amount to the paisa, so compare only that much.
    private fun dupKey(accountId: Long, dateTime: Long, amountPaise: Long, type: String, title: String): String =
        "$accountId|${dateTime / 1000}|$amountPaise|$type|${title.trim().lowercase()}"
}
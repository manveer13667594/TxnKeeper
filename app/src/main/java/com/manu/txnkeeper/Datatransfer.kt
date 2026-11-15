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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One line of a result message. [warning] lines are shown in red. */
data class StatusLine(val text: String, val warning: Boolean = false)

/** What an import did. If [fileError] is set nothing was imported. */
data class ImportResult(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val invalid: List<CsvRowError> = emptyList(),
    val accountsCreated: List<String> = emptyList(),
    /** Images brought in (with new transactions, or added to ones the app already had without one). */
    val imagesAdded: Int = 0,
    /** Rows that name an image the ZIP does not hold (or could not be read): imported without it. */
    val imagesMissing: Int = 0,
    val fileError: String? = null
) {
    /** The report to show the user, line by line. Failures, missing images and skipped rows are warnings (red). */
    fun summaryLines(): List<StatusLine> {
        if (fileError != null) return listOf(StatusLine("Import failed: $fileError", warning = true))
        val lines = mutableListOf<StatusLine>()
        lines += StatusLine(if (imported == 0) "No new transactions were imported." else "Imported $imported transaction${if (imported == 1) "" else "s"}.")
        if (duplicates > 0) lines += StatusLine("Skipped $duplicates already in the app.")
        if (imagesAdded > 0) lines += StatusLine("Added $imagesAdded image${if (imagesAdded == 1) "" else "s"}.")
        if (imagesMissing > 0) lines += StatusLine("$imagesMissing image${if (imagesMissing == 1) " was" else "s were"} missing from the ZIP.", warning = true)
        if (accountsCreated.isNotEmpty()) lines += StatusLine("New account${if (accountsCreated.size == 1) "" else "s"}: ${accountsCreated.joinToString(", ")}.")
        if (invalid.isNotEmpty()) {
            lines += StatusLine("${invalid.size} row${if (invalid.size == 1) "" else "s"} skipped:", warning = true)
            invalid.take(MAX_SHOWN).forEach { lines += StatusLine("  Row ${it.row}: ${it.reason}", warning = true) }
            if (invalid.size > MAX_SHOWN) lines += StatusLine("  ...and ${invalid.size - MAX_SHOWN} more", warning = true)
        }
        return lines
    }

    /** The same report as plain text. */
    fun summary(): String = summaryLines().joinToString("\n") { it.text }

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

/** What an export wrote: how many transactions, and how many images went with them. */
data class ExportResult(val transactions: Int, val images: Int = 0)

/** File layout of the ZIP made by "export with images" (and read back by import). */
private const val ZIP_CSV_NAME = "transactions.csv"
private const val ZIP_IMAGES_DIR = "images/"

/** The CSV inside a ZIP is read into memory, so a damaged file cannot use it all up. */
private const val MAX_ZIP_CSV_BYTES = 64 * 1024 * 1024

/** Longest side of an image drawn into a PDF: sharp enough to read a receipt, keeps the PDF small. */
private const val PDF_IMAGE_PX = 1200

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
        val rows = loadRows(context, database, scope, filters, withImages = false)
        val csv = TxnCsv.write(rows)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not open the file for writing")
        rows.size
    }

    /**
     * Writes the transactions of [scope] that match [filters] to [uri] as a PDF. With [withImages] the
     * receipt images follow the table, one per page. Returns how many transactions (and images) were written.
     */
    suspend fun exportPdf(
        context: Context, database: AppDatabase, uri: Uri,
        scope: ExportScope = ExportScope.All,
        filters: ExportFilters = ExportFilters(),
        withImages: Boolean = false
    ): ExportResult = withContext(Dispatchers.IO) {
        val rows = loadRows(context, database, scope, filters, withImages)
        val accountName = filters.accountId?.let { id ->
            database.transactionDao().getAccountsOnce().firstOrNull { it.id == id }?.name
        }
        val label = scopeLabel(scope, filters, accountName)
        var images = 0
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            images = TxnPdf.write(
                rows, it, scopeLabel = label,
                imageLoader = if (withImages) { name -> ReceiptStore.decodeSampled(context, name, PDF_IMAGE_PX) } else null
            )
        } ?: error("Could not open the file for writing")
        ExportResult(rows.size, images)
    }

    /**
     * Writes a ZIP to [uri]: transactions.csv (with an Image column) and an images folder holding every
     * receipt image of those transactions. [importFile] reads it back, images included.
     */
    suspend fun exportZip(
        context: Context, database: AppDatabase, uri: Uri,
        scope: ExportScope = ExportScope.All,
        filters: ExportFilters = ExportFilters()
    ): ExportResult = withContext(Dispatchers.IO) {
        // Only rows whose image file really exists get an image name, so the CSV never points at nothing
        val rows = loadRows(context, database, scope, filters, withImages = true)
        val csv = TxnCsv.write(rows, withImageColumn = true)
        var images = 0
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file for writing")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry(ZIP_CSV_NAME))
            zip.write(csv.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // The images are already JPEG, so compressing them again only costs time
            zip.setLevel(Deflater.NO_COMPRESSION)
            val written = HashSet<String>()
            for (name in rows.mapNotNull { it.imageFile }) {
                if (!written.add(name)) continue
                val file = ReceiptStore.file(context, name)?.takeIf { it.isFile } ?: continue
                zip.putNextEntry(ZipEntry(ZIP_IMAGES_DIR + name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                images++
            }
        }
        ExportResult(rows.size, images)
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

    private suspend fun loadRows(
        context: Context, database: AppDatabase, scope: ExportScope, filters: ExportFilters, withImages: Boolean
    ): List<TxnRow> {
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
                description = it.description,
                // Only an image that is really on the phone is named, so a file never points at nothing
                imageFile = if (withImages) it.imageFile?.takeIf { name -> ReceiptStore.exists(context, name) } else null
            )
        }
    }

    /**
     * Imports the file at [uri]: a CSV, or a ZIP made by "export with images" (CSV + images folder).
     * All or nothing: the whole import is one database transaction.
     *  - A row already in the app (same account, time to the second, amount, type and title) is
     *    skipped, so importing the same file twice adds nothing. Identical rows are counted, so
     *    a file that really has two identical rows still brings in both on a fresh install.
     *  - An account name not found in the app is created (type BANK, opening balance 0).
     *    Opening balances are not part of the CSV, so set them again in Accounts if you need them.
     *  - A blank account goes to the oldest account.
     *  - From a ZIP, each row's image comes along. A row the app already has, but without an image,
     *    gets the image from the ZIP. A row that names an image the ZIP does not hold is imported without it.
     *    Image files are saved under new names, never under the names in the file.
     */
    suspend fun importFile(context: Context, database: AppDatabase, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val isZip = openInput(context, uri).use { looksLikeZip(it) }

        val text: String
        if (isZip) {
            val csv: String? = try {
                readZipCsv(context, uri)
            } catch (e: ZipException) {
                return@withContext ImportResult(fileError = "This ZIP file could not be read.")
            }
            if (csv == null) {
                return@withContext ImportResult(fileError = "This ZIP has no CSV file in it (expected $ZIP_CSV_NAME).")
            }
            text = csv
        } else {
            text = openInput(context, uri).use { it.readBytes().toString(Charsets.UTF_8) }
        }

        val parsed = TxnCsv.read(text)
        if (parsed.fileError != null) return@withContext ImportResult(fileError = parsed.fileError)

        // From a ZIP: save the images the CSV names (null = a plain CSV, images are not involved)
        val images: Map<String, String>? = if (isZip) {
            try {
                extractImages(context, uri, parsed.rows.mapNotNull { it.imageFile }.toSet())
            } catch (e: ZipException) {
                return@withContext ImportResult(fileError = "This ZIP file could not be read.")
            }
        } else {
            null
        }
        val extracted = images?.values?.toSet().orEmpty()

        val applied = try {
            importRows(database, parsed, images)
        } catch (e: Throwable) {
            // Nothing was imported, so the image files saved for it are not needed
            ReceiptStore.deleteAll(context, extracted)
            throw e
        }
        // Saved from the ZIP but not used (the app already had that transaction, with its own image)
        ReceiptStore.deleteAll(context, extracted - applied.usedImages)
        applied.result
    }

    private class Applied(val result: ImportResult, val usedImages: Set<String>)

    /** The database part of an import. [images] maps an image name in the file to the file saved for it. */
    private suspend fun importRows(
        database: AppDatabase,
        parsed: CsvReadResult,
        images: Map<String, String>?
    ): Applied {
        val dao = database.transactionDao()
        return database.withTransaction {
            val accountIds = HashMap<String, Long>()
            val accounts = dao.getAccountsOnce()
            accounts.forEach { accountIds.putIfAbsent(it.name.trim().lowercase(), it.id) }
            val defaultAccountId = accounts.minByOrNull { it.id }?.id
                ?: dao.insertAccount(AccountEntity(name = DEFAULT_ACCOUNT_NAME))
            val created = mutableListOf<String>()

            // The transactions the app already has, per key (a key can have several identical ones)
            val existing = HashMap<String, ArrayDeque<TransactionEntity>>()
            if (parsed.rows.isNotEmpty()) {
                // Only rows inside the file's own time span (to the second) can be duplicates,
                // so the rest of a big table is not loaded
                val from = parsed.rows.minOf { it.dateTime } / 1000 * 1000
                val to = parsed.rows.maxOf { it.dateTime } / 1000 * 1000 + 999
                dao.getBetweenOnce(from, to).forEach {
                    existing.getOrPut(dupKey(it.accountId, it.dateTime, it.amountPaise, it.type, it.title)) { ArrayDeque() }
                        .addLast(it)
                }
            }

            val toInsert = ArrayList<TransactionEntity>()
            val used = HashSet<String>()
            var duplicates = 0
            var missing = 0
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

                // The image this row brings along. One saved file belongs to one transaction only.
                var image: String? = null
                if (images != null && row.imageFile != null) {
                    val saved = images[row.imageFile]
                    if (saved != null && saved !in used) image = saved else missing++
                }

                val key = dupKey(accountId, row.dateTime, row.amountPaise, row.type, row.title)
                val already = existing[key]?.removeFirstOrNull()
                if (already != null) {
                    duplicates++
                    // The app has it without an image: the one from the file completes it
                    if (image != null && already.imageFile == null) {
                        dao.update(already.copy(imageFile = image))
                        used += image
                    }
                } else {
                    toInsert += TransactionEntity(
                        title = row.title,
                        amountPaise = row.amountPaise,
                        type = row.type,
                        dateTime = row.dateTime,
                        paymentMethod = row.paymentMethod,
                        description = row.description,
                        accountId = accountId,
                        imageFile = image
                    )
                    if (image != null) used += image
                }
            }
            dao.insertAll(toInsert)

            Applied(
                result = ImportResult(
                    imported = toInsert.size,
                    duplicates = duplicates,
                    invalid = parsed.errors,
                    accountsCreated = created,
                    imagesAdded = used.size,
                    imagesMissing = missing
                ),
                usedImages = used
            )
        }
    }

    // ------------------------------------------------------------------ reading a ZIP

    private fun openInput(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("Could not open the file for reading")

    /** A ZIP file starts with the bytes P K 3 4. */
    private fun looksLikeZip(input: InputStream): Boolean {
        val head = ByteArray(4)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        return read == head.size && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
    }

    /** The text of the CSV inside the ZIP (the first .csv outside the images folder), or null if there is none. */
    private fun readZipCsv(context: Context, uri: Uri): String? {
        var found: String? = null
        ZipInputStream(BufferedInputStream(openInput(context, uri))).use { zip ->
            while (found == null) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.startsWith(ZIP_IMAGES_DIR) || name.startsWith("__MACOSX/")) continue
                if (!name.lowercase().endsWith(".csv")) continue
                val bytes = readLimited(zip, MAX_ZIP_CSV_BYTES) ?: error("The CSV file inside the ZIP is too large.")
                found = bytes.toString(Charsets.UTF_8)
            }
        }
        return found
    }

    /** Reads up to [limit] bytes; null if there is more than that. */
    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Saves the images of the ZIP that the CSV names ([wanted]) and returns image name in the file ->
     * file saved in the app. Entry names are only compared with the wanted names, never used as a
     * path, so a ZIP cannot write anywhere outside the receipts folder. Unreadable or oversized
     * images are left out (their rows import without one).
     */
    private suspend fun extractImages(context: Context, uri: Uri, wanted: Set<String>): Map<String, String> {
        val result = HashMap<String, String>()
        if (wanted.isEmpty()) return result
        try {
            ZipInputStream(BufferedInputStream(openInput(context, uri))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.startsWith(ZIP_IMAGES_DIR)) continue
                    val key = entry.name.removePrefix(ZIP_IMAGES_DIR)
                    if (key !in wanted || key in result) continue
                    ReceiptStore.saveFromStream(context, zip)?.let { result[key] = it }
                }
            }
        } catch (e: Throwable) {
            ReceiptStore.deleteAll(context, result.values)
            throw e
        }
        return result
    }

    // The CSV keeps time to the second and the amount to the paisa, so compare only that much.
    private fun dupKey(accountId: Long, dateTime: Long, amountPaise: Long, type: String, title: String): String =
        "$accountId|${dateTime / 1000}|$amountPaise|$type|${title.trim().lowercase()}"
}
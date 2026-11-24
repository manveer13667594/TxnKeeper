package com.manu.txnkeeper.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Sums for whatever the current filters match (computed by SQL, not from the loaded rows). */
data class TransactionTotals(
    val incomePaise: Long,
    val expensePaise: Long,
    val count: Int
)

/** The net change (income minus expense, in paise) of one time slice; slice 0 is the oldest. */
data class SparkBucket(
    val bucket: Int,
    val netPaise: Long
)

/** An account with its current balance and how many transactions it holds. */
data class AccountSummary(
    val id: Long,
    val name: String,
    val type: String,
    val openingBalancePaise: Long,
    val balancePaise: Long,
    val txnCount: Int
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity)

    /** Adds many transactions in one go (used by CSV import, inside a database transaction). */
    @Insert
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    /** Every transaction once (not observed), newest first. Used by CSV / PDF export and import. */
    @Query("SELECT * FROM transactions ORDER BY dateTime DESC, id DESC")
    suspend fun getAllOnce(): List<TransactionEntity>

    /** Transactions between two moments (both included), newest first. Used by "export a date range". */
    @Query("SELECT * FROM transactions WHERE dateTime BETWEEN :from AND :to ORDER BY dateTime DESC, id DESC")
    suspend fun getBetweenOnce(from: Long, to: Long): List<TransactionEntity>

    /** The newest [count] transactions. Used by "export the latest N". */
    @Query("SELECT * FROM transactions ORDER BY dateTime DESC, id DESC LIMIT :count")
    suspend fun getLatestOnce(count: Int): List<TransactionEntity>

    /** Every account once (not observed). Used by export (names) and import (matching names). */
    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun getAccountsOnce(): List<AccountEntity>

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    /** Kept for compatibility; the list screen no longer loads everything with this. */
    @Query("SELECT * FROM transactions ORDER BY dateTime DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    /**
     * One page of matching rows. The SQL is built in TxnSql (Txnsearch.kt) because the search box
     * can add any number of conditions. The tables it reads are listed so the list refreshes
     * whenever a transaction or an account changes.
     */
    @RawQuery(observedEntities = [TransactionEntity::class, AccountEntity::class])
    fun rawTransactions(query: SupportSQLiteQuery): Flow<List<TransactionEntity>>

    /** Income / expense / count over ALL matching rows, not just the loaded page. */
    @RawQuery(observedEntities = [TransactionEntity::class, AccountEntity::class])
    fun rawTotals(query: SupportSQLiteQuery): Flow<TransactionTotals>

    /** Net change per time slice for the home card's trend line (see observeSparkBuckets). */
    @RawQuery(observedEntities = [TransactionEntity::class, AccountEntity::class])
    fun rawSparkBuckets(query: SupportSQLiteQuery): Flow<List<SparkBucket>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun getTotalCount(): Flow<Int>

    // ------------------------------------------------------------------ several transactions at once
    // Used by the list's selection mode (see TransactionSelection.kt). Every list of ids passed in here
    // must stay under SQLite's limit on "?" marks, so callers hand them over in chunks (ID_CHUNK).

    /** The ids of every row a raw query matches ("Select all" on the list). */
    @RawQuery
    suspend fun rawIds(query: SupportSQLiteQuery): List<Int>

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<TransactionEntity>

    /** The image file names of these transactions (read before they are deleted). */
    @Query("SELECT imageFile FROM transactions WHERE id IN (:ids) AND imageFile IS NOT NULL")
    suspend fun imageFilesOfIds(ids: List<Int>): List<String>

    @Query("UPDATE transactions SET accountId = :accountId WHERE id IN (:ids)")
    suspend fun moveByIds(ids: List<Int>, accountId: Long)

    @Query("UPDATE transactions SET paymentMethod = :method WHERE id IN (:ids)")
    suspend fun setPaymentByIds(ids: List<Int>, method: String)

    /** Their tag links go with them (ON DELETE CASCADE). */
    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    /** First saved transaction whose description contains this reference number. */
    @Query("SELECT * FROM transactions WHERE description LIKE '%' || :ref || '%' LIMIT 1")
    suspend fun findByReference(ref: String): TransactionEntity?

    // ------------------------------------------------------------------ receipt images

    /** Every image file name in use (to clean up files nothing refers to any more). */
    @Query("SELECT imageFile FROM transactions WHERE imageFile IS NOT NULL")
    suspend fun allImageFiles(): List<String>

    /** The image file names of one account's transactions (read before the account is deleted). */
    @Query("SELECT imageFile FROM transactions WHERE accountId = :accountId AND imageFile IS NOT NULL")
    suspend fun imageFilesOfAccount(accountId: Long): List<String>

    /** How many transactions have an image (decides whether the export offers "include images"). */
    @Query("SELECT COUNT(*) FROM transactions WHERE imageFile IS NOT NULL")
    fun observeImageCount(): Flow<Int>

    // ------------------------------------------------------------------ payment methods

    /**
     * Every payment method that at least one transaction uses. The filter list adds these to the
     * saved list, so you can still filter by a method after removing it from the dropdown.
     */
    @Query("SELECT DISTINCT paymentMethod FROM transactions WHERE paymentMethod != '' ORDER BY paymentMethod")
    fun observePaymentMethodsInUse(): Flow<List<String>>

    /** How many transactions use one payment method (shown before removing it from the list). */
    @Query("SELECT COUNT(*) FROM transactions WHERE paymentMethod = :method")
    suspend fun countByPaymentMethod(method: String): Int

    // ------------------------------------------------------------------ accounts

    @Insert
    suspend fun insertAccount(account: AccountEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: Long)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun accountCount(): Int

    /** Moves every transaction of one account into another (used before deleting an account). */
    @Query("UPDATE transactions SET accountId = :toId WHERE accountId = :fromId")
    suspend fun moveTransactions(fromId: Long, toId: Long)

    /** Deletes every transaction of one account (used when the account itself is deleted). */
    @Query("DELETE FROM transactions WHERE accountId = :accountId")
    suspend fun deleteTransactionsOfAccount(accountId: Long)

    /** Deletes every transaction in the app (Settings > Delete data > All data). */
    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    /** Deletes every account except one (the one kept and reset when all data is deleted). */
    @Query("DELETE FROM accounts WHERE id != :keepId")
    suspend fun deleteAccountsExcept(keepId: Long)

    /** Every account with balance = opening balance + income - expense (all in paise). */
    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.type AS type, a.openingBalancePaise AS openingBalancePaise,
               a.openingBalancePaise + COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amountPaise
                                                    WHEN t.type = 'EXPENSE' THEN -t.amountPaise END), 0) AS balancePaise,
               COUNT(t.id) AS txnCount
        FROM accounts a
        LEFT JOIN transactions t ON t.accountId = a.id
        GROUP BY a.id
        ORDER BY a.id
        """
    )
    fun observeAccounts(): Flow<List<AccountSummary>>

    // ------------------------------------------------------------ tags

    /** Like [insert], but gives back the new transaction's id (needed to attach its tags). */
    @Insert
    suspend fun insertWithId(transaction: TransactionEntity): Long

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    /** Deleting a tag only removes the label: its rows in transaction_tags go with it, transactions stay. */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagEntity>>

    /** Every "transaction has tag" pair; the list joins these with the tags to show chips on rows. */
    @Query("SELECT * FROM transaction_tags")
    fun observeTagLinks(): Flow<List<TransactionTagCrossRef>>

    /** How many transactions use each tag (tags nobody uses are not listed). */
    @Query("SELECT tagId, COUNT(*) AS uses FROM transaction_tags GROUP BY tagId")
    fun observeTagUsage(): Flow<List<TagUsage>>

    /** How often each tag was used on transactions whose title matches the LIKE pattern (most used first). */
    @Query(
        "SELECT tt.tagId AS tagId, COUNT(*) AS uses FROM transaction_tags tt " +
            "JOIN transactions t ON t.id = tt.transactionId " +
            "WHERE t.title LIKE :pattern ESCAPE '\\' GROUP BY tt.tagId ORDER BY uses DESC LIMIT 3"
    )
    suspend fun tagUsageForTitleLike(pattern: String): List<TagUsage>

    /** How many transactions have a title matching the LIKE pattern (tagged or not). */
    @Query("SELECT COUNT(*) FROM transactions WHERE title LIKE :pattern ESCAPE '\\'")
    suspend fun countTitleLike(pattern: String): Int

    @Query("DELETE FROM transaction_tags WHERE transactionId = :transactionId")
    suspend fun clearTagLinks(transactionId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagLinks(links: List<TransactionTagCrossRef>)

    // ---- bill items

    /** Every item of every transaction; the list groups them by transaction for counts and details. */
    @Query("SELECT * FROM transaction_items ORDER BY transactionId, position, id")
    fun observeAllItems(): Flow<List<TransactionItemEntity>>

    @Insert
    suspend fun insertItems(items: List<TransactionItemEntity>)

    @Query("DELETE FROM transaction_items WHERE transactionId = :transactionId")
    suspend fun clearItems(transactionId: Int)
}

/** How many transactions carry one tag. */
data class TagUsage(
    val tagId: Long,
    val uses: Int
)
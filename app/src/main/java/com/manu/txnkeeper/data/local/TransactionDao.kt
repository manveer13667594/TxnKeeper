package com.manu.txnkeeper.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
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
    suspend fun insertAll(transactions: List<TransactionEntity>)

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

    @Query("SELECT COUNT(*) FROM transactions")
    fun getTotalCount(): Flow<Int>

    /** First saved transaction whose description contains this reference number. */
    @Query("SELECT * FROM transactions WHERE description LIKE '%' || :ref || '%' LIMIT 1")
    suspend fun findByReference(ref: String): TransactionEntity?

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
}
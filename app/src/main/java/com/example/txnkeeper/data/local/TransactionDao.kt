package com.example.txnkeeper.data.local

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
    val income: Double,
    val expense: Double,
    val count: Int
)

/** An account with its current balance and how many transactions it holds. */
data class AccountSummary(
    val id: Long,
    val name: String,
    val type: String,
    val openingBalance: Double,
    val balance: Double,
    val txnCount: Int
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity)

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

    /** Every account with balance = opening balance + income - expense. */
    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.type AS type, a.openingBalance AS openingBalance,
               a.openingBalance + COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount
                                                    WHEN t.type = 'EXPENSE' THEN -t.amount END), 0.0) AS balance,
               COUNT(t.id) AS txnCount
        FROM accounts a
        LEFT JOIN transactions t ON t.accountId = a.id
        GROUP BY a.id
        ORDER BY a.id
        """
    )
    fun observeAccounts(): Flow<List<AccountSummary>>
}

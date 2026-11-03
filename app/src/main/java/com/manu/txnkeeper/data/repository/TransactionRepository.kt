
package com.manu.txnkeeper.data.repository

import com.manu.txnkeeper.data.local.TransactionDao
import com.manu.txnkeeper.data.local.TransactionEntity
import kotlinx.coroutines.flow.Flow

class TransactionRepository (
    private val dao: TransactionDao
) {
    suspend fun insert(transaction: TransactionEntity) {
        dao.insert(transaction)
    }

    fun getAllTransactions(): Flow<List<TransactionEntity>> {
        return dao.getAllTransactions()
    }
}

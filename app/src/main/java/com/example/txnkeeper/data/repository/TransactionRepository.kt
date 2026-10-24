
package com.example.txnkeeper.data.repository

import com.example.txnkeeper.data.local.TransactionDao
import com.example.txnkeeper.data.local.TransactionEntity
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

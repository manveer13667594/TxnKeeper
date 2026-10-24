package com.example.txnkeeper.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val title: String,
    val amount: Double,
    val type: String,
    val dateTime: Long = System.currentTimeMillis(),
    val paymentMethod: String = "CASH",
    val description: String = "",

    // Which account this belongs to. Old transactions were moved to account 1 by the migration.
    @ColumnInfo(defaultValue = "1")
    val accountId: Long = 1
)

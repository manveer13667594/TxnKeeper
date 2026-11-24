package com.manu.txnkeeper.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of a bill ("Milk, 2 x 30") that belongs to a transaction. A transaction with items has an
 * amount equal to the sum of its lines. Deleting the transaction removes its lines by itself
 * (ON DELETE CASCADE), so none is ever left behind.
 */
@Entity(
    tableName = "transaction_items",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("transactionId")]
)
data class TransactionItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val transactionId: Int,
    val name: String,
    /** How many (or how much: 1.5 kg). */
    val quantity: Double = 1.0,
    /** Price of one, in paise. */
    val unitPricePaise: Long,
    /** Order on the bill, first line = 0. */
    val position: Int = 0
) {
    /** What this line adds up to, in paise. */
    val totalPaise: Long get() = Math.round(quantity * unitPricePaise)
}

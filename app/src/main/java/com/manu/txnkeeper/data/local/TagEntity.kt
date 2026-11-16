package com.manu.txnkeeper.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A label you can put on transactions ("Groceries", "Savings"...). A transaction can have any number
 * of them. The colour is not stored as a colour: [colorIndex] says which one of TAG_COLORS (Tags.kt)
 * to use, so the colours can follow the theme.
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val colorIndex: Int = 0
)

/**
 * "This transaction has this tag." Deleting a transaction or a tag removes its rows here by itself
 * (ON DELETE CASCADE), so nothing is ever left pointing at something that is gone.
 */
@Entity(
    tableName = "transaction_tags",
    primaryKeys = ["transactionId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // transactionId is covered by the primary key; this one makes "which transactions have tag X" fast
    indices = [Index("tagId")]
)
data class TransactionTagCrossRef(
    val transactionId: Int,
    val tagId: Long
)

/** The tags a new install starts with (name, colour number). They can be renamed or deleted. */
val DEFAULT_TAGS = listOf(
    "Groceries" to 0,
    "Food" to 1,
    "Bills" to 2,
    "Entertainment" to 3,
    "Shopping" to 4,
    "Travel" to 5,
    "Savings" to 6
)

/** Saves a new transaction together with its tags. */
suspend fun TransactionDao.insertWithTags(transaction: TransactionEntity, tagIds: Set<Long>) {
    val id = insertWithId(transaction).toInt()
    if (tagIds.isNotEmpty()) insertTagLinks(tagIds.map { TransactionTagCrossRef(id, it) })
}

/** Saves a changed transaction and replaces its tags with [tagIds]. */
suspend fun TransactionDao.updateWithTags(transaction: TransactionEntity, tagIds: Set<Long>) {
    update(transaction)
    clearTagLinks(transaction.id)
    if (tagIds.isNotEmpty()) insertTagLinks(tagIds.map { TransactionTagCrossRef(transaction.id, it) })
}

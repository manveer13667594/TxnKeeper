package com.example.txnkeeper.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One account (a bank account, a cash wallet, a card...). Every transaction belongs to exactly one.
 * The balance is not stored: it is [openingBalance] + income - expense, worked out by the database.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val type: String = "BANK",              // one of ACCOUNT_TYPES
    val openingBalance: Double = 0.0
)

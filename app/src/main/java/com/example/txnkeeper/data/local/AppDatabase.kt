package com.example.txnkeeper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransactionEntity::class, AccountEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}

/** Name of the account every existing transaction is moved into (the user can rename it). */
const val DEFAULT_ACCOUNT_NAME = "Main account"

/**
 * Version 1 -> 2: adds accounts. All existing transactions go into account 1, so nothing is lost.
 * The SQL here must produce exactly the tables Room expects for the entities above.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`openingBalance` REAL NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `accounts` (`id`, `name`, `type`, `openingBalance`) " +
                    "VALUES (1, '$DEFAULT_ACCOUNT_NAME', 'BANK', 0.0)"
        )
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `accountId` INTEGER NOT NULL DEFAULT 1")
    }
}

/** A brand-new install has no migration to run, so the first account is added when the file is created. */
val SEED_DEFAULT_ACCOUNT = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `accounts` (`id`, `name`, `type`, `openingBalance`) " +
                    "VALUES (1, '$DEFAULT_ACCOUNT_NAME', 'BANK', 0.0)"
        )
    }
}

package com.manu.txnkeeper.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TransactionEntity::class, AccountEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}

/** Name of the account every existing transaction is moved into (the user can rename it). */
const val DEFAULT_ACCOUNT_NAME = "Main account"

/**
 * Version 1 -> 2: adds accounts. All existing transactions go into account 1, so nothing is lost.
 * This produces the version-2 tables (amounts still decimal); MIGRATION_2_3 then converts them.
 * Never edit an old migration: people upgrading from an old version run every step in order.
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

/**
 * Version 2 -> 3: money is stored as whole paise (a Long) instead of rupees (a Double), so that
 * adding many amounts never drifts. 12.35 becomes 1235. Nothing else changes.
 *
 * SQLite cannot change a column's type in place, so each table is rebuilt: create the new table,
 * copy every row across (rounding to the nearest paisa), drop the old table, rename the new one.
 * The SQL must produce exactly the tables Room expects for the entities above.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- accounts: openingBalance (REAL rupees) -> openingBalancePaise (INTEGER)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`openingBalancePaise` INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `accounts_new` (`id`, `name`, `type`, `openingBalancePaise`) " +
                    "SELECT `id`, `name`, `type`, CAST(ROUND(`openingBalance` * 100) AS INTEGER) FROM `accounts`"
        )
        db.execSQL("DROP TABLE `accounts`")
        db.execSQL("ALTER TABLE `accounts_new` RENAME TO `accounts`")

        // ---- transactions: amount (REAL rupees) -> amountPaise (INTEGER)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transactions_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`amountPaise` INTEGER NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`dateTime` INTEGER NOT NULL, " +
                    "`paymentMethod` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`accountId` INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "INSERT INTO `transactions_new` " +
                    "(`id`, `title`, `amountPaise`, `type`, `dateTime`, `paymentMethod`, `description`, `accountId`) " +
                    "SELECT `id`, `title`, CAST(ROUND(`amount` * 100) AS INTEGER), `type`, `dateTime`, " +
                    "`paymentMethod`, `description`, `accountId` FROM `transactions`"
        )
        db.execSQL("DROP TABLE `transactions`")
        db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
    }
}

/** A brand-new install has no migration to run, so the first account is added when the file is created. */
val SEED_DEFAULT_ACCOUNT = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `accounts` (`id`, `name`, `type`, `openingBalancePaise`) " +
                    "VALUES (1, '$DEFAULT_ACCOUNT_NAME', 'BANK', 0)"
        )
    }
}

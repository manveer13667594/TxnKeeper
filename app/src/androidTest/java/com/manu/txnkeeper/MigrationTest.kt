package com.manu.txnkeeper

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manu.txnkeeper.data.local.AppDatabase
import com.manu.txnkeeper.data.local.MIGRATION_1_2
import com.manu.txnkeeper.data.local.MIGRATION_2_3
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a phone or emulator (right-click > Run 'MigrationTest').
 * Builds a real version-2 database file by hand (amounts as decimal rupees), opens it with the
 * app's Room database, and checks the data came through as paise. Room also checks that the
 * migrated tables match what the app expects, so a wrong migration fails here and not for users.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-test.db"

    @Before fun clean() { context.deleteDatabase(dbName) }
    @After fun cleanUp() { context.deleteDatabase(dbName) }

    @Test fun version2_to_3_turnsRupeesIntoPaise_andKeepsEverythingElse() = runBlocking {
        val file = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            "CREATE TABLE `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `openingBalance` REAL NOT NULL)"
        )
        raw.execSQL(
            "CREATE TABLE `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                    "`amount` REAL NOT NULL, `type` TEXT NOT NULL, `dateTime` INTEGER NOT NULL, " +
                    "`paymentMethod` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                    "`accountId` INTEGER NOT NULL DEFAULT 1)"
        )
        raw.execSQL("INSERT INTO accounts VALUES (1, 'Main account', 'BANK', -500.25)")
        raw.execSQL("INSERT INTO accounts VALUES (2, 'Cash', 'CASH', 1000.1)")
        raw.execSQL("INSERT INTO transactions VALUES (1, 'Chai', 12.35, 'EXPENSE', 1700000000000, 'UPI', 'a note', 1)")
        raw.execSQL("INSERT INTO transactions VALUES (2, 'Salary', 1250.5, 'INCOME', 1700000100000, 'BANK TRANSFER', '', 1)")
        raw.execSQL("INSERT INTO transactions VALUES (3, 'Drift', 0.30000000000000004, 'EXPENSE', 1700000200000, 'CASH', '', 2)")
        raw.version = 2
        raw.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        try {
            val dao = db.transactionDao()

            val byId = dao.getAllOnce().associateBy { it.id }
            assertEquals(3, byId.size)
            assertEquals(1235L, byId[1]!!.amountPaise)
            assertEquals(125050L, byId[2]!!.amountPaise)
            assertEquals(30L, byId[3]!!.amountPaise)
            assertEquals("Chai", byId[1]!!.title)
            assertEquals("a note", byId[1]!!.description)
            assertEquals("BANK TRANSFER", byId[2]!!.paymentMethod)
            assertEquals(1700000000000L, byId[1]!!.dateTime)
            assertEquals(2L, byId[3]!!.accountId)

            val accounts = dao.observeAccounts().first().associateBy { it.id }
            assertEquals(-50025L, accounts[1]!!.openingBalancePaise)
            assertEquals(100010L, accounts[2]!!.openingBalancePaise)
            assertEquals("Cash", accounts[2]!!.name)
            // -500.25 - 12.35 + 1250.50 = 737.90      and      1000.10 - 0.30 = 999.80
            assertEquals(73790L, accounts[1]!!.balancePaise)
            assertEquals(99980L, accounts[2]!!.balancePaise)
        } finally {
            db.close()
        }
    }
}

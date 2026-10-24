package com.example.txnkeeper.data.local

import android.content.Context
import androidx.room.Room

object DatabaseModule {
    @Volatile
    private var instance: AppDatabase? = null

    /** One shared database for the whole app (Room is meant to be used as a singleton). */
    fun provideDatabase(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "txnkeeper_database"
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(SEED_DEFAULT_ACCOUNT)
                .build().also { instance = it }
        }
}

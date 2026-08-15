package com.example.parkincare.data.local

import android.content.Context
import androidx.room.Room
import com.example.parkincare.data.MIGRATION_2_3
import com.example.parkincare.data.MIGRATION_3_4

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "app_database"
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .build()
            INSTANCE = instance
            instance
        }
    }
}

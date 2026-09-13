package com.example.parkincare.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MedicineHistoryEntity::class, WatchPairingEntity::class, ApiTokenHistory::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicineHistoryDao(): MedicineHistoryDao
    abstract fun watchPairingDao(): WatchPairingDao
    abstract fun apiTokenHistoryDao(): ApiTokenHistoryDao
}

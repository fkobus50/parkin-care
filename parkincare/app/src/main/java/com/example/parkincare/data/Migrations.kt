package com.example.parkincare.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `watch_pairing` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `watchId` TEXT,
                `patientId` TEXT NOT NULL,
                `fromTs` INTEGER NOT NULL,
                `toTs` INTEGER
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_watch_pairing_watchId` ON `watch_pairing` (`watchId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_watch_pairing_patientId` ON `watch_pairing` (`patientId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_watch_pairing_watchId_fromTs` ON `watch_pairing` (`watchId`, `fromTs`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `api_token_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `api_token` TEXT NOT NULL,
                `patient_id` TEXT NOT NULL,
                `fromTs` INTEGER NOT NULL,
                `toTs` INTEGER
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_api_token_history_api_token` ON `api_token_history` (`api_token`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_api_token_history_patient_id` ON `api_token_history` (`patient_id`)")
    }
}
